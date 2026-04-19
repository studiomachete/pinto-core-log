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
     * 버퍼 상한. [maxCount] 초과 시 오래된 것부터 삭제.
     * Room은 LIMIT/OFFSET이 있는 서브쿼리 DELETE를 지원하므로 subquery로 처리.
     */
    @Query(
        """DELETE FROM log_entries WHERE id IN (
            SELECT id FROM log_entries ORDER BY createdAt ASC
            LIMIT MAX(0, (SELECT COUNT(*) FROM log_entries) - :maxCount)
        )"""
    )
    abstract suspend fun deleteOverLimit(maxCount: Int): Int

    /** 동의 철회 훅 — 아직 업로드 안 된 레코드 전체 조회 */
    @Query("SELECT * FROM log_entries")
    abstract suspend fun getPendingEntries(): List<LogEntryEntity>

    @Query("UPDATE log_entries SET recordJson = :json WHERE id = :id")
    abstract suspend fun updateRecordJson(id: String, json: String)

    /**
     * 버퍼 insert 와 상한 초과분 삭제를 **원자적**으로 수행 (bug #6 fix).
     *
     * 기존 [insert] + [deleteOverLimit] 순차 호출은 두 호출 사이 race 로
     * 순간적으로 maxCount+1 상태가 노출되고, 다른 스레드의 삭제가 방금 넣은
     * 항목을 가져갈 여지가 있었음. @Transaction 로 단일 트랜잭션 보장.
     */
    @Transaction
    open suspend fun insertAndEnforceLimit(entity: LogEntryEntity, maxCount: Int) {
        insert(entity)
        deleteOverLimit(maxCount)
    }
}
