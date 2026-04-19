package com.music961.pintocore.log.consent

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * [LogConsentStore] 바인딩.
 *
 * 라이브러리 단독 빌드 / 메인 앱이 phase-05 에서 자체 LogConsentModule 삭제 후 정합 보장.
 *
 * @since pinto-core-log 0.1.0
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LogConsentModule {
    @Binds
    @Singleton
    abstract fun bindLogConsentStore(impl: LogConsentStoreImpl): LogConsentStore
}
