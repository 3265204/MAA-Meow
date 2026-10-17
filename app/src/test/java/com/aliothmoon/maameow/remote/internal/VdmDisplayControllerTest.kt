package com.aliothmoon.maameow.remote.internal

import android.os.Build
import com.aliothmoon.maameow.constant.DefaultDisplayConfig
import com.aliothmoon.maameow.remote.canReuseActiveDisplay
import com.aliothmoon.maameow.remote.startBackgroundDisplay
import com.aliothmoon.maameow.third.Ln
import io.mockk.every
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VdmDisplayControllerTest {

    private companion object {
        const val LEGACY_DISPLAY_ID = 12
        const val VDM_DISPLAY_ID = 13
        const val MAIN_USER_ID = 0
        const val WORK_PROFILE_USER_ID = 10
        const val SHELL_PACKAGE = "com.android.shell"
        const val ASSOCIATION_ADDRESS = "02:00:00:00:00:01"
        const val STREAMING_PROFILE = "android.app.role.COMPANION_DEVICE_APP_STREAMING"
    }

    /**
     * 清理失败路径会经 [Ln.w] 打阶段日志；JVM 单测里的 `android.util.Log` 是桩，
     * 所以只在覆盖失败路径的用例内挂日志 Mock，纯逻辑用例不初始化日志依赖。
     */
    private fun withStubbedLogging(block: () -> Unit) {
        mockkStatic(Ln::class)
        try {
            every { Ln.w(any<String>()) } just runs
            block()
        } finally {
            unmockkStatic(Ln::class)
        }
    }

    @Test
    fun activeDisplayIsNotReusedWithoutActiveDisplay() {
        assertFalse(
            canReuseActiveDisplay(
                activeDisplayId = DefaultDisplayConfig.DISPLAY_NONE,
                activeUsesVdm = false,
                activeVdmUserId = MAIN_USER_ID,
                requestedUsesVdm = false,
                requestedUserId = MAIN_USER_ID,
            )
        )
    }

    @Test
    fun legacyDisplayIsReusedForAnyUser() {
        assertTrue(
            canReuseActiveDisplay(
                activeDisplayId = LEGACY_DISPLAY_ID,
                activeUsesVdm = false,
                activeVdmUserId = MAIN_USER_ID,
                requestedUsesVdm = false,
                requestedUserId = WORK_PROFILE_USER_ID,
            )
        )
    }

    @Test
    fun vdmDisplayIsReusedOnlyForTheSameUser() {
        assertTrue(
            canReuseActiveDisplay(
                activeDisplayId = VDM_DISPLAY_ID,
                activeUsesVdm = true,
                activeVdmUserId = MAIN_USER_ID,
                requestedUsesVdm = true,
                requestedUserId = MAIN_USER_ID,
            )
        )
        assertFalse(
            canReuseActiveDisplay(
                activeDisplayId = VDM_DISPLAY_ID,
                activeUsesVdm = true,
                activeVdmUserId = MAIN_USER_ID,
                requestedUsesVdm = true,
                requestedUserId = WORK_PROFILE_USER_ID,
            )
        )
    }

    @Test
    fun displayIsNotReusedAcrossBackendChanges() {
        assertFalse(
            canReuseActiveDisplay(
                activeDisplayId = LEGACY_DISPLAY_ID,
                activeUsesVdm = false,
                activeVdmUserId = MAIN_USER_ID,
                requestedUsesVdm = true,
                requestedUserId = MAIN_USER_ID,
            )
        )
        assertFalse(
            canReuseActiveDisplay(
                activeDisplayId = VDM_DISPLAY_ID,
                activeUsesVdm = true,
                activeVdmUserId = MAIN_USER_ID,
                requestedUsesVdm = false,
                requestedUserId = MAIN_USER_ID,
            )
        )
    }

    @Test
    fun android14AssociationCommandUsesLegacyArity() {
        assertEquals(
            listOf("cmd", "companiondevice", "associate", "10", SHELL_PACKAGE, ASSOCIATION_ADDRESS),
            VdmDisplayController.buildAssociateCommand(
                address = ASSOCIATION_ADDRESS,
                userId = WORK_PROFILE_USER_ID,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            ),
        )
    }

    @Test
    fun android15And16AssociationCommandsIncludeStreamingProfile() {
        val expected = listOf(
            "cmd", "companiondevice", "associate", "0", SHELL_PACKAGE, ASSOCIATION_ADDRESS,
            STREAMING_PROFILE, "false",
        )
        assertEquals(
            expected,
            VdmDisplayController.buildAssociateCommand(
                address = ASSOCIATION_ADDRESS,
                userId = MAIN_USER_ID,
                sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
            ),
        )
        assertEquals(
            expected,
            VdmDisplayController.buildAssociateCommand(
                address = ASSOCIATION_ADDRESS,
                userId = MAIN_USER_ID,
                sdkInt = Build.VERSION_CODES.BAKLAVA,
            ),
        )
    }

    @Test
    fun android14DoesNotRequireProfileMigration() {
        assertFalse(
            VdmDisplayController.requiresProfileMigration(
                profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            )
        )
        assertFalse(
            VdmDisplayController.requiresProfileMigration(
                profile = STREAMING_PROFILE,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            )
        )
    }

    @Test
    fun android15And16RequireStreamingProfileMigration() {
        assertTrue(
            VdmDisplayController.requiresProfileMigration(
                profile = null,
                sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
            )
        )
        assertTrue(
            VdmDisplayController.requiresProfileMigration(
                profile = "android.app.role.COMPANION_DEVICE_WATCH",
                sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
            )
        )
        assertFalse(
            VdmDisplayController.requiresProfileMigration(
                profile = STREAMING_PROFILE,
                sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
            )
        )
        assertTrue(
            VdmDisplayController.requiresProfileMigration(
                profile = null,
                sdkInt = Build.VERSION_CODES.BAKLAVA,
            )
        )
        assertFalse(
            VdmDisplayController.requiresProfileMigration(
                profile = STREAMING_PROFILE,
                sdkInt = Build.VERSION_CODES.BAKLAVA,
            )
        )
    }

    @Test
    fun cleanupReleasesDisplayThenDeviceThenAssociation() {
        val calls = mutableListOf<String>()

        val failure = VdmDisplayController.cleanupInReverseOrder(
            releaseDisplay = { calls += "releaseDisplay" },
            closeVirtualDevice = { calls += "closeVirtualDevice" },
            removeAssociation = { calls += "removeAssociation" },
        )

        assertNull(failure)
        assertEquals(listOf("releaseDisplay", "closeVirtualDevice", "removeAssociation"), calls)
    }

    @Test
    fun cleanupContinuesAfterFailureAndKeepsFirstFailure() = withStubbedLogging {
        val calls = mutableListOf<String>()
        val releaseFailure = IllegalStateException("display already released")
        val closeFailure = IllegalStateException("device close failed")

        val failure = VdmDisplayController.cleanupInReverseOrder(
            releaseDisplay = {
                calls += "releaseDisplay"
                throw releaseFailure
            },
            closeVirtualDevice = {
                calls += "closeVirtualDevice"
                throw closeFailure
            },
            removeAssociation = { calls += "removeAssociation" },
        )

        assertEquals(listOf("releaseDisplay", "closeVirtualDevice", "removeAssociation"), calls)
        assertSame("首个失败应作为清理结果返回", releaseFailure, failure)
        assertSame("后续失败应作为 suppressed 附加", closeFailure, releaseFailure.suppressed.single())
    }

    @Test
    fun cleanupReturnsAssociationFailureWhenOnlyLastStageFails() = withStubbedLogging {
        val calls = mutableListOf<String>()
        val associationFailure = IllegalStateException("association was not removed")

        val failure = VdmDisplayController.cleanupInReverseOrder(
            releaseDisplay = { calls += "releaseDisplay" },
            closeVirtualDevice = { calls += "closeVirtualDevice" },
            removeAssociation = {
                calls += "removeAssociation"
                throw associationFailure
            },
        )

        assertEquals(listOf("releaseDisplay", "closeVirtualDevice", "removeAssociation"), calls)
        assertSame(associationFailure, failure)
    }

    @Test
    fun legacyBackendIsUsedWhenVdmIsNotRequested() {
        var legacyStarts = 0

        val displayId = startBackgroundDisplay(
            useVdm = false,
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            startVdm = { error("VDM must not start when Legacy is requested") },
            startLegacy = {
                legacyStarts++
                LEGACY_DISPLAY_ID
            },
            onVdmFailure = { error("VDM failure must not be reported") },
        )

        assertEquals(LEGACY_DISPLAY_ID, displayId)
        assertEquals(1, legacyStarts)
    }

    @Test
    fun android13DoesNotAttemptVdm() {
        var legacyStarts = 0

        val displayId = startBackgroundDisplay(
            useVdm = true,
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            startVdm = { error("VDM must not start before Android 14") },
            startLegacy = {
                legacyStarts++
                LEGACY_DISPLAY_ID
            },
            onVdmFailure = { error("VDM failure must not be reported") },
        )

        assertEquals(LEGACY_DISPLAY_ID, displayId)
        assertEquals(1, legacyStarts)
    }

    @Test
    fun vdmBackendReturnsItsDisplayId() {
        val displayId = startBackgroundDisplay(
            useVdm = true,
            sdkInt = Build.VERSION_CODES.BAKLAVA,
            startVdm = { VDM_DISPLAY_ID },
            startLegacy = { error("Legacy must not start when VDM succeeds") },
            onVdmFailure = { error("VDM failure must not be reported") },
        )

        assertEquals(VDM_DISPLAY_ID, displayId)
    }

    @Test
    fun android14VdmFailureDoesNotFallBackToLegacy() {
        val cause = IllegalStateException("association was not created")
        var legacyStarts = 0
        var reportedFailure: Throwable? = null

        val displayId = startBackgroundDisplay(
            useVdm = true,
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            startVdm = { throw cause },
            startLegacy = {
                legacyStarts++
                LEGACY_DISPLAY_ID
            },
            onVdmFailure = { reportedFailure = it },
        )

        assertEquals(DefaultDisplayConfig.DISPLAY_NONE, displayId)
        assertSame(cause, reportedFailure)
        assertEquals("VDM 失败时不得回退到 Legacy 显示", 0, legacyStarts)
    }
}
