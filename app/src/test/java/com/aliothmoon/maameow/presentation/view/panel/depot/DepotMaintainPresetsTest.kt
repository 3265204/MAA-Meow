package com.aliothmoon.maameow.presentation.view.panel.depot

import com.aliothmoon.maameow.data.model.DepotMaintainPlan
import com.aliothmoon.maameow.data.model.activity.ActivityStage
import com.aliothmoon.maameow.data.model.activity.StageActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DepotMaintainPresetsTest {

    @Test
    fun chip1_matchesUpstreamOrderAndTargets() {
        assertPreset(
            preset = DepotMaintainPreset.CHIP_1,
            expected = listOf(
                Triple("PR-A-1", "3261", 20),
                Triple("PR-A-1", "3231", 20),
                Triple("PR-B-1", "3251", 20),
                Triple("PR-B-1", "3241", 20),
                Triple("PR-C-1", "3211", 20),
                Triple("PR-C-1", "3271", 20),
                Triple("PR-D-1", "3221", 20),
                Triple("PR-D-1", "3281", 20),
            ),
        )
    }

    @Test
    fun chip2_matchesUpstreamOrderAndTargets() {
        assertPreset(
            preset = DepotMaintainPreset.CHIP_2,
            expected = listOf(
                Triple("PR-A-2", "3262", 32),
                Triple("PR-A-2", "3232", 32),
                Triple("PR-B-2", "3252", 32),
                Triple("PR-B-2", "3242", 32),
                Triple("PR-C-2", "3212", 32),
                Triple("PR-C-2", "3272", 32),
                Triple("PR-D-2", "3222", 32),
                Triple("PR-D-2", "3282", 32),
            ),
        )
    }

    @Test
    fun resourcePresets_matchUpstreamTargets() {
        assertPreset(
            DepotMaintainPreset.LMD,
            listOf(Triple("CE-6", "4001", 5_000_000)),
        )
        assertPreset(
            DepotMaintainPreset.PURCHASE_CERTIFICATE,
            listOf(Triple("AP-5", "4006", 8_000)),
        )
        assertPreset(
            DepotMaintainPreset.SKILL_SUMMARY_3,
            listOf(Triple("CA-5", "3303", 400)),
        )
    }

    @Test
    fun presetPlans_leaveConsumablesDisabled() {
        DepotMaintainPreset.entries.flatMap { it.plans }.forEach { plan ->
            assertFalse(plan.useMedicine)
            assertEquals(0, plan.medicineCount)
            assertFalse(plan.useStone)
            assertEquals(0, plan.stoneCount)
        }
    }

    @Test
    fun append_keepsExistingPlansAndAddsPresetEveryTime() {
        val existing = DepotMaintainPlan(stage = "1-7", dropId = "30011", dropCount = 100)

        val once = appendDepotMaintainPreset(listOf(existing), DepotMaintainPreset.LMD)
        val twice = appendDepotMaintainPreset(once, DepotMaintainPreset.LMD)

        assertEquals(existing, once.first())
        assertEquals(
            listOf(
                Triple("1-7", "30011", 100),
                Triple("CE-6", "4001", 5_000_000),
                Triple("CE-6", "4001", 5_000_000),
            ),
            twice.map { Triple(it.stage, it.dropId, it.dropCount) },
        )
    }

    // ---- 当前活动预设（上游 a7ee2c2bf7） ----

    @Test
    fun activityPreset_turnsKnownDropStagesIntoPlansWithDefaultCount() {
        val act = activity("落叶逐火")
        val presets = buildActivityPresets(
            stages = listOf(
                stage("ME-6", "30012", act, "act1"),
                stage("ME-7", "30063", act, "act1"),
                stage("ME-8", "活动商店兑换", act, "act1"), // 文案型掉落
                stage("ME-1", null, act, "act1"),
            ),
            isKnownItem = KNOWN::contains,
            nowMillis = NOW,
        )

        assertEquals(listOf("act1"), presets.map { it.key })
        assertEquals("落叶逐火", presets.single().name)
        assertEquals(
            listOf(
                Triple("ME-6", "30012", ACTIVITY_PRESET_DEFAULT_COUNT),
                Triple("ME-7", "30063", ACTIVITY_PRESET_DEFAULT_COUNT),
            ),
            presets.single().plans.map { Triple(it.stage, it.dropId, it.dropCount) },
        )
        assertEquals(200, ACTIVITY_PRESET_DEFAULT_COUNT)
    }

    @Test
    fun activityPreset_onlyListsActivitiesOpenAtTheGivenMoment() {
        val presets = buildActivityPresets(
            stages = listOf(
                stage("A-1", "30012", activity("未开始", start = NOW + 1, end = NOW + DAY), "pending"),
                stage("B-1", "30012", activity("刚开始", start = NOW, end = NOW + DAY), "started"),
                stage("C-1", "30012", activity("刚结束", start = NOW - DAY, end = NOW), "expired"),
            ),
            isKnownItem = KNOWN::contains,
            nowMillis = NOW,
        )

        assertEquals(listOf("started"), presets.map { it.key })
    }

    @Test
    fun activityPreset_skipsActivitiesWithoutUsableStages() {
        val presets = buildActivityPresets(
            stages = listOf(
                stage("A-1", "活动商店兑换", activity("只有文案掉落"), "textOnly"),
                stage("B-1", "30012", activity("资源收集", resourceCollection = true), "resource"),
                stage("C-1", "30012", null, "noInfo"),
            ),
            isKnownItem = KNOWN::contains,
            nowMillis = NOW,
        )

        assertEquals(emptyList<DepotActivityPreset>(), presets)
    }

    @Test
    fun activityPreset_groupsByKeyInSourceOrder_andFallsBackToKeyForBlankName() {
        val presets = buildActivityPresets(
            stages = listOf(
                stage("B-1", "30012", activity(""), "actB"),
                stage("A-1", "30063", activity("活动 A"), "actA"),
                stage("B-2", "30063", activity(""), "actB"),
            ),
            isKnownItem = KNOWN::contains,
            nowMillis = NOW,
        )

        assertEquals(listOf("actB" to "actB", "actA" to "活动 A"), presets.map { it.key to it.name })
        assertEquals(listOf("B-1", "B-2"), presets.first().plans.map { it.stage })
    }

    @Test
    fun append_acceptsActivityPresets() {
        val preset = DepotActivityPreset(
            key = "act1",
            name = "活动",
            plans = listOf(DepotMaintainPlan(stage = "ME-6", dropId = "30012", dropCount = 200)),
        )
        assertEquals(preset.plans, appendDepotMaintainPreset(emptyList(), preset))
    }

    private fun activity(
        name: String,
        start: Long = NOW - DAY,
        end: Long = NOW + DAY,
        resourceCollection: Boolean = false,
    ) = StageActivityInfo(
        name = name,
        tip = "",
        utcStartTime = start,
        utcExpireTime = end,
        isResourceCollection = resourceCollection,
    )

    private fun stage(code: String, drop: String?, activity: StageActivityInfo?, key: String) =
        ActivityStage(display = code, value = code, drop = drop, activity = activity, activityKey = key)

    private fun assertPreset(
        preset: DepotMaintainPreset,
        expected: List<Triple<String, String, Int>>,
    ) {
        assertEquals(
            expected,
            preset.plans.map { Triple(it.stage, it.dropId, it.dropCount) },
        )
    }

    private companion object {
        const val NOW = 1_760_000_000_000L
        const val DAY = 24 * 60 * 60 * 1000L
        val KNOWN = setOf("30012", "30063")
    }
}
