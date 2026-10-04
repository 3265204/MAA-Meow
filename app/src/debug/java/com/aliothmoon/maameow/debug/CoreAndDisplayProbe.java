package com.aliothmoon.maameow.debug;

import android.os.IBinder;
import android.os.Looper;
import android.graphics.SurfaceTexture;
import android.hardware.display.VirtualDisplay;
import android.view.Surface;
import com.aliothmoon.maameow.root.RootUserService;
import com.aliothmoon.maameow.third.wrappers.ServiceManager;
import com.aliothmoon.maameow.third.Workarounds;
import java.lang.reflect.Method;

/** One-shot, root-side diagnostic entry point. Never launches or moves the game. */
public final class CoreAndDisplayProbe {
    public static void main(String[] args) throws Exception {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        RootUserService.CreatedService created = RootUserService.create(args);
        if (created == null) {
            System.err.println("PROBE service creation failed");
            System.exit(1);
            return;
        }
        IBinder service = created.service();
        Class<?> type = service.getClass();
        Method stop = type.getMethod("stopVirtualDisplay");
        boolean started = false;
        SurfaceTexture texture = null;
        Surface surface = null;
        VirtualDisplay privateDisplay = null;
        try {
            String version = (String) type.getMethod("version").invoke(service);
            System.err.println("PROBE CORE " + version.replace('\n', ' '));
            type.getMethod("setVirtualDisplayMode", int.class).invoke(service, 2);
            int displayId = (int) type.getMethod("startVirtualDisplay").invoke(service);
            System.err.println("PROBE DISPLAY id=" + displayId);
            started = displayId >= 0;
            Workarounds.apply();
            texture = new SurfaceTexture(false);
            surface = new Surface(texture);
            // Match scrcpy's --new-display flag combination on Android 14+.
            privateDisplay = ServiceManager.getDisplayManager().createNewVirtualDisplay(
                    "SCRCPY_FLAGS_PROBE", 1280, 720, 160, surface, 0xfdcb);
            int comparisonId = privateDisplay.getDisplay().getDisplayId();
            System.err.println("PROBE SCRCPY_FLAGS id=" + comparisonId
                    + " groupId=" + ServiceManager.getDisplayManager().getDisplayGroupId(comparisonId)
                    + " state=" + privateDisplay.getDisplay().getState());
            Thread.sleep(10_000);
        } finally {
            if (privateDisplay != null) privateDisplay.release();
            if (surface != null) surface.release();
            if (texture != null) texture.release();
            if (started) stop.invoke(service);
        }
        System.exit(0);
    }
}
