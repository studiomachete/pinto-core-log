package com.music961.pintocore.log.api

/**
 * 라이브러리 내부 에러 신고 contract. Firebase Crashlytics 등 앱이 쓰는 추적기로 위임.
 *
 * 라이브러리는 Firebase 의존을 갖지 않기 위해 이 인터페이스만 사용한다.
 *
 * @since pinto-core-log 0.1.0
 */
interface LogErrorReporter {
    fun setKey(key: String, value: Boolean)
    fun report(throwable: Throwable, message: String? = null)
    fun log(message: String)
}
