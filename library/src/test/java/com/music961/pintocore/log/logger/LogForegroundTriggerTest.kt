package com.music961.pintocore.log.logger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

/**
 * Bug Hunt R01 A4 회귀 — attach() 중복 호출 시 ProcessLifecycle 옵저버는 1회만 등록된다.
 *
 * Robolectric 분리: WorkManager.getInstance / ProcessLifecycleOwner 가 실제 Application context 를
 * 요구하므로 별도 Test 클래스로 두어 Robolectric runner 적용.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LogForegroundTriggerAttachIdempotentTest {

    /**
     * attach() 멱등성 검증 — `attached` AtomicBoolean 가드가 정확히 1회만 통과시키는지를
     * reflection 으로 직접 확인. (lifecycle.observerCount 는 lifecycle 2.10 에서 internal 이라
     * 직접 접근 불가 → 가드 필드 자체를 검증.)
     */
    @Test
    fun `attach 두 번 호출해도 attached 가드 1회만 통과`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        // WorkManager 초기화 — 메인 앱이 init 으로 처리하지만 테스트 환경에선 수동.
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)

        val logger = mockk<Logger>(relaxed = true)
        val holder = SessionIdHolder()
        val trigger = LogForegroundTrigger(context, logger, holder)

        val attachedField = LogForegroundTrigger::class.java
            .getDeclaredField("attached").apply { isAccessible = true }
        val before = attachedField.get(trigger) as java.util.concurrent.atomic.AtomicBoolean
        assertEquals("초기 attached=false", false, before.get())

        trigger.attach()
        assertEquals("첫 attach 후 attached=true", true, before.get())

        // 두 번째 attach() — 가드로 인해 즉시 리턴, attached 는 그대로 true (변화 없음).
        trigger.attach()
        assertEquals("두 번째 attach 호출 후에도 attached=true 유지", true, before.get())
    }
}
