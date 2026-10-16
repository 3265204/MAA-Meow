package com.aliothmoon.maameow.schedule.service

import android.content.Context
import android.os.PowerManager

/** 限时 [PowerManager.PARTIAL_WAKE_LOCK] 的获取/释放原语；调度侧调用方均复用默认 `tag`，只有需要区分持有者时才传自定义值。 */
internal object ScheduleWakeLock {
    fun acquire(
        context: Context,
        timeoutMs: Long,
        tag: String = "MaaMeow:schedule",
    ): PowerManager.WakeLock {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
            setReferenceCounted(false)
            acquire(timeoutMs)
        }
    }

    /** 实际调用 [PowerManager.WakeLock.release] 返回 `true`，锁已不再持有而跳过时返回 `false`。 */
    fun release(wakeLock: PowerManager.WakeLock): Boolean {
        if (!wakeLock.isHeld) return false
        wakeLock.release()
        return true
    }
}
