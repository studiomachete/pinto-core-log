package com.music961.pintocore.log.transport

import com.music961.pintocore.aws.BatchWriteItemResult
import com.music961.pintocore.aws.PintoAWS
import com.music961.pintocore.aws.PintoAWSLocal
import com.music961.pintocore.aws.dynamoBatchWriteItem
import com.music961.pintocore.log.api.LogErrorReporter
import com.music961.pintocore.log.consent.LogConsent
import com.music961.pintocore.log.consent.LogConsentStore
import com.music961.pintocore.log.storage.LogEntryDao
import com.music961.pintocore.log.storage.LogEntryEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * LogUploader 의 wifiOnly 분기 + askedOnce 게이트 + toDynamoItem timestamp fallback 계약 검증.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogUploaderTest {

    private class FakeConsentStore(initial: LogConsent) : LogConsentStore {
        val state = MutableStateFlow(initial)
        override val consent: Flow<LogConsent> = state.asStateFlow()
        override suspend fun setIdentifiedConsent(v: Boolean) { state.value = state.value.copy(identifiedConsent = v) }
        override suspend fun setWifiOnly(v: Boolean) { state.value = state.value.copy(wifiOnlyUpload = v) }
        override suspend fun markAsked() { state.value = state.value.copy(askedOnce = true) }
        override suspend fun setConsentAndMarkAsked(allow: Boolean) {
            state.value = state.value.copy(identifiedConsent = allow, askedOnce = true)
        }
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

    private fun stubNetMonitor(type: NetworkMonitor.Type): NetworkMonitor {
        val m = mockk<NetworkMonitor>()
        io.mockk.every { m.current() } returns type
        return m
    }

    @Test
    fun `wifiOnly true 이고 cellular 이면 DAO 안 건드리고 skip`() = runTest {
        val dao = mockk<LogEntryDao>(relaxed = true)
        val aws = mockk<PintoAWS>(relaxed = true)
        val consent = FakeConsentStore(
            LogConsent(identifiedConsent = true, wifiOnlyUpload = true, askedOnce = true)
        )
        val net = stubNetMonitor(NetworkMonitor.Type.CELLULAR)

        val uploader = LogUploader(dao, aws, consent, net, FakeErrorReporter())
        val result = uploader.upload()

        assertEquals(0, result.getOrNull())
        // skip 경로 → DAO oldestBatch 조차 호출하지 않아야 함
        coVerify(exactly = 0) { dao.oldestBatch(any()) }
    }

    @Test
    fun `askedOnce false 이면 업로드 보류`() = runTest {
        val dao = mockk<LogEntryDao>(relaxed = true)
        val aws = mockk<PintoAWS>(relaxed = true)
        val consent = FakeConsentStore(
            LogConsent(identifiedConsent = false, wifiOnlyUpload = false, askedOnce = false)
        )
        val net = stubNetMonitor(NetworkMonitor.Type.WIFI)

        val uploader = LogUploader(dao, aws, consent, net, FakeErrorReporter())
        val result = uploader.upload()

        assertEquals(0, result.getOrNull())
        coVerify(exactly = 0) { dao.oldestBatch(any()) }
    }

    // ─────────────────────────────────────────────────────────────
    // Bug Hunt R01 회귀 테스트
    // ─────────────────────────────────────────────────────────────

    /**
     * bug #1 fix — JSON 에 timestamp 누락 시 createdAt 을 fallback 으로 사용해야 함.
     * `System.currentTimeMillis()` fallback 은 금지 (파티션키 왜곡).
     */
    @Test
    fun `toDynamoItem - timestamp 없을 때 createdAt 기반으로 pk 계산`() {
        val aws = mockk<PintoAWS>(relaxed = true)
        val consent = FakeConsentStore(
            LogConsent(identifiedConsent = false, wifiOnlyUpload = false, askedOnce = true)
        )
        val uploader = LogUploader(
            mockk(relaxed = true), aws, consent, stubNetMonitor(NetworkMonitor.Type.WIFI), FakeErrorReporter()
        )
        // 2025-01-01 UTC = 1735689600000
        val createdAt = 1735689600000L
        val entity = LogEntryEntity(
            id = "test-id",
            recordJson = """{"eventName":"X","platform":"android"}""", // timestamp 없음
            createdAt = createdAt,
        )
        val item = uploader.debugToDynamoItem(entity)
        val expectedPk = DateTimeFormatter.ISO_LOCAL_DATE.format(
            Instant.ofEpochMilli(createdAt).atOffset(ZoneOffset.UTC)
        )
        assertEquals("pk 는 createdAt 기준이어야 함 (today 가 아닌)", expectedPk, item["pk"])
        assertEquals("timestamp 필드 채워짐", createdAt, item["timestamp"])
    }

    @Test
    fun `toDynamoItem - timestamp 존재 시 해당 값 우선`() {
        val aws = mockk<PintoAWS>(relaxed = true)
        val consent = FakeConsentStore(
            LogConsent(identifiedConsent = false, wifiOnlyUpload = false, askedOnce = true)
        )
        val uploader = LogUploader(
            mockk(relaxed = true), aws, consent, stubNetMonitor(NetworkMonitor.Type.WIFI), FakeErrorReporter()
        )
        val ts = 1704067200000L // 2024-01-01 UTC
        val createdAt = 1735689600000L // 2025-01-01 UTC — 다르게 설정
        val entity = LogEntryEntity(
            id = "test-id",
            recordJson = """{"eventName":"X","timestamp":$ts}""",
            createdAt = createdAt,
        )
        val item = uploader.debugToDynamoItem(entity)
        assertEquals("2024-01-01", item["pk"])
    }

    /**
     * Bug Hunt R01 A5 fix — mutationsDisabled 응답을 두 번 받아도 errorReporter.log 는 1회만 호출.
     * 첫 호출에서 보고하고, 두 번째 호출은 즉시 0 반환하므로 dynamoBatchWriteItem 도 1번만 호출된다.
     */
    @Test
    fun `R01_A5 - mutationsDisabled 두 번 받아도 errorReporter log 1회만 호출`() = runTest {
        mockkStatic("com.music961.pintocore.aws.DynamoBatchWriteKt")
        try {
            val dao = mockk<LogEntryDao>(relaxed = true)
            // 배치 1건 반환 (BatchWrite 진입 조건 만족)
            coEvery { dao.oldestBatch(any()) } returns listOf(
                LogEntryEntity(
                    id = "id-1",
                    recordJson = """{"eventName":"X","timestamp":1735689600000}""",
                    createdAt = 1735689600000L,
                ),
            )
            val aws = mockk<PintoAWS>(relaxed = true)
            // dynamoBatchWriteItem 가 mutationsEnabled 거절 예외 던지도록.
            coEvery {
                aws.dynamoBatchWriteItem(any(), any(), any(), any())
            } returns Result.failure(IllegalStateException("mutations disabled for BatchWriteItem"))

            val consent = FakeConsentStore(
                LogConsent(identifiedConsent = false, wifiOnlyUpload = false, askedOnce = true),
            )
            val reporter = FakeErrorReporter()
            val uploader = LogUploader(
                dao, aws, consent, stubNetMonitor(NetworkMonitor.Type.WIFI), reporter,
            )

            val r1 = uploader.upload()
            val r2 = uploader.upload()

            assertEquals(0, r1.getOrNull())
            assertEquals(0, r2.getOrNull())
            // mutationsDisabled 보고는 첫 호출 시 1회만.
            val disabledLogs = reporter.logs.filter { it.contains("log_upload_mutations_disabled") }
            assertEquals("mutationsDisabled 보고는 1회만", 1, disabledLogs.size)
            // 두 번째 호출은 mutationsDisabledReported 가드로 dynamoBatchWriteItem 까지 가지 않음.
            coVerify(exactly = 1) { aws.dynamoBatchWriteItem(any(), any(), any(), any()) }
        } finally {
            unmockkStatic("com.music961.pintocore.aws.DynamoBatchWriteKt")
        }
    }

    /**
     * Bug Hunt R01 B4 fix — unprocessed item 에 logEntryId 가 누락되면 errorReporter.log 호출.
     */
    @Test
    fun `R01_B4 - unprocessed 에 logEntryId 없으면 errorReporter log 호출`() = runTest {
        mockkStatic("com.music961.pintocore.aws.DynamoBatchWriteKt")
        try {
            val dao = mockk<LogEntryDao>(relaxed = true)
            coEvery { dao.oldestBatch(any()) } returns listOf(
                LogEntryEntity(
                    id = "id-1",
                    recordJson = """{"eventName":"X","timestamp":1735689600000}""",
                    createdAt = 1735689600000L,
                ),
            )
            val aws = mockk<PintoAWS>(relaxed = true)
            // unprocessed 에 logEntryId 키가 없는 맵 반환 (시뮬: 손상된 응답).
            coEvery {
                aws.dynamoBatchWriteItem(any(), any(), any(), any())
            } returns Result.success(
                BatchWriteItemResult(
                    accepted = 0,
                    unprocessed = listOf(mapOf("pk" to "2025-01-01", "sk" to "abc")),
                ),
            )

            val consent = FakeConsentStore(
                LogConsent(identifiedConsent = false, wifiOnlyUpload = false, askedOnce = true),
            )
            val reporter = FakeErrorReporter()
            val uploader = LogUploader(
                dao, aws, consent, stubNetMonitor(NetworkMonitor.Type.WIFI), reporter,
            )

            uploader.upload()

            val missingLogs = reporter.logs.filter {
                it.contains("log_upload_unprocessed_missing_id")
            }
            assertEquals("unprocessed missing logEntryId 보고는 1회", 1, missingLogs.size)
        } finally {
            unmockkStatic("com.music961.pintocore.aws.DynamoBatchWriteKt")
        }
    }

    @Test
    fun `toDynamoItem - timestamp 와 createdAt 모두 없으면 error`() {
        val aws = mockk<PintoAWS>(relaxed = true)
        val consent = FakeConsentStore(
            LogConsent(identifiedConsent = false, wifiOnlyUpload = false, askedOnce = true)
        )
        val uploader = LogUploader(
            mockk(relaxed = true), aws, consent, stubNetMonitor(NetworkMonitor.Type.WIFI), FakeErrorReporter()
        )
        val entity = LogEntryEntity(
            id = "bad",
            recordJson = """{"eventName":"X"}""",
            createdAt = 0L, // 비정상
        )
        try {
            uploader.debugToDynamoItem(entity)
            fail("timestamp/createdAt 모두 없으면 예외 발생해야 함")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("timestamp"))
        }
    }
}
