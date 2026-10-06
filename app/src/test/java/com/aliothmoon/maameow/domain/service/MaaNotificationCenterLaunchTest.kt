package com.aliothmoon.maameow.domain.service

import android.content.Context
import com.aliothmoon.maameow.R
import com.aliothmoon.maameow.data.notification.NotificationSettingsManager
import com.aliothmoon.maameow.domain.notification.LiveNotifyIds
import com.aliothmoon.maameow.domain.notification.LiveSession
import com.aliothmoon.maameow.domain.notification.LiveSessionCoordinator
import com.aliothmoon.maameow.schedule.model.ExecutionResult
import com.aliothmoon.maameow.utils.i18n.uiTextDynamic
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 定时 / 外部触发没跑起来时的通知：失败外推，跳过不外推，都不设超时 */
class MaaNotificationCenterLaunchTest {

    private val context = mockk<Context> {
        every { applicationContext } returns this
        every { getString(R.string.notification_schedule_failed) } returns "failed"
        every { getString(R.string.notification_schedule_skipped) } returns "skipped"
        every { getString(R.string.notification_schedule_detail, *anyVararg()) } answers {
            val args = secondArg<Array<Any?>>()
            "${args[0]}: ${args[1]}"
        }
    }
    private val external = mockk<ExternalNotificationService>(relaxed = true)
    private val settings = mockk<NotificationSettingsManager> {
        every { sendOnError } returns MutableStateFlow(true)
    }
    private val live = mockk<LiveSessionCoordinator>(relaxed = true)
    private val center = MaaNotificationCenter(
        context = context,
        eventNotifier = mockk(relaxed = true),
        externalService = external,
        settings = settings,
        liveCoordinator = live,
        frameSnapshotter = mockk(relaxed = true),
    )

    @Test
    fun failure_notifiesAndPushes() {
        val session = slot<LiveSession>()
        every { live.publishStandalone(capture(session)) } returns Unit

        center.notifyLaunchNotStarted("Daily", ExecutionResult.FAILED_START, uiTextDynamic("Shizuku down"))

        // 独立 ID：下一轮开跑、任务结果都不会把它顶掉
        assertEquals(LiveNotifyIds.LAUNCH_SESSION, session.captured.sessionId)
        assertEquals("failed", session.captured.title)
        assertEquals("Daily: Shizuku down", session.captured.text)
        assertTrue(session.captured.isError)
        // 凌晨失败到早上还得在
        assertNull(session.captured.timeoutSec)
        verify(exactly = 1) { external.send("failed", "Daily: Shizuku down") }
    }

    @Test
    fun skip_notifiesWithoutPush() {
        val session = slot<LiveSession>()
        every { live.publishStandalone(capture(session)) } returns Unit

        center.notifyLaunchNotStarted("Daily", ExecutionResult.SKIPPED_LOCKED, uiTextDynamic("locked"))

        assertEquals("skipped", session.captured.title)
        verify(exactly = 0) { external.send(any(), any()) }
    }

    @Test
    fun failure_respectsPushSwitch() {
        every { settings.sendOnError } returns MutableStateFlow(false)

        center.notifyLaunchNotStarted("Daily", ExecutionResult.FAILED_UI_LAUNCH, uiTextDynamic("x"))

        verify(exactly = 1) { live.publishStandalone(any()) }
        verify(exactly = 0) { external.send(any(), any()) }
    }
}
