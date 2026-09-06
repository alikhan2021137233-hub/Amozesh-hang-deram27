package com.example.data.repository

class AssessmentRecoveryCoordinator(
    private val repository: HandpanRepository
) {
    suspend fun findRecoverable(nowTimestampNanos: Long = System.nanoTime()): List<RecoveredAssessment> =
        repository.loadRecoverableAssessments().mapNotNull { session ->
            runCatching {
                repository.recoverAssessment(session.sessionId, nowTimestampNanos)
            }.getOrElse {
                repository.invalidateAssessment(session.sessionId)
                null
            }
        }
}
