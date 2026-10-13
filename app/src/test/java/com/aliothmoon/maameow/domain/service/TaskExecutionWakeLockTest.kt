package com.aliothmoon.maameow.domain.service

import android.content.Context
import android.os.PowerManager
import com.aliothmoon.maameow.domain.models.RunDurationLimit
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskExecutionWakeLockTest {
    private val wakeLockTag = "MaaMeow:task-execution"

    @Test
    fun acquireIsBoundedAndIdempotent_releaseIsIdempotent() {
        val lock = mockk<PowerManager.WakeLock>(relaxed = true)
        every { lock.isHeld } returns true
        val (context, _) = contextReturning(lock)
        val holder = TaskExecutionWakeLock(context)

        holder.acquire()
        holder.acquire()
        holder.release()
        holder.release()

        // 幂等断言必须落在 WakeLock.acquire 上：只断言 newWakeLock 次数测不到重复 acquire。
        verify(exactly = 1) { lock.acquire(TaskExecutionWakeLock.MAX_HOLD_MS) }
        verify(exactly = 1) { lock.release() }
        verifyOrder {
            lock.setReferenceCounted(false)
            lock.acquire(TaskExecutionWakeLock.MAX_HOLD_MS)
            lock.release()
        }
    }

    @Test
    fun acquireWhileHeldDoesNotRenewTimeoutOrAcquireAgain() {
        val lock = mockk<PowerManager.WakeLock>(relaxed = true)
        every { lock.isHeld } returns true
        val (context, _) = contextReturning(lock)
        val holder = TaskExecutionWakeLock(context)

        holder.acquire()
        holder.acquire()

        // AOSP acquire(long) 每次都会 postDelayed(mReleaser)，重复调用会重置超时；
        // 本类在持有中直接返回，因此不会二次 acquire，上限固定在首次 acquire 之后。
        verify(exactly = 1) { lock.acquire(TaskExecutionWakeLock.MAX_HOLD_MS) }
    }

    @Test
    fun acquireAfterSystemReclaimedLockTakesNewLock() {
        val first = mockk<PowerManager.WakeLock>(relaxed = true)
        val second = mockk<PowerManager.WakeLock>(relaxed = true)
        // 系统按超时回收后 isHeld 变 false，本类字段仍指向旧对象，此时必须重新获取。
        every { first.isHeld } returns false
        // 新锁处于持有状态，释放必须落在它身上而不是旧的、已被回收的锁。
        every { second.isHeld } returns true
        val (context, power) = contextReturning(first, second)
        val holder = TaskExecutionWakeLock(context)

        holder.acquire()
        holder.acquire()
        holder.release()

        verify(exactly = 2) { power.newWakeLock(1, wakeLockTag) }
        verify(exactly = 1) { first.acquire(TaskExecutionWakeLock.MAX_HOLD_MS) }
        verify(exactly = 1) { second.acquire(TaskExecutionWakeLock.MAX_HOLD_MS) }
        verify(exactly = 1) { second.release() }
        verify(exactly = 0) { first.release() }
    }

    @Test
    fun maxHoldCoversLongestRunDurationWithBoundedSafetyMargin() {
        // 取值依据：MAX_HOLD_MS 是「异常兜底」而非预期持有时间，必须覆盖用户可配置的最长任务
        // 并留有余量；同时不能无界。这里用相对断言，避免把 25 小时写死后与公式耦合。
        val longestRunMs = RunDurationLimit.MAX_MINUTES * 60_000L
        val safetyMarginMs = TaskExecutionWakeLock.MAX_HOLD_MS - longestRunMs
        assertTrue(
            "MAX_HOLD_MS must cover the longest run duration",
            safetyMarginMs > 0L,
        )
        assertTrue(
            "safety margin must be at least 30 minutes",
            safetyMarginMs >= 30 * 60_000L,
        )
        assertTrue(
            "MAX_HOLD_MS must stay bounded (currently <= 25h)",
            TaskExecutionWakeLock.MAX_HOLD_MS <= 25 * 60 * 60_000L,
        )
    }

    @Test
    fun releaseAfterSystemReclaimedLockSkipsServerRelease() {
        // 系统已按超时回收：isHeld 为 false，服务端状态已释放，客户端不再补一刀。
        val lock = mockk<PowerManager.WakeLock>(relaxed = true)
        every { lock.isHeld } returns false
        val (context, _) = contextReturning(lock)
        val holder = TaskExecutionWakeLock(context)

        holder.acquire()
        holder.release()

        verify(exactly = 1) { lock.acquire(TaskExecutionWakeLock.MAX_HOLD_MS) }
        verify(exactly = 0) { lock.release() }
    }

    /** 顺序返回 [locks] 的 PowerManager；用完最后一个后一直返回它。 */
    private fun contextReturning(
        vararg locks: PowerManager.WakeLock,
    ): Pair<Context, PowerManager> {
        var index = 0
        val power = mockk<PowerManager> {
            every { newWakeLock(1, wakeLockTag) } answers {
                locks[index.coerceAtMost(locks.size - 1)].also { index++ }
            }
        }
        val context = mockk<Context> {
            every { getSystemService(Context.POWER_SERVICE) } returns power
        }
        return context to power
    }
}
