package com.music961.pintocore.log.logger

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Logger 인터페이스 → 구현체 바인딩 모듈.
 *
 * LogUploader, LogUploadWorker, NetworkMonitor, LogForegroundTrigger 는
 * `@Inject constructor` + `@Singleton` (또는 `@HiltWorker`) 로 자동 바인딩되므로
 * 별도 모듈 작성 불필요.
 *
 * @since pinto-core-log 0.1.0
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LogModule {
    @Binds
    @Singleton
    abstract fun bindLogger(impl: LoggerImpl): Logger
}
