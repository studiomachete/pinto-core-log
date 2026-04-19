package com.music961.pintocore.log.storage

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 로그 전용 Room DB. 본체 앱 DB 와 분리하여 별도 DB 파일(`PintoLog.db`) 사용.
 *
 * 분리 이유:
 * - 로그는 부가 기능. 장애 격리 (로그 DB 손상이 본체 앱 기능에 영향 주지 않도록)
 * - 마이그레이션 독립
 * - 파일 크기 모니터링 분리
 *
 * 마이그레이션 정책: 신규 DB. [androidx.room.RoomDatabase.Builder.fallbackToDestructiveMigration]
 * 사용 금지 (향후 스키마 변경 시 명시적 Migration 작성).
 *
 * @since pinto-core-log 0.1.0
 */
@Database(
    entities = [LogEntryEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class PintoLogDatabase : RoomDatabase() {
    abstract fun logEntryDao(): LogEntryDao
}
