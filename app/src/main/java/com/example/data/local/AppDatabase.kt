package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PatternEntity::class,
        PracticeProgressEntity::class,
        LessonProgressEntity::class,
        RecordingTrackEntity::class,
        AssessmentEntity::class,
        EvidenceEntity::class,
        ProcessedAssessmentEntity::class,
        AssessmentSessionEntity::class,
        MasteredSkillEntity::class,
        ImportedScoreEntity::class
    ],
    version = 9,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun patternDao(): PatternDao
    abstract fun practiceProgressDao(): PracticeProgressDao
    abstract fun lessonProgressDao(): LessonProgressDao
    abstract fun recordingTrackDao(): RecordingTrackDao
    abstract fun assessmentDao(): AssessmentDao
    abstract fun evidenceDao(): EvidenceDao
    abstract fun processedAssessmentDao(): ProcessedAssessmentDao
    abstract fun assessmentSessionDao(): AssessmentSessionDao
    abstract fun masteredSkillDao(): MasteredSkillDao
    abstract fun importedScoreDao(): ImportedScoreDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `lesson_progress` (
                        `lessonId` TEXT NOT NULL PRIMARY KEY,
                        `isCompleted` INTEGER NOT NULL,
                        `stars` INTEGER NOT NULL,
                        `bestScore` INTEGER NOT NULL,
                        `attempts` INTEGER NOT NULL,
                        `lastPracticedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recording_tracks` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `title` TEXT NOT NULL,
                        `date` TEXT NOT NULL,
                        `scaleId` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `eventsJson` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `recording_tracks` ADD COLUMN `bpm` INTEGER NOT NULL DEFAULT 70")
                db.execSQL("ALTER TABLE `recording_tracks` ADD COLUMN `timeSignature` TEXT NOT NULL DEFAULT '4/4'")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `recording_tracks` ADD COLUMN `timelineEventsJson` TEXT NOT NULL DEFAULT '[]'"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `assessments` (
                        `sessionId` TEXT NOT NULL PRIMARY KEY,
                        `patternId` TEXT NOT NULL,
                        `bpm` INTEGER NOT NULL,
                        `completedAtEpochMs` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `activeDurationMs` INTEGER NOT NULL,
                        `validity` TEXT NOT NULL,
                        `qualityScore` REAL NOT NULL,
                        `signalQuality` REAL NOT NULL,
                        `validEventCount` INTEGER NOT NULL,
                        `eventCount` INTEGER NOT NULL,
                        `restartCount` INTEGER NOT NULL,
                        `correctCount` INTEGER NOT NULL,
                        `wrongCount` INTEGER NOT NULL,
                        `missedCount` INTEGER NOT NULL,
                        `extraCount` INTEGER NOT NULL,
                        `unknownCount` INTEGER NOT NULL,
                        `timingScore` REAL NOT NULL,
                        `pitchScore` REAL NOT NULL,
                        `noteAccuracy` REAL NOT NULL,
                        `overallPerformance` REAL NOT NULL,
                        `consistencyScore` REAL NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `assessment_evidence` (
                        `sessionId` TEXT NOT NULL PRIMARY KEY,
                        `validity` TEXT NOT NULL,
                        `validEvidenceCount` INTEGER NOT NULL,
                        `overallPerformance` REAL NOT NULL,
                        `timingScore` REAL NOT NULL,
                        `pitchScore` REAL NOT NULL,
                        `noteAccuracy` REAL NOT NULL,
                        `completionRate` REAL NOT NULL,
                        `missRate` REAL NOT NULL,
                        `falseStrikeRate` REAL NOT NULL,
                        `consistencyScore` REAL NOT NULL,
                        `confidenceScore` REAL NOT NULL,
                        `rhythmScore` REAL NOT NULL,
                        `dynamicsScore` REAL NOT NULL,
                        `speedScore` REAL NOT NULL,
                        `techniqueScore` REAL NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `processed_assessments` (
                        `sessionId` TEXT NOT NULL PRIMARY KEY
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `mastered_skills` (
                        `skill` TEXT NOT NULL PRIMARY KEY,
                        `masteryScore` REAL NOT NULL,
                        `confidence` REAL NOT NULL,
                        `recentPerformance` REAL NOT NULL,
                        `longTermPerformance` REAL NOT NULL,
                        `attemptCount` INTEGER NOT NULL,
                        `lastPracticedEpochMs` INTEGER,
                        `trend` REAL NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `imported_scores` (
                        `sourceId` TEXT NOT NULL PRIMARY KEY,
                        `sourceHash` TEXT NOT NULL,
                        `title` TEXT,
                        `composer` TEXT,
                        `provenanceJson` TEXT NOT NULL,
                        `format` TEXT NOT NULL,
                        `importedAtEpochMs` INTEGER NOT NULL,
                        `recognitionStatus` TEXT NOT NULL,
                        `confidence` REAL NOT NULL,
                        `pageCount` INTEGER,
                        `validationStatus` TEXT NOT NULL,
                        `timelineJson` TEXT NOT NULL,
                        `exerciseId` TEXT,
                        `adaptationStatus` TEXT,
                        `adaptationConfidence` REAL,
                        `omittedRatio` REAL,
                        `transformedRatio` REAL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `imported_scores` ADD COLUMN `adaptationApproval` TEXT NOT NULL DEFAULT 'NOT_REQUIRED'")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `assessment_sessions` (
                        `sessionId` TEXT NOT NULL PRIMARY KEY,
                        `patternId` TEXT NOT NULL,
                        `exerciseId` TEXT,
                        `sourceId` TEXT,
                        `sourceHash` TEXT,
                        `lifecycle` TEXT NOT NULL,
                        `startedAtEpochMs` INTEGER NOT NULL,
                        `lastUpdatedAtEpochMs` INTEGER NOT NULL,
                        `elapsedDurationMs` INTEGER NOT NULL,
                        `activeDurationMs` INTEGER NOT NULL,
                        `accumulatedPausedDurationMs` INTEGER NOT NULL,
                        `pauseStartedAtEpochMs` INTEGER,
                        `restartCount` INTEGER NOT NULL,
                        `bpm` INTEGER NOT NULL,
                        `inputMode` TEXT NOT NULL,
                        `assessmentSchemaVersion` INTEGER NOT NULL,
                        `eventSchemaVersion` INTEGER NOT NULL,
                        `evaluationAlgorithmVersion` INTEGER NOT NULL,
                        `timelinePayload` TEXT NOT NULL,
                        `timelineRevision` INTEGER NOT NULL,
                        `timelineChecksum` TEXT NOT NULL,
                        `eventCount` INTEGER NOT NULL,
                        `lastEventOrdinal` INTEGER NOT NULL,
                        `finalizationState` TEXT NOT NULL,
                        `finalizedAtEpochMs` INTEGER,
                        `audioContinuityState` TEXT NOT NULL
                    )
                """.trimIndent())
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "handpan_learning_db"
                )
                .addMigrations(MIGRATION_1_2)
                .addMigrations(MIGRATION_2_3)
                .addMigrations(MIGRATION_3_4)
                .addMigrations(MIGRATION_4_5)
                .addMigrations(MIGRATION_5_6)
                .addMigrations(MIGRATION_6_7)
                .addMigrations(MIGRATION_7_8)
                .addMigrations(MIGRATION_8_9)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
