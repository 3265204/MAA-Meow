package com.aliothmoon.maameow.domain.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.aliothmoon.maameow.R
import com.aliothmoon.maameow.domain.models.RunDurationLimit
import com.aliothmoon.maameow.domain.models.RunMode
import com.aliothmoon.maameow.domain.notification.LiveCategory
import com.aliothmoon.maameow.domain.notification.LiveNotifyIds
import com.aliothmoon.maameow.domain.notification.LiveSession
import com.aliothmoon.maameow.domain.notification.LiveSessionCoordinator
import com.aliothmoon.maameow.domain.state.MaaExecutionState
import com.aliothmoon.maameow.maa.callback.TaskChainStatusTracker
import com.aliothmoon.maameow.maa.callback.TaskRunInfo
import com.aliothmoon.maameow.maa.callback.TaskRunStatus
import com.aliothmoon.maameow.schedule.service.ScheduleWakeLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import timber.log.Timber

class TaskExecutionService : Service() {

    companion object {
        private const val MIN_UPDATE_INTERVAL_MS = 1000L
        private const val WAKE_LOCK_SAFETY_MARGIN_MINUTES = 60L

        // 单次超时只防异常路径永久持锁，不是整场任务累计硬上限：超时后要等下一次内容有变化的状态快照才会重新获取
        // （combine(...).distinctUntilChanged() 会吞掉完全相同的快照），累计持有无硬边界；
        // 任务时长限制默认关闭且辅助任务不受其约束，不能推出任务必在 MAX_WAKE_LOCK_HOLD_MS 内结束；
        // 超时回调基于 uptime，设备真正 suspend 时不推进。
        private const val MAX_WAKE_LOCK_HOLD_MS =
            (RunDurationLimit.MAX_MINUTES + WAKE_LOCK_SAFETY_MARGIN_MINUTES) * 60_000L
        private const val TASK_WAKE_LOCK_TAG = "MaaMeow:task-execution"

        private val VISIBLE_TASK_TITLE_RES = mapOf(
            "Fight" to R.string.maa_fight,
            "Recruit" to R.string.maa_recruit,
            "Infrast" to R.string.maa_infrast,
            "Mall" to R.string.maa_mall,
            "Award" to R.string.maa_award,
            "Roguelike" to R.string.maa_roguelike,
            "Copilot" to R.string.maa_copilot,
            "SSSCopilot" to R.string.maa_sss_copilot,
            "ParadoxCopilot" to R.string.maa_paradox_copilot,
            "Reclamation" to R.string.maa_reclamation,
            "Custom" to R.string.maa_custom,
            "CloseDown" to R.string.maa_close_down,
            "StartUp" to R.string.maa_start_up,
            "Depot" to R.string.maa_depot,
            "OperBox" to R.string.maa_oper_box,
            "SwitchTheme" to R.string.maa_switch_theme,
        )

        // 只提供 start 不提供外部 stop：startForegroundService 后若 stopService
        // 抢在服务创建前到达，系统会因 startForeground 未调用直接杀进程；
        // 终态退出由服务观察状态流自行 stopSelf 完成
        fun start(context: Context) {
            val intent = Intent(context, TaskExecutionService::class.java)
            context.startForegroundService(intent)
        }
    }

    private val compositionService: MaaCompositionService by inject()
    private val sessionLogger: MaaSessionLogger by inject()
    private val taskChainStatusTracker: TaskChainStatusTracker by inject()
    private val liveCoordinator: LiveSessionCoordinator by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressJob: Job? = null
    private var renderJob: Job? = null
    private var boundToken: Long = 0L
    private var observeToken: Long = 0L
    private var taskWakeLock: PowerManager.WakeLock? = null

    /** startForeground 被系统拒绝后本实例已 stopSelf，排队中的 start 不再重试 */
    private var foregroundDenied = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        bindToken()
        // 必须先 startForeground，再做任何可能读到 IDLE/ERROR 并 stop 的逻辑，
        // 避免与 Composition 快速失败/stopService 竞态触发
        // ForegroundServiceDidNotStartInTimeException。
        // postForegroundNotification 只负责构建+notify，耗时的断网闸门已在 STARTING 时拿过
        val initial = currentSnapshot()
        if (!startAsForeground(postForegroundNotification(initial, firstFloat = true))) return
        if (isTerminal(initial.state)) {
            handleTerminalState(boundToken, initial)
            return
        }
        ensureTaskWakeLock()
        ensureObserveProgress()
        ensureObserveRenderChanges()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (foregroundDenied) return START_NOT_STICKY
        bindToken()
        // 系统可能只走 onStartCommand；再保证一次 FGS 提升
        // onCreate 若因终态提前 return 未启动观察，随后竞态进入 STARTING 时须在此补上
        val snapshot = currentSnapshot()
        if (!startAsForeground(postForegroundNotification(snapshot, firstFloat = false))) {
            return START_NOT_STICKY
        }
        if (isTerminal(snapshot.state)) {
            handleTerminalState(boundToken, snapshot)
        } else {
            ensureTaskWakeLock()
            ensureObserveProgress()
            ensureObserveRenderChanges()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // 系统侧终止与 StateFlow 收集存在竞态；此处兜底确保 Live Update 通知被清除。
        // observeProgress 的 collector 由 serviceScope.cancel() 结构化取消
        progressJob = null
        releaseTaskWakeLock()
        removeActiveNotification(boundToken)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun bindToken() {
        boundToken = liveCoordinator.currentToken()
    }

    private fun currentSnapshot(): TaskNotificationSnapshot = TaskNotificationSnapshot(
        state = compositionService.state.value,
        statusText = sessionLogger.logs.value.lastOrNull()?.content,
        tasks = taskChainStatusTracker.tasks.value,
    )

    private fun ensureObserveProgress() {
        if (progressJob?.isActive == true && observeToken == boundToken) return
        progressJob?.cancel()
        observeToken = boundToken
        val token = observeToken
        progressJob = serviceScope.launch {
            var lastUpdateTime = 0L
            var pending: TaskNotificationSnapshot? = null
            var scheduledJob: Job? = null

            fun resetSchedule() {
                scheduledJob?.cancel()
                scheduledJob = null
                pending = null
            }

            fun forceUpdate(snapshot: TaskNotificationSnapshot) {
                resetSchedule()
                lastUpdateTime = SystemClock.elapsedRealtime()
                updateNotification(token, snapshot)
            }

            fun throttledUpdate(snapshot: TaskNotificationSnapshot) {
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - lastUpdateTime
                if (elapsed >= MIN_UPDATE_INTERVAL_MS) {
                    lastUpdateTime = now
                    updateNotification(token, snapshot)
                    return
                }
                pending = snapshot
                if (scheduledJob?.isActive == true) return
                scheduledJob = launch {
                    delay((MIN_UPDATE_INTERVAL_MS - elapsed).coerceAtLeast(0L))
                    pending?.let {
                        lastUpdateTime = SystemClock.elapsedRealtime()
                        updateNotification(token, it)
                    }
                    pending = null
                    scheduledJob = null
                }
            }

            // 三个数据源合并为单一 Snapshot 流，distinctUntilChanged 避免无意义刷新
            combine(
                compositionService.state,
                taskChainStatusTracker.tasks,
                sessionLogger.logs
                    .map { it.lastOrNull()?.content }
                    .distinctUntilChanged(),
            ) { state, tasks, lastLog ->
                TaskNotificationSnapshot(state, lastLog, tasks)
            }
                .distinctUntilChanged()
                .collect { snapshot ->
                    when (snapshot.state) {
                        MaaExecutionState.IDLE,
                        MaaExecutionState.ERROR -> {
                            resetSchedule()
                            handleTerminalState(token, snapshot)
                        }

                        MaaExecutionState.STARTING -> {
                            ensureTaskWakeLock()
                            forceUpdate(
                                snapshot.copy(
                                    statusText = getString(R.string.notification_task_starting)
                                )
                            )
                        }

                        MaaExecutionState.STOPPING -> {
                            ensureTaskWakeLock()
                            forceUpdate(
                                snapshot.copy(
                                    statusText = getString(R.string.notification_task_stopping)
                                )
                            )
                        }

                        MaaExecutionState.RUNNING -> {
                            ensureTaskWakeLock()
                            throttledUpdate(
                                snapshot.copy(
                                    statusText = snapshot.statusText
                                        ?: getString(R.string.notification_task_running)
                                )
                            )
                        }
                    }
                }
        }
    }

    private fun handleTerminalState(token: Long, snapshot: TaskNotificationSnapshot) {
        if (!isTerminal(compositionService.state.value)) return
        // 锁只跟随实时运行状态；通知 token 过期不能阻止终态释放。
        releaseTaskWakeLock()
        if (!liveCoordinator.isCurrent(token)) return
        Timber.i("TaskExecutionService: state=%s token=%s, stopping", snapshot.state, token)
        liveCoordinator.cancelProgress(token)
        clearProgressNotification()
        stopSelf()
    }

    /** 被拒时停服务，onDestroy 撤掉已发出的进度通知；任务本身仍在提权进程继续 */
    private fun startAsForeground(notification: Notification): Boolean {
        try {
            SpecialUseFgsGate.startForeground(this, LiveNotifyIds.PROGRESS, notification)
            return true
        } catch (e: SecurityException) {
            // 预检放行但系统仍拒（如 appop 为 FOREGROUND）；AOSP 抛出前已清 fgRequired，stopSelf 不会触发未启动超时
            Timber.w(e, "TaskExecutionService: startForeground denied, run without FGS")
            foregroundDenied = true
            SpecialUseFgsGate.appendDeniedLog(this, sessionLogger)
            stopSelf()
            return false
        }
    }

    private fun ensureTaskWakeLock() {
        // 模式门控只作用于获取：前台模式靠屏幕亮着运行，持锁只会让熄屏后的识别白耗电；
        // 释放不加 mode 前置条件，原因见 releaseTaskWakeLock。
        if (compositionService.currentRunMode != RunMode.BACKGROUND) return
        // isHeld 只反映客户端状态：Doze/cached 仅在系统侧禁用，重复 acquire 无法解禁；
        // 只有客户端超时或显式 release 会翻成 false，之后的下一次状态快照才重新获取。
        // 保留该早退以避免每个状态快照都重复 acquire：AOSP acquire(long) 每次都会重排 mReleaser，使超时无限顺延；
        // ScheduleWakeLock.acquire 每次都会 newWakeLock，覆盖字段后旧对象便再无句柄可释放。
        if (taskWakeLock?.isHeld == true) return
        val lock = runCatching {
            ScheduleWakeLock.acquire(this, MAX_WAKE_LOCK_HOLD_MS, TASK_WAKE_LOCK_TAG)
        }
            .onFailure {
                Timber.e(
                    it,
                    "TaskExecutionService: acquire wake lock failed tag=%s, task continues without it",
                    TASK_WAKE_LOCK_TAG,
                )
            }
            .getOrNull() ?: return
        taskWakeLock = lock
        // ReleaseTree/FileLogTree 在发布版只放行 WARN 及以上；这是设备是否被唤醒保护的排障锚点，故用 warn 而非 info。
        Timber.w(
            "TaskExecutionService: wake lock acquired tag=%s timeout=%dms",
            TASK_WAKE_LOCK_TAG,
            MAX_WAKE_LOCK_HOLD_MS,
        )
    }

    /**
     * 释放任务 WakeLock。主路径是 [onDestroy]（服务实例必经）；[handleTerminalState] 里那次是
     * stopSelf 到 onDestroy 之间的窗口期提前释放，两者共用本方法并靠字段置空保证幂等。
     * 释放不能加 currentRunMode/状态前置条件：字段可能已被终态清空或被下一轮刷新，否则 rejectStart
     * 的锁泄漏会回归；释放可能先于下一轮 acquire，下一轮自己的 STARTING 快照会重新取锁。
     */
    private fun releaseTaskWakeLock() {
        val wakeLock = taskWakeLock ?: return
        taskWakeLock = null
        runCatching {
            if (ScheduleWakeLock.release(wakeLock)) {
                Timber.w("TaskExecutionService: wake lock released tag=%s", TASK_WAKE_LOCK_TAG)
            } else {
                Timber.w(
                    "TaskExecutionService: wake lock release skipped, already reclaimed (client timeout) tag=%s",
                    TASK_WAKE_LOCK_TAG,
                )
            }
        }
            .onFailure {
                Timber.w(
                    it,
                    "TaskExecutionService: release wake lock failed tag=%s",
                    TASK_WAKE_LOCK_TAG,
                )
            }
    }

    private fun postForegroundNotification(
        snapshot: TaskNotificationSnapshot,
        firstFloat: Boolean,
    ): Notification {
        val session = toSession(snapshot, firstFloat)
        return liveCoordinator.postProgressForeground(boundToken, session)
            ?: liveCoordinator.fallbackBuild(session)
    }

    private fun toSession(
        snapshot: TaskNotificationSnapshot,
        firstFloat: Boolean,
    ): LiveSession {
        val statusText = snapshot.statusText ?: defaultStatusText(snapshot.state)
        val progress = progressOf(snapshot)
        val activeName = activeTaskName(snapshot)
        val title = activeName ?: getString(R.string.notification_task_running_title)
        val label = progress.label
        val contentText = if (label != null) "$label · $statusText" else statusText
        // AOSP 胶囊只有一个字符串：任务名在前，系统从尾部截断时先保住它
        // 不预截断（8 字上限只是超级岛摘要态的要求）；任务链未登记时留空，由下游决定不设胶囊
        val capsule = listOfNotNull(activeName, label).joinToString(" ")
        return LiveSession(
            sessionId = LiveNotifyIds.PROGRESS_SESSION,
            category = LiveCategory.PROGRESS,
            title = title,
            text = contentText,
            capsuleText = capsule,
            taskName = activeName,
            progressCurrent = progress.current,
            progressMax = progress.max,
            progressLabel = progress.label,
            ongoing = true,
            firstFloat = firstFloat,
            timeoutSec = 86_400,
            isError = snapshot.state == MaaExecutionState.ERROR || progress.hasTaskError,
        )
    }

    /** 样式或后端设置变化时重发当前进度，不等下一次任务事件 */
    private fun ensureObserveRenderChanges() {
        if (renderJob?.isActive == true) return
        renderJob = serviceScope.launch {
            liveCoordinator.renderChanges.collect { liveCoordinator.republishProgress(boundToken) }
        }
    }

    private fun defaultStatusText(state: MaaExecutionState): String = when (state) {
        MaaExecutionState.STARTING -> getString(R.string.notification_task_starting)
        MaaExecutionState.STOPPING -> getString(R.string.notification_task_stopping)
        MaaExecutionState.RUNNING -> getString(R.string.notification_task_running)
        MaaExecutionState.IDLE -> getString(R.string.notification_task_completed)
        MaaExecutionState.ERROR -> getString(R.string.notification_task_error)
    }

    private fun progressOf(snapshot: TaskNotificationSnapshot): ProgressNumbers {
        val tasks = snapshot.tasks
        val total = tasks.size
        // 任务链还没登记时 max=0，交给下游渲染成不确定态而不是停在 0%
        if (total == 0) {
            return ProgressNumbers(0, 0, null, hasTaskError = false)
        }
        val completedCount = tasks.count { it.status == TaskRunStatus.COMPLETED }
        val activeIndex = tasks.indexOfFirst { it.status == TaskRunStatus.IN_PROGRESS }
            .takeIf { it >= 0 }
        val taskErrorIndex = tasks.indexOfFirst { it.status == TaskRunStatus.ERROR }
            .takeIf { it >= 0 }

        fun progressFor(finishedCount: Int): Int =
            (finishedCount.toLong() * LiveNotifyIds.PROGRESS_STYLE_MAX / total).toInt()

        val current = when {
            taskErrorIndex != null -> progressFor(taskErrorIndex + 1)
            snapshot.state == MaaExecutionState.ERROR -> progressFor(completedCount)
            snapshot.state == MaaExecutionState.IDLE -> LiveNotifyIds.PROGRESS_STYLE_MAX
            snapshot.state == MaaExecutionState.STOPPING -> {
                val idx = completedCount.coerceAtLeast(activeIndex ?: completedCount)
                    .coerceIn(0, total)
                progressFor(idx)
            }

            activeIndex != null -> {
                progressFor(activeIndex) + (LiveNotifyIds.PROGRESS_STYLE_MAX / total) / 2
            }

            completedCount > 0 -> progressFor(completedCount)
            else -> 0
        }.coerceIn(0, LiveNotifyIds.PROGRESS_STYLE_MAX)

        return ProgressNumbers(
            current = current,
            max = LiveNotifyIds.PROGRESS_STYLE_MAX,
            label = "$completedCount/$total",
            hasTaskError = taskErrorIndex != null,
        )
    }

    private fun activeTaskName(snapshot: TaskNotificationSnapshot): String? =
        snapshot.tasks
            .firstOrNull { it.status == TaskRunStatus.IN_PROGRESS }
            ?.taskChain
            ?.trim()
            ?.let { taskChain ->
                // 未映射的任务类型回退原始名，避免进度数字失去任务名参照
                VISIBLE_TASK_TITLE_RES[taskChain]?.let(::getString) ?: taskChain
            }

    private fun updateNotification(token: Long, snapshot: TaskNotificationSnapshot) {
        if (!liveCoordinator.isCurrent(token)) return
        liveCoordinator.publishProgress(token, toSession(snapshot, firstFloat = false))
    }

    private fun removeActiveNotification(token: Long) {
        if (liveCoordinator.isCurrent(token)) {
            liveCoordinator.cancelProgress(token)
        }
        clearProgressNotification()
    }

    private fun clearProgressNotification() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.cancel(LiveNotifyIds.PROGRESS)
        } catch (e: SecurityException) {
            Timber.w(e, "cancel blocked by POST_NOTIFICATIONS denial")
        }
    }

    private fun isTerminal(state: MaaExecutionState): Boolean =
        state == MaaExecutionState.IDLE || state == MaaExecutionState.ERROR

    private data class TaskNotificationSnapshot(
        val state: MaaExecutionState,
        val statusText: String?,
        val tasks: List<TaskRunInfo>,
    )

    private data class ProgressNumbers(
        val current: Int,
        val max: Int,
        val label: String?,
        /** 任务链里有子任务失败，进度条与强调色转红，即使整条链还在继续 */
        val hasTaskError: Boolean,
    )
}
