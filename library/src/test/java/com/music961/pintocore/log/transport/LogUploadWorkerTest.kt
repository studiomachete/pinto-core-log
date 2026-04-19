package com.music961.pintocore.log.transport

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LogUploadWorker 의 `doWork()` 분기 검증 (bug #5 fix).
 *
 * - uploader.upload() 가 성공 반환 → `Result.success()` (값이 0이든 >0 이든)
 * - uploader.upload() 가 실패(Exception wrapped in Result.failure) → `Result.retry()`
 *
 * 기존 버그: 0 반환도 `Result.retry()` 로 처리해 무한 backoff.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogUploadWorkerTest {

    private fun newWorker(uploader: LogUploader): LogUploadWorker {
        val context = mockk<Context>(relaxed = true)
        val params = mockk<WorkerParameters>(relaxed = true)
        return LogUploadWorker(context, params, uploader)
    }

    @Test
    fun `uploader 0 반환 시 Result_success`() = runTest {
        val uploader = mockk<LogUploader>()
        coEvery { uploader.upload() } returns Result.success(0)

        val worker = newWorker(uploader)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `uploader 양수 반환 시 Result_success`() = runTest {
        val uploader = mockk<LogUploader>()
        coEvery { uploader.upload() } returns Result.success(42)

        val worker = newWorker(uploader)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `uploader 실패 Result 시 Worker Result_retry`() = runTest {
        val uploader = mockk<LogUploader>()
        coEvery { uploader.upload() } returns Result.failure(RuntimeException("io error"))

        val worker = newWorker(uploader)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }
}
