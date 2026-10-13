package com.aliothmoon.maameow.domain.service

import android.content.Context
import android.os.PowerManager
import com.aliothmoon.maameow.domain.models.RunDurationLimit
import timber.log.Timber

/**
 * 任务运行期间阻止设备进入 suspend。
 *
 * 释放有两条路径：服务终止时 `onDestroy` 的 release（正常生命周期下的主路径）与终态处理里的
 * 提前释放（`stopSelf` 到 `onDestroy` 之间的窗口期兜底）；二者以本类的 wakeLock 字段为
 * 唯一状态，重复调用幂等。进程被系统直接杀死时 `onDestroy` 不保证执行，此时锁交由系统回收。
 *
 * 与 ScheduleWakeLock 的差异：本锁跨服务生命周期、需要在每个状态快照上重复 acquire 且只生效一次，
 * 因此用持有实例 + 幂等 acquire 的形态，不是可合并的重复实现。
 */
internal class TaskExecutionWakeLock(private val context: Context) {
    companion object {
        private const val SAFETY_MARGIN_MINUTES = 60L

        /**
         * 单次获取的超时护栏，按「可配置的最长任务时长 + [SAFETY_MARGIN_MINUTES] 余量」取经验值。
         *
         * 它既不是预期持有时间，也不构成整场任务累计上的硬上限：只要继续投递活动快照，
         * 锁被系统回收后还会再次获取，总持有时间因此无硬边界；而任务自身的时长限制默认
         * 不启用、copilot 等辅助任务也不受其约束，故不能从该常量推出「任务一定在
         * MAX_HOLD_MS 内结束」。真正的兜底只在正常路径的释放（见类注释）。
         */
        internal const val MAX_HOLD_MS =
            (RunDurationLimit.MAX_MINUTES + SAFETY_MARGIN_MINUTES) * 60_000L
        private const val TAG = "MaaMeow:task-execution"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * AOSP 的 acquire(long) 每次都会 postDelayed(mReleaser, timeout)（acquireLocked() 里
     * 先 removeCallbacks(mReleaser)），即重复调用会重置超时；而 AOSP 自己刻意不在
     * acquireLocked() 里对已持有的锁早退，是为了让系统强制释放后的下一次 acquire 能自愈。
     * 本类在持有中直接返回，不接受超时重置；isHeld 变 false 后字段仍在，下一次调用会
     * 重新 acquire，自愈不受影响。
     */
    @Synchronized
    fun acquire() {
        // isHeld 只反映客户端的 mHeld：系统策略/超时回收后它会变 false，此时不能早退，需重新获取。
        if (wakeLock?.isHeld == true) return
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).apply {
            setReferenceCounted(false)
            acquire(MAX_HOLD_MS)
        }
        Timber.i("%s acquired, timeout=%dms", TAG, MAX_HOLD_MS)
    }

    @Synchronized
    fun release() {
        val lock = wakeLock ?: return
        wakeLock = null
        // isHeld 为 false 说明锁已不在本客户端持有（超时回收或从未成功获取）：再调 release
        // 只会在客户端空转，这里跳过并留下可检索的痕迹。
        if (!lock.isHeld) {
            Timber.w("%s release skipped: lock no longer held (system timeout reclaim)", TAG)
            return
        }
        lock.release()
        Timber.i("%s released", TAG)
    }
}
