package com.music961.pintocore.log.logger

import com.music961.pintocore.log.api.LogAppId
import com.music961.pintocore.log.api.LogAppVersion
import com.music961.pintocore.log.api.LogErrorReporter
import com.music961.pintocore.log.api.LogEvent
import com.music961.pintocore.log.api.LogPayloadKey
import com.music961.pintocore.log.api.LogUserIdProvider
import com.music961.pintocore.log.consent.LogConsent
import com.music961.pintocore.log.consent.LogConsentStore
import com.music961.pintocore.log.device.DeviceHashProvider
import com.music961.pintocore.log.device.DeviceInfoProvider
import com.music961.pintocore.log.device.SessionIdHolder
import com.music961.pintocore.log.model.LogCategory
import com.music961.pintocore.log.model.LogRecord
import com.music961.pintocore.log.model.PayloadKey
import com.music961.pintocore.log.storage.LogEntryDao
import com.music961.pintocore.log.storage.LogEntryEntity
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

/**
 * [Logger] 구현체.
 *
 * - 모든 호출은 IO dispatcher 로 offload 되어 Room insert.
 * - 호출 시점의 [LogConsent.identifiedConsent] 를 StateFlow 로 읽어 userId 포함 여부 결정.
 *   false → userId=null (익명 수집). true → [LogUserIdProvider.currentUserId] 결과.
 * - 버퍼 임계치 초과 시 오래된 것부터 자동 삭제 (상한 [BUFFER_MAX_COUNT]).
 * - 동의 철회 훅: `identifiedConsent` true→false 전이 시 pending 레코드의 userId 를 null 로 치환.
 *
 * `askedOnce=false` 상태 (동의 질문 전) 에서 저장된 레코드는 `identifiedConsent=false` 전제로
 * 저장 시점에 userId=null 이므로 별도 처리 불필요. 업로드는 LogUploader 에서 askedOnce 기준으로 게이트.
 *
 * @since pinto-core-log 0.1.0
 */
@Singleton
class LoggerImpl @Inject constructor(
    // NOTE: LogEntryDao 주입 그래프에 Room build 가 들어가므로 Lazy 로 감싸서 DI 사이클 회피.
    private val daoLazy: Lazy<LogEntryDao>,
    private val consentStore: LogConsentStore,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val deviceHashProvider: DeviceHashProvider,
    private val sessionIdHolder: SessionIdHolder,
    private val userIdProvider: LogUserIdProvider,
    private val errorReporter: LogErrorReporter,
    @param:LogAppId private val appId: String,
    @param:LogAppVersion private val appVersion: String,
) : Logger {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    // Logger 전용 scope. 프로세스 생명주기 동안 유지.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 현재 동의 상태 캐시. 호출 hot path 에서 Flow collect 하지 않고 StateFlow 값 참조.
    private val _consent = MutableStateFlow(LogConsent.기본값)
    val consentState: StateFlow<LogConsent> = _consent.asStateFlow()

    /**
     * enqueue(insert) 와 nullifyUserIdOnPendingEntries 를 직렬화하기 위한 Mutex (Bug Hunt R02 #12 fix).
     *
     * Race: enqueue 의 scope.launch 가 아직 insert 를 끝내기 전에 consent true→false 전이가 발생하면,
     * nullify 가 실행될 때 해당 레코드는 아직 pending 테이블에 없어 userId 가 남는 pending 레코드가
     * 생긴다. 두 경로에 같은 Mutex 를 걸어 insert 와 nullify 가 섞이지 않도록 한다.
     *
     * 주의: Mutex 보유 상태에서 네트워크 호출 금지 — DB 작업만 수행한다.
     */
    private val enqueueMutex = Mutex()

    init {
        // 0) Bug Hunt R01 A2 fix — 첫 동의 값을 동기적으로 캡처.
        //    Flow 구독 시작 전 event() 호출이 들어오면 _consent 가 기본값(identifiedConsent=false)
        //    상태로 남아 있어 이미 동의한 사용자의 첫 이벤트가 익명으로 기록되는 윈도우가 발생.
        //    Singleton 생성 시 1회만 호출 → 앱 시작 시점 미세 지연만 발생.
        runCatching {
            runBlocking { consentStore.consent.first() }
        }.getOrNull()?.let { _consent.value = it }

        // 1) consent 변화 관찰 — 캐시 + 동의 철회 훅
        consentStore.consent
            .onEach { next ->
                val prev = _consent.value
                _consent.value = next
                // true → false 전이 시 pending 레코드의 userId 를 null 로 치환
                if (prev.identifiedConsent && !next.identifiedConsent) {
                    nullifyUserIdWithRetry()
                }
            }
            .launchIn(scope)
    }

    override fun event(event: LogEvent, payload: Map<LogPayloadKey, Any?>) {
        enqueue(event = event, category = event.defaultCategory.name, payload = payload, durationMs = null)
    }

    override fun perfStart(event: LogEvent): PerfSpan {
        val startedAt = System.currentTimeMillis()
        return object : PerfSpan {
            override fun end(extra: Map<LogPayloadKey, Any?>) {
                // Bug Hunt R01 B3 fix — clock skew/시스템 시간 역행 시 음수 방지.
                val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
                enqueue(
                    event = event,
                    category = LogCategory.performance.name,
                    payload = extra,
                    durationMs = elapsed,
                )
            }
        }
    }

    override fun error(event: LogEvent, errorCode: String, payload: Map<LogPayloadKey, Any?>) {
        val merged = payload + (PayloadKey.ERROR_CODE to errorCode)
        enqueue(
            event = event,
            category = LogCategory.error.name,
            payload = merged,
            durationMs = null,
        )
    }

    /** 내부 공용 enqueue. 호출자는 fire-and-forget. 실제 insert 는 IO 스레드에서. */
    private fun enqueue(
        event: LogEvent,
        category: String,
        payload: Map<LogPayloadKey, Any?>,
        durationMs: Long?,
    ) {
        scope.launch {
            runCatching {
                // Mutex: enqueue ↔ nullify 직렬화 (Bug Hunt R02 #12 fix).
                // buildRecord → entity 생성 → insertAndEnforceLimit 전체를 한 블록으로 보호.
                enqueueMutex.withLock {
                    val record = buildRecord(
                        eventName = event.name,
                        category = category,
                        payload = payload,
                        durationMs = durationMs,
                    )
                    val entity = LogEntryEntity(
                        id = Uuid.random().toHexString(),
                        recordJson = json.encodeToString(LogRecord.serializer(), record),
                        createdAt = record.timestamp,
                    )
                    val dao = daoLazy.get()
                    // insert + 상한 체크를 @Transaction 으로 원자화 (bug #6 fix)
                    dao.insertAndEnforceLimit(entity, BUFFER_MAX_COUNT)
                }
            }.onFailure {
                Timber.w(it, "LoggerImpl.enqueue 실패 name=${event.name}")
            }
        }
    }

    /**
     * [LogRecord] 조립. userId 는 현재 동의 상태 + 로그인 상태 기반.
     */
    suspend fun buildRecord(
        eventName: String,
        category: String,
        payload: Map<LogPayloadKey, Any?>,
        durationMs: Long?,
    ): LogRecord {
        // Bug Hunt R01 B2 fix — 함수 진입 시점 동의 스냅샷.
        // 함수 실행 중간에 _consent 가 변해도 단일 레코드의 식별 결정은 일관성 유지.
        val consent = _consent.value
        val deviceHash = deviceHashProvider.get()
        // Bug Hunt R01 (마리사#2) — currentUserId 가 빈 문자열을 반환해도 null 처리.
        val userId = if (consent.identifiedConsent) {
            userIdProvider.currentUserId()?.takeIf { it.isNotBlank() }
        } else null

        return LogRecord(
            platform = "android",
            appId = appId,
            appVersion = appVersion,
            eventName = eventName,
            timestamp = System.currentTimeMillis(),
            sessionId = sessionIdHolder.sessionId,
            userId = userId,
            deviceHash = deviceHash,
            deviceInfo = deviceInfoProvider.info,
            category = category,
            payload = payload.toJsonElementMap(),
            durationMs = durationMs,
        )
    }

    /**
     * 동의 철회 훅 재시도 래퍼 (bug #3 fix).
     *
     * 1회 실패 시 errorReporter 에 `log_nullify_failed` 키 + 1회 재시도. 재시도도 실패하면
     * 추가 보고 후 포기 (상위 launch scope 에서 예외 swallow — 다음 동의 철회 기회에 다시 시도).
     *
     * 두 시도 모두 [enqueueMutex] 안에서 수행하여 enqueue 와의 race 를 방지한다 (Bug Hunt R02 #12 fix).
     * Mutex 구간은 DB 작업만 수행하며 네트워크 호출은 포함하지 않는다.
     */
    internal suspend fun nullifyUserIdWithRetry(): Boolean {
        val first = runCatching { enqueueMutex.withLock { nullifyUserIdOnPendingEntries() } }
        if (first.isSuccess) return true
        val firstError = first.exceptionOrNull()
        runCatching {
            errorReporter.setKey("log_nullify_failed", true)
            errorReporter.report(
                firstError ?: RuntimeException("log_nullify_failed (no cause)"),
            )
        }
        Timber.w(firstError, "LoggerImpl: userId 익명화 1차 실패, 재시도")

        val retry = runCatching { enqueueMutex.withLock { nullifyUserIdOnPendingEntries() } }
        if (retry.isSuccess) return true
        val retryError = retry.exceptionOrNull()
        runCatching {
            errorReporter.setKey("log_nullify_failed_retry", true)
            errorReporter.report(
                retryError ?: RuntimeException("log_nullify_failed_retry (no cause)"),
            )
        }
        Timber.w(retryError, "LoggerImpl: userId 익명화 재시도도 실패")
        return false
    }

    /**
     * 동의 철회 훅. 이미 Room 에 쌓인 미업로드 레코드에서 userId 만 null 치환.
     *
     * SQL 단 replace 를 쓰지 않는 이유: JSON 이스케이프 이슈. 전체 SELECT → 파싱 → userId=null → 재직렬화.
     *
     * **JSON 파싱 실패 처리 (bug #4 fix)**: 손상된 레코드는 silent continue 대신 DAO 에서 delete +
     * errorReporter 보고. 오염된 레코드를 쌓아두면 다음 업로드 루프에서도 같은 문제를 반복하므로.
     */
    private suspend fun nullifyUserIdOnPendingEntries() {
        val dao = daoLazy.get()
        val pending = dao.getPendingEntries()
        val corrupt = mutableListOf<String>()
        for (e in pending) {
            val parsed = runCatching { json.parseToJsonElement(e.recordJson).asObjectOrNull() }
            val obj = parsed.getOrNull()
            if (obj == null) {
                // 파싱 실패 또는 object 가 아님 — 오염된 레코드 삭제 예약
                corrupt.add(e.id)
                val cause = parsed.exceptionOrNull()
                runCatching {
                    errorReporter.setKey("log_parse_corrupt", true)
                    errorReporter.report(
                        cause ?: RuntimeException("log_parse_corrupt id=${e.id}"),
                    )
                }
                Timber.w(cause, "LoggerImpl: 오염 레코드 삭제 id=${e.id}")
                continue
            }
            // 이미 null 이면 스킵
            if (obj["userId"] == null || obj["userId"] is JsonNull) continue
            val mutated = JsonObject(obj.toMutableMap().also { it["userId"] = JsonNull })
            dao.updateRecordJson(e.id, mutated.toString())
        }
        if (corrupt.isNotEmpty()) dao.deleteByIds(corrupt)
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

    companion object {
        /** 로컬 버퍼 상한. 초과 시 오래된 것부터 drop. */
        const val BUFFER_MAX_COUNT = 5_000
    }
}

/**
 * [LogPayloadKey] → String + 값 → [JsonElement] 변환.
 *
 * 지원 타입: null / Boolean / Number / String. 그 외는 `toString()` fallback.
 * 자유 텍스트(사용자 입력) 금지 원칙은 호출자 측에서 지킨다 (LogPayloadKey 정의에 명시됨).
 */
internal fun Map<LogPayloadKey, Any?>.toJsonElementMap(): Map<String, JsonElement> {
    val out = LinkedHashMap<String, JsonElement>(this.size)
    for ((k, v) in this) {
        out[k.key] = v.toJsonElement()
    }
    return out
}

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(this)
    is Int -> JsonPrimitive(this)
    is Long -> JsonPrimitive(this)
    is Short -> JsonPrimitive(this)
    is Byte -> JsonPrimitive(this.toInt())
    is Float -> JsonPrimitive(this)
    is Double -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    else -> JsonPrimitive(this.toString())
}
