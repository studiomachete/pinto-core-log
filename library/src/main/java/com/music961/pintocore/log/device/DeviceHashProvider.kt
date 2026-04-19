package com.music961.pintocore.log.device

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

/**
 * 라이브러리 자체 DataStore. 메인 앱의 `pintoDataStore` 와는 별도 파일 (`pinto_log_device`).
 */
private val Context.pintoLogDeviceDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "pinto_log_device")

/**
 * 설치 시 1회 생성 후 영구 저장되는 익명 디바이스 식별자.
 *
 * - Advertising ID 쓰지 않음 (익명 유지)
 * - 동의 거부 상태에서도 유지 — deviceHash는 PII가 아닌 설치 식별자
 * - 재설치 시 새 값 (의도된 동작)
 *
 * 저장 위치: 라이브러리 자체 DataStore (`pinto_log_device`).
 *
 * @since pinto-core-log 0.1.0
 */
@Singleton
class DeviceHashProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val key = stringPreferencesKey("LOG_DEVICE_HASH")
    private val mutex = Mutex()

    @Volatile
    private var cached: String? = null

    /**
     * 디바이스 해시 조회. 없으면 UUID v7 생성 후 저장.
     *
     * 첫 호출은 DataStore I/O 때문에 suspend. Logger 내부에서 IO dispatcher 를 보장한다.
     */
    suspend fun get(): String {
        cached?.let { return it }
        return mutex.withLock {
            cached?.let { return@withLock it }
            val existing = context.pintoLogDeviceDataStore.data.first()[key]
            val hash = existing ?: run {
                val newHash = Uuid.random().toHexString()
                context.pintoLogDeviceDataStore.edit { it[key] = newHash }
                newHash
            }
            cached = hash
            hash
        }
    }

    /** 프로세스 시작 직후 warm-up 용 블로킹 조회 (ProcessLifecycle 옵저버 등). */
    fun getBlocking(): String = cached ?: runBlocking { get() }
}
