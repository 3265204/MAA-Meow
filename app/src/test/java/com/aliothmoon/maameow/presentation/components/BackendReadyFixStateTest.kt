package com.aliothmoon.maameow.presentation.components

import com.aliothmoon.maameow.manager.PermissionManager
import com.aliothmoon.maameow.manager.ShizukuReadiness
import com.aliothmoon.maameow.manager.ShizukuReadinessProvider
import com.aliothmoon.maameow.manager.ShizukuReadinessStage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendReadyFixStateTest {

    private val permissionManager = mockk<PermissionManager>()
    private val readinessProvider = mockk<ShizukuReadinessProvider>()

    private fun TestScope.state() =
        BackendReadyFixState(permissionManager, readinessProvider, backgroundScope)

    private fun resolvesTo(stage: ShizukuReadinessStage) {
        coEvery { readinessProvider.resolveUnskipped() } returns ShizukuReadiness(stage)
    }

    @Test
    fun fixableStages_openGuidanceWithoutRequesting() = runTest {
        for (stage in listOf(
            ShizukuReadinessStage.NotInstalled,
            ShizukuReadinessStage.NotRunning,
            ShizukuReadinessStage.NeedAuth,
        )) {
            resolvesTo(stage)
            val state = state()

            state.request()
            runCurrent()

            assertEquals(stage, state.guidance?.stage)
            assertFalse(state.stillNotReady)
        }
        // 引导弹窗里才有授权按钮，点出来之前不该先弹系统授权框
        coVerify(exactly = 0) { permissionManager.requestRemoteAccess() }
    }

    @Test
    fun rootBackend_requestsDirectlyAndFlagsFailure() = runTest {
        // Root 后端判定恒为 Ready，引导覆盖不到
        resolvesTo(ShizukuReadinessStage.Ready)
        coEvery { permissionManager.requestRemoteAccess() } returns false
        val state = state()

        state.request()
        runCurrent()

        assertNull(state.guidance)
        assertTrue(state.stillNotReady)
    }


    @Test
    fun dismiss_closesGuidance() = runTest {
        resolvesTo(ShizukuReadinessStage.NeedAuth)
        val state = state()
        state.request()
        runCurrent()

        state.dismiss()

        assertNull(state.guidance)
    }
}
