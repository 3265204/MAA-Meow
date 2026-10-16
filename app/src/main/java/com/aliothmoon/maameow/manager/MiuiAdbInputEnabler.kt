package com.aliothmoon.maameow.manager

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 用 Root 打开小米「USB 调试（安全设置）」，照 enable_miui_adb 来，去掉 USB 安装那两步
 * 这些属性 shell 写不了；运行时就生效，不用重启
 */
object MiuiAdbInputEnabler {

    private val SCRIPT = arrayOf(
        "[ \"$(getprop persist.security.adbinput)\" = 1 ] || setprop persist.security.adbinput 1",
        "[ \"$(getprop persist.fastboot.enable)\" = 1 ] || setprop persist.fastboot.enable 1",
        "kill -9 $(pidof com.miui.securitycenter.remote) 2>/dev/null || true",
        // 安全中心重启时可能回写，最后再设一次让它即时生效
        "setprop persist.security.adbinput 1",
    )

    suspend fun enable(): Boolean = withContext(Dispatchers.IO) {
        val result = runCatching { Shell.cmd(*SCRIPT).exec() }
            .onFailure { Timber.w(it, "enable MIUI adb input failed") }
            .getOrNull() ?: return@withContext false
        // 默认 Builder 把 stderr 并进 out 且与 stdout 交错，读回单独跑
        val enabled = runCatching {
            Shell.cmd("getprop persist.security.adbinput").exec().out.firstOrNull()?.trim() == "1"
        }.getOrDefault(false)
        Timber.i("enable MIUI adb input: enabled=%s code=%d out=%s", enabled, result.code, result.out)
        enabled
    }
}
