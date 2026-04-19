package com.music961.pintocore.log.api

import com.music961.pintocore.log.model.LogCategory

/**
 * 로그 이벤트 contract.
 *
 * 앱은 도메인별 enum 으로 이 인터페이스를 구현한다 (예: pinto-smalto 의 EventName).
 * 라이브러리는 [name] 문자열만 DynamoDB 에 저장하므로 자유롭게 버전 관리 가능
 * (예: SCREEN_VIEW_V2 처럼 enum 상수명에 버전 suffix).
 *
 * @since pinto-core-log 0.1.0
 */
interface LogEvent {
    /** DynamoDB `eventName` 필드에 저장될 문자열. enum 의 [name] 그대로 사용 권장. */
    val name: String

    /** 카테고리. Logger 가 명시 카테고리를 받지 않으면 이 값 사용. */
    val defaultCategory: LogCategory
}
