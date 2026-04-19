package com.music961.pintocore.log.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 로그 시스템의 단일 레코드. Room 버퍼링 후 DynamoDB `PintoLog` 테이블로 배치 전송.
 *
 * DynamoDB 파티션 설계:
 * - pk = YYYY-MM-DD (timestamp 기준 로컬 날짜)
 * - sk = {timestamp}#{uuid-v7}
 * - expireAt = timestamp + 30일 (초 단위 unix epoch, TTL)
 *
 * iOS 포팅 시 동일 스키마 유지 (schema-spec.md 참조).
 *
 * @param schemaVersion 스키마 버전. 변경 시 +1. (초기값 1)
 * @param platform "android" | "ios"
 * @param appId 앱 식별자 (`"pinto"` | `"pinto-console"` | `"pinto-studio"` 등).
 *              platform 과 직교 — 플랫폼이 같아도 앱이 다를 수 있음. 향후 여러 앱이 같은 PintoLog 테이블 공유 시 집계용.
 * @param appVersion BuildConfig.VERSION_NAME (예: "1.18.4")
 * @param eventName [com.music961.pintocore.log.api.LogEvent]의 name (자유 문자열 금지)
 * @param timestamp 이벤트 발생 시각 epoch millis
 * @param sessionId 세션별 UUID v7 (세션 시작 시 생성, 앱 종료까지 고정)
 * @param userId 동의 시만 포함. 익명 수집 시 null
 * @param deviceHash 설치 시 1회 생성한 익명 식별자 (UUID v7). 동의 거부 시에도 유지
 * @param deviceInfo 디바이스 사양 (세션 1회 캐시)
 * @param category [LogCategory]의 name
 * @param payload 구조화 필드만. 키는 [PayloadKey] 상수 사용. 자유 텍스트(사용자 입력) 금지
 * @param durationMs [LogCategory.performance] 카테고리 시 스팬 길이. 그 외 null
 *
 * @since 2026-04-19 로그 시스템 재설계
 */
@Serializable
data class LogRecord(
    val schemaVersion: Int = 1,
    val platform: String,
    val appId: String,
    val appVersion: String,
    val eventName: String,
    val timestamp: Long,
    val sessionId: String,
    val userId: String? = null,
    val deviceHash: String,
    val deviceInfo: DeviceInfo,
    val category: String,
    val payload: Map<String, JsonElement> = emptyMap(),
    val durationMs: Long? = null,
)
