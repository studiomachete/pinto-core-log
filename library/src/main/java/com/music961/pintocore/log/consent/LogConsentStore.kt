package com.music961.pintocore.log.consent

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 라이브러리 자체 DataStore. 메인 앱의 `pintoDataStore` 와는 별도 파일 (`pinto_log_consent`).
 *
 * 분리 이유:
 * - 라이브러리 단독 동작 보장 (메인 앱 BaseApplication 의존 제거)
 * - 메인 앱이 DataStore 전체 clear 를 수행하는 경우(로그아웃 등)에도 로그 동의 상태 보존
 */
private val Context.pintoLogDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "pinto_log_consent")

/**
 * 로그 동의 상태 저장소.
 *
 * DataStore Preferences 기반으로 3개 플래그를 관리한다. phase-03 Logger 가 이 인스턴스를
 * @Inject 받아 `consent` Flow 를 구독하고 수집/전송 여부를 결정한다.
 *
 * @since pinto-core-log 0.1.0
 */
interface LogConsentStore {
    val consent: Flow<LogConsent>
    suspend fun setIdentifiedConsent(v: Boolean)
    suspend fun setWifiOnly(v: Boolean)
    suspend fun markAsked()

    /**
     * 동의값 저장과 `askedOnce=true` 를 **단일 DataStore edit** 으로 원자화 (bug #8 fix).
     *
     * 기존 `setIdentifiedConsent(v)` → `markAsked()` 순차 호출은 두 edit 사이에 앱 kill 시
     * `askedOnce=false` 상태로 남아 다이얼이 재표시 되는 윈도우가 존재했음.
     */
    suspend fun setConsentAndMarkAsked(allow: Boolean)
}

@Singleton
class LogConsentStoreImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : LogConsentStore {

    private val keyIdentifiedConsent = LogConsentKeys.IDENTIFIED_CONSENT
    private val keyWifiOnlyUpload = LogConsentKeys.WIFI_ONLY_UPLOAD
    private val keyAskedOnce = LogConsentKeys.ASKED_ONCE

    override val consent: Flow<LogConsent> = context.pintoLogDataStore.data.map { prefs ->
        LogConsent(
            identifiedConsent = prefs[keyIdentifiedConsent] ?: LogConsent.기본값.identifiedConsent,
            wifiOnlyUpload = prefs[keyWifiOnlyUpload] ?: LogConsent.기본값.wifiOnlyUpload,
            askedOnce = prefs[keyAskedOnce] ?: LogConsent.기본값.askedOnce,
        )
    }

    override suspend fun setIdentifiedConsent(v: Boolean) {
        context.pintoLogDataStore.edit { it[keyIdentifiedConsent] = v }
    }

    override suspend fun setWifiOnly(v: Boolean) {
        context.pintoLogDataStore.edit { it[keyWifiOnlyUpload] = v }
    }

    override suspend fun markAsked() {
        context.pintoLogDataStore.edit { it[keyAskedOnce] = true }
    }

    override suspend fun setConsentAndMarkAsked(allow: Boolean) {
        context.pintoLogDataStore.edit { prefs ->
            prefs[keyIdentifiedConsent] = allow
            prefs[keyAskedOnce] = true
        }
    }
}
