package com.music961.pintocore.log.device

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

/**
 * 앱 프로세스 세션 식별자.
 *
 * 프로세스 시작 시 1회 UUID v7 생성. 프로세스가 죽으면 새 인스턴스가 새 세션을 부여.
 * SESSION_START / SESSION_END 발행은 Logger 측 ForegroundTrigger 책임 (phase-03).
 *
 * **주의 — SESSION_END는 best-effort**: 안드로이드가 OOM/시스템 kill로 프로세스를 끊으면
 * `ON_STOP` 이 안 불리는 경우가 있다. 집계는 SESSION_START 기준으로도 동작하도록 설계.
 *
 * @since pinto-core-log 0.1.0
 */
@Singleton
class SessionIdHolder @Inject constructor() {
    val sessionId: String = Uuid.random().toHexString()
    val sessionStartedAt: Long = System.currentTimeMillis()
}
