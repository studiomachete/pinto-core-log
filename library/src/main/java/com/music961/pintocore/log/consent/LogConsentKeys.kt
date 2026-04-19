package com.music961.pintocore.log.consent

import androidx.datastore.preferences.core.booleanPreferencesKey

/**
 * [LogConsentStoreImpl] 가 사용하는 DataStore Preferences 키.
 *
 * 키 이름은 메인 앱과 동일 유지 (`LOG_IDENTIFIED_CONSENT` 등) — 단, 라이브러리 자체 DataStore 파일
 * (`pinto_log_consent`) 로 분리되었으므로 메인 앱의 `pintoDataStore` 와는 별도 파일.
 */
internal object LogConsentKeys {
    val IDENTIFIED_CONSENT = booleanPreferencesKey("LOG_IDENTIFIED_CONSENT")
    val WIFI_ONLY_UPLOAD = booleanPreferencesKey("LOG_WIFI_ONLY_UPLOAD")
    val ASKED_ONCE = booleanPreferencesKey("LOG_CONSENT_ASKED_ONCE")
}
