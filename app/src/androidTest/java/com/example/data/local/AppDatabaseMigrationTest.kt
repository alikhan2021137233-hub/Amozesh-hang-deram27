package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate8To9PreservesExistingDataAndValidatesRoomSchema() = runBlocking {
        val databaseName = "migration-8-9-test.db"
        try {
            helper.createDatabase(databaseName, 8).apply {
                execSQL(
                    """
                    INSERT INTO assessments (
                        sessionId, patternId, bpm, completedAtEpochMs, durationMs,
                        activeDurationMs, validity, qualityScore, signalQuality,
                        validEventCount, eventCount, restartCount, correctCount,
                        wrongCount, missedCount, extraCount, unknownCount,
                        timingScore, pitchScore, noteAccuracy, overallPerformance,
                        consistencyScore
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf(
                        "assessment-8", "pattern-8", 90, 8_000L, 4_000L, 3_500L,
                        "VALID", 0.8, 0.9, 4, 5, 1, 3, 1, 1, 0, 0,
                        0.82, 0.78, 0.75, 0.8, 0.77
                    )
                )
                execSQL(
                    """
                    INSERT INTO imported_scores (
                        sourceId, sourceHash, title, composer, provenanceJson,
                        format, importedAtEpochMs, recognitionStatus, confidence,
                        pageCount, validationStatus, timelineJson, exerciseId,
                        adaptationStatus, adaptationConfidence, omittedRatio,
                        transformedRatio, adaptationApproval
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf(
                        "source-8", "hash-8", "Score 8", null, "{}", "MIDI",
                        7_000L, "VALID", 0.95, 1, "VALID", "{}", "exercise-8",
                        "PENDING", 0.7, 0.1, 0.2, "PENDING"
                    )
                )
                close()
            }

            helper.runMigrationsAndValidate(
                databaseName,
                9,
                true,
                AppDatabase.MIGRATION_8_9
            ).use { migrated ->
                assertEquals(9, migrated.version)
                assertEquals(1, countRows(migrated, "assessments"))
                assertEquals(1, countRows(migrated, "imported_scores"))
                assertEquals(0, countRows(migrated, "assessment_sessions"))
                assertEquals(
                    "assessment-8",
                    migrated.query("SELECT sessionId FROM assessments").use { cursor ->
                        cursor.moveToFirst()
                        cursor.getString(0)
                    }
                )
                assertEquals(
                    "PENDING",
                    migrated.query("SELECT adaptationApproval FROM imported_scores").use { cursor ->
                        cursor.moveToFirst()
                        cursor.getString(0)
                    }
                )
            }

            val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
                .addMigrations(AppDatabase.MIGRATION_8_9)
                .build()
            try {
                val sessionDao = database.assessmentSessionDao()
                database.openHelper.writableDatabase.execSQL(
                    """
                    INSERT INTO assessment_sessions (
                        sessionId, patternId, exerciseId, sourceId, sourceHash,
                        lifecycle, startedAtEpochMs, lastUpdatedAtEpochMs,
                        elapsedDurationMs, activeDurationMs, accumulatedPausedDurationMs,
                        pauseStartedAtEpochMs, restartCount, bpm, inputMode,
                        assessmentSchemaVersion, eventSchemaVersion,
                        evaluationAlgorithmVersion, timelinePayload, timelineRevision,
                        timelineChecksum, eventCount, lastEventOrdinal, finalizationState,
                        finalizedAtEpochMs, audioContinuityState
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf(
                        "session-9", "pattern-9", null, null, null, "ACTIVE",
                        9_000L, 9_000L, 0L, 0L, 0L, null, 0, 90, "REAL_HANDPAN",
                        1, 1, 1, "{\"events\":[]}", 0L, "checksum", 0, -1L,
                        "NOT_FINALIZED", null, "UNKNOWN"
                    )
                )
                assertNotNull(sessionDao.getBySessionId("session-9"))
                assertEquals("pattern-9", sessionDao.getBySessionId("session-9")?.patternId)
                assertEquals("ACTIVE", sessionDao.getBySessionId("session-9")?.lifecycle)
                assertEquals("REAL_HANDPAN", sessionDao.getBySessionId("session-9")?.inputMode)
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun countRows(database: SupportSQLiteDatabase, table: String): Int =
        database.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
