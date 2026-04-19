package com.music961.pintocore.log.api

import javax.inject.Qualifier

/**
 * `LogRecord.appId` 값 주입을 위한 Hilt Qualifier.
 * 앱(핀토 게이머 / 콘솔 / 스튜디오 등)별로 이 qualifier 로 상수 주입.
 *
 * @since pinto-core-log 0.1.0
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LogAppId
