package com.aliothmoon.maameow.manager

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.Process
import com.aliothmoon.maameow.BuildConfig
import com.aliothmoon.maameow.RemoteService
import com.aliothmoon.maameow.remote.CoreDataDir
import com.aliothmoon.maameow.root.BootstrapRegistry
import com.aliothmoon.maameow.root.RootServiceBootstrapRegistry
import com.aliothmoon.maameow.root.RemoteServiceStarter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.milliseconds

/** Android 14+ 输入注入需要 Root UID；launcher 在 shell 身份下忽略该标志 */
internal val keepRootForInputInjection: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

/**
 * 自研 starter 进程路径的公共基座：launcher 拉起 app_process，
 * binder 回投经 MaaMeow 自己的 bootstrap provider 按 token 认领，
 * 生命周期由 appLifecycleBinder linkToDeath + 心跳看门狗自管
 *
 * 拉起方式由 [spawner] 决定（su 后台化 / Shizuku 前台 exec），服务规格由子类给出
 */
abstract class ProcessServiceConnectorBackend(
    private val spawner: ProcessSpawner,
    private val registry: BootstrapRegistry = RootServiceBootstrapRegistry,
) : RemoteServiceConnectorBackend {

    protected open val spawnTimeoutMs: Long = 15_000L

    // spawn 同步调用与超时后的 kill / dump 不在 withTimeout 内，另计余量
    override val worstCaseConnectMs: Long
        get() = (spawnTimeoutMs + SPAWN_OVERHEAD_MS) *
            (if (sidecarServiceClass == null) 1 else 2)

    protected abstract val eventPrefix: String

    /** 同时是残留清理的匹配键 */
    protected abstract val processNameSuffix: String

    protected abstract val serviceClass: Class<*>

    /** 各连接器独立，launcher O_TRUNC 打开，共用会互相覆盖 */
    protected abstract val logFileName: String

    protected open val keepRoot: Boolean get() = false

    /** 可选的永久降权辅助进程；主 Binder 对外发布前完成挂载。 */
    protected open val sidecarServiceClass: Class<*>? get() = null
    protected open val sidecarProcessNameSuffix: String get() = "${processNameSuffix}_sidecar"
    protected open val sidecarLogFileName: String get() = "${sidecarProcessNameSuffix}_launch_debug.log"
    protected open val sidecarKeepRoot: Boolean get() = false

    protected open fun attachSidecar(primaryBinder: IBinder, sidecarBinder: IBinder) = Unit

    protected open fun destroySidecar(sidecarBinder: IBinder) = Unit

    // 单并发：spawn 与 killResidual 都是阻塞调用，独占队列位，先入队者先做完
    private val scope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())

    @Volatile
    private var activeLaunch: ActiveLaunch? = null

    private val currentSidecarBinder = java.util.concurrent.atomic.AtomicReference<IBinder?>()

    private lateinit var appContext: Context

    /** launcher 以 shell 身份写 --log-file，落 core 侧 debug/ */
    private lateinit var debugDir: File

    /** 拉起进行中（binder 尚未回投） */
    val isConnecting: Boolean get() = activeLaunch?.job?.isActive == true

    private val processName: String get() = "${appContext.packageName}:$processNameSuffix"
    private val sidecarProcessName: String
        get() = "${appContext.packageName}:$sidecarProcessNameSuffix"

    fun initialize(context: Context, debugDir: File) {
        appContext = context.applicationContext
        this.debugDir = debugDir
    }

    override fun connect(callbacks: RemoteServiceConnectorBackend.Callbacks) {
        check(::appContext.isInitialized) { "${javaClass.simpleName} is not initialized" }
        // 取代进行中的旧拉起：旧 token 即刻注销，旧进程回投会被 provider 拒绝而自退
        dropActiveLaunch()
        val token = UUID.randomUUID().toString()
        val deferred = registry.register(token)

        val job = scope.launch {
            val logFile = debugLogFile()
            var spawned = false
            // 整段包住：check(launcherFile) 等异常逃逸会让协程未捕获致崩溃
            val result = runCatching {
                // 先清旧日志，确保 dump 到的一定是本次启动的产物
                logFile.delete()
                val command = buildStartCommand(token, logFile)
                ServiceBootLogger.event("${eventPrefix}_SPAWN_CALL", "token=$token")
                val handle = spawner.spawn(command)
                spawned = true
                val primaryBinder = awaitBinder(deferred, handle)
                attachOptionalSidecar(token, primaryBinder)
                primaryBinder
            }
            if (activeLaunch?.token != token) {
                registry.unregister(token)
                return@launch
            }
            result.onSuccess { binder ->
                onBinderArrived(token, binder, callbacks)
            }.onFailure { throwable ->
                activeLaunch = null
                registry.unregister(token)
                // 进程路径没有 server 端 record 代杀，卡在半路的服务进程只能自己清
                if (spawned) spawner.killResidual(processName)
                if (sidecarServiceClass != null) spawner.killResidual(sidecarProcessName)
                reportFailure(token, throwable, logFile, callbacks)
            }
        }
        activeLaunch = ActiveLaunch(token, job)
    }

    override fun disconnect(currentBinder: IBinder?) {
        dropActiveLaunch()
        if (currentBinder != null) {
            // 主进程通过 linkToDeath 管理辅助进程。正常退出时先由主进程释放 VDM，
            // 再由主进程死亡通知结束辅助进程；若先结束辅助进程，主进程会将其误判为崩溃。
            val sidecarBinder = currentSidecarBinder.getAndSet(null)
            runCatching { destroyRemote(currentBinder) }
                .onFailure {
                    Timber.w(it, "destroy %s remote service failed", backend)
                    sidecarBinder?.let(::terminateSidecar)
                }
        } else {
            clearSidecar(killWhenMissing = true)
            // 没连上就断开：进程可能卡在半路，排进单并发队列先清后起
            scope.launch { spawner.killResidual(processName) }
        }
    }

    protected open fun destroyRemote(binder: IBinder) {
        RemoteService.Stub.asInterface(binder)?.destroy()
    }

    protected open fun buildStartCommand(token: String, logFile: File): String {
        return buildProcessStartCommand(
            token = token,
            logFile = logFile,
            targetProcessName = processName,
            targetServiceClass = serviceClass,
            targetKeepRoot = keepRoot,
        )
    }

    protected open fun buildSidecarStartCommand(
        token: String,
        logFile: File,
        targetClass: Class<*>,
    ): String = buildProcessStartCommand(
        token = token,
        logFile = logFile,
        targetProcessName = sidecarProcessName,
        targetServiceClass = targetClass,
        targetKeepRoot = sidecarKeepRoot,
    )

    private fun buildProcessStartCommand(
        token: String,
        logFile: File,
        targetProcessName: String,
        targetServiceClass: Class<*>,
        targetKeepRoot: Boolean,
    ): String {
        val launcher = File(appContext.applicationInfo.nativeLibraryDir, "liblauncher.so")
        check(launcher.exists()) { "launcher not found: ${launcher.absolutePath}" }
        val invocation = buildString {
            append(shellQuote(launcher.absolutePath))
            append(" --apk=").append(shellQuote(appContext.applicationInfo.sourceDir))
            append(" --process-name=").append(shellQuote(targetProcessName))
            append(" --starter-class=").append(shellQuote(RemoteServiceStarter::class.java.name))
            append(" --token=").append(shellQuote(token))
            append(" --package=").append(shellQuote(appContext.packageName))
            append(" --class=").append(shellQuote(targetServiceClass.name))
            append(" --uid=").append(Process.myUid())
            if (targetKeepRoot) append(" --keep-root")
            append(" --log-file=").append(shellQuote(logFile.absolutePath))
            if (BuildConfig.DEBUG) {
                append(" --debug-name=").append(shellQuote(targetProcessName))
            }
        }
        // 独立目录下 debug/ 在 /data/local/tmp，App 进程建不了，交给 shell
        val mkdir = "mkdir -p ${shellQuote(logFile.parentFile!!.absolutePath)} 2>/dev/null; "
        return mkdir + spawner.wrapCommand(launcher.absolutePath, invocation)
    }

    protected open fun debugLogFile(): File {
        runCatching { debugDir.mkdirs() }
        return File(debugDir, logFileName)
    }

    private fun dropActiveLaunch() {
        val previous = activeLaunch ?: return
        activeLaunch = null
        previous.job.cancel()
        registry.unregister(previous.token)
        previous.sidecarToken?.let(registry::unregister)
    }

    private suspend fun attachOptionalSidecar(primaryToken: String, primaryBinder: IBinder) {
        val targetClass = sidecarServiceClass ?: return
        val token = UUID.randomUUID().toString()
        val launch = activeLaunch
        if (launch?.token != primaryToken) return
        launch.sidecarToken = token
        val deferred = registry.register(token)
        val logFile = File(debugDir, sidecarLogFileName)
        logFile.delete()

        val sidecarBinder = runCatching {
            val command = buildSidecarStartCommand(token, logFile, targetClass)
            ServiceBootLogger.event("${eventPrefix}_SIDECAR_SPAWN_CALL", "token=$token")
            val handle = spawner.spawn(command)
            awaitBinder(deferred, handle)
        }.onFailure { failure ->
            registry.unregister(token)
            spawner.killResidual(sidecarProcessName)
            val described = describeFailure(
                throwable = failure,
                logTail = dumpSpawnDebugLog(logFile, sidecarProcessName),
                targetProcessName = sidecarProcessName,
            )
            ServiceBootLogger.event(
                "${eventPrefix}_SIDECAR_FAIL",
                "${described.javaClass.simpleName}: ${described.message}",
            )
            Timber.e(described, "%s sidecar failed; independent display will use legacy fallback", sidecarProcessName)
        }.getOrNull() ?: return

        runCatching { attachSidecar(primaryBinder, sidecarBinder) }
            .onSuccess {
                currentSidecarBinder.getAndSet(sidecarBinder)?.let(::terminateSidecar)
                ServiceBootLogger.event("${eventPrefix}_SIDECAR_CONNECTED", "token=$token")
            }
            .onFailure { failure ->
                runCatching { destroySidecar(sidecarBinder) }
                spawner.killResidual(sidecarProcessName)
                Timber.e(failure, "Failed to attach %s sidecar", sidecarProcessName)
            }
    }

    private fun clearSidecar(killWhenMissing: Boolean) {
        val binder = currentSidecarBinder.getAndSet(null)
        if (binder != null) {
            terminateSidecar(binder)
        } else if (killWhenMissing && sidecarServiceClass != null) {
            scope.launch { spawner.killResidual(sidecarProcessName) }
        }
    }

    private fun terminateSidecar(binder: IBinder) {
        runCatching { destroySidecar(binder) }
            .onFailure {
                Timber.w(it, "destroy %s failed", sidecarProcessName)
                scope.launch { spawner.killResidual(sidecarProcessName) }
            }
    }

    /** binder 回投与进程存活竞速：进程先退出立即失败并携带退出码，不等满超时 */
    private suspend fun awaitBinder(deferred: Deferred<IBinder>, handle: SpawnHandle?): IBinder =
        withTimeout(spawnTimeoutMs.milliseconds) {
            if (handle == null) return@withTimeout deferred.await()
            coroutineScope {
                val watcher = launch {
                    while (isActive) {
                        if (!handle.isAlive()) throw ProcessExitedException(handle.exitCode())
                        delay(ALIVE_POLL_INTERVAL_MS.milliseconds)
                    }
                }
                try {
                    deferred.await()
                } finally {
                    watcher.cancel()
                }
            }
        }

    private fun onBinderArrived(
        token: String,
        binder: IBinder,
        callbacks: RemoteServiceConnectorBackend.Callbacks,
    ) {
        // token 认领：旧进程死讯不能误伤新连接
        runCatching {
            binder.linkToDeath({
                if (activeLaunch?.token != token) {
                    Timber.i("Stale %s process binder death ignored (token=%s)", processName, token)
                    return@linkToDeath
                }
                Timber.e("%s process died unexpectedly.", processName)
                clearSidecar(killWhenMissing = true)
                callbacks.onDisconnected(backend)
            }, 0)
        }.onFailure {
            Timber.w(it, "linkToDeath failed for %s process binder", processName)
        }

        Timber.i("%s connected by %s bootstrap", processName, backend)
        ServiceBootLogger.event("${eventPrefix}_PROCESS_CONNECTED", "token=$token")
        callbacks.onConnected(backend, binder)
    }

    private fun reportFailure(
        token: String,
        throwable: Throwable,
        logFile: File,
        callbacks: RemoteServiceConnectorBackend.Callbacks,
    ) {
        val error = describeFailure(
            throwable,
            dumpSpawnDebugLog(logFile, processName),
            processName,
        )
        ServiceBootLogger.event(
            "${eventPrefix}_PROCESS_FAIL",
            "token=$token ${error.javaClass.simpleName}: ${error.message}"
        )
        Timber.e(error, "%s start failed: token=%s", processName, token)
        callbacks.onError(backend, error)
    }

    /** 失败原因带上 launcher 日志尾部，日志导出里能直接看到进程侧死因 */
    private fun describeFailure(
        throwable: Throwable,
        logTail: String?,
        targetProcessName: String,
    ): Throwable {
        val suffix = if (logTail.isNullOrBlank()) "" else "; launcher log tail: $logTail"
        return when {
            throwable is TimeoutCancellationException ->
                TimeoutException("$targetProcessName binder not attached within ${spawnTimeoutMs}ms$suffix")

            suffix.isEmpty() -> throwable
            else -> IllegalStateException("${throwable.message}$suffix", throwable)
        }
    }

    /** 全量打进 Timber，返回尾部几行供拼进错误信息 */
    private fun dumpSpawnDebugLog(log: File, targetProcessName: String): String? {
        if (!log.exists()) {
            if (log.absolutePath.startsWith(CoreDataDir.ROOT)) {
                Timber.e(
                    "%s launch debug log is in core dir, unreadable from app: %s (adb shell cat it, or export logs)",
                    targetProcessName,
                    log.absolutePath,
                )
            } else {
                Timber.e("%s launch debug log not found: %s", targetProcessName, log.absolutePath)
            }
            return null
        }
        val lines =
            runCatching { log.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
        if (lines.isEmpty()) {
            Timber.e(
                "%s launch debug log is empty (launcher may have crashed before opening it)",
                targetProcessName
            )
            return null
        }
        Timber.e(
            "%s launch debug log (%s):\n%s",
            targetProcessName,
            log.absolutePath,
            lines.joinToString("\n")
        )
        return lines.takeLast(LOG_TAIL_LINES).joinToString(" | ")
    }

    private data class ActiveLaunch(
        val token: String,
        val job: Job,
    ) {
        @Volatile
        var sidecarToken: String? = null
    }

    private companion object {
        const val SPAWN_OVERHEAD_MS = 3_000L
        const val ALIVE_POLL_INTERVAL_MS = 400L
        const val LOG_TAIL_LINES = 5
    }
}
