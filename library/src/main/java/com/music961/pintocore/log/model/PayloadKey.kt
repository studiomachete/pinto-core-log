package com.music961.pintocore.log.model

import com.music961.pintocore.log.api.LogPayloadKey

/**
 * 라이브러리 공통 payload 키. 앱은 추가 도메인 키 enum 을 자체 정의 가능.
 *
 * **규칙**: payload 키는 자유 문자열 금지. 이 enum 또는 [LogPayloadKey] 구현 enum 의 [key] 값만 사용.
 *
 * iOS 포팅 시 동일 문자열 값 사용 (schema-spec.md 참조).
 *
 * @since pinto-core-log 0.1.0
 */
enum class PayloadKey(override val key: String) : LogPayloadKey {

    // 공통
    FROM_SCREEN("fromScreen"),          // 진입 직전 화면 이름
    TO_SCREEN("toScreen"),              // 이동한 화면 이름
    DURATION_MS("durationMs"),          // 추가 세부 지속시간 (LogRecord.durationMs와 별개 용도)
    NETWORK_TYPE("networkType"),        // "wifi" | "cellular" | "none"

    // 작품 / 메이커
    WORK_ID("workId"),                  // 작품 키 (UUID v7)
    WORK_TITLE_HASH("workTitleHash"),   // 제목 해시 (PII 방지, 실제 제목 금지)
    EPISODE_INDEX("episodeIndex"),      // 에피소드 순번
    CHARACTER_COUNT("characterCount"),  // 캐릭터 수
    VARIABLE_COUNT("variableCount"),    // 변수 개수

    // 게임 플레이
    SLOT_INDEX("slotIndex"),            // 세이브 슬롯 번호
    PLAY_MODE("playMode"),              // 플레이 모드 (enum name)
    EPISODE_KEY("episodeKey"),          // 에피소드 키 (UUID v7)

    // 사용자 활동
    TARGET_USER_ID("targetUserId"),     // 팔로우/신고 대상
    REPORT_REASON("reportReason"),      // 신고 사유 코드 (자유 텍스트 금지)
    COMMENT_LENGTH("commentLength"),    // 댓글 길이 (본문 금지)

    // 업로드 / 다운로드
    FILE_SIZE_BYTES("fileSizeBytes"),
    FILE_TYPE("fileType"),              // "image" | "audio" | "video"

    // 오류
    ERROR_CODE("errorCode"),            // 구조화된 코드 (문자열/정수)
    ERROR_DOMAIN("errorDomain"),        // "api" | "auth" | "upload" | ...
    HTTP_STATUS("httpStatus"),          // HTTP 상태코드
    RETRY_COUNT("retryCount"),

    // 세션
    SESSION_DURATION_MS("sessionDurationMs"),

    // 기타
    EXTRA("extra"),                     // 예비 슬롯 (구조화된 Map 만)
}
