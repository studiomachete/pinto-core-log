package com.music961.pintocore.log.logger

import com.music961.pintocore.log.api.LogEvent
import com.music961.pintocore.log.api.LogPayloadKey

/**
 * 라이브러리 공용 Logger 인터페이스.
 *
 * - 이벤트는 [LogEvent] contract 구현체. 자유 문자열 금지.
 * - payload 키는 [LogPayloadKey] contract 구현체. 자유 문자열 금지.
 * - userId 포함 여부는 [com.music961.pintocore.log.consent.LogConsent.identifiedConsent]
 *   상태에 따라 Logger 내부에서 자동 결정.
 * - 세션 이벤트 (SESSION_START/SESSION_END) 는 [LogForegroundTrigger] 가 내부에서 발행.
 *
 * @since pinto-core-log 0.1.0
 */
interface Logger {

    /** 일반 이벤트 (category = event.defaultCategory) */
    fun event(event: LogEvent, payload: Map<LogPayloadKey, Any?> = emptyMap())

    /** 퍼포먼스 스팬 시작. 반환된 [PerfSpan.end] 호출 시점까지 durationMs 측정. */
    fun perfStart(event: LogEvent): PerfSpan

    /** 오류 (category = error). errorCode 는 구조화 문자열. */
    fun error(
        event: LogEvent,
        errorCode: String,
        payload: Map<LogPayloadKey, Any?> = emptyMap(),
    )
}

/** [Logger.perfStart] 반환값. end() 호출 시 durationMs 계산되어 기록. */
interface PerfSpan {
    fun end(extra: Map<LogPayloadKey, Any?> = emptyMap())
}
