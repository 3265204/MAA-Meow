package com.aliothmoon.maameow.utils

import android.content.Context
import android.content.Intent

/** 桌面可见应用；清单已声明 launcher 的 queries */
object LauncherApps {

    data class App(val label: String, val packageName: String)

    /** 按应用名排序；查询较慢，调用方放 IO 线程 */
    fun load(context: Context): List<App> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcherIntent, 0)
            .distinctBy { it.activityInfo?.packageName }
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                App(info.loadLabel(pm).toString().ifBlank { pkg }, pkg)
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun label(context: Context, packageName: String): String? {
        if (packageName.isBlank()) return null
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()
    }
}
