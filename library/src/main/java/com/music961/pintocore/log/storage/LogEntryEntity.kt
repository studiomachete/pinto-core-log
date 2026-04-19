package com.music961.pintocore.log.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 로그 레코드의 Room 버퍼 엔티티.
 *
 * 디스크에 쌓아두고 LogUploader 가 배치로 DynamoDB `PintoLog` 테이블에 업로드 후 삭제한다.
 *
 * - [id] 은 내부 식별자(UUID v7). DynamoDB sk는 업로드 직전 새 UUID를 붙인다.
 * - [recordJson] 은 [com.music961.pintocore.log.model.LogRecord] 의 JSON 직렬화
 *   문자열. 동의 철회 훅에서 userId만 null 치환할 때 이 문자열을 재가공한다.
 * - [retryCount] 업로드 실패 누적 횟수. 3회 초과 시 drop.
 *
 * @since pinto-core-log 0.1.0
 */
@Entity(tableName = "log_entries")
data class LogEntryEntity(
    @PrimaryKey val id: String,
    val recordJson: String,
    val createdAt: Long,
    val retryCount: Int = 0,
)
