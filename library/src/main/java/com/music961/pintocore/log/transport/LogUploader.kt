package com.music961.pintocore.log.transport

import com.music961.pintocore.aws.PintoAWS
import com.music961.pintocore.aws.dynamoBatchWriteItem
import com.music961.pintocore.log.api.LogErrorReporter
import com.music961.pintocore.log.consent.LogConsentStore
import com.music961.pintocore.log.storage.LogEntryDao
import com.music961.pintocore.log.storage.LogEntryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import timber.log.Timber
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

/**
 * Room 버퍼 → DynamoDB `PintoLog` 배치 업로드기.
 *
 * **Lambda 경유하지 않음** (phase-03 지침, 2026-04-19 변경). [PintoAWS.dynamoBatchWriteItem] 로 직접 Put.
 *
 * 규칙:
 * - 한 번 호출당 최대 [BATCH_SIZE] (기본 100) 건.
 * - 분당 호출 상한 [RATE_LIMIT_PER_MIN]. 초과 시 [Result] 성공 반환 (skip).
 * - 동의 `askedOnce=false` 상태면 업로드 보류 (서버 전송 금지).
 * - WiFi-only 토글 ON + 현재 모바일 데이터 → skip.
 * - 레코드 직렬화 크기 > [MAX_RECORD_BYTES] (400KB) → drop + errorReporter 보고.
 * - `mutationsEnabled=false` 로 거절되면 errorReporter 1회 보고 후 이후 호출은 즉시 반환.
 *
 * 반환: 업로드 성공 건수 (skip 시 0).
 *
 * @since pinto-core-log 0.1.0
 */
@Singleton
class LogUploader @Inject constructor(
    private val dao: LogEntryDao,
    private val pintoAws: PintoAWS,
    private val consentStore: LogConsentStore,
    private val networkMonitor: NetworkMonitor,
    private val errorReporter: LogErrorReporter,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // 분당 rate limit 카운터 (Mutex 로 원자성 보장 — bug #2 fix)
    private val rateMutex = Mutex()
    private var windowStartMs: Long = 0L
    private var windowCount: Int = 0

    // Bug Hunt R01 A5 fix — read-modify-write 원자성 보장 (compareAndSet).
    private val mutationsDisabledReported = AtomicBoolean(false)

    /**
     * 업로드 1회 시도.
     *
     * @return 업로드된(accepted) 건수. 조건 불충족으로 skip된 경우 0.
     */
    suspend fun upload(): Result<Int> = runCatching {
        // 0) 동의/네트워크 게이트
        val consent = consentStore.consent.first()
        if (!consent.askedOnce) {
            Timber.d("LogUploader: askedOnce=false → 업로드 보류")
            return@runCatching 0
        }
        if (consent.wifiOnlyUpload && networkMonitor.current() == NetworkMonitor.Type.CELLULAR) {
            Timber.d("LogUploader: wifiOnly ON + cellular → skip")
            return@runCatching 0
        }
        if (networkMonitor.current() == NetworkMonitor.Type.NONE) {
            Timber.d("LogUploader: 네트워크 없음 → skip")
            return@runCatching 0
        }

        // 1) rate limit
        if (!passRateLimit()) {
            Timber.w("LogUploader: rate limit 초과 → skip")
            return@runCatching 0
        }

        if (mutationsDisabledReported.get()) {
            return@runCatching 0
        }

        // 2) 배치 조회
        val batch = dao.oldestBatch(BATCH_SIZE)
        if (batch.isEmpty()) return@runCatching 0

        // 3) DynamoDB 아이템으로 변환 + 크기 검증
        val accepted = mutableListOf<String>()      // 성공시 삭제 대상 entity.id
        val overSized = mutableListOf<String>()     // drop 대상 entity.id
        val items = mutableListOf<Map<String, Any?>>()
        for (e in batch) {
            val bytes = e.recordJson.toByteArray(Charsets.UTF_8).size
            if (bytes > MAX_RECORD_BYTES) {
                overSized.add(e.id)
                reportOversized(bytes)
                continue
            }
            // Bug Hunt R01 B5 fix — toDynamoItem 캐스팅/파싱 실패 시 errorReporter 보고.
            val item = runCatching { e.toDynamoItem() }
                .onFailure { t ->
                    runCatching {
                        errorReporter.report(t, "log_dynamo_item_drop id=${e.id}")
                    }
                }
                .getOrNull()
            if (item == null) {
                overSized.add(e.id) // 파싱 실패도 drop
                continue
            }
            items.add(item)
            accepted.add(e.id)
        }
        if (overSized.isNotEmpty()) dao.deleteByIds(overSized)
        if (items.isEmpty()) return@runCatching 0

        // 4) BatchWrite
        val result = pintoAws.dynamoBatchWriteItem(
            tableName = TABLE_NAME,
            items = items,
        ).getOrElse { throwable ->
            // mutationsEnabled=false 판별 → 무한루프 방지
            val msg = throwable.message.orEmpty()
            if (msg.contains("mutations disabled", ignoreCase = true) ||
                msg.contains("mutationsEnabled", ignoreCase = true)
            ) {
                // Bug Hunt R01 A5 fix — compareAndSet 으로 errorReporter.log 1회만 호출.
                if (mutationsDisabledReported.compareAndSet(false, true)) {
                    runCatching {
                        errorReporter.log("log_upload_mutations_disabled: $msg")
                    }
                }
                return@runCatching 0
            }
            throw throwable
        }

        // 5) 성공분 삭제, 실패분 retry 증가
        if (result.unprocessed.isEmpty()) {
            dao.deleteByIds(accepted)
            // retry 과다 레코드 drop
            dao.dropOverRetry(MAX_RETRY_COUNT)
            return@runCatching result.accepted
        }
        // unprocessed 원본 맵 → recover id 매칭 (id 는 item map 의 "logEntryId")
        // Bug Hunt R01 B4 fix — logEntryId 누락 시 silent skip 대신 errorReporter 보고.
        val unprocessedIds = result.unprocessed.mapNotNull { item ->
            val id = item["logEntryId"] as? String
            if (id == null) {
                runCatching {
                    errorReporter.log("log_upload_unprocessed_missing_id: keys=${item.keys}")
                }
            }
            id
        }.toSet()
        val successIds = accepted.filter { it !in unprocessedIds }
        if (successIds.isNotEmpty()) dao.deleteByIds(successIds)
        if (unprocessedIds.isNotEmpty()) {
            dao.incrementRetry(unprocessedIds.toList())
            dao.dropOverRetry(MAX_RETRY_COUNT)
        }
        result.accepted
    }.onFailure { Timber.w(it, "LogUploader.upload 실패") }

    /**
     * 분당 호출 상한. true → 진행, false → skip.
     *
     * Mutex 로 windowStartMs/windowCount 원자성 보장 (bug #2 fix — 동시 호출 race 제거).
     */
    private suspend fun passRateLimit(): Boolean = rateMutex.withLock {
        val now = System.currentTimeMillis()
        if (now - windowStartMs >= 60_000L) {
            windowStartMs = now
            windowCount = 0
        }
        if (windowCount >= RATE_LIMIT_PER_MIN) return@withLock false
        windowCount++
        true
    }

    private fun reportOversized(bytes: Int) {
        runCatching {
            errorReporter.log("log_record_oversized: $bytes bytes > $MAX_RECORD_BYTES")
        }
    }

    /**
     * Room 엔티티 → DynamoDB 아이템 변환.
     *
     * - `pk` = YYYY-MM-DD (timestamp UTC)
     * - `sk` = `{timestamp}#{UUID v7}` — 업로드 직전 UUID 새로 생성
     * - `expireAt` = timestamp_seconds + 30일 (TTL)
     * - LogRecord 나머지 필드는 flatten 하여 top-level attribute 로
     * - 내부 추적용 `logEntryId` (Room id) 포함 — unprocessed 매칭에 사용
     *
     * **timestamp fallback 정책 (bug #1 fix)**:
     * JSON 의 `timestamp` 가 없거나 파싱 실패하면 Room 저장 시점인 [LogEntryEntity.createdAt] 을
     * fallback 으로 사용한다. `System.currentTimeMillis()` fallback 은 금지
     * (오늘 날짜로 pk 가 찍혀 파티션 집계가 왜곡됨).
     * createdAt 도 비정상(<=0)이면 해당 레코드는 drop 처리되도록 상위에서 체크.
     */
    private fun LogEntryEntity.toDynamoItem(): Map<String, Any?> {
        // JSON → LogRecord 재파싱하지 않고 JsonObject 로 처리 (flatten 편의)
        val obj = json.parseToJsonElement(recordJson) as? JsonObject ?: error("not an object")
        val tsFromJson = (obj["timestamp"] as? JsonPrimitive)?.longOrNull
        val ts: Long = when {
            tsFromJson != null && tsFromJson > 0L -> tsFromJson
            createdAt > 0L -> createdAt
            else -> error("no valid timestamp (json=null, createdAt<=0)")
        }
        val tsSec = ts / 1000L
        val pk = DateTimeFormatter.ISO_LOCAL_DATE.format(
            Instant.ofEpochMilli(ts).atOffset(ZoneOffset.UTC)
        )
        val sk = "${ts}#${Uuid.random().toHexString()}"
        val expireAt = tsSec + TTL_SECONDS

        val out = LinkedHashMap<String, Any?>(obj.size + 4)
        out["pk"] = pk
        out["sk"] = sk
        out["expireAt"] = expireAt
        out["logEntryId"] = id
        for ((k, v) in obj) {
            // pk/sk/expireAt 충돌 방지
            if (k == "pk" || k == "sk" || k == "expireAt" || k == "logEntryId") continue
            out[k] = v.toDynamoNative()
        }
        // tsFromJson 이 없었다면 createdAt 을 timestamp 로 채워준다 (server-side 집계 일관성)
        if (tsFromJson == null || tsFromJson <= 0L) {
            out["timestamp"] = ts
        }
        return out
    }

    private fun JsonElement.toDynamoNative(): Any? = when (this) {
        is JsonNull -> null
        is JsonObject -> this.mapValues { (_, v) -> v.toDynamoNative() }
        is JsonPrimitive -> when {
            this.isString -> this.content
            booleanOrNull != null -> booleanOrNull
            longOrNull != null -> longOrNull
            doubleOrNull != null -> doubleOrNull
            else -> this.content
        }
        is kotlinx.serialization.json.JsonArray -> this.map { it.toDynamoNative() }
    }

    /**
     * 테스트 전용 — toDynamoItem 의 timestamp/pk fallback 검증용.
     * @return (pk, timestamp) 쌍. 검증 실패 시 IllegalStateException.
     */
    internal fun debugToDynamoItem(entity: LogEntryEntity): Map<String, Any?> =
        entity.toDynamoItem()

    companion object {
        const val TABLE_NAME = "PintoLog"
        const val BATCH_SIZE = 100
        const val RATE_LIMIT_PER_MIN = 10
        const val MAX_RECORD_BYTES = 400 * 1024
        const val MAX_RETRY_COUNT = 3

        /** TTL 30일 (초). */
        const val TTL_SECONDS = 30L * 86_400L
    }
}
