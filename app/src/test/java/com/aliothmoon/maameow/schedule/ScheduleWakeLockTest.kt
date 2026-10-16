package com.aliothmoon.maameow.schedule

import android.content.Context
import android.os.PowerManager
import com.aliothmoon.maameow.schedule.service.ScheduleWakeLock
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Test

class ScheduleWakeLockTest {
    // 本用例只验证 tag 参数被透传到 newWakeLock，不与生产常量耦合。
    @Test
    fun customTagIsPassedToPowerManager() {
        val tag = "MaaMeow:test-tag"
        val lock = mockk<PowerManager.WakeLock>(relaxed = true)
        val power = mockk<PowerManager> {
            every {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
            } returns lock
        }
        val context = mockk<Context> {
            every { getSystemService(Context.POWER_SERVICE) } returns power
        }

        ScheduleWakeLock.acquire(context, 10_000L, tag)

        verify(exactly = 1) {
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
        }
    }

    @Test
    fun lockIsBounded_andCleanupHandlesAlreadyExpiredLock() {
        val lock = mockk<PowerManager.WakeLock>(relaxed = true)
        val power = mockk<PowerManager> {
            every { newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, any()) } returns lock
        }
        val context = mockk<Context> {
            every { getSystemService(Context.POWER_SERVICE) } returns power
        }
        val acquired = ScheduleWakeLock.acquire(context, 10_000L)
        every { lock.isHeld } returns true
        ScheduleWakeLock.release(acquired)
        every { lock.isHeld } returns false
        ScheduleWakeLock.release(acquired)
        verifyOrder {
            lock.setReferenceCounted(false)
            lock.acquire(10_000L)
            lock.release()
        }
        verify(exactly = 1) { lock.release() }
    }
}
