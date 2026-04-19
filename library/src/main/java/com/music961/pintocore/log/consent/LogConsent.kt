package com.music961.pintocore.log.consent

/**
 * 로그 수집 동의 상태.
 *
 * - [identifiedConsent]: 분석 데이터 수집 동의 여부.
 *   true 일 때만 userId 포함하여 전송. false 면 익명 수집(또는 전송 자체 중단)은 phase-03 Logger 책임.
 * - [wifiOnlyUpload]: WiFi 연결 시에만 로그 배치 업로드.
 * - [askedOnce]: 온보딩 동의 다이얼로그를 이미 한 번 표시했는지 여부.
 *
 * @since pinto-core-log 0.1.0
 */
data class LogConsent(
    val identifiedConsent: Boolean,
    val wifiOnlyUpload: Boolean,
    val askedOnce: Boolean,
) {
    companion object {
        val 기본값 = LogConsent(
            identifiedConsent = false,
            wifiOnlyUpload = false,
            askedOnce = false,
        )
    }
}
