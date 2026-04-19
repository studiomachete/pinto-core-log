package com.music961.pintocore.log.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * [LogEntryEntity] 접근 DAO.
 *
 * @since pinto-core-log 0.1.0
 */
@Dao
abstract class LogEntryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(entity: LogEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(list: List<LogEntryEntity>)

    @Query("SELECT COUNT(*) FROM log_entries")
    abstract suspend fun countAll(): Long

    /** FIFO — 오래된 것부터 [limit] 개 */
    @Query("SELECT * FROM log_entries ORDER BY createdAt ASC LIMIT :limit")
    abstract suspend fun oldestBatch(limit: Int = 100): List<LogEntryEntity>

    @Query("DELETE FROM log_entries WHERE id IN (:ids)")
    abstract suspend fun deleteByIds(ids: List<String>)

    @Query("UPDATE log_entries SET retryCount = retryCount + 1 WHERE id IN (:ids)")
    abstract suspend fun incrementRetry(ids: List<String>)

    @Query("DELETE FROM log_entries WHERE retryCount >= :maxRetry")
    abstract suspend fun dropOverRetry(maxRetry: Int): Int

    /**
     * 오래된 순 [deleteCount] 개 삭제.
     *
     * Bug Hunt R01 B10 fix — 기존 `deleteOverLimit` 의 SQL `MAX(0, COUNT - :maxCount)` 서브쿼리는
     * SQLite 의 MAX 집계 함수와 스칼라 함수 우선순위가 헷갈리는 안티패턴이었음. 카운트/초과분 계산을
     * Kotlin 측 [insertAndEnforceLimit] 에서 처리하고, 이 쿼리는 단순 LIMIT 삭제만 담당.
     */
    @Query(
        """DELETE FROM log_entries WHERE id IN (
            SELECT id FROM log_entries ORDER BY createdAt ASC LIMIT :deleteCount
        )"""
    )
    abstract suspend fun deleteOldest(deleteCount: Int): Int

    /** 동의 철회 훅 — 아직 업로드 안 된 레코드 전체 조회 */
    @Query("SELECT * FROM log_entries")
    abstract suspend fun getPendingEntries(): List<LogEntryEntity>

    @Query("UPDATE log_entries SET recordJson = :json WHERE id = :id")
    abstract suspend fun updateRecordJson(id: String, json: String)

    /**
     * 버퍼 insert 와 상한 초과분 삭제를 **원자적**으로 수행 (bug #6 fix).
     *
     * 기존 [insert] + 삭제 쿼리 순차 호출은 두 호출 사이 race 로
     * 순간적으로 maxCount+1 상태가 노출되고, 다른 스레드의 삭제가 방금 넣은
     * 항목을 가져갈 여지가 있었음. @Transaction 로 단일 트랜잭션 보장.
     *
     * Bug Hunt R01 B10 fix — 카운트/초과분 계산을 Kotlin 측에서 수행 (deleteOldest 는 단순 LIMIT 삭제).
     */
    @Transaction
    open suspend fun insertAndEnforceLimit(entity: LogEntryEntity, maxCount: Int) {
        // Bug Hunt R02 M2 fix — 음수 maxCount 방어. 음수면 excess 가 양수가 되어
        // deleteOldest(양수) 호출 → SQL LIMIT 음수("제한 없음")로 의도와 다른 삭제 위험.
        // 라이브러리 공개 API 진입에서 require 로 차단.
        require(maxCount >= 0) { "maxCount must be non-negative, was $maxCount" }
        insert(entity)
        val count = countAll()
        val excess = (count - maxCount).coerceAtLeast(0L).toInt()
        if (excess > 0) deleteOldest(excess)
    }
}
