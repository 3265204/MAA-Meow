package com.aliothmoon.maameow.remote.internal;

import android.content.Context;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.view.Surface;

import com.aliothmoon.maameow.BuildConfig;
import com.aliothmoon.maameow.bridge.NativeBridgeLib;
import com.aliothmoon.maameow.third.FakeContext;
import com.aliothmoon.maameow.third.wrappers.ServiceManager;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Owns a temporary companion association and the virtual device attached to it. */
public final class IndependentDisplayDevice implements AutoCloseable {
    private static final String SHELL_PACKAGE = "com.android.shell";
    private static final String STREAMING_PROFILE = "android.app.role.COMPANION_DEVICE_APP_STREAMING";
    private static final int USER_ID = 0;
    private static final int LOCK_STATE_ALWAYS_UNLOCKED = 1;
    private static final int TRUSTED = 1 << 10;
    private static final int SUPPORTS_TOUCH = 1 << 6;
    private static final int OWN_FOCUS = 1 << 14;
    // Keep display-0 Back gestures away from the game; targeted input still reaches this display.
    private static final int STEAL_TOP_FOCUS_DISABLED = 1 << 16;
    private static final long COMMAND_TIMEOUT_SECONDS = 5;

    private final String address;
    private final Object device;
    private final VirtualDisplay display;

    private IndependentDisplayDevice(String address, Object device, VirtualDisplay display) {
        this.address = address;
        this.device = device;
        this.display = display;
    }

    public static IndependentDisplayDevice create(String name, int width, int height, int dpi,
                                                  Surface surface) throws Exception {
        return onOwnerThread(() -> createOnOwnerThread(name, width, height, dpi, surface));
    }

    private static IndependentDisplayDevice createOnOwnerThread(String name, int width, int height,
                                                                 int dpi, Surface surface) throws Exception {
        if (Build.VERSION.SDK_INT < 34) throw new UnsupportedOperationException("VDM requires Android 14");
        if (Process.myUid() != Process.ROOT_UID && Process.myUid() != Process.SHELL_UID) {
            throw new SecurityException("VDM helper requires root or shell identity");
        }

        String address = associationAddress();
        // A previous privileged process may have died before its shutdown hook ran.
        if (findAssociationId(address) != -1) disassociate(address);
        runCommand("cmd", "companiondevice", "associate", Integer.toString(USER_ID),
                SHELL_PACKAGE, address, STREAMING_PROFILE, "false");

        try {
            int associationId = findAssociationId(address);
            if (associationId < 0) throw new IllegalStateException("companion association was not created");
            try (ShellIdentity ignored = ShellIdentity.enter()) {
                return createForAssociation(address, associationId, name, width, height, dpi, surface);
            }
        } catch (Exception | Error failure) {
            try {
                disassociate(address);
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static IndependentDisplayDevice createForAssociation(String address, int associationId,
            String name, int width, int height, int dpi, Surface surface) throws Exception {
        Object device = null;
        VirtualDisplay display = null;
        try {

            Class<?> paramsClass = Class.forName("android.companion.virtual.VirtualDeviceParams");
            Class<?> builderClass = Class.forName("android.companion.virtual.VirtualDeviceParams$Builder");
            Object builder = builderClass.getConstructor().newInstance();
            builderClass.getMethod("setLockState", int.class)
                    .invoke(builder, LOCK_STATE_ALWAYS_UNLOCKED);
            Object params = builderClass.getMethod("build").invoke(builder);

            Class<?> aidlClass = Class.forName("android.companion.virtual.IVirtualDeviceManager");
            Class<?> stubClass = Class.forName("android.companion.virtual.IVirtualDeviceManager$Stub");
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) serviceManagerClass.getMethod("getService", String.class)
                    .invoke(null, "virtualdevice");
            if (binder == null) throw new IllegalStateException("virtualdevice service is unavailable");
            Object aidl = stubClass.getMethod("asInterface", IBinder.class).invoke(null, binder);

            Class<?> managerClass = Class.forName("android.companion.virtual.VirtualDeviceManager");
            Constructor<?> constructor = managerClass.getDeclaredConstructor(aidlClass, Context.class);
            constructor.setAccessible(true);
            Object manager = constructor.newInstance(aidl, FakeContext.get());
            device = managerClass.getMethod("createVirtualDevice", int.class, paramsClass)
                    .invoke(manager, associationId, params);

            VirtualDisplayConfig config = new VirtualDisplayConfig.Builder(name, width, height, dpi)
                    .setSurface(surface)
                    .setFlags(TRUSTED | SUPPORTS_TOUCH | OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED)
                    .build();
            Method createDisplay = device.getClass().getMethod("createVirtualDisplay",
                    VirtualDisplayConfig.class, Executor.class, VirtualDisplay.Callback.class);
            display = (VirtualDisplay) createDisplay.invoke(device, config, null, null);
            if (display == null) throw new IllegalStateException("VDM returned no display");
            int displayId = display.getDisplay().getDisplayId();
            int groupId = ServiceManager.getDisplayManager().getDisplayGroupId(displayId);
            if (groupId <= 0) throw new IllegalStateException("VDM display remained in group " + groupId);
            return new IndependentDisplayDevice(address, device, display);
        } catch (Exception | Error failure) {
            if (display != null) display.release();
            if (device != null) {
                try {
                    closeDevice(device);
                } catch (Exception cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    public VirtualDisplay getDisplay() {
        return display;
    }

    @Override
    public void close() throws Exception {
        onOwnerThread(() -> {
            closeOnOwnerThread();
            return null;
        });
    }

    private void closeOnOwnerThread() throws Exception {
        try {
            try (ShellIdentity ignored = ShellIdentity.enter()) {
                try {
                    display.release();
                } finally {
                    closeDevice(device);
                }
            }
        } finally {
            disassociate(address);
        }
    }

    /** Binder uses the process leader's credential on this kernel, so Root VDM calls run there. */
    private static <T> T onOwnerThread(Callable<T> action) throws Exception {
        if (Process.myUid() != Process.ROOT_UID) return action.call();
        Looper mainLooper = Looper.getMainLooper();
        if (mainLooper == null) throw new IllegalStateException("Root service has no main Looper");
        if (Looper.myLooper() == mainLooper) return action.call();

        FutureTask<T> task = new FutureTask<>(action);
        if (!new Handler(mainLooper).post(task)) {
            throw new IllegalStateException("Root service main Looper rejected VDM work");
        }
        try {
            return task.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException | TimeoutException failure) {
            task.cancel(true);
            throw failure;
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(cause);
        }
    }

    /** VDM checks the shell package against the Binder caller UID on this device. */
    private static final class ShellIdentity implements AutoCloseable {
        private final boolean switched;

        private ShellIdentity(boolean switched) {
            this.switched = switched;
        }

        static ShellIdentity enter() {
            if (Process.myUid() == Process.SHELL_UID) return new ShellIdentity(false);
            if (NativeBridgeLib.getEffectiveUid() != Process.ROOT_UID
                    || !NativeBridgeLib.setEffectiveUid(Process.SHELL_UID)
                    || NativeBridgeLib.getEffectiveUid() != Process.SHELL_UID) {
                throw new SecurityException("could not enter shell Binder identity");
            }
            return new ShellIdentity(true);
        }

        @Override
        public void close() {
            if (switched && (!NativeBridgeLib.setEffectiveUid(Process.ROOT_UID)
                    || NativeBridgeLib.getEffectiveUid() != Process.ROOT_UID)) {
                throw new SecurityException("could not restore root Binder identity");
            }
        }
    }

    private static void closeDevice(Object device) throws Exception {
        device.getClass().getMethod("close").invoke(device);
    }

    private static String associationAddress() {
        int hash = BuildConfig.APPLICATION_ID.hashCode();
        return String.format(Locale.ROOT, "02:4D:41:%02X:%02X:%02X",
                (hash >>> 16) & 0xff, (hash >>> 8) & 0xff, hash & 0xff);
    }

    private static int findAssociationId(String address) throws Exception {
        String listing = runCommand("cmd", "companiondevice", "list", Integer.toString(USER_ID));
        for (String line : listing.split("\\R")) {
            String[] fields = line.split("\\|");
            if (fields.length >= 3 && SHELL_PACKAGE.equals(fields[1].trim())
                    && address.equalsIgnoreCase(fields[2].trim())) {
                return Integer.parseInt(fields[0].trim());
            }
        }
        return -1;
    }

    private static void disassociate(String address) throws Exception {
        runCommand("cmd", "companiondevice", "disassociate", Integer.toString(USER_ID),
                SHELL_PACKAGE, address);
    }

    private static String runCommand(String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        java.lang.Process process = builder.start();
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("companiondevice command timed out");
        }
        String output = new String(process.getInputStream().readAllBytes());
        if (process.exitValue() != 0) {
            throw new IOException("companiondevice command failed: " + output.trim());
        }
        return output;
    }
}
