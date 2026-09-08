package com.example.audio

import com.example.model.AssessmentEventType
import com.example.model.AssessmentTimelineEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class AssessmentRecoveryCursorTest {
    @Test
    fun restoresEarliestPendingObligationByBeat() {
        val events = listOf(
            event("later", 4.0, "later"),
            event("earlier", 2.0, "earlier")
        )

        assertEquals(
            2.0 - PatternScheduler.BEAT_EPSILON,
            AssessmentRecoveryCursor.startBeat(events),
            0.0
        )
    }

    @Test
    fun keepsAnUnconsumedChordObligationPending() {
        val events = listOf(
            event("chord-a", 3.0, "a", isConsumed = true),
            event("chord-b", 3.0, "b")
        )

        assertEquals(
            3.0 - PatternScheduler.BEAT_EPSILON,
            AssessmentRecoveryCursor.startBeat(events),
            0.0
        )
    }

    @Test
    fun treatsMissedObligationAsResolved() {
        val events = listOf(
            event("miss", 2.0, "miss", AssessmentEventType.MISSED),
            event("extra", 5.0, null, AssessmentEventType.EXTRA)
        )

        assertEquals(5.0, AssessmentRecoveryCursor.startBeat(events), 0.0)
    }

    private fun event(
        eventId: String,
        beat: Double,
        obligationId: String?,
        eventType: AssessmentEventType = AssessmentEventType.EXPECTED,
        isConsumed: Boolean = false
    ) = AssessmentTimelineEvent(
        eventId = eventId,
        sessionId = "session",
        loopId = "loop",
        sequenceIndex = 0,
        expectedNote = 1,
        detectedNote = null,
        eventType = eventType,
        expectedTimestampNanos = 10_000L,
        detectedTimestampNanos = null,
        deviationNanos = null,
        timingResult = null,
        confidence = 0f,
        targetId = obligationId,
        source = "test",
        durationNanos = null,
        isConsumed = isConsumed,
        obligationId = obligationId,
        beatPosition = beat
    )
}