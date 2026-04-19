package com.music961.pintocore.log.api

/**
 * 현재 로그인 사용자 ID 제공 contract. 앱이 자기 인증 시스템에 맞게 구현.
 *
 * 동의 (`LogConsent.identifiedConsent = true`) 상태일 때만 LoggerImpl 이 이 값을 호출하여
 * LogRecord.userId 에 채운다. 미로그인/알 수 없음 시 null 반환.
 *
 * @since pinto-core-log 0.1.0
 */
interface LogUserIdProvider {
    suspend fun currentUserId(): String?
}
