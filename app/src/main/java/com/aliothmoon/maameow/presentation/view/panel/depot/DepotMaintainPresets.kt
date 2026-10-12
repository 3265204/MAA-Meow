package com.aliothmoon.maameow.presentation.view.panel.depot

import androidx.annotation.StringRes
import com.aliothmoon.maameow.R
import com.aliothmoon.maameow.data.model.DepotMaintainPlan
import com.aliothmoon.maameow.data.model.activity.ActivityStage

/** 预设菜单的一项，常驻与活动共用追加入口 */
internal sealed interface DepotPresetOption {
    val plans: List<DepotMaintainPlan>
}

/**
 * 库存保持的 UI 内置预设
 *
 * 数据与 MAA WPF DepotMaintainTaskUserControlModel.PresetData 保持一致
 */
internal enum class DepotMaintainPreset(
    @get:StringRes val labelRes: Int,
    override val plans: List<DepotMaintainPlan>,
) : DepotPresetOption {
    CHIP_1(
        labelRes = R.string.panel_depot_preset_chip_1,
        plans = buildPresetPlans(
            defaultCount = 20,
            "PR-A-1" to listOf("3261", "3231"),
            "PR-B-1" to listOf("3251", "3241"),
            "PR-C-1" to listOf("3211", "3271"),
            "PR-D-1" to listOf("3221", "3281"),
        ),
    ),
    CHIP_2(
        labelRes = R.string.panel_depot_preset_chip_2,
        plans = buildPresetPlans(
            defaultCount = 32,
            "PR-A-2" to listOf("3262", "3232"),
            "PR-B-2" to listOf("3252", "3242"),
            "PR-C-2" to listOf("3212", "3272"),
            "PR-D-2" to listOf("3222", "3282"),
        ),
    ),
    LMD(
        labelRes = R.string.panel_depot_preset_lmd,
        plans = buildPresetPlans(
            defaultCount = 5_000_000,
            "CE-6" to listOf("4001"),
        ),
    ),
    PURCHASE_CERTIFICATE(
        labelRes = R.string.panel_depot_preset_certificate,
        plans = buildPresetPlans(
            defaultCount = 8_000,
            "AP-5" to listOf("4006"),
        ),
    ),
    SKILL_SUMMARY_3(
        labelRes = R.string.panel_depot_preset_skill_summary,
        plans = buildPresetPlans(
            defaultCount = 400,
            "CA-5" to listOf("3303"),
        ),
    ),
}

/**
 * 当前活动预设：一个进行中的活动，掉落为已知物品的关卡各占一条计划
 *
 * @param key 活动标识，即 sideStoryStage 的键
 */
internal data class DepotActivityPreset(
    val key: String,
    val name: String,
    override val plans: List<DepotMaintainPlan>,
) : DepotPresetOption

/** 活动预设的统一目标数量，导入后按个人兑换计划调整，对齐上游 ActivityPresetDefaultCount */
internal const val ACTIVITY_PRESET_DEFAULT_COUNT = 200

/**
 * 对齐上游 RefreshActivityPresetList + AddActivityPresetPlan
 *
 * 活动起止不会触发数据刷新，调用方须在菜单展开时以当下时刻重算
 * 掉落非已知物品的是文案型 Drop，生成不了计划；一关都没有的活动不列出
 */
internal fun buildActivityPresets(
    stages: List<ActivityStage>,
    isKnownItem: (String) -> Boolean,
    nowMillis: Long,
): List<DepotActivityPreset> = stages
    .filter { stage ->
        val activity = stage.activity ?: return@filter false
        val drop = stage.drop
        !activity.isResourceCollection &&
                nowMillis >= activity.utcStartTime && nowMillis < activity.utcExpireTime &&
                !drop.isNullOrEmpty() && isKnownItem(drop)
    }
    .groupBy { it.activityKey }
    .map { (key, group) ->
        DepotActivityPreset(
            key = key,
            name = group.first().activity?.name?.ifBlank { null } ?: key,
            plans = group.map {
                DepotMaintainPlan(
                    stage = it.value,
                    dropId = it.drop.orEmpty(),
                    dropCount = ACTIVITY_PRESET_DEFAULT_COUNT,
                )
            },
        )
    }

internal fun appendDepotMaintainPreset(
    existingPlans: List<DepotMaintainPlan>,
    preset: DepotPresetOption,
): List<DepotMaintainPlan> = existingPlans + preset.plans

private fun buildPresetPlans(
    defaultCount: Int,
    vararg stages: Pair<String, List<String>>,
): List<DepotMaintainPlan> = stages.flatMap { (stage, dropIds) ->
    dropIds.map { dropId ->
        DepotMaintainPlan(
            stage = stage,
            dropId = dropId,
            dropCount = defaultCount,
        )
    }
}
