package com.aliothmoon.maameow.telemetry

import io.sentry.ISpan
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.protocol.Message
import io.sentry.protocol.SentryId

/** 一条 `SubTaskError` */
internal data class FailureSignal(
    val subtask: String,
    val className: String?,
    /** `ProcessTask` 的起始任务；别的子任务没有这个字段 */
    val first: String?,
    /** `ProcessTask` 最后一个执行成功的任务，一个都没命中时为空串 */
    val preTask: String?,
    val what: String?,
    val why: String?,
    val atMs: Long,
) {
    val isProcessTask: Boolean get() = first != null

    val label: String
        get() = when {
            first == null -> listOfNotNull(subtask, what).joinToString(":")
            preTask.isNullOrBlank() -> "$subtask($first)"
            else -> "$subtask($first, after $preTask)"
        }
}

internal sealed interface Incident {
    val runId: String?
    val tags: Map<String, String>

    /** 写进日志记录的 `diagnostic.reason` */
    val reason: String

    /** 日志记录上用来筛这件事的属性 */
    val logAttributes: Map<String, String>

    fun toSentryEvent(): SentryEvent
}

/**
 * 一条任务链的终态失败，由 [RunTracer] 攒出
 *
 * Core 的子任务出错会层层上报：`ProcessTask` 先报（带起始任务与卡在哪之后），
 * 包着它的子任务重试耗尽再报一条，然后任务链才失败
 * 未标记忽略错误的子任务一失败 `PackageTask` 立即返回，所以最后一条 `SubTaskError` 就是致命的那条
 */
internal data class TaskFailure(
    override val runId: String,
    val taskChain: String,
    val taskId: Int,
    val startedAtMs: Long,
    val durationMs: Long,
    val failedSubTasks: Int,
    /** 最后一条 `SubTaskError`；Core 抛异常时没有 */
    val terminal: FailureSignal?,
    /** 紧挨在 [terminal] 之前的 `ProcessTask` 错误，或 [terminal] 自己 */
    val node: FailureSignal?,
    /** Core 侧异常种类，如 `OutOfMemory` */
    val exception: String?,
    val recent: List<FailureSignal>,
    val options: Map<String, String>,
    override val tags: Map<String, String>,
    /** 事件靠它的上下文挂回所在的 Trace */
    val span: ISpan,
) : Incident {

    override val reason: String get() = "task_failure"

    private val subtask: String get() = terminal?.subtask ?: UNOBSERVED_SUBTASK

    private val nodeLabel: String?
        get() = node?.let { if (it.preTask.isNullOrBlank()) it.first else "${it.first}, after ${it.preTask}" }

    /** Sentry 归组用的指纹；同一轮里也靠它认是不是同一个失败 */
    val fingerprint: List<String>
        get() = listOf(
            TASK_FAILURE_FINGERPRINT,
            taskChain,
            subtask,
            node?.first.orEmpty(),
            node?.preTask.orEmpty(),
            terminal?.what.orEmpty(),
            exception.orEmpty(),
        )

    override val logAttributes: Map<String, String>
        get() = mapOf(
            "task.chain" to taskChain,
            "task.id" to taskId.toString(),
            "failure.subtask" to subtask,
            "failure.node" to node?.first.orEmpty(),
        )

    override fun toSentryEvent(): SentryEvent = SentryEvent().also { event ->
        event.level = SentryLevel.ERROR
        event.logger = TASK_LOGGER
        event.transaction = TASK_FAILURE_TRANSACTION
        event.message = Message().apply {
            formatted = buildString {
                append("Maa task failed: ").append(taskChain)
                // 异常没有子任务可指，不硬凑一个占位
                if (terminal != null || exception == null) append(" at ").append(subtask)
                (exception ?: nodeLabel ?: terminal?.what)?.let { append(" (").append(it).append(')') }
            }
        }
        event.fingerprints = fingerprint

        event.putTags(
            tags + mapOf(
                "task.chain" to taskChain,
                "failure.subtask" to subtask,
                "failure.node" to node?.first.orEmpty(),
                "failure.pre_task" to node?.preTask.orEmpty(),
                "failure.what" to terminal?.what.orEmpty(),
                "failure.exception" to exception.orEmpty(),
                "result" to "failure",
            )
        )

        event.setExtra("task.id", taskId)
        event.setExtra("task.duration_ms", durationMs)
        event.setExtra("task.started_at_ms", startedAtMs)
        event.setExtra("failure.count", failedSubTasks)
        terminal?.className?.let { event.setExtra("failure.class", it) }
        terminal?.why?.let { event.setExtra("failure.why", it) }
        if (recent.isNotEmpty()) event.setExtra("failure.recent", recent.joinToString(" > ") { it.label })
        options.forEach { (key, value) -> event.setExtra("option.$key", value) }

        // 事务的 Span 数满了之后任务 Span 是 no-op，它的 trace id 是全零，写上去反而指错地方
        span.spanContext.takeIf { it.traceId != SentryId.EMPTY_ID }?.let(event.contexts::setTrace)
    }
}

/** 没跑起来：资源、实例、显示或连接层的故障 */
internal data class StartFailure(
    override val runId: String?,
    /** 失败环节的状态码，如 `MAA_CONNECT_ERROR` */
    val code: String,
    /** 同一环节下再细分的原因，目前是异常类名 */
    val what: String? = null,
    val cause: String? = null,
    override val tags: Map<String, String>,
) : Incident {

    override val reason: String get() = "start_failure"

    override val logAttributes: Map<String, String> get() = mapOf("failure.code" to code)

    override fun toSentryEvent(): SentryEvent = SentryEvent().also { event ->
        event.level = SentryLevel.ERROR
        event.logger = RUN_LOGGER
        event.transaction = START_FAILURE_TRANSACTION
        event.message = Message().apply {
            formatted = "Maa start failed: $code" + (what?.let { " ($it)" } ?: "")
        }
        event.fingerprints = listOf(START_FAILURE_FINGERPRINT, code, what.orEmpty())
        event.putTags(tags + mapOf("failure.code" to code, "failure.what" to what.orEmpty(), "result" to "failure"))
        cause?.let { event.setExtra("failure.cause", it) }
    }
}

internal data class ServiceDeath(
    override val runId: String?,
    /** 死的那一刻的执行状态，小写 */
    val state: String,
    /** `died` 是进程没了，`error` 是连接层报错 */
    val serviceState: String,
    val backend: String,
    /** 当时在跑的任务链，还没开跑或已跑完为 null */
    val taskChain: String?,
    override val tags: Map<String, String>,
) : Incident {

    override val reason: String get() = "service_died"

    override val logAttributes: Map<String, String>
        get() = mapOf("run.state" to state, "service.state" to serviceState, "task.chain" to taskChain.orEmpty())

    override fun toSentryEvent(): SentryEvent = SentryEvent().also { event ->
        event.level = SentryLevel.FATAL
        event.logger = RUN_LOGGER
        event.transaction = SERVICE_DEATH_TRANSACTION
        event.message = Message().apply {
            formatted = "Maa service $serviceState during $state" + (taskChain?.let { " ($it)" } ?: "")
        }
        event.fingerprints = listOf(SERVICE_DEATH_FINGERPRINT, serviceState, taskChain.orEmpty(), backend)
        event.putTags(tags + logAttributes + mapOf("result" to "aborted"))
    }
}

/** 定时或外部触发的启动没走到任务开跑：校验没过、界面拉不起来、解不了锁、启动失败 */
internal data class LaunchFailure(
    /** 小写的结果名，如 `failed_ui_launch` */
    val result: String,
    /** 终局文案的英文模板，不随界面语言和参数变 */
    val launchReason: String?,
    val message: String?,
    val delayMs: Long?,
    /** 触发日志相对 debug 目录的路径，整份当证据带上 */
    val logFile: String,
    override val tags: Map<String, String> = emptyMap(),
    val extras: Map<String, Long> = emptyMap(),
    /** 再带上启动诊断日志的尾巴：提权服务连不上的根因在那里 */
    val withBootLogs: Boolean = false,
) : Incident {

    override val runId: String? get() = null

    override val reason: String get() = "launch_failure"

    override val logAttributes: Map<String, String> get() = mapOf("launch.result" to result)

    override fun toSentryEvent(): SentryEvent = SentryEvent().also { event ->
        event.level = SentryLevel.ERROR
        event.logger = RUN_LOGGER
        event.transaction = LAUNCH_FAILURE_TRANSACTION
        event.message = Message().apply {
            formatted = "Maa launch failed: $result" + (launchReason?.let { " ($it)" } ?: "")
        }
        event.fingerprints = listOf(LAUNCH_FAILURE_FINGERPRINT, result, launchReason.orEmpty())
        event.putTags(
            tags + mapOf("launch.result" to result, "launch.reason" to launchReason.orEmpty(), "result" to "failure")
        )
        message?.let { event.setExtra("launch.message", it) }
        delayMs?.let { event.setExtra("launch.delay_ms", it) }
        extras.forEach { (key, value) -> event.setExtra(key, value) }
    }
}

/** 有启用的定时而 Shizuku 没在跑，提醒用户时报一条，看它多常发生、多是开机后没起还是中途停了 */
internal data class ShizukuDown(
    val afterBoot: Boolean,
    val enabledSchedules: Int,
    override val tags: Map<String, String>,
    val extras: Map<String, Long>,
) : Incident {

    private val cause: String get() = if (afterBoot) "boot" else "stopped"

    override val runId: String? get() = null

    override val reason: String get() = "shizuku_down"

    override val logAttributes: Map<String, String> get() = mapOf("shizuku.down" to cause)

    override fun toSentryEvent(): SentryEvent = SentryEvent().also { event ->
        event.level = SentryLevel.WARNING
        event.logger = RUN_LOGGER
        event.transaction = SHIZUKU_DOWN_TRANSACTION
        event.message = Message().apply {
            formatted = "Shizuku not running with schedules enabled ($cause)"
        }
        event.fingerprints = listOf(SHIZUKU_DOWN_FINGERPRINT, cause)
        event.putTags(tags + mapOf("shizuku.down" to cause))
        event.setExtra("schedules.enabled", enabledSchedules)
        extras.forEach { (key, value) -> event.setExtra(key, value) }
    }
}

/**
 * 证据带没带上、为什么没带，写进 `logs.*` 与 `attachment.*`
 *
 * 日志正文走 Sentry Logs、截图是附件，事件自己身上只留这份摘要
 */
internal fun SentryEvent.setEvidence(evidence: Evidence) {
    val logs = evidence.logs
    if (logs == null) {
        setExtra("logs.status", "not_available")
    } else {
        setExtra("logs.status", if (logs.entries.isEmpty()) "no_evidence" else "captured")
        setExtra("logs.count", logs.entries.size)
        setExtra("logs.selected_raw_bytes", logs.selectedRawBytes)
        setExtra("logs.truncated", logs.truncated)
        if (logs.warnings.isNotEmpty()) setExtra("logs.warnings", logs.warnings.joinToString(","))
    }
    when (val attachment = evidence.attachment) {
        AttachmentOutcome.NotSelected -> setExtra("attachment.status", "not_selected")
        is AttachmentOutcome.Attached -> {
            setExtra("attachment.status", "attached")
            setExtra("attachment.image_count", attachment.images.size)
            setExtra("attachment.bytes", attachment.images.sumOf { it.bytes.size })
        }
        is AttachmentOutcome.Omitted -> {
            setExtra("attachment.status", attachment.status)
            setExtra("attachment.detail", attachment.detail)
        }
    }
}

/** 事件 ID 在构造时就定了，发之前即可引用 */
internal fun SentryEvent.diagnosticLogContext(incident: Incident): DiagnosticLogContext {
    val trace = contexts.trace
    return DiagnosticLogContext(
        eventId = eventId.toString(),
        reason = incident.reason,
        runId = incident.runId,
        attributes = incident.logAttributes,
        traceId = trace?.traceId?.toString(),
        spanId = trace?.spanId?.toString(),
    )
}

private fun SentryEvent.putTags(tags: Map<String, String>) {
    tags.forEach { (key, value) -> if (value.isNotBlank()) setTag(key, value.take(MAX_TAG_VALUE_LENGTH)) }
}

internal const val TASK_FAILURE_TRANSACTION = "maameow.task.failure"
internal const val START_FAILURE_TRANSACTION = "maameow.start.failure"
internal const val SERVICE_DEATH_TRANSACTION = "maameow.service.died"
internal const val LAUNCH_FAILURE_TRANSACTION = "maameow.launch.failure"
internal const val SHIZUKU_DOWN_TRANSACTION = "maameow.shizuku.down"
private const val TASK_LOGGER = "maameow.task"
private const val RUN_LOGGER = "maameow.run"
private const val TASK_FAILURE_FINGERPRINT = "maameow-task-failure"
private const val START_FAILURE_FINGERPRINT = "maameow-start-failure"
private const val SERVICE_DEATH_FINGERPRINT = "maameow-service-died"
private const val LAUNCH_FAILURE_FINGERPRINT = "maameow-launch-failure"
private const val SHIZUKU_DOWN_FINGERPRINT = "maameow-shizuku-down"

/** 任务链没报过子任务错误就失败时的占位，与 MaaFwApp 同名 */
internal const val UNOBSERVED_SUBTASK = "terminal_failure"

/** Sentry 对 tag 值的长度上限 */
private const val MAX_TAG_VALUE_LENGTH = 200
