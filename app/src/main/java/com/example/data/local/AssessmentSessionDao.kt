package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AssessmentSessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: AssessmentSessionEntity)

    @Query("SELECT * FROM assessment_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getBySessionId(sessionId: String): AssessmentSessionEntity?

    @Query("SELECT * FROM assessment_sessions WHERE lifecycle IN ('ACTIVE', 'PAUSED', 'FINALIZING') ORDER BY lastUpdatedAtEpochMs ASC")
    suspend fun getRecoverable(): List<AssessmentSessionEntity>

    @Query("UPDATE assessment_sessions SET timelinePayload = :timelinePayload, timelineRevision = :timelineRevision, timelineChecksum = :timelineChecksum, eventCount = :eventCount, lastEventOrdinal = :lastEventOrdinal, elapsedDurationMs = :elapsedDurationMs, activeDurationMs = :activeDurationMs, lastUpdatedAtEpochMs = :lastUpdatedAtEpochMs WHERE sessionId = :sessionId AND lifecycle IN ('ACTIVE', 'PAUSED', 'FINALIZING')")
    suspend fun updateTimeline(
        sessionId: String,
        timelinePayload: String,
        timelineRevision: Long,
        timelineChecksum: String,
        eventCount: Int,
        lastEventOrdinal: Long,
        elapsedDurationMs: Long,
        activeDurationMs: Long,
        lastUpdatedAtEpochMs: Long
    ): Int

    @Query("UPDATE assessment_sessions SET lifecycle = :lifecycle, pauseStartedAtEpochMs = :pauseStartedAtEpochMs, accumulatedPausedDurationMs = :accumulatedPausedDurationMs, elapsedDurationMs = :elapsedDurationMs, activeDurationMs = :activeDurationMs, lastUpdatedAtEpochMs = :lastUpdatedAtEpochMs, audioContinuityState = :audioContinuityState WHERE sessionId = :sessionId AND lifecycle = :expectedLifecycle")
    suspend fun updateLifecycle(
        sessionId: String,
        expectedLifecycle: String,
        lifecycle: String,
        pauseStartedAtEpochMs: Long?,
        accumulatedPausedDurationMs: Long,
        elapsedDurationMs: Long,
        activeDurationMs: Long,
        lastUpdatedAtEpochMs: Long,
        audioContinuityState: String
    ): Int

    @Query("UPDATE assessment_sessions SET lifecycle = 'FINALIZED', finalizationState = 'COMMITTED', finalizedAtEpochMs = :finalizedAtEpochMs, lastUpdatedAtEpochMs = :finalizedAtEpochMs, audioContinuityState = :audioContinuityState WHERE sessionId = :sessionId")
    suspend fun markFinalized(sessionId: String, finalizedAtEpochMs: Long, audioContinuityState: String): Int

    @Query("UPDATE assessment_sessions SET lifecycle = 'INVALIDATED', finalizationState = 'INVALIDATED', lastUpdatedAtEpochMs = :updatedAtEpochMs, audioContinuityState = 'UNKNOWN' WHERE sessionId = :sessionId AND lifecycle IN ('ACTIVE', 'PAUSED', 'FINALIZING')")
    suspend fun markInvalidated(sessionId: String, updatedAtEpochMs: Long): Int
}
