package com.music961.pintocore.log.model

/**
 * 로그 레코드의 상위 카테고리.
 *
 * - [event]: 사용자 액션·화면 진입 등 단발성 이벤트
 * - [performance]: 퍼포먼스 스팬 (durationMs 동반)
 * - [session]: 세션 시작/종료
 * - [error]: 경량 오류 (API 실패, 로그인 실패 등)
 *
 * 이름이 소문자인 이유: DynamoDB 레코드에 `name` 문자열로 저장되므로
 * 크로스 플랫폼(iOS) 문자열 값과 일치시킨다. schema-spec.md 참조.
 *
 * @since 2026-04-19 로그 시스템 재설계
 */
enum class LogCategory {
    event,
    performance,
    session,
    error,
}
