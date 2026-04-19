package com.music961.pintocore.log.transport

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * 15분 주기 + 포그라운드 진입 시 1회성으로 실행되는 업로드 Worker.
 *
 * 제약은 [com.music961.pintocore.log.logger.LogForegroundTrigger] 에서 설정.
 * (기본 `NetworkType.CONNECTED`. wifiOnly 토글은 Uploader 내부에서 한 번 더 판별.)
 *
 * **retry 정책 (bug #5 fix)**:
 * - Uploader 가 업로드 건수(Int) 만 반환 (0 은 "할 일 없음 / 동의 전 / rate limit 차단" 의미).
 *   즉 0 반환은 정상 상황이므로 [Result.success] 으로 처리. 무한 backoff 회피.
 * - 네트워크/SDK 오류 등 실제 예외만 [Result.retry] 로 처리.
 *
 * @since pinto-core-log 0.1.0
 */
@HiltWorker
class LogUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val uploader: LogUploader,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val outcome = uploader.upload()
        return outcome.fold(
            onSuccess = {
                // 0 = skip(동의 전/rate limit/네트워크 없음/빈 큐), >0 = 업로드 성공
                // 둘 다 정상 경로이므로 success. (fix #5)
                Result.success()
            },
            onFailure = { t ->
                Timber.w(t, "LogUploadWorker 실패, retry")
                Result.retry()
            },
        )
    }
}
