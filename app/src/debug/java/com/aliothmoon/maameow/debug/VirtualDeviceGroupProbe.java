package com.aliothmoon.maameow.debug;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.os.IBinder;
import android.os.Looper;
import android.view.Surface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;

/** One-shot shell-side VDM group probe. Does not launch activities or inject input. */
public final class VirtualDeviceGroupProbe {
    private static final int TRUSTED = 1 << 10;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("association ID required");
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        Class.forName("com.aliothmoon.maameow.third.Workarounds")
                .getMethod("apply").invoke(null);
        Context shellContext = (Context) Class.forName("com.aliothmoon.maameow.third.FakeContext")
                .getMethod("get").invoke(null);

        Object device = null;
        VirtualDisplay display = null;
        SurfaceTexture texture = null;
        Surface surface = null;
        try {
            Class<?> paramsClass = Class.forName("android.companion.virtual.VirtualDeviceParams");
            Class<?> builderClass = Class.forName("android.companion.virtual.VirtualDeviceParams$Builder");
            Object builder = builderClass.getConstructor().newInstance();
            builderClass.getMethod("setLockState", int.class).invoke(builder, 1);
            Object params = builderClass.getMethod("build").invoke(builder);

            Class<?> aidlClass = Class.forName("android.companion.virtual.IVirtualDeviceManager");
            Class<?> stubClass = Class.forName("android.companion.virtual.IVirtualDeviceManager$Stub");
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) serviceManagerClass.getMethod("getService", String.class)
                    .invoke(null, "virtualdevice");
            if (binder == null) throw new IllegalStateException("virtualdevice service is missing");
            Object aidl = stubClass.getMethod("asInterface", IBinder.class).invoke(null, binder);

            Class<?> managerClass = Class.forName("android.companion.virtual.VirtualDeviceManager");
            Constructor<?> managerCtor = managerClass.getDeclaredConstructor(aidlClass, Context.class);
            managerCtor.setAccessible(true);
            Object manager = managerCtor.newInstance(aidl, shellContext);
            device = managerClass.getMethod("createVirtualDevice", int.class, paramsClass)
                    .invoke(manager, Integer.parseInt(args[0]), params);
            System.err.println("VDM_PROBE deviceId=" + device.getClass().getMethod("getDeviceId").invoke(device));

            texture = new SurfaceTexture(false);
            texture.setDefaultBufferSize(128, 128);
            surface = new Surface(texture);
            VirtualDisplayConfig config = new VirtualDisplayConfig.Builder("MAA_VDM_GROUP_PROBE", 128, 128, 160)
                    .setSurface(surface)
                    .setFlags(TRUSTED)
                    .build();
            Method createDisplay = device.getClass().getMethod("createVirtualDisplay",
                    VirtualDisplayConfig.class, Executor.class, VirtualDisplay.Callback.class);
            display = (VirtualDisplay) createDisplay.invoke(device, config, null, null);
            if (display == null) throw new IllegalStateException("VDM returned null display");
            int displayId = display.getDisplay().getDisplayId();
            Object displayManager = Class.forName("com.aliothmoon.maameow.third.wrappers.ServiceManager")
                    .getMethod("getDisplayManager").invoke(null);
            Method getGroupId = displayManager.getClass().getMethod("getDisplayGroupId", int.class);
            for (int i = 0; i < 15; i++) {
                Thread.sleep(1000);
                System.err.println("VDM_PROBE displayId=" + displayId
                        + " groupId=" + getGroupId.invoke(displayManager, displayId)
                        + " state=" + display.getDisplay().getState());
            }
        } finally {
            if (display != null) display.release();
            if (device != null) device.getClass().getMethod("close").invoke(device);
            if (surface != null) surface.release();
            if (texture != null) texture.release();
        }
        System.exit(0);
    }
}
