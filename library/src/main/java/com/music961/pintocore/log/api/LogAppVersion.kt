package com.music961.pintocore.log.api

import javax.inject.Qualifier

/**
 * `LogRecord.appVersion` 값 주입을 위한 Hilt Qualifier.
 * 앱이 BuildConfig.VERSION_NAME 등을 주입.
 *
 * @since pinto-core-log 0.1.0
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LogAppVersion
