package com.aliothmoon.maameow.remote.internal.display

import android.companion.AssociationInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.aliothmoon.maameow.BuildConfig
import com.aliothmoon.maameow.third.Ln
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** VDM 使用的临时 Companion Device 关联及其角色授权。 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal object CompanionAssociation {
    private const val SHELL_PACKAGE = "com.android.shell"
    private const val STREAMING_PROFILE = "android.app.role.COMPANION_DEVICE_APP_STREAMING"
    private const val COMMAND_TIMEOUT_SECONDS = 5L
    private const val ASSOCIATION_TIMEOUT_MS = 3_000L
    private const val ASSOCIATION_POLL_MS = 100L

    private const val COMPANION_INTERFACE = "android.companion.ICompanionDeviceManager"

    fun address(userId: Int): String {
        val hash = 31 * BuildConfig.APPLICATION_ID.hashCode() + userId
        return String.format(
            Locale.ROOT,
            "02:4D:41:%02X:%02X:%02X",
            hash ushr 16 and 0xff,
            hash ushr 8 and 0xff,
            hash and 0xff,
        )
    }

    fun ensure(address: String, userId: Int): AssociationInfo {
        find(address, userId)?.let { return it }

        val command = mutableListOf(
            "cmd",
            "companiondevice",
            "associate",
            userId.toString(),
            SHELL_PACKAGE,
            address,
        )
        // Android 14 的 shell 命令只消费前三个参数；15+ 才正式支持 profile 和
        // self-managed 参数。按平台能力组装命令，避免依赖“忽略多余参数”的实现细节。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            command += STREAMING_PROFILE
            command += "false"
        }
        runCommand(*command.toTypedArray())
        return await(address, userId)
            ?: throw IllegalStateException("Companion association was not created")
    }

    fun cleanupStale(userId: Int) {
        val address = address(userId)
        if (find(address, userId) == null) return

        remove(address, userId)
        check(awaitRemoval(address, userId)) {
            "Stale companion association was not removed"
        }
        Ln.i("Removed stale VDM companion association")
    }

    fun remove(address: String, userId: Int) {
        if (find(address, userId) == null) return
        runCommand(
            "cmd",
            "companiondevice",
            "disassociate",
            userId.toString(),
            SHELL_PACKAGE,
            address,
        )
    }

    private fun find(address: String, userId: Int): AssociationInfo? =
        associationsForUser(userId).firstOrNull { association ->
            address.equals(
                association.deviceMacAddress?.toString(),
                ignoreCase = true,
            )
        }

    private fun await(address: String, userId: Int): AssociationInfo? {
        val deadline = SystemClock.elapsedRealtime() + ASSOCIATION_TIMEOUT_MS
        do {
            val association = find(address, userId)
            if (association != null) return association
            SystemClock.sleep(ASSOCIATION_POLL_MS)
        } while (SystemClock.elapsedRealtime() < deadline)
        return find(address, userId)
    }

    private fun awaitRemoval(address: String, userId: Int): Boolean {
        val deadline = SystemClock.elapsedRealtime() + ASSOCIATION_TIMEOUT_MS
        do {
            if (find(address, userId) == null) return true
            SystemClock.sleep(ASSOCIATION_POLL_MS)
        } while (SystemClock.elapsedRealtime() < deadline)
        return find(address, userId) == null
    }

    @Suppress("UNCHECKED_CAST")
    private fun associationsForUser(userId: Int): List<AssociationInfo> {
        val aidlClass = Class.forName(COMPANION_INTERFACE)
        val aidl = getBinderInterface("companiondevice", aidlClass)
        return aidl.javaClass
            .getMethod("getAssociations", String::class.java, Int::class.javaPrimitiveType)
            .invokeUnwrapped(aidl, SHELL_PACKAGE, userId) as List<AssociationInfo>
    }

    private fun getBinderInterface(serviceName: String, aidlClass: Class<*>): Any {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("getService", String::class.java)
            .invokeUnwrapped(null, serviceName) as? IBinder
            ?: throw IllegalStateException("$serviceName service is unavailable")
        val stubClass = Class.forName("${aidlClass.name}\$Stub")
        return stubClass.getMethod("asInterface", IBinder::class.java)
            .invokeUnwrapped(null, binder)
            ?: throw IllegalStateException("Could not bind $serviceName service")
    }

    private fun runCommand(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("${command.joinToString(" ")} timed out")
        }

        val bytes = ByteArrayOutputStream()
        process.inputStream.use { input -> input.copyTo(bytes) }
        val output = bytes.toString(Charsets.UTF_8.name())
        if (process.exitValue() != 0) {
            throw IOException(
                "${command.joinToString(" ")} failed (${process.exitValue()}): " +
                    output.trim()
            )
        }
        return output
    }
}

private fun java.lang.reflect.Method.invokeUnwrapped(
    receiver: Any?,
    vararg arguments: Any?,
): Any? = try {
    invoke(receiver, *arguments)
} catch (failure: InvocationTargetException) {
    throw failure.targetException
}
