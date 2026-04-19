package com.music961.pintocore.log.logger

import com.music961.pintocore.log.api.LogErrorReporter
import com.music961.pintocore.log.api.LogEvent
import com.music961.pintocore.log.api.LogUserIdProvider
import com.music961.pintocore.log.consent.LogConsent
import com.music961.pintocore.log.consent.LogConsentStore
import com.music961.pintocore.log.device.DeviceHashProvider
import com.music961.pintocore.log.device.DeviceInfoProvider
import com.music961.pintocore.log.device.SessionIdHolder
import com.music961.pintocore.log.model.DeviceInfo
import com.music961.pintocore.log.model.LogCategory
import com.music961.pintocore.log.model.LogRecord
import com.music961.pintocore.log.model.PayloadKey
import com.music961.pintocore.log.storage.LogEntryDao
import com.music961.pintocore.log.storage.LogEntryEntity
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoggerImpl 계약 테스트 (라이브러리 분리 후).
 *
 * 원본 메인 앱 테스트의 RepoState 의존을 [LogUserIdProvider] 로,
 * Crashlytics 의존을 [LogErrorReporter] 로 치환했다.
 * EventName 도메인 enum 대신 테스트 전용 [TestEvent] 사용.
 *
 * 검증 항목:
 *  1. Logger.event 호출 시 Room insert 가 일어난다.
 *  2. identifiedConsent=false 일 때 LogRecord.userId = null.
 *  3. identifiedConsent=true 일 때 LogRecord.userId = userIdProvider 결과.
 *  4. 주입된 appId/appVersion 이 LogRecord 에 그대로 기록.
 *  5. R01 nullify 회귀 (파싱 실패 → delete).
 *  6. R01 nullifyUserIdWithRetry — 1차 실패 후 재시도 성공.
 *  7. R01 nullifyUserIdWithRetry — 재시도도 실패하면 false.
 *  8. R02 #12 — enqueue ↔ nullify 직렬화 (Mutex).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoggerImplTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 테스트 전용 LogEvent enum. */
    private enum class TestEvent(override val defaultCategory: LogCategory) : LogEvent {
        SCREEN_ENTER_HOME(LogCategory.event),
        USER_LOGIN(LogCategory.event),
        SCREEN_ENTER_MAKER_HOME(LogCategory.event),
    }

    private fun fakeDeviceInfo() = DeviceInfo(
        model = "TEST",
        manufacturer = "AOSP",
        os = "android",
        osVersion = "14",
        ramMb = 4096L,
        cpuCores = 8,
        screenDp = "411 x 914",
        locale = "ko-KR",
    )

    private class FakeConsentStore(initial: LogConsent = LogConsent.기본값) : LogConsentStore {
        val state = MutableStateFlow(initial)
        override val consent: Flow<LogConsent> = state.asStateFlow()
        override suspend fun setIdentifiedConsent(v: Boolean) { state.value = state.value.copy(identifiedConsent = v) }
        override suspend fun setWifiOnly(v: Boolean) { state.value = state.value.copy(wifiOnlyUpload = v) }
        override suspend fun markAsked() { state.value = state.value.copy(askedOnce = true) }
        override suspend fun setConsentAndMarkAsked(allow: Boolean) {
            state.value = state.value.copy(identifiedConsent = allow, askedOnce = true)
        }
    }

    private class FakeUserIdProvider(private val userId: String?) : LogUserIdProvider {
        override suspend fun currentUserId(): String? = userId
    }

    private class FakeErrorReporter : LogErrorReporter {
        val keys = mutableMapOf<String, Boolean>()
        val reports = mutableListOf<Throwable>()
        val logs = mutableListOf<String>()
        override fun setKey(key: String, value: Boolean) { keys[key] = value }
        override fun report(throwable: Throwable, message: String?) {
            reports += throwable
            message?.let { logs += it }
        }
        override fun log(message: String) { logs += message }
    }

    private fun newLogger(
        consent: LogConsent,
        userId: String?,
        dao: LogEntryDao,
        appId: String = "pinto",
        appVersion: String = "1.18.4-test",
    ): Pair<LoggerImpl, FakeConsentStore> {
        val consentStore = FakeConsentStore(consent)

        val deviceInfo = mockk<DeviceInfoProvider>()
        io.mockk.every { deviceInfo.info } returns fakeDeviceInfo()

        val deviceHash = mockk<DeviceHashProvider>()
        coEvery { deviceHash.get() } returns "testhash"

        val session = SessionIdHolder()

        val logger = LoggerImpl(
            daoLazy = Lazy { dao },
            consentStore = consentStore,
            deviceInfoProvider = deviceInfo,
            deviceHashProvider = deviceHash,
            sessionIdHolder = session,
            userIdProvider = FakeUserIdProvider(userId),
            errorReporter = FakeErrorReporter(),
            appId = appId,
            appVersion = appVersion,
        )
        return logger to consentStore
    }

    @Test
    fun `1_Logger event 호출시 Room insert 발생`() = runTest {
        val captured = java.util.concurrent.atomic.AtomicReference<LogEntryEntity?>()
        val dao = mockk<LogEntryDao>(relaxed = true)
        coEvery { dao.insertAndEnforceLimit(any(), any()) } answers { captured.set(firstArg()) }

        val (logger, _) = newLogger(
            consent = LogConsent(identifiedConsent = false, wifiOnlyUpload = false, askedOnce = true),
            userId = null,
            dao = dao,
        )

        logger.event(TestEvent.SCREEN_ENTER_HOME)

        awaitNotNull { captured.get() }
        val entity = captured.get()!!
        assertTrue(
            "recordJson 에 SCREEN_ENTER_HOME 포함해야 함",
            entity.recordJson.contains("SCREEN_ENTER_HOME"),
        )
    }

    @Test
    fun `2_identifiedConsent_false 이면 userId null 로 저장`() = runTest {
        val captured = java.util.concurrent.atomic.AtomicReference<LogEntryEntity?>()
        val dao = mockk<LogEntryDao>(relaxed = true)
        coEvery { dao.insertAndEnforceLimit(any(), any()) } answers {
            captured.set(firstArg())
        }

        val (logger, _) = newLogger(
            consent = LogConsent(identifiedConsent = false, wifiOnlyUpload = false, askedOnce = true),
            userId = "12345", // 로그인되어 있지만 동의 거부 → null 이어야 함
            dao = dao,
        )

        logger.event(TestEvent.USER_LOGIN, mapOf(PayloadKey.SLOT_INDEX to 1))

        // IO scope에서 async insert — 폴링 대기
        awaitNotNull { captured.get() }

        val entity = captured.get()!!
        val record = json.decodeFromString(LogRecord.serializer(), entity.recordJson)
        assertNull("identifiedConsent=false 상태에서 userId 는 null 이어야 함", record.userId)
        val slotVal = (record.payload[PayloadKey.SLOT_INDEX.key] as? JsonPrimitive)?.contentOrNull
        assertEquals("1", slotVal)
    }

    @Test
    fun `3_identifiedConsent_true 이면 userId 포함`() = runTest {
        val captured = java.util.concurrent.atomic.AtomicReference<LogEntryEntity?>()
        val dao = mockk<LogEntryDao>(relaxed = true)
        coEvery { dao.insertAndEnforceLimit(any(), any()) } answers {
            captured.set(firstArg())
        }

        val (logger, _) = newLogger(
            consent = LogConsent(identifiedConsent = true, wifiOnlyUpload = false, askedOnce = true),
            userId = "99",
            dao = dao,
        )

        logger.event(TestEvent.SCREEN_ENTER_MAKER_HOME)

        awaitNotNull { captured.get() }

        val entity = captured.get()!!
        val record = json.decodeFromString(LogRecord.serializer(), entity.recordJson)
        assertEquals("99", record.userId)
        assertTrue(record.eventName == TestEvent.SCREEN_ENTER_MAKER_HOME.name)
    }

    @Test
    fun `4_주입된 appId 와 appVersion 이 LogRecord 에 기록됨`() = runTest {
        val captured = java.util.concurrent.atomic.AtomicReference<LogEntryEntity?>()
        val dao = mockk<LogEntryDao>(relaxed = true)
        coEvery { dao.insertAndEnforceLimit(any(), any()) } answers { captured.set(firstArg()) }

        val (logger, _) = newLogger(
            consent = LogConsent(identifiedConsent = false, wifiOnlyUpload = false, askedOnce = true),
            userId = null,
            dao = dao,
            appId = "pinto",
            appVersion = "9.9.9",
        )

        logger.event(TestEvent.SCREEN_ENTER_HOME)

        awaitNotNull { captured.get() }
        val entity = captured.get()!!
        val record = json.decodeFromString(LogRecord.serializer(), entity.recordJson)
        assertEquals("pinto", record.appId)
        assertEquals("9.9.9", record.appVersion)
    }

    // ─────────────────────────────────────────────────────────
    // Bug Hunt R01 회귀 테스트
    // ─────────────────────────────────────────────────────────

    /** bug #4 fix — nullify 중 JSON 파싱 실패 시 해당 엔트리를 DAO 에서 delete. */
    @Test
    fun `nullifyUserId - 파싱 실패 엔트리는 delete 처리`() = runTest {
        val dao = mockk<LogEntryDao>(relaxed = true)
        // 정상 entry + 망가진 entry 혼합
        val goodJson = """{"userId":"42","eventName":"X","timestamp":1}"""
        val corruptJson = """not-a-json"""
        val good = LogEntryEntity(id = "g1", recordJson = goodJson, createdAt = 1)
        val corrupt = LogEntryEntity(id = "c1", recordJson = corruptJson, createdAt = 2)

        coEvery { dao.getPendingEntries() } returns listOf(good, corrupt)

        val capturedDeletedIds = java.util.concurrent.atomic.AtomicReference<List<String>?>()
        coEvery { dao.deleteByIds(any()) } answers {
            capturedDeletedIds.set(firstArg())
        }
        val capturedUpdated = java.util.concurrent.atomic.AtomicReference<Pair<String, String>?>()
        coEvery { dao.updateRecordJson(any(), any()) } answers {
            capturedUpdated.set(firstArg<String>() to secondArg<String>())
        }

        val (logger, _) = newLogger(
            consent = LogConsent(identifiedConsent = false, wifiOnlyUpload = false, askedOnce = true),
            userId = null,
            dao = dao,
        )
        // 직접 호출 (초기화 후 consent true→false 전이 없이 테스트 안정성 확보)
        logger.nullifyUserIdWithRetry()

        val deletedIds = capturedDeletedIds.get()
        assertTrue("파싱 실패 레코드 c1 삭제 목록에 포함", deletedIds != null && deletedIds.contains("c1"))
        // 정상 entry 는 updateRecordJson 으로 userId=null 치환
        val updated = capturedUpdated.get()
        assertEquals("g1", updated?.first)
        assertTrue("치환된 JSON 에 \"userId\":null 포함", updated?.second?.contains("\"userId\":null") == true)
    }

    /**
     * bug #3 fix — nullify 1차 실패 시 재시도. 재시도도 실패하면 false 반환.
     * 첫 getPendingEntries 는 예외, 두 번째는 정상 → 재시도가 성공하므로 true.
     */
    @Test
    fun `nullifyUserIdWithRetry - 1차 실패 후 재시도 성공`() = runTest {
        val dao = mockk<LogEntryDao>(relaxed = true)
        val call = java.util.concurrent.atomic.AtomicInteger(0)
        coEvery { dao.getPendingEntries() } answers {
            if (call.getAndIncrement() == 0) error("simulated IO failure")
            else emptyList()
        }

        val (logger, _) = newLogger(
            consent = LogConsent(identifiedConsent = false, wifiOnlyUpload = false, askedOnce = true),
            userId = null,
            dao = dao,
        )
        val ok = logger.nullifyUserIdWithRetry()
        assertTrue("재시도로 성공해야 함", ok)
        assertEquals("getPendingEntries 두 번 호출되어야 함", 2, call.get())
    }

    @Test
    fun `nullifyUserIdWithRetry - 재시도도 실패하면 false`() = runTest {
        val dao = mockk<LogEntryDao>(relaxed = true)
        coEvery { dao.getPendingEntries() } throws RuntimeException("always fail")

        val (logger, _) = newLogger(
            consent = LogConsent(identifiedConsent = false, wifiOnlyUpload = false, askedOnce = true),
            userId = null,
            dao = dao,
        )
        val ok = logger.nullifyUserIdWithRetry()
        assertEquals(false, ok)
    }

    // ─────────────────────────────────────────────────────────
    // Bug Hunt R02 회귀 테스트 (#12 — enqueue ↔ nullify 직렬화)
    // ─────────────────────────────────────────────────────────

    /**
     * #12 회귀: consent=true 상태에서 enqueue 직후 nullify 호출되면, pending 레코드의 userId 가
     * 최종적으로 null 로 치환되어야 한다. Mutex 덕분에 insert → nullify 가 순차 실행되어
     * nullify 가 방금 insert 된 레코드를 놓치지 않는다.
     */
    @Test
    fun `R02_12 - enqueue 후 nullify 호출되면 insert된 레코드의 userId null 치환`() = runTest {
        // 단순 in-memory dao. insertAndEnforceLimit 이 저장소에 넣고, getPendingEntries 가 돌려주고,
        // updateRecordJson 으로 userId=null 치환이 일어나는지 확인.
        val store = java.util.concurrent.ConcurrentHashMap<String, LogEntryEntity>()
        val dao = mockk<LogEntryDao>(relaxed = true)
        coEvery { dao.insertAndEnforceLimit(any(), any()) } answers {
            val e = firstArg<LogEntryEntity>()
            store[e.id] = e
        }
        coEvery { dao.getPendingEntries() } answers { store.values.toList() }
        coEvery { dao.updateRecordJson(any(), any()) } answers {
            val id = firstArg<String>()
            val newJson = secondArg<String>()
            val existing = store[id]
            if (existing != null) {
                store[id] = existing.copy(recordJson = newJson)
            }
        }
        coEvery { dao.deleteByIds(any()) } answers {
            val ids = firstArg<List<String>>()
            ids.forEach { store.remove(it) }
        }

        val (logger, _) = newLogger(
            consent = LogConsent(identifiedConsent = true, wifiOnlyUpload = false, askedOnce = true),
            userId = "777",
            dao = dao,
        )

        // 1) consent=true 상태에서 enqueue (userId="777" 로 insert 예정)
        logger.event(TestEvent.SCREEN_ENTER_HOME)
        // IO scope 로 offload 된 insert 완료 대기
        awaitNotNull { store.values.firstOrNull() }

        // 2) consent 철회 훅 실행 (Mutex 가 enqueue 와 nullify 를 직렬화)
        val ok = logger.nullifyUserIdWithRetry()
        assertTrue("nullify 성공", ok)

        // 3) insert 된 레코드의 userId 는 null 로 치환되어 있어야 함
        val remaining = store.values.toList()
        assertEquals(1, remaining.size)
        val record = json.decodeFromString(LogRecord.serializer(), remaining.first().recordJson)
        assertNull("Mutex 로 insert ↔ nullify 직렬화 → userId 가 null 이어야 함", record.userId)
    }

    /** IO 디스패처 완료 대기 폴링. */
    private fun awaitNotNull(timeoutMs: Long = 2_000L, predicate: () -> Any?) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (predicate() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        if (predicate() == null) {
            throw AssertionError("timeout: insert 발생 안 함")
        }
    }
}
