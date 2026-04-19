package com.music961.pintocore.log.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 현재 네트워크 타입 조회 유틸. [LogUploader.upload] 에서 wifiOnly 분기 판정에 사용.
 *
 * - WIFI : Wi-Fi 또는 이더넷 (unmetered 인식)
 * - CELLULAR : 모바일 데이터
 * - NONE : 연결 없음 / 불명
 *
 * @since pinto-core-log 0.1.0
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    enum class Type { WIFI, CELLULAR, NONE }

    fun current(): Type {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return Type.NONE
        val active = cm.activeNetwork ?: return Type.NONE
        val caps = cm.getNetworkCapabilities(active) ?: return Type.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Type.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Type.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Type.CELLULAR
            else -> Type.NONE
        }
    }
}
