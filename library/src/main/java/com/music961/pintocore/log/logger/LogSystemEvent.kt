package com.music961.pintocore.log.logger

import com.music961.pintocore.log.api.LogEvent
import com.music961.pintocore.log.model.LogCategory

/**
 * 라이브러리 자체 발행 시스템 이벤트.
 *
 * `LogForegroundTrigger` 가 ProcessLifecycle ON_START / ON_STOP 시점에 발행한다.
 * enum 의 `name` 속성이 `"SESSION_START"` / `"SESSION_END"` 그대로 DynamoDB `eventName` 에 저장된다.
 *
 * 앱 측 EventName 에 동일 상수가 있어도 문자열 값이 같으므로 집계상 무해하지만,
 * 권장은 앱이 SESSION_START/SESSION_END 를 자체 enum 에서 제거하고 라이브러리 발행에 위임.
 *
 * @since pinto-core-log 0.1.0
 */
internal enum class LogSystemEvent(override val defaultCategory: LogCategory) : LogEvent {
    SESSION_START(LogCategory.session),
    SESSION_END(LogCategory.session),
}
