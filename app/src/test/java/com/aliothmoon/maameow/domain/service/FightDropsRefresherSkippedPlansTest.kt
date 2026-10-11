package com.aliothmoon.maameow.domain.service

import com.aliothmoon.maameow.data.repository.DepotRepository
import com.aliothmoon.maameow.data.resource.ItemHelper
import com.aliothmoon.maameow.data.resource.ItemInfo
import com.aliothmoon.maameow.domain.models.SkippedDepotPlans
import com.aliothmoon.maameow.domain.models.StaleSkippedPlan
import com.aliothmoon.maameow.maa.task.TaskSlot
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** 上游 70d1f06bb8：仓库识别后复查预检时跳过的库存保持计划 */
class FightDropsRefresherSkippedPlansTest {

    private val inventory = mutableMapOf<String, Int>()
    private val depotRepository: DepotRepository = mockk {
        every { countOf(any()) } answers { inventory[firstArg()] ?: 0 }
    }
    private val itemHelper: ItemHelper = mockk {
        every { getItemInfo(any()) } answers {
            if (firstArg<String>() == ROCK) ItemInfo(id = ROCK, name = "源岩") else null
        }
    }
    private val refresher = FightDropsRefresher(depotRepository, itemHelper, mockk(), mockk())

    @Before
    fun setUp() {
        inventory[ROCK] = 50
        inventory[DEVICE] = 50
    }

    private fun skipped(firstOnly: Boolean = false) = SkippedDepotPlans(
        taskName = "材料补货",
        plans = listOf(
            SkippedDepotPlans.Plan(no = 1, dropId = ROCK, dropCount = 40),
            SkippedDepotPlans.Plan(no = 3, dropId = DEVICE, dropCount = 40),
        ),
        firstOnly = firstOnly,
    )

    /** 主链任务入队时才 bind，模拟一轮真正开跑 */
    private fun startRun() = refresher.bind(TaskSlot("n1", 0), taskId = 1)

    @Test
    fun flippedPlans_areReportedOnce() {
        refresher.stageSkipped(skipped())
        startRun()
        inventory[ROCK] = 30
        inventory[DEVICE] = 10

        assertEquals(
            listOf(
                StaleSkippedPlan("材料补货", 1, "源岩", 30, 40),
                StaleSkippedPlan("材料补货", 3, DEVICE, 10, 40),
            ),
            refresher.reviewSkippedPlans(),
        )
        // 同一轮后面再识别一次不重复提示
        assertEquals(emptyList<StaleSkippedPlan>(), refresher.reviewSkippedPlans())
    }

    @Test
    fun stillEnough_isNotReported() {
        refresher.stageSkipped(skipped())
        startRun()

        assertEquals(emptyList<StaleSkippedPlan>(), refresher.reviewSkippedPlans())
    }

    @Test
    fun firstOnlyMode_reportsFirstFlipOnly() {
        refresher.stageSkipped(skipped(firstOnly = true))
        startRun()
        inventory[ROCK] = 0
        inventory[DEVICE] = 0

        assertEquals(listOf(1), refresher.reviewSkippedPlans().map { it.no })
    }

    @Test
    fun analysedButNotStarted_isNotReviewed() {
        // 只分析没开跑，期间工具箱的仓库识别不该带出提示
        refresher.stageSkipped(skipped())
        inventory[ROCK] = 0

        assertEquals(emptyList<StaleSkippedPlan>(), refresher.reviewSkippedPlans())
    }

    @Test
    fun clear_dropsStagedPlans() {
        refresher.stageSkipped(skipped())
        startRun()
        refresher.clear()
        startRun()
        inventory[ROCK] = 0

        assertEquals(emptyList<StaleSkippedPlan>(), refresher.reviewSkippedPlans())
    }

    private companion object {
        const val ROCK = "30011"
        const val DEVICE = "30061"
    }
}
