package com.aliothmoon.maameow.remote.internal

import android.os.Build
import com.aliothmoon.maameow.TestSources
import com.aliothmoon.maameow.remote.canReuseActiveDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VdmDisplayControllerTest {

    @Test
    fun activeDisplayIsReusedOnlyForTheSameBackendSession() {
        assertFalse(canReuseActiveDisplay(-1, false, 0, false, 0))
        assertTrue(canReuseActiveDisplay(12, false, 0, false, 10))
        assertTrue(canReuseActiveDisplay(13, true, 0, true, 0))
        assertFalse(canReuseActiveDisplay(12, false, 0, true, 0))
        assertFalse(canReuseActiveDisplay(13, true, 0, true, 10))
        assertFalse(canReuseActiveDisplay(13, true, 0, false, 0))
    }

    @Test
    fun android14AssociationCommandUsesLegacyArity() {
        assertEquals(
            listOf("cmd", "companiondevice", "associate", "10", "com.android.shell", "02:00:00:00:00:01"),
            VdmDisplayController.buildAssociateCommand(
                address = "02:00:00:00:00:01",
                userId = 10,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            ),
        )
    }

    @Test
    fun android15AssociationCommandIncludesStreamingProfile() {
        assertEquals(
            listOf(
                "cmd", "companiondevice", "associate", "0", "com.android.shell", "02:00:00:00:00:01",
                "android.app.role.COMPANION_DEVICE_APP_STREAMING", "false",
            ),
            VdmDisplayController.buildAssociateCommand(
                address = "02:00:00:00:00:01",
                userId = 0,
                sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
            ),
        )
        assertTrue(VdmDisplayController.requiresProfileMigration(null, Build.VERSION_CODES.VANILLA_ICE_CREAM))
        assertFalse(
            VdmDisplayController.requiresProfileMigration(
                "android.app.role.COMPANION_DEVICE_APP_STREAMING",
                Build.VERSION_CODES.VANILLA_ICE_CREAM,
            )
        )
    }

    @Test
    fun cleanupOrderAndNoFallbackRemainExplicit() {
        val controller = TestSources.resolve(
            "src/main/java/com/aliothmoon/maameow/remote/internal/VdmDisplayController.kt"
        ).readText()
        val stop = controller.substringAfter("fun stop()").substringBefore("fun getDisplayId")
        assertTrue(stop.indexOf("releaseDisplay") < stop.indexOf("closeVirtualDevice"))
        assertTrue(stop.indexOf("closeVirtualDevice") < stop.indexOf("removeAssociation"))
        assertTrue(controller.contains("Process.myUid() == Process.SHELL_UID && Os.geteuid() == Process.SHELL_UID"))
        assertTrue(controller.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA"))
        assertTrue(controller.contains("createVirtualDevice(associationId, alwaysUnlocked = false)"))
        assertTrue(controller.lineSequence().count() <= 400)

        val remote = TestSources.resolve(
            "src/main/java/com/aliothmoon/maameow/remote/RemoteServiceImpl.kt"
        ).readText()
        val selection = remote.substringAfter("val result = if (useVdm").substringBefore("activeBackgroundIsVdm.set")
        assertTrue(selection.contains("VdmDisplayController.start"))
        assertTrue(selection.contains("getOrDefault(DefaultDisplayConfig.DISPLAY_NONE)"))
        assertEquals(1, selection.windowed("VirtualDisplayManager.start".length)
            .count { it == "VirtualDisplayManager.start" })
        assertTrue(remote.contains("VdmDisplayController.stop()"))

        val rules = TestSources.resolve("proguard-rules.pro").readText()
        assertFalse(rules.contains("VdmDisplayController"))
    }
}
