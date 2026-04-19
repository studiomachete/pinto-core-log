package com.music961.pintocore.log.model

import kotlinx.serialization.Serializable

/**
 * 로그 레코드에 포함되는 디바이스 기본 정보.
 *
 * 세션 1회 수집 후 캐시하여 모든 레코드에 동일값 주입 (메모리 절약).
 * PII 없음 - 모델/제조사/OS/메모리/CPU/화면dp/로케일만.
 *
 * iOS 포팅 시 동일 필드명 유지 (schema-spec.md 참조).
 *
 * @param model Build.MODEL (예: "SM-G998N")
 * @param manufacturer Build.MANUFACTURER (예: "samsung")
 * @param os 플랫폼 ("android" | "ios")
 * @param osVersion Build.VERSION.RELEASE (예: "14")
 * @param ramMb 총 RAM MB 단위
 * @param cpuCores Runtime.availableProcessors()
 * @param screenDp 화면 사이즈 "widthDp x heightDp" (예: "411 x 914")
 * @param locale 시스템 로케일 (예: "ko-KR")
 *
 * @since 2026-04-19 로그 시스템 재설계
 */
@Serializable
data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val os: String,
    val osVersion: String,
    val ramMb: Long,
    val cpuCores: Int,
    val screenDp: String,
    val locale: String,
)
