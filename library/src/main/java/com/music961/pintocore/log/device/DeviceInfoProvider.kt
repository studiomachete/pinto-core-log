package com.music961.pintocore.log.device

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.music961.pintocore.log.model.DeviceInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 디바이스 사양 1회 수집 후 캐시.
 *
 * PII 미포함 — 모델/제조사/OS/메모리/CPU/화면dp/로케일만.
 * Advertising ID는 쓰지 않는다 (익명 유지 원칙).
 *
 * @since pinto-core-log 0.1.0
 */
@Singleton
class DeviceInfoProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val info: DeviceInfo by lazy { compute() }

    private fun compute(): DeviceInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also {
            am?.getMemoryInfo(it)
        }
        val ramMb = memInfo.totalMem / (1024L * 1024L)

        val cfg: Configuration = context.resources.configuration
        val screenDp = "${cfg.screenWidthDp} x ${cfg.screenHeightDp}"

        val locale = Locale.getDefault().toLanguageTag()

        return DeviceInfo(
            model = Build.MODEL ?: "unknown",
            manufacturer = Build.MANUFACTURER ?: "unknown",
            os = "android",
            osVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
            ramMb = ramMb,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            screenDp = screenDp,
            locale = locale,
        )
    }
}
