package com.aliothmoon.maameow.maa.callback

import android.content.Context
import android.content.res.Resources
import com.alibaba.fastjson2.JSONObject
import com.aliothmoon.maameow.data.model.LogLevel
import com.aliothmoon.maameow.domain.service.MaaSessionLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

/** core 的 basic_info_with_what 把 what 放在顶层，与 details 同级 */
class SubTaskHandlerSubTaskErrorTest {
    private val pkg = "com.aliothmoon.maameow"
    private val resources = mockk<Resources>()
    private val context = mockk<Context> {
        every { resources } returns this@SubTaskHandlerSubTaskErrorTest.resources
        every { packageName } returns pkg
    }
    private val logger = mockk<MaaSessionLogger>(relaxed = true)
    private val handler = SubTaskHandler(
        applicationContext = context,
        statusTracker = mockk(relaxed = true),
        sessionLogger = logger,
        copilotRuntimeStateStore = mockk(relaxed = true),
        resourceDataManager = mockk(relaxed = true),
        toolboxResultCollector = mockk(relaxed = true),
        notificationCenter = mockk(relaxed = true),
        chainState = mockk(relaxed = true),
        activityManager = mockk(relaxed = true),
        achievementRepository = mockk(relaxed = true),
        depotRepository = mockk(relaxed = true),
    )

    @Before
    fun setUp() {
        MaaStringRes.clearCacheForTest()
        every { resources.getIdentifier(any(), "string", pkg) } returns 0
        stubFormat("maa_copilot_user_additional_name_invalid", 1, "名称无效：")
        stubPlain("maa_infrast_facility_layout_recognition_failed", 4, "布局识别失败")
    }

    @After
    fun tearDown() = MaaStringRes.clearCacheForTest()

    private fun stubFormat(name: String, id: Int, prefix: String) {
        every { resources.getIdentifier(name, "string", pkg) } returns id
        every { resources.getString(id, *anyVararg()) } answers {
            prefix + secondArg<Array<Any>>()[0]
        }
    }

    private fun stubPlain(name: String, id: Int, text: String) {
        every { resources.getIdentifier(name, "string", pkg) } returns id
        every { resources.getString(id) } returns text
    }

    private fun error(subtask: String, what: String, details: JSONObject = JSONObject()) {
        handler.onSubTaskError(
            JSONObject.of(
                "taskchain", "Copilot",
                "taskid", 0,
                "class", "asst::$subtask",
                "subtask", subtask,
                "what", what,
                "details", details,
            )
        )
    }

    @Test
    fun copilotUserAdditionalInvalid_readsTopLevelWhat() {
        error("CopilotTask", "UserAdditionalOperInvalid", JSONObject.of("name", "阿米驴"))
        verify { logger.append("名称无效：阿米驴", LogLevel.ERROR) }
    }

    @Test
    fun infrastLayoutFailure_readsTopLevelWhat() {
        error("InfrastInfoTask", "FacilityLayoutRecognitionFailed", JSONObject.of("attempts", 3))
        verify { logger.append("布局识别失败", LogLevel.ERROR) }
    }
}
