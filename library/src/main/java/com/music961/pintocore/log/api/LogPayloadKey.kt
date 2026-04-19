package com.music961.pintocore.log.api

/**
 * Payload 키 contract. 자유 문자열 금지 — 호출자는 항상 이 인터페이스 구현 enum 을 거친다.
 * 라이브러리 공통 키는 [com.music961.pintocore.log.model.PayloadKey] 가 제공.
 * 앱 도메인 키는 앱 자체 enum 으로 추가 가능.
 *
 * @since pinto-core-log 0.1.0
 */
interface LogPayloadKey {
    val key: String
}
