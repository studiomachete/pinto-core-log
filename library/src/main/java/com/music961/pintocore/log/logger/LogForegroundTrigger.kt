package com.music961.pintocore.log.logger

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.music961.pintocore.log.device.SessionIdHolder
import com.music961.pintocore.log.model.PayloadKey
import com.music961.pintocore.log.transport.LogUploadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ProcessLifecycle 옵저버.
 *
 * - `ON_START` (포그라운드 진입) : SESSION_START 이벤트 + 1회성 업로드 Worker enqueue
 * - `ON_STOP` (백그라운드 진입) : SESSION_END 이벤트 + 1회성 업로드 Worker enqueue (best-effort)
 *
 * 또한 주기 15분 PeriodicWork 를 최초 1회 등록한다 (KEEP 정책).
 *
 * SESSION_END 누락 가능성: OOM/시스템 kill 시 ON_STOP 이 안 불릴 수 있음. SESSION_START 기반
 * 집계가 가능하도록 스키마 설계됨 (context.md 참조).
 *
 * @since pinto-core-log 0.1.0
 */
@Singleton
class LogForegroundTrigger @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val logger: Logger,
    private val sessionIdHolder: SessionIdHolder,
) {

    /** 테스트 주입 가능한 now 공급자. Default 는 [System.currentTimeMillis]. */
    internal var nowProvider: () -> Long = { System.currentTimeMillis() }

    fun attach() {
        // 주기 업로드 PeriodicWork 최초 등록 (KEEP — 이미 있으면 유지)
        val periodic = PeriodicWorkRequestBuilder<LogUploadWorker>(
            PERIODIC_INTERVAL_MIN, TimeUnit.MINUTES
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                periodic,
            )

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        handleOnStart()
                        enqueueOneTimeUpload()
                    }
                    Lifecycle.Event.ON_STOP -> {
                        handleOnStop()
                        enqueueOneTimeUpload()
                    }
                    else -> Unit
                }
            }
        )
    }

    internal fun handleOnStart() {
        runCatching { logger.event(LogSystemEvent.SESSION_START) }
            .onFailure { Timber.w(it, "SESSION_START 기록 실패") }
    }

    /**
     * SESSION_END payload 에 세션 지속 시간(ms) 을 계산해서 실어 보낸다 (bug #7 fix).
     *
     * - 기존: 하드코딩 `SESSION_DURATION_MS=0L` → 집계 시 무의미.
     * - fix: [SessionIdHolder.sessionStartedAt] 기준으로 `now - start` 계산.
     *   clock skew 등으로 음수가 나올 여지를 제거하기 위해 `coerceAtLeast(0)`.
     */
    internal fun handleOnStop() {
        runCatching {
            val duration = (nowProvider() - sessionIdHolder.sessionStartedAt).coerceAtLeast(0L)
            logger.event(
                LogSystemEvent.SESSION_END,
                mapOf(PayloadKey.SESSION_DURATION_MS to duration),
            )
        }.onFailure { Timber.w(it, "SESSION_END 기록 실패") }
    }

    private fun enqueueOneTimeUpload() {
        val req = OneTimeWorkRequestBuilder<LogUploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_ONETIME_NAME,
            ExistingWorkPolicy.REPLACE,
            req,
        )
    }

    companion object {
        /** 주기 Worker 간격 (분). WorkManager 최소 15분 제약에 맞춤. */
        const val PERIODIC_INTERVAL_MIN = 15L
        const val UNIQUE_PERIODIC_NAME = "pinto_log_upload_periodic"
        const val UNIQUE_ONETIME_NAME = "pinto_log_upload_once"
    }
}
