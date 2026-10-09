package com.aliothmoon.maameow.remote.internal.display

import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/** 一个已经创建并校验过的 VDM VirtualDevice/VirtualDisplay 资源会话。 */
internal class VdmDisplaySession internal constructor(
    private val address: String,
    private val virtualDevice: VirtualDeviceHandle,
    val display: VirtualDisplay,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    /** 候选显示通过结构检查后，再挂载采集 Surface。 */
    fun attachSurface(surface: Surface) {
        check(!closed.get()) { "Independent display is already closed" }
        display.setSurface(surface)

        val deadline = SystemClock.elapsedRealtime() + DISPLAY_READY_TIMEOUT_MS
        do {
            if (display.display.state == Display.STATE_ON) return
            SystemClock.sleep(DISPLAY_READY_POLL_MS)
        } while (SystemClock.elapsedRealtime() < deadline)

        display.setSurface(null)
        throw IllegalStateException(
            "VDM display ${display.display.displayId} did not reach STATE_ON " +
                "(state=${display.display.state})"
        )
    }

    fun detachSurface() {
        if (!closed.get()) display.setSurface(null)
    }

    override fun close() = release(removeAssociation = true)

    /** 重建显示时保留关联及其角色授权，供替换后的 VDM 设备复用。 */
    fun closeForRestart() = release(removeAssociation = false)

    private fun release(removeAssociation: Boolean) {
        if (!closed.compareAndSet(false, true)) return

        var failure: Throwable? = null
        try {
            try {
                display.setSurface(null)
                display.release()
            } finally {
                // 关闭 VirtualDevice 时保持 root 身份，避免再次扩大 Binder 身份切换窗口。
                virtualDevice.close()
            }
        } catch (t: Throwable) {
            failure = t
        }

        if (removeAssociation) {
            try {
                CompanionAssociation.remove(address)
            } catch (cleanupFailure: Throwable) {
                if (failure == null) {
                    failure = cleanupFailure
                } else {
                    failure.addSuppressed(cleanupFailure)
                }
            }
        }

        failure?.let { throw it }
    }

    private companion object {
        private const val DISPLAY_READY_TIMEOUT_MS = 1_000L
        private const val DISPLAY_READY_POLL_MS = 20L
    }
}
