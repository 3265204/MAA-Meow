package com.aliothmoon.maameow.remote.internal.display

import android.util.Log
import com.aliothmoon.maameow.bridge.NativeBridgeLib
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test

class VirtualDisplayManagerFailureTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any<String>(), any()) } returns 0
        assumeFalse("JVM test expects the Android native bridge to be unavailable", NativeBridgeLib.LOADED)
    }

    @After
    fun tearDown() {
        VirtualDisplayManager.stop()
        unmockkStatic(Log::class)
    }

    @Test
    fun unsatisfiedLinkError_resetsStateAndAllowsRetry() {
        assertEquals(VirtualDisplayManager.DISPLAY_NONE, VirtualDisplayManager.start())
        assertEquals(VirtualDisplayManager.DISPLAY_NONE, VirtualDisplayManager.start())
    }
}
