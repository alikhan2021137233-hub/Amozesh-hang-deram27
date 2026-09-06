package com.example.data.local

import androidx.room.Entity

@Entity(tableName = "assessment_sessions")
data class AssessmentSessionEntity(
    @androidx.room.PrimaryKey
    val sessionId: String,
    val patternId: String,
    val exerciseId: String? = null,
    val sourceId: String? = null,
    val sourceHash: String? = null,
    val lifecycle: String,
    val startedAtEpochMs: Long,
    val lastUpdatedAtEpochMs: Long,
    val elapsedDurationMs: Long,
    val activeDurationMs: Long,
    val accumulatedPausedDurationMs: Long,
    val pauseStartedAtEpochMs: Long? = null,
    val restartCount: Int,
    val bpm: Int,
    val inputMode: String,
    val assessmentSchemaVersion: Int,
    val eventSchemaVersion: Int,
    val evaluationAlgorithmVersion: Int,
    val timelinePayload: String,
    val timelineRevision: Long,
    val timelineChecksum: String,
    val eventCount: Int,
    val lastEventOrdinal: Long,
    val finalizationState: String,
    val finalizedAtEpochMs: Long? = null,
    val audioContinuityState: String
)
