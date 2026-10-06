package com.aliothmoon.maameow.schedule.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.aliothmoon.maameow.domain.models.RemoteBackend
import com.aliothmoon.maameow.domain.service.MaaNotificationCenter
import com.aliothmoon.maameow.domain.service.RunTelemetry
import com.aliothmoon.maameow.manager.RemoteAccessCoordinator
import com.aliothmoon.maameow.schedule.data.ScheduleStrategyRepository
import com.aliothmoon.maameow.schedule.receiver.ShizukuCheckReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Shizuku 没在跑就提前提醒，别等定时到点才失败
 *
 * adb 模式一重启就没了，运行中也可能被停；两种都先留宽限期再查，Root 模式开机自启、用户重启 Shizuku 都要点时间
 * 到点仍不可用、后端选的是 Shizuku、且有启用的定时才提醒
 * 用闹钟排检查而不是协程 delay：进程进了缓存会被冻结，delay 到不了点
 */
class ShizukuDownMonitor(
    private val context: Context,
    private val repository: ScheduleStrategyRepository,
    private val notificationCenter: MaaNotificationCenter,
    private val telemetry: RunTelemetry,
) {
    private val alarmManager by lazy { context.getSystemService(AlarmManager::class.java) }

    /** 本进程见过 Shizuku 活着之后它没了，才算「停了」；冷启动时 binder 还没到不算 */
    fun start(scope: CoroutineScope) {
        scope.launch {
            var seenAlive = false
            RemoteAccessCoordinator.state
                .map { it.shizukuAvailable }
                .distinctUntilChanged()
                .collect { available ->
                    if (available) {
                        seenAlive = true
                        disarm()
                        notificationCenter.withdrawShizukuDown()
                    } else if (seenAlive) {
                        Timber.i("Shizuku stopped, check again in %ds", STOPPED_GRACE_MS / 1000)
                        arm(afterBoot = false, STOPPED_GRACE_MS)
                    }
                }
        }
    }

    fun armAfterBoot() = arm(afterBoot = true, BOOT_GRACE_MS)

    suspend fun check(afterBoot: Boolean) {
        if (RemoteAccessCoordinator.configuredBackend() != RemoteBackend.SHIZUKU) return
        withTimeoutOrNull(LOAD_TIMEOUT_MS) { repository.isLoaded.first { it } } ?: return
        val enabled = repository.strategies.value.count { it.enabled }
        if (enabled == 0) return
        if (awaitAvailable()) return
        Timber.w("Shizuku down with %d enabled schedule(s), afterBoot=%s", enabled, afterBoot)
        notificationCenter.notifyShizukuDown(afterBoot)
        telemetry.onShizukuDown(afterBoot, enabled)
    }

    /** 闹钟拉起的冷进程，binder 可能还在路上 */
    private suspend fun awaitAvailable(): Boolean = withTimeoutOrNull(BINDER_WAIT_MS) {
        RemoteAccessCoordinator.refresh()
        RemoteAccessCoordinator.state.first { it.shizukuAvailable }
    } != null

    // 不用精确闹钟：晚几分钟提醒无妨，也不必占精确闹钟权限
    private fun arm(afterBoot: Boolean, delayMs: Long) {
        val intent = checkIntent(afterBoot, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                intent,
            )
        }.onFailure { Timber.w(it, "arm Shizuku check failed") }
    }

    private fun disarm() {
        val intent = checkIntent(afterBoot = false, PendingIntent.FLAG_NO_CREATE) ?: return
        alarmManager.cancel(intent)
        intent.cancel()
    }

    // extras 不参与 PendingIntent 判等，开机与停止共用一个闹钟，后排的覆盖先排的
    private fun checkIntent(afterBoot: Boolean, flag: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ShizukuCheckReceiver::class.java)
            .putExtra(ShizukuCheckReceiver.EXTRA_AFTER_BOOT, afterBoot),
        flag or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val BOOT_GRACE_MS = 3 * 60_000L
        const val STOPPED_GRACE_MS = 60_000L
        const val BINDER_WAIT_MS = 3_000L
        const val LOAD_TIMEOUT_MS = 3_000L
    }
}
