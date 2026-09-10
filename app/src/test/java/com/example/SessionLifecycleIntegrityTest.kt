package com.example

import com.example.audio.AcousticPracticeEvaluator
import com.example.audio.AudioAnalysisSession
import com.example.audio.PatternScheduler
import com.example.audio.PracticeClock
import com.example.model.DetectedStrikeEvent
import com.example.model.AssessmentEventType
import com.example.model.AssessmentSessionValidity
import com.example.model.AssessmentSessionValidator
import com.example.model.AssessmentTimeline
import com.example.model.AssessmentTimelineEvent
import com.example.model.HandpanPattern
import com.example.model.NoteEvent
import com.example.model.NotePitchConfig
import com.example.model.PracticeSessionContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLifecycleIntegrityTest {
    @Test
    fun pauseResumeExcludesPausedTimeAndRepeatedTransitionsAreIdempotent() {
        val session = PracticeSessionContext.start("pattern", 0L)

        session.pause(2_000_000_000L)
        session.pause(3_000_000_000L)
        session.resume(5_000_000_000L)
        session.resume(6_000_000_000L)
        session.finalize(8_000_000_000L)

        assertEquals(5_000L, session.activeDurationMs)
        assertEquals(3_000L, session.accumulatedPausedDurationNanos / 1_000_000L)
        assertEquals(com.example.model.PracticeSessionLifecycle.FINALIZED, session.lifecycle)
    }

    @Test
    fun finalizeIsIdempotentAndDoesNotMoveEndTimestamp() {
        val session = PracticeSessionContext.start("pattern", 1_000L)

        session.finalize(2_000_000_000L)
        val firstEnd = session.endTimestampNanos
        session.finalize(9_000_000_000L)

        assertEquals(firstEnd, session.endTimestampNanos)
        assertTrue(session.finalized)
    }

    @Test
    fun invalidatedSessionCannotBeFinalizedAfterCaptureFailure() {
        val session = PracticeSessionContext.start(
            patternId = "capture-failure",
            startTimestampNanos = 1_000L,
            sessionId = "capture-failure-session"
        )

        session.invalidate()
        session.finalize(9_000L)

        assertEquals(
            com.example.model.PracticeSessionLifecycle.INVALIDATED,
            session.lifecycle
        )
        assertFalse(session.finalized)
    }

    @Test
    fun restartCreatesAnIsolatedSessionIdentity() {
        val first = PracticeSessionContext.start("pattern", 1_000_000_000L)
        first.finalize(3_000_000_000L)
        val restarted = PracticeSessionContext.start(
            patternId = first.patternId,
            startTimestampNanos = 4_000_000_000L,
            restartCount = 1
        )

        assertNotEquals(first.sessionId, restarted.sessionId)
        assertEquals(1, restarted.restartCount)
    }

    @Test
    fun timelineRejectsMismatchedAssessmentIdentity() {
        val timeline = AssessmentTimeline()

        val event = timelineEvent(
            eventId = "event",
            sessionId = "session-a",
            assessmentSessionId = "session-b"
        )

        var rejected = false
        try {
            timeline.append(event)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun incompleteSessionCannotProduceEvidence() {
        val session = PracticeSessionContext.start("pattern", 0L)
        val timeline = AssessmentTimeline(session.sessionId)
        timeline.append(timelineEvent("event-1", session.sessionId))
        timeline.append(timelineEvent("event-2", session.sessionId))

        val quality = AssessmentSessionValidator.derive(session, timeline)

        assertEquals(AssessmentSessionValidity.INVALID_NOT_FINALIZED, quality.validity)
        assertNull(com.example.model.SkillEvidenceCalculator.calculateValidEvidence(session, timeline))
    }

    @Test
    fun missedTargetDoesNotInvalidateOtherwiseContextualSession() {
        val session = PracticeSessionContext.start("pattern", 0L)
        session.finalize(2_000_000_000L)
        val timeline = AssessmentTimeline(session.sessionId)
        timeline.append(timelineEvent("event-1", session.sessionId))
        timeline.append(timelineEvent("event-2", session.sessionId))
        timeline.append(
            timelineEvent(
                eventId = "missed",
                sessionId = session.sessionId,
                eventType = AssessmentEventType.MISSED,
                detectedNote = null,
                targetNoteId = "target-note-missed"
            )
        )

        val quality = AssessmentSessionValidator.derive(session, timeline)

        assertEquals(AssessmentSessionValidity.VALID, quality.validity)
        assertEquals(2, quality.validEventCount)
        assertNotNull(com.example.model.SkillEvidenceCalculator.calculateValidEvidence(session, timeline))
    }

    @Test
    fun schedulerAndEvaluatorPreserveCanonicalSessionIdentity() {
        val clock = FixedClock(1_000_000_000L)
        val session = PracticeSessionContext.start("pattern", clock.nowNanos())
        val pattern = HandpanPattern(
            id = session.patternId,
            title = "pattern",
            description = "pattern",
            events = listOf(NoteEvent(0, 0.0))
        )
        val evaluator = AcousticPracticeEvaluator(clock = clock)
        val target = PatternScheduler.buildSchedule(
            events = pattern.events,
            beatsPerBar = 4,
            totalBars = 1,
            assessmentSessionId = session.sessionId,
            patternId = pattern.id,
            scheduleStartTimestampNanos = session.startTimestampNanos
        ).first { it.target != null }.target!!

        evaluator.startAssessment(session, pattern, NotePitchConfig.D_KURD_9)
        evaluator.notifyExpectedTarget(target)

        assertEquals(session.sessionId, evaluator.assessmentSessionIdForTesting)
        assertTrue(evaluator.timeline.snapshot().all { it.sessionId == session.sessionId })
        assertEquals(AssessmentEventType.EXPECTED, evaluator.timeline.snapshot().first().eventType)
    }

    @Test
    fun staleCallbackIsIgnoredAndDuplicateDetectionIsConsumedOnce() {
        val analysis = RecordingAnalysisSession()
        val clock = FixedClock(1_000_000_000L)
        val evaluator = AcousticPracticeEvaluator(clock = clock, analysisSession = analysis)
        val pattern = HandpanPattern(
            id = "pattern",
            title = "pattern",
            description = "pattern",
            bpm = 60,
            events = listOf(NoteEvent(0, 0.0))
        )
        val sessionA = PracticeSessionContext.start("pattern", 1_000_000_000L)
        val sessionB = PracticeSessionContext.start("pattern", 2_000_000_000L)

        evaluator.startAssessment(sessionA, pattern, NotePitchConfig.D_KURD_9, bpm = 60)
        val callbackA = analysis.callbacks.single { it.sessionId == sessionA.sessionId }
        evaluator.startAssessment(sessionB, pattern, NotePitchConfig.D_KURD_9, bpm = 60)
        val targetB = PatternScheduler.buildSchedule(
            events = pattern.events,
            beatsPerBar = 4,
            totalBars = 1,
            assessmentSessionId = sessionB.sessionId,
            patternId = pattern.id,
            scheduleStartTimestampNanos = sessionB.startTimestampNanos,
            bpm = 60
        ).first { it.target != null }.target!!
        evaluator.notifyExpectedTarget(targetB)

        callbackA.onStrike(detectedStrike("stale-a", sessionA.sessionId, 2_000_000_000L))
        analysis.callbacks.last { it.sessionId == sessionB.sessionId }
            .onStrike(detectedStrike("valid-b", sessionB.sessionId, 2_000_000_000L))
        analysis.callbacks.last { it.sessionId == sessionB.sessionId }
            .onStrike(detectedStrike("valid-b", sessionB.sessionId, 2_000_000_000L))

        val events = evaluator.timeline.snapshot()
        assertTrue(events.none { it.sessionId == sessionA.sessionId })
        assertEquals(1, events.count { it.eventType == AssessmentEventType.CORRECT })
        assertEquals(1, events.count { it.eventId == "valid-b-assessment" })
    }

    private fun timelineEvent(
        eventId: String,
        sessionId: String,
        assessmentSessionId: String = sessionId,
        eventType: AssessmentEventType = AssessmentEventType.CORRECT,
        detectedNote: Int? = 0,
        targetNoteId: String = "target-note"
    ) = AssessmentTimelineEvent(
        eventId = eventId,
        sessionId = sessionId,
        assessmentSessionId = assessmentSessionId,
        loopId = "loop-0",
        sequenceIndex = 0,
        expectedNote = 0,
        detectedNote = detectedNote,
        eventType = eventType,
        expectedTimestampNanos = 1_000_000_000L,
        detectedTimestampNanos = 1_000_000_000L,
        deviationNanos = 0L,
        timingResult = null,
        confidence = 0.9f,
        targetId = "target-0",
        source = "test",
        durationNanos = null,
        isConsumed = true,
        patternId = "pattern",
        targetNoteId = targetNoteId,
        targetBpm = 60,
        subdivision = com.example.model.Subdivision.QUARTER,
        beatPosition = 0.0,
        expectedTimingWindow = com.example.model.TimingToleranceProfile(0L, 45_000_000L, 90_000_000L, 160_000_000L),
        expectedTechnique = com.example.model.HandpanTechnique.DING,
        sessionValidity = AssessmentSessionValidity.VALID,
        signalQuality = 0.9f
    )

    private class FixedClock(private val value: Long) : PracticeClock {
        override fun nowNanos(): Long = value
    }

    private class RecordingAnalysisSession : AudioAnalysisSession() {
        data class Callback(
            val sessionId: String,
            val onStrike: (DetectedStrikeEvent) -> Unit
        )

        val callbacks = mutableListOf<Callback>()

        override fun acquire(
            scaleConfig: NotePitchConfig,
            onStrike: (DetectedStrikeEvent) -> Unit,
            onPitch: (com.example.audio.DetectedPitchResult) -> Unit,
            sessionId: String
        ): Subscription {
            callbacks += Callback(sessionId, onStrike)
            return Subscription({}, isActive = true)
        }
    }

    private fun detectedStrike(id: String, sessionId: String, timestampNanos: Long) =
        DetectedStrikeEvent(
            id = id,
            sessionId = sessionId,
            monotonicTimestampNanos = timestampNanos,
            detectedFrequencyHz = 146.83f,
            detectedNoteName = "D3",
            detectedCentsOffset = 0,
            detectedNote = 0,
            matchedPitchDiffHz = 0f,
            pitchConfidence = 0.95f,
            onsetStrength = 0.8f,
            energy = 0.8f,
            pitchValid = true,
            signalQuality = 0.9f,
            source = "microphone"
        )
}