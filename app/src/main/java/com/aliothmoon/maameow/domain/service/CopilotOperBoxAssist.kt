package com.aliothmoon.maameow.domain.service

import com.aliothmoon.maameow.constant.MaaFiles
import com.aliothmoon.maameow.data.config.MaaPathConfig
import com.aliothmoon.maameow.data.model.toolbox.OperBoxOperator
import com.aliothmoon.maameow.data.preferences.AppSettingsManager
import com.aliothmoon.maameow.data.repository.OperBoxRepository
import com.aliothmoon.maameow.utils.JsonUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * 自动战斗「一图流数据辅助编队」，对齐上游 #17414
 *
 * 开跑前把当前配置档的已拥有干员写成 core 认的 OperBoxData，路径经 operbox_data_path 下发；
 * core 在 set_params 阶段据此预检：缺人直接拒收，配得上的组只留预匹配的那名干员
 */
class CopilotOperBoxAssist(
    private val appSettings: AppSettingsManager,
    private val operBoxRepository: OperBoxRepository,
    private val pathConfig: MaaPathConfig,
) {
    data class State(
        val yituliuEnabled: Boolean = false,
        val dataUsable: Boolean = false,
        val syncTimeMillis: Long = 0L,
    ) {
        /** 对齐上游 CanUseOperBoxAssist：开了一图流获取且数据带练度 */
        val available: Boolean get() = yituliuEnabled && dataUsable
    }

    val state: Flow<State> =
        combine(appSettings.operBoxUseYituliuApi, operBoxRepository.snapshot) { enabled, snap ->
            State(enabled, snap.canAssistFormation, snap.syncTimeMillis)
        }

    val isAvailable: Boolean
        get() = appSettings.operBoxUseYituliuApi.value &&
                operBoxRepository.snapshot.value.canAssistFormation

    /** 返回 core 侧路径，写失败为 null；独立目录模式下随用户文件一起投递 */
    suspend fun writeCoreData(): String? = withContext(Dispatchers.IO) {
        val file = File(pathConfig.rootDir, "${MaaFiles.OPER_BOX_DATA_DIR}/${MaaFiles.OPER_BOX_DATA_FILE}")
        try {
            file.parentFile?.mkdirs()
            file.writeText(encodeCoreData(operBoxRepository.snapshot.value.owned), Charsets.UTF_8)
            pathConfig.toCorePath(file.absolutePath)
        } catch (e: IOException) {
            Timber.e(e, "write operbox data failed")
            null
        }
    }

    /** core 只读 own_opers，字段名与 OperBoxDataConfig::parse 一致 */
    @Serializable
    private data class CoreData(@SerialName("own_opers") val ownOpers: List<OperBoxOperator>)

    companion object {
        internal fun encodeCoreData(owned: List<OperBoxOperator>): String =
            JsonUtils.common.encodeToString(CoreData.serializer(), CoreData(owned))
    }
}
