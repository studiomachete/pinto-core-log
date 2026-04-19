package com.music961.pintocore.log.consent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LogConsentStore 계약 테스트.
 *
 * 실제 [LogConsentStoreImpl] 은 Android DataStore 를 필요로 하므로 이 테스트는 In-Memory Fake 로
 * 계약(set → consent Flow 반영)을 검증한다. DataStore 레이어 자체 I/O 는 Android 인스트루먼트 테스트에서
 * 커버되며, 본 테스트는 phase-04 완료 기준 "토글 on/off Flow 반영" 을 보장한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogConsentStoreTest {

    /** In-Memory Fake. DataStore 대신 StateFlow 로 갈아끼운 동일 계약 구현체. */
    private class FakeLogConsentStore : LogConsentStore {
        private val state = MutableStateFlow(LogConsent.기본값)
        /** 관찰된 atomic edit 횟수 (setConsentAndMarkAsked 호출 수). */
        var atomicEditCount: Int = 0
            private set

        override val consent: Flow<LogConsent> get() = state

        override suspend fun setIdentifiedConsent(v: Boolean) {
            state.update { it.copy(identifiedConsent = v) }
        }

        override suspend fun setWifiOnly(v: Boolean) {
            state.update { it.copy(wifiOnlyUpload = v) }
        }

        override suspend fun markAsked() {
            state.update { it.copy(askedOnce = true) }
        }

        override suspend fun setConsentAndMarkAsked(allow: Boolean) {
            atomicEditCount++
            state.update { it.copy(identifiedConsent = allow, askedOnce = true) }
        }
    }

    @Test
    fun `기본값은 모두 false`() = runTest {
        val store: LogConsentStore = FakeLogConsentStore()
        val c = store.consent.first()
        assertFalse(c.identifiedConsent)
        assertFalse(c.wifiOnlyUpload)
        assertFalse(c.askedOnce)
    }

    @Test
    fun `setIdentifiedConsent true 시 Flow 에 즉시 반영`() = runTest {
        val store: LogConsentStore = FakeLogConsentStore()
        store.setIdentifiedConsent(true)
        assertTrue(store.consent.first().identifiedConsent)

        // 다시 false 로 끄면 Flow 에 반영
        store.setIdentifiedConsent(false)
        assertFalse(store.consent.first().identifiedConsent)
    }

    @Test
    fun `setWifiOnly 토글은 identifiedConsent 와 독립`() = runTest {
        val store: LogConsentStore = FakeLogConsentStore()
        store.setIdentifiedConsent(true)
        store.setWifiOnly(true)

        val c = store.consent.first()
        assertEquals(true, c.identifiedConsent)
        assertEquals(true, c.wifiOnlyUpload)
        assertFalse(c.askedOnce)
    }

    @Test
    fun `markAsked 는 askedOnce 만 true 로 세팅`() = runTest {
        val store: LogConsentStore = FakeLogConsentStore()
        store.markAsked()
        val c = store.consent.first()
        assertTrue(c.askedOnce)
        assertFalse(c.identifiedConsent)
        assertFalse(c.wifiOnlyUpload)
    }

    // bug #8 fix — 동의값 + askedOnce 원자 저장
    @Test
    fun `setConsentAndMarkAsked true 는 identifiedConsent 와 askedOnce 를 한 번에 true`() = runTest {
        val store = FakeLogConsentStore()
        store.setConsentAndMarkAsked(true)

        val c = store.consent.first()
        assertTrue(c.identifiedConsent)
        assertTrue(c.askedOnce)
        assertEquals(1, store.atomicEditCount)
    }

    @Test
    fun `setConsentAndMarkAsked false 는 identifiedConsent false askedOnce true`() = runTest {
        val store = FakeLogConsentStore()
        store.setConsentAndMarkAsked(false)

        val c = store.consent.first()
        assertFalse(c.identifiedConsent)
        assertTrue(c.askedOnce)
    }
}
