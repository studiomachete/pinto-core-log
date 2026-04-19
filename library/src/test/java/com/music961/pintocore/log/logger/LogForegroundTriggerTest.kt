package com.music961.pintocore.log.logger

import android.content.Context
import com.music961.pintocore.log.api.LogEvent
import com.music961.pintocore.log.api.LogPayloadKey
import com.music961.pintocore.log.device.SessionIdHolder
import com.music961.pintocore.log.model.PayloadKey
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LogForegroundTrigger 회귀 테스트 (bug #7 fix).
 *
 * SESSION_END payload 의 [PayloadKey.SESSION_DURATION_MS] 가 0 이 아니라
 * `now - sessionStartedAt` 으로 계산되는지 검증.
 */
class LogForegroundTriggerTest {

    @Test
    fun `SESSION_END payload 에 세션 지속 시간 기록`() {
        val context = mockk<Context>(relaxed = true)
        val logger = mockk<Logger>(relaxed = true)
        val holder = SessionIdHolder() // sessionStartedAt 자동 세팅

        val trigger = LogForegroundTrigger(context, logger, holder)
        // 세션 시작 12345ms 이후를 now 로 주입
        trigger.nowProvider = { holder.sessionStartedAt + 12_345L }

        val nameSlot = slot<LogEvent>()
        val payloadSlot = slot<Map<LogPayloadKey, Any?>>()
        every { logger.event(capture(nameSlot), capture(payloadSlot)) } returns Unit

        trigger.handleOnStop()

        assertEquals("SESSION_END", nameSlot.captured.name)
        val duration = payloadSlot.captured[PayloadKey.SESSION_DURATION_MS]
        assertEquals(12_345L, duration)
    }

    @Test
    fun `SESSION_END - now가 start보다 이전이어도 음수 노출 안 함`() {
        val context = mockk<Context>(relaxed = true)
        val logger = mockk<Logger>(relaxed = true)
        val holder = SessionIdHolder()

        val trigger = LogForegroundTrigger(context, logger, holder)
        trigger.nowProvider = { holder.sessionStartedAt - 999L } // clock skew 시뮬

        val payloadSlot = slot<Map<LogPayloadKey, Any?>>()
        every { logger.event(any(), capture(payloadSlot)) } returns Unit

        trigger.handleOnStop()

        assertEquals(0L, payloadSlot.captured[PayloadKey.SESSION_DURATION_MS])
    }

    @Test
    fun `SESSION_START - 하드코딩된 이벤트 발행`() {
        val context = mockk<Context>(relaxed = true)
        val logger = mockk<Logger>(relaxed = true)
        val holder = SessionIdHolder()

        val trigger = LogForegroundTrigger(context, logger, holder)
        trigger.handleOnStart()

        verify(exactly = 1) { logger.event(match<LogEvent> { it.name == "SESSION_START" }, any()) }
    }
}
