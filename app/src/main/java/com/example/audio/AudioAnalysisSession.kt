package com.example.audio

import com.example.model.DetectedStrikeEvent
import com.example.model.NotePitchConfig
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

open class AudioAnalysisSession(
    private val detector: PitchDetector = PitchDetector()
) {
    internal var onCaptureError: ((AudioCaptureError) -> Unit)? = null
    private data class Listener(
        val onStrike: (DetectedStrikeEvent) -> Unit,
        val onPitch: (DetectedPitchResult) -> Unit
    )

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val eventSequence = AtomicLong(0L)
    private var listening = false
    private var activeSessionId: String? = null
    private var microphoneLease: AudioResourceCoordinator.Lease? = null

    internal fun bindSessionId(sessionId: String) {
        require(sessionId.isNotBlank())
        activeSessionId = sessionId
    }

    @Deprecated("Use acquire(..., sessionId) for assessment sessions.")
    @Synchronized
    open fun acquire(
        scaleConfig: NotePitchConfig,
        onStrike: (DetectedStrikeEvent) -> Unit,
        onPitch: (DetectedPitchResult) -> Unit = {}
    ): Subscription {
        val sessionId = activeSessionId ?: return Subscription({}, isActive = false)
        return acquire(scaleConfig, onStrike, onPitch, sessionId)
    }

    @Synchronized
    open fun acquire(
        scaleConfig: NotePitchConfig,
        onStrike: (DetectedStrikeEvent) -> Unit,
        onPitch: (DetectedPitchResult) -> Unit = {},
        sessionId: String
    ): Subscription {
        val listener = Listener(onStrike, onPitch)
        if (listening && activeSessionId != sessionId) {
            return Subscription({}, isActive = false)
        }
        activeSessionId = sessionId
        if (!listening) {
            microphoneLease = AudioResourceCoordinator.tryAcquire(sessionId)
                ?: run {
                    onCaptureError?.invoke(
                        AudioCaptureError(
                            AudioCaptureErrorKind.UNAVAILABLE,
                            IllegalStateException("Microphone lease unavailable for session $sessionId")
                        )
                    )
                    return Subscription({}, isActive = false)
                }
        }
        listeners += listener
        if (!listening) {
            try {
                detector.startListening(
                    scaleConfig = scaleConfig,
                    onStrikeDetected = { result, timestamp ->
                        val event = DetectedStrikeEvent(
                            id = "$sessionId-${eventSequence.incrementAndGet()}",
                            sessionId = sessionId,
                            monotonicTimestampNanos = timestamp,
                            detectedFrequencyHz = result.frequencyHz,
                            detectedNoteName = result.noteName,
                            detectedCentsOffset = result.centsOffset,
                            detectedNote = result.matchedNoteNumber,
                            matchedPitchDiffHz = result.matchedPitchDiffHz,
                            pitchConfidence = result.confidence,
                            onsetStrength = result.amplitude,
                            energy = result.amplitude,
                            pitchValid = result.matchedNoteNumber != null && result.confidence >= 0.5f,
                            onsetConfidence = result.onsetConfidence,
                            signalQuality = result.signalQuality
                            ,audioQuality = result.audioQuality
                        )
                        listeners.forEach { it.onStrike(event) }
                    },
                    onContinuousPitch = { result -> listeners.forEach { it.onPitch(result) } },
                    onCaptureError = { error ->
                        if (activeSessionId == sessionId) {
                            listening = false
                            listeners.clear()
                            microphoneLease?.close()
                            microphoneLease = null
                            onCaptureError?.invoke(error)
                        }
                    }
                )
                listening = detector.isListeningNow
                if (!listening) {
                    listeners -= listener
                    microphoneLease?.close()
                    microphoneLease = null
                }
            } catch (error: RuntimeException) {
                listeners -= listener
                listening = false
                microphoneLease?.close()
                microphoneLease = null
                onCaptureError?.invoke(AudioCaptureError(AudioCaptureErrorKind.STARTUP, error))
            }
        }
        return Subscription({ release(listener) }, isActive = listening)
    }

    @Synchronized
    private fun release(listener: Listener) {
        listeners -= listener
        if (listeners.isEmpty() && listening) {
            detector.stopListening()
            listening = false
            microphoneLease?.close()
            microphoneLease = null
        }
    }

    fun close() {
        synchronized(this) {
            listeners.clear()
            if (listening) {
                detector.stopListening()
                listening = false
            }
            microphoneLease?.close()
            microphoneLease = null
        }
        detector.release()
    }

    class Subscription internal constructor(
        private val onClose: () -> Unit,
        val isActive: Boolean = true
    ) : AutoCloseable {
        private var closed = false

        override fun close() {
            if (!closed) {
                closed = true
                onClose()
            }
        }
    }
}

internal fun DetectedStrikeEvent.toPitchResult(): DetectedPitchResult {
    return DetectedPitchResult(
        frequencyHz = detectedFrequencyHz,
        noteName = detectedNoteName,
        centsOffset = detectedCentsOffset,
        amplitude = energy,
        matchedNoteNumber = detectedNote,
        matchedPitchDiffHz = matchedPitchDiffHz,
        confidence = pitchConfidence
    )
}
