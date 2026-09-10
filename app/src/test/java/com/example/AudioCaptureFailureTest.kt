package com.example

import com.example.audio.AudioAnalysisSession
import com.example.audio.AudioCaptureError
import com.example.audio.AcousticPracticeEvaluator
import com.example.audio.AudioCaptureErrorKind
import com.example.audio.MicrophoneState
import com.example.audio.PitchDetector
import com.example.audio.PracticeClock
import com.example.model.DetectedStrikeEvent
import com.example.model.HandpanPattern
import com.example.model.NoteEvent
import com.example.model.NotePitchConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioCaptureFailureTest {
    @Test
    fun detectorStartupFailureIsVisibleAndLeavesAssessmentInactive() {
        val evaluator = AcousticPracticeEvaluator(
            clock = PracticeClock.Default,
            analysisSession = AudioAnalysisSession(FailingPitchDetector())
        )
        val pattern = HandpanPattern(
            id = "capture-failure",
            title = "Capture failure",
            description = "Capture failure",
            events = listOf(NoteEvent(0, 0.0))
        )

        evaluator.startAssessment(
            pattern = pattern,
            scaleConfig = NotePitchConfig.D_KURD_9
        )

        assertEquals(MicrophoneState.MIC_ERROR, evaluator.state.value.microphoneState)
        assertFalse(evaluator.state.value.isListening)
        assertFalse(evaluator.state.value.assessmentActive)
        evaluator.release()
    }

    @Test
    fun unavailableMicrophoneIsTerminalAndCannotProduceFinalizedEvidence() {
        val analysisSession = UnavailableAnalysisSession()
        val evaluator = AcousticPracticeEvaluator(analysisSession = analysisSession)
        val pattern = HandpanPattern(
            id = "unavailable-microphone",
            title = "Unavailable microphone",
            description = "Unavailable microphone",
            events = listOf(NoteEvent(0, 0.0))
        )

        evaluator.startAssessment(
            context = com.example.model.PracticeSessionContext.start("unavailable-microphone", 1_000L),
            pattern = pattern,
            scaleConfig = NotePitchConfig.D_KURD_9,
            bpm = pattern.bpm
        )
        evaluator.stopAssessment(showSummary = false)

        assertEquals(MicrophoneState.MIC_UNAVAILABLE, evaluator.state.value.microphoneState)
        assertFalse(evaluator.state.value.assessmentActive)
        assertNull(evaluator.finalizedAssessment(2_000L))
        assertEquals(0, evaluator.timeline.snapshot().size)
        evaluator.release()
    }

    private class FailingPitchDetector : PitchDetector() {
        override fun startListening(
            scaleConfig: NotePitchConfig,
            onStrikeDetected: (com.example.audio.DetectedPitchResult, Long) -> Unit,
            onContinuousPitch: (com.example.audio.DetectedPitchResult) -> Unit,
            onCaptureError: (AudioCaptureError) -> Unit
        ): Boolean {
            onCaptureError(AudioCaptureError(AudioCaptureErrorKind.STARTUP, IllegalStateException("test startup failure")))
            return false
        }
    }

    private class UnavailableAnalysisSession : AudioAnalysisSession() {
        override fun acquire(
            scaleConfig: NotePitchConfig,
            onStrike: (DetectedStrikeEvent) -> Unit,
            onPitch: (com.example.audio.DetectedPitchResult) -> Unit,
            sessionId: String
        ): Subscription {
            onCaptureError?.invoke(
                AudioCaptureError(
                    AudioCaptureErrorKind.UNAVAILABLE,
                    IllegalStateException("test microphone unavailable")
                )
            )
            return Subscription({}, isActive = false)
        }
    }
}