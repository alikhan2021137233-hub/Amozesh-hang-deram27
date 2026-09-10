package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.example.model.NotePitchConfig
import com.example.model.AudioFrameQuality
import com.example.model.AudioFrameQualityAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt
import java.util.concurrent.atomic.AtomicLong

/**
 * Result of real-time pitch and onset detection from microphone.
 */
data class DetectedPitchResult(
    val frequencyHz: Float,
    val noteName: String,
    val centsOffset: Int, // -50 to +50 cents from standard 12-TET pitch
    val amplitude: Float,
    val matchedNoteNumber: Int?, // 0 for Ding, 1..8, 9 for Slap, or null if outside scale
    val matchedPitchDiffHz: Float,
    val confidence: Float = 0.8f,
    val onsetConfidence: Float = 0f,
    val signalQuality: Float = 0f,
    val audioQuality: AudioFrameQuality? = null
)

enum class AudioCaptureErrorKind {
    STARTUP,
    READ,
    UNAVAILABLE
}

data class AudioCaptureError(
    val kind: AudioCaptureErrorKind,
    val cause: Throwable
)

/**
 * Real-time Pitch and Strike Detector for acoustic handpans.
 * Uses unified monotonic time stamps (nanoseconds) for latency-free evaluation.
 */
open class PitchDetector(
    private val clock: PracticeClock = PracticeClock.Default
) {

    companion object {
        private const val TAG = "PitchDetector"
        private const val SAMPLE_RATE = 22050
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE = 2048 // ~92ms at 22.05kHz

        fun frequencyToNoteName(freq: Float): Pair<String, Int> {
            return YinPitchDetector.frequencyToNoteAndCents(freq)
        }
    }

    private var audioRecord: AudioRecord? = null
    private var trackingJob: Job? = null
    @Volatile
    private var isListening = false
    private val listeningGeneration = AtomicLong(0L)
    private val detectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val onsetMatcher = OnsetAndPitchMatcher(SAMPLE_RATE)

    @SuppressLint("MissingPermission")
    open fun startListening(
        scaleConfig: NotePitchConfig,
        onStrikeDetected: (DetectedPitchResult, Long) -> Unit, // Monotonic timestamp in nanoseconds
        onContinuousPitch: (DetectedPitchResult) -> Unit = {},
        onCaptureError: (AudioCaptureError) -> Unit = {}
    ): Boolean {
        if (isListening) stopListening()
        val generation = listeningGeneration.incrementAndGet()
        onsetMatcher.reset()

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(BUFFER_SIZE * 2)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                failStartup(
                    onCaptureError,
                    IllegalStateException("AudioRecord initialization failed")
                )
                return false
            }

            audioRecord?.startRecording()
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                failStartup(
                    onCaptureError,
                    IllegalStateException("AudioRecord did not enter recording state")
                )
                return false
            }
            isListening = true

            trackingJob = detectorScope.launch {
                val audioBuffer = ShortArray(BUFFER_SIZE)
                var lastRms = 0f
                var lastStrikeTimestampNanos = 0L

                try {
                    while (isActive && isListening) {
                        val readSamples = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                        if (readSamples < 0) {
                            throw IllegalStateException("AudioRecord.read failed with code $readSamples")
                        }
                        if (readSamples < BUFFER_SIZE) continue

                    val frameAvailableNanos = clock.nowNanos()
                    val captureTimestampNanos = frameAvailableNanos -
                        (readSamples * 1_000_000_000L / SAMPLE_RATE)
                    val analysisStartNanos = clock.nowNanos()

                    // Calculate RMS energy
                    var sumSquares = 0.0
                    for (i in 0 until readSamples) {
                        val sample = audioBuffer[i].toDouble() / 32768.0
                        sumSquares += sample * sample
                    }
                    val rms = sqrt(sumSquares / readSamples).toFloat()

                    val eval = onsetMatcher.processFrame(audioBuffer, readSamples, rms, lastRms, scaleConfig)
                    val analysisEndNanos = clock.nowNanos()
                    val audioQuality = AudioFrameQualityAnalyzer.analyze(
                        samples = audioBuffer,
                        sampleCount = readSamples,
                        sampleRateHz = SAMPLE_RATE,
                        noiseFloorRms = eval.noiseFloorRms,
                        captureTimestampNanos = captureTimestampNanos.coerceAtLeast(0L),
                        analysisStartTimestampNanos = analysisStartNanos.coerceAtLeast(captureTimestampNanos),
                        analysisEndTimestampNanos = analysisEndNanos
                    )

                    // Sub-frame timestamp based on sample offset
                    val exactStrikeTimestampNanos = frameAvailableNanos -
                        ((readSamples - eval.onsetSampleOffset).toLong() * 1_000_000_000L / SAMPLE_RATE)

                    val result = DetectedPitchResult(
                        frequencyHz = eval.detectedFreqHz,
                        noteName = eval.noteName,
                        centsOffset = eval.centsOffset,
                        amplitude = (rms * 5f).coerceIn(0f, 1f),
                        matchedNoteNumber = eval.matchedScaleNote,
                        matchedPitchDiffHz = eval.centsDeviationFromScale,
                        confidence = eval.confidence,
                        onsetConfidence = eval.onsetConfidence,
                        signalQuality = eval.signalQuality,
                        audioQuality = audioQuality
                    )

                    if (listeningGeneration.get() == generation && isListening) {
                        withContext(Dispatchers.Main) {
                            if (listeningGeneration.get() == generation && isListening) {
                                onContinuousPitch(result)
                            }
                        }
                    }

                    // Refractory window of 130ms (130,000,000 ns) for distinct strikes.
                    // Keep unpitched onsets: the evaluator must score them instead of hiding them.
                    if (eval.isStrike && listeningGeneration.get() == generation && isListening &&
                        audioQuality.status == com.example.model.AudioFrameStatus.VALID &&
                        (exactStrikeTimestampNanos - lastStrikeTimestampNanos) > 130_000_000L
                    ) {
                        lastStrikeTimestampNanos = exactStrikeTimestampNanos
                        withContext(Dispatchers.Main) {
                            if (listeningGeneration.get() == generation && isListening) {
                                onStrikeDetected(result, exactStrikeTimestampNanos)
                            }
                        }
                    }

                        lastRms = rms
                    }
                } catch (error: Exception) {
                    if (listeningGeneration.get() == generation && isListening) {
                        isListening = false
                        onCaptureError(AudioCaptureError(AudioCaptureErrorKind.READ, error))
                        try {
                            audioRecord?.stop()
                        } catch (_: Exception) {
                        }
                        audioRecord?.release()
                        audioRecord = null
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting pitch detection", e)
            failStartup(onCaptureError, e)
            return false
        }
    }

    private fun failStartup(
        onCaptureError: (AudioCaptureError) -> Unit,
        cause: Throwable
    ) {
        isListening = false
        audioRecord?.let { record -> runCatching { record.release() } }
        audioRecord = null
        onCaptureError(AudioCaptureError(AudioCaptureErrorKind.STARTUP, cause))
    }

    val isListeningNow: Boolean
        get() = isListening

    open fun stopListening() {
        listeningGeneration.incrementAndGet()
        isListening = false
        trackingJob?.cancel()
        trackingJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    open fun release() {
        listeningGeneration.incrementAndGet()
        stopListening()
        detectorScope.cancel()
    }
}
