package com.example.audio

import com.example.model.HandpanPattern
import com.example.model.NoteEvent
import com.example.model.NotePitchConfig
import com.example.model.DetectedStrikeEvent
import com.example.model.StrikeClassification
import com.example.model.TimingResult
import com.example.model.TimingStatus
import com.example.model.PracticeScoreCalculator
import com.example.model.ScoreCounters
import com.example.model.AssessmentEventType
import com.example.model.AssessmentTimeline
import com.example.model.AssessmentTimelineEvent
import com.example.model.MusicalTargetIdentity
import com.example.model.MusicalTargetMatcher
import com.example.model.TargetRegistry
import com.example.model.TimingPolicy
import com.example.model.TargetMatchType
import com.example.model.MusicalTarget
import com.example.model.TargetObligation
import com.example.model.CanonicalAssessmentMetrics
import com.example.model.AssessmentSessionValidity
import com.example.model.AssessmentSessionSummary
import com.example.model.PracticeSessionContext
import com.example.model.AssessmentSessionValidator
import com.example.model.AudioCalibrationSession
import com.example.model.AudioCalibrationSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs

enum class StrikeAccuracyStatus {
    PERFECT,    // ±45ms + correct pitch
    EXCELLENT,  // Configured excellent timing window + correct pitch
    GOOD,       // ±90ms + correct pitch
    EARLY,      // -160ms to -90ms
    LATE,       // +90ms to +160ms
    WRONG_NOTE, // correct timing window but incorrect note
    UNKNOWN_NOTE,
    EXTRA_STRIKE,
    MISSED      // no strike detected within window
}

enum class MicrophoneState {
    MIC_IDLE,
    MIC_REQUESTING_PERMISSION,
    MIC_READY,
    MIC_UNAVAILABLE,
    MIC_ERROR,
    MIC_ACTIVE,
    MIC_PAUSED
}

enum class TimingAccuracyStatus {
    PERFECT,
    EXCELLENT,
    GOOD,
    EARLY,
    LATE,
    MISSED,
    UNKNOWN
}

enum class ExpectedEventLifecycle {
    EXPECTED,
    WAITING,
    MATCHED,
    MISSED,
    EXPIRED
}

data class StrikeFeedback(
    val status: StrikeAccuracyStatus,
    val timingStatus: TimingAccuracyStatus,
    val deviationMs: Long,
    val expectedNotes: List<Int>,
    val detectedNote: Int?,
    val detectedFreqHz: Float,
    val detectedCentsOffset: Int,
    val confidence: Float,
    val expectedTimestampNanos: Long,
    val monotonicTimestampNanos: Long,
    val noteCorrect: Boolean,
    val classification: StrikeClassification = when (status) {
        StrikeAccuracyStatus.PERFECT,
        StrikeAccuracyStatus.EXCELLENT,
        StrikeAccuracyStatus.GOOD,
        StrikeAccuracyStatus.EARLY,
        StrikeAccuracyStatus.LATE -> StrikeClassification.CORRECT_NOTE
        StrikeAccuracyStatus.WRONG_NOTE -> StrikeClassification.WRONG_NOTE
        StrikeAccuracyStatus.UNKNOWN_NOTE -> StrikeClassification.UNKNOWN_NOTE
        StrikeAccuracyStatus.EXTRA_STRIKE -> StrikeClassification.EXTRA_STRIKE
        StrikeAccuracyStatus.MISSED -> StrikeClassification.MISSED_NOTE
    },
    val timingResult: TimingResult? = if (status == StrikeAccuracyStatus.EXTRA_STRIKE || status == StrikeAccuracyStatus.MISSED) {
        null
    } else {
        TimingResult(
            status = when (timingStatus) {
                TimingAccuracyStatus.PERFECT -> TimingStatus.PERFECT
                TimingAccuracyStatus.EXCELLENT -> TimingStatus.EXCELLENT
                TimingAccuracyStatus.GOOD -> TimingStatus.GOOD
                TimingAccuracyStatus.EARLY -> TimingStatus.EARLY
                TimingAccuracyStatus.LATE -> TimingStatus.LATE
                TimingAccuracyStatus.MISSED,
                TimingAccuracyStatus.UNKNOWN -> TimingStatus.GOOD
            },
            deviationNanos = deviationMs * 1_000_000L
        )
    }
)

data class AcousticAssessmentState(
    val isEnabled: Boolean = false,
    val isListening: Boolean = false,
    val liveFrequencyHz: Float = 0f,
    val liveNoteName: String = "--",
    val liveCentsOffset: Int = 0,
    val liveDetectedScaleNote: Int? = null,
    val lastFeedback: StrikeFeedback? = null,
    val totalExpectedNotes: Int = 0,
    val totalStrikesEvaluated: Int = 0,
    val perfectCount: Int = 0,
    val excellentCount: Int = 0,
    val goodCount: Int = 0,
    val earlyCount: Int = 0,
    val lateCount: Int = 0,
    val wrongNoteCount: Int = 0,
    val unknownNoteCount: Int = 0,
    val wrongNoteTimingPoints: Int = 0,
    val extraStrikeCount: Int = 0,
    val missedCount: Int = 0,
    // Separate Metrics
    val timingAccuracyPercentage: Float = 0f,
    val noteAccuracyPercentage: Float = 0f,
    val accuracyPercentage: Float = 0f, // Combined overall
    val averageTimingDeviationMs: Float = 0f,
    val consistencyPercentage: Float = 100f,
    val isSummaryDialogVisible: Boolean = false,
    val microphoneState: MicrophoneState = MicrophoneState.MIC_IDLE,
    val calibration: AudioCalibrationSnapshot = AudioCalibrationSnapshot(),
    val assessmentActive: Boolean = false,
    val canonicalMetrics: CanonicalAssessmentMetrics = CanonicalAssessmentMetrics.EMPTY
) {
    val isActive: Boolean
        get() = isEnabled && assessmentActive

    val totalHits: Int
        get() = perfectCount + goodCount + earlyCount + lateCount + wrongNoteCount

    val starRating: Int
        get() = when {
            accuracyPercentage >= 90f -> 3
            accuracyPercentage >= 70f -> 2
            accuracyPercentage >= 50f -> 1
            else -> 0
        }
}

/**
 * Real-time evaluator that listens to the user's acoustic handpan playing via the microphone,
 * compares strike timing and pitch against the practice pattern using a unified monotonic PracticeClock,
 * and computes separate timing vs note accuracy metrics.
 */
class AcousticPracticeEvaluator(
    private val clock: PracticeClock = PracticeClock.Default,
    private val analysisSession: AudioAnalysisSession = AudioAnalysisSession(),
    private val ownsAnalysisSession: Boolean = true,
    val timeline: AssessmentTimeline = AssessmentTimeline()
) {
    internal val assessmentSessionIdForTesting: String
        get() = assessmentSessionId

    private val _state = MutableStateFlow(AcousticAssessmentState())
    val state: StateFlow<AcousticAssessmentState> = _state.asStateFlow()

    private var scaleConfig: NotePitchConfig = NotePitchConfig()
    private val timingWindowList = mutableListOf<Long>()
    private var assessmentStartTimestampNanos: Long = 0L
    private var beatDurationNanos: Long = 500_000_000L
    private var timingPolicy = TimingPolicy()
    private var analysisSubscription: AudioAnalysisSession.Subscription? = null
    private var assessmentSessionId: String = ""
    private var sessionContext: PracticeSessionContext? = null
    var finalSummary: AssessmentSessionSummary? = null
        private set
    @Volatile
    private var practiceRunning: Boolean = false
    private val targetMatcher = MusicalTargetMatcher()
    private val targetRegistry = TargetRegistry()
    private val calibrationSession = AudioCalibrationSession()

    init {
        analysisSession.onCaptureError = { error ->
            _state.update {
                it.copy(
                    isListening = false,
                    microphoneState = MicrophoneState.MIC_ERROR,
                    assessmentActive = false
                )
            }
        }
    }

    // Current expected target note events and monotonic window timestamp in nanoseconds
    @Volatile
    private var expectedNoteEvents: List<NoteEvent> = emptyList()
    @Volatile
    private var expectedBeatTargetTimestampNanos: Long = 0L
    @Volatile
    private var strikeProcessedForCurrentBeat: Boolean = false

    fun setScaleConfig(config: NotePitchConfig) {
        this.scaleConfig = config
    }

    fun setTimingPolicy(policy: TimingPolicy) {
        timingPolicy = policy
    }

    @Deprecated("Use startAssessment(context, pattern, scaleConfig, bpm) for canonical session lifecycle.")
    fun startAssessment(pattern: HandpanPattern, scaleConfig: NotePitchConfig, bpm: Int = pattern.bpm) {
        startAssessment(
            context = PracticeSessionContext.start(pattern.id, clock.nowNanos()),
            pattern = pattern,
            scaleConfig = scaleConfig,
            bpm = bpm
        )
    }

    fun startAssessment(
        context: PracticeSessionContext,
        pattern: HandpanPattern,
        scaleConfig: NotePitchConfig,
        bpm: Int
    ) {
        startAssessment(context, pattern, scaleConfig, bpm, preserveTimeline = false)
    }

    fun startAssessment(
        context: PracticeSessionContext,
        pattern: HandpanPattern,
        scaleConfig: NotePitchConfig,
        bpm: Int = pattern.bpm,
        preserveTimeline: Boolean = false
    ) {
        this.scaleConfig = scaleConfig
        sessionContext = context
        assessmentSessionId = context.sessionId
        targetRegistry.clear()
        if (!preserveTimeline) {
            timeline.clear()
            timeline.bindToSession(context.sessionId)
        } else {
            require(timeline.sessionId() == context.sessionId) {
                "Recovered timeline belongs to another assessment session"
            }
        }
        beatDurationNanos = MusicalTiming.beatDurationNanos(bpm)
        resetStats()
        if (preserveTimeline) restoreTimelineState()
        calibrationSession.start()
        practiceRunning = true

        assessmentStartTimestampNanos = context.startTimestampNanos
        finalSummary = null
        _state.update {
            it.copy(
                isEnabled = true,
                isListening = false,
                    isSummaryDialogVisible = false,
                microphoneState = MicrophoneState.MIC_READY,
                assessmentActive = true
            )
        }

        attachAnalysisSubscription()
    }

    fun setPracticeRunning(running: Boolean) {
        practiceRunning = running
    }

    fun setBpm(bpm: Int) {
        require(bpm > 0)
        beatDurationNanos = MusicalTiming.beatDurationNanos(bpm)
    }

    fun pauseAssessment() {
        if (!_state.value.isEnabled) return
        sessionContext?.pause(clock.nowNanos())
        analysisSubscription?.close()
        analysisSubscription = null
        _state.update { it.copy(isListening = false, microphoneState = MicrophoneState.MIC_PAUSED) }
    }

    fun resumeAssessment() {
        if (!_state.value.isEnabled || _state.value.isListening) return
        sessionContext?.resume(clock.nowNanos())
        attachAnalysisSubscription()
    }

    private fun attachAnalysisSubscription() {
        analysisSubscription?.close()
        analysisSession.bindSessionId(assessmentSessionId)
        analysisSubscription = analysisSession.acquire(
            scaleConfig = scaleConfig,
            onPitch = { pitch ->
                val calibration = pitch.audioQuality?.let(calibrationSession::observe)
                _state.update {
                    it.copy(
                        liveFrequencyHz = pitch.frequencyHz,
                        liveNoteName = pitch.noteName,
                        liveCentsOffset = pitch.centsOffset,
                        liveDetectedScaleNote = pitch.matchedNoteNumber,
                        calibration = calibration ?: it.calibration
                    )
                }
            },
            onStrike = { event ->
                handleStrikeDetected(event)
            },
            sessionId = assessmentSessionId
        )
        val active = analysisSubscription?.isActive == true
        _state.update {
            it.copy(
                isListening = active,
                microphoneState = when {
                    active -> MicrophoneState.MIC_ACTIVE
                    it.microphoneState == MicrophoneState.MIC_ERROR -> MicrophoneState.MIC_ERROR
                    else -> MicrophoneState.MIC_UNAVAILABLE
                }
            )
        }
    }

    /**
     * Directly evaluates a detected pitch against the active expected pattern notes.
     * Used for real-time DSP evaluation pipelines and automated verification test suites.
     */
    fun evaluateDetectedPitch(
        frequencyHz: Float,
        confidence: Float = 0.9f,
        timestampNanos: Long = clock.nowNanos()
    ): StrikeFeedback? {
        val matcher = OnsetAndPitchMatcher(22050)
        val (matchedNote, centsDev) = matcher.matchToScaleByCents(frequencyHz, scaleConfig)
        val (noteName, cents) = YinPitchDetector.frequencyToNoteAndCents(frequencyHz)

        handleStrikeDetected(
            DetectedStrikeEvent(
                id = "manual-$timestampNanos-$frequencyHz-$confidence",
                sessionId = assessmentSessionId,
                monotonicTimestampNanos = timestampNanos,
                detectedFrequencyHz = frequencyHz,
                detectedNoteName = noteName,
                detectedCentsOffset = cents,
                detectedNote = matchedNote,
                matchedPitchDiffHz = centsDev,
                pitchConfidence = confidence,
                onsetStrength = 0.85f,
                energy = 0.85f,
                pitchValid = matchedNote != null && confidence >= 0.5f,
                source = "manual"
            )
        )
        return _state.value.lastFeedback
    }

    fun stopAssessment(showSummary: Boolean = true) {
        // Ending a session closes every still-pending target; there will be no later strike
        // or expected slice to advance the evaluator past its miss window.
        finalizePendingEvents()
        sessionContext?.finalize(clock.nowNanos())
        sessionContext?.let {
            finalSummary = AssessmentSessionSummary(it, AssessmentSessionValidator.derive(it, timeline))
        }
        analysisSubscription?.close()
        analysisSubscription = null
        practiceRunning = false
        _state.update {
            it.copy(
                isEnabled = false,
                isListening = false,
                isSummaryDialogVisible = showSummary && it.totalStrikesEvaluated > 0,
                microphoneState = MicrophoneState.MIC_IDLE,
                assessmentActive = false
            )
        }
    }

    fun finalizedAssessment(completedAtEpochMs: Long): com.example.model.FinalizedAssessment? {
        val summary = finalSummary ?: return null
        val metrics = com.example.model.SkillEvidenceCalculator.calculateValidEvidence(
            summary.session,
            timeline
        ) ?: return null
        return com.example.model.FinalizedAssessment(
            sessionId = summary.session.sessionId,
            patternId = summary.session.patternId,
            bpm = (60_000_000_000L / beatDurationNanos).toInt(),
            completedAtEpochMs = completedAtEpochMs,
            quality = summary.quality,
            metrics = metrics,
            score = PracticeScoreCalculator.calculate(timeline)
        )
    }

    fun release() {
        practiceRunning = false
        analysisSubscription?.close()
        analysisSubscription = null
        if (ownsAnalysisSession) analysisSession.close()
    }

    fun setEnabled(enabled: Boolean) {
        _state.update { it.copy(isEnabled = enabled) }
        if (!enabled) {
            analysisSubscription?.close()
            analysisSubscription = null
            _state.update { it.copy(isListening = false, microphoneState = MicrophoneState.MIC_IDLE) }
        }
    }

    fun toggleEnabled() {
        setEnabled(!_state.value.isEnabled)
    }

    fun dismissSummary() {
        _state.update { it.copy(isSummaryDialogVisible = false) }
    }

    internal fun expireTargetsAt(nowNanos: Long) {
        if (_state.value.isEnabled) expirePendingEvents(nowNanos)
    }

    fun resetStats() {
        timingWindowList.clear()
        expectedNoteEvents = emptyList()
        expectedBeatTargetTimestampNanos = 0L
        strikeProcessedForCurrentBeat = false
        targetRegistry.clear()

        _state.update {
            it.copy(
                totalExpectedNotes = 0,
                totalStrikesEvaluated = 0,
                perfectCount = 0,
                excellentCount = 0,
                goodCount = 0,
                earlyCount = 0,
                lateCount = 0,
                wrongNoteCount = 0,
                unknownNoteCount = 0,
                wrongNoteTimingPoints = 0,
                extraStrikeCount = 0,
                missedCount = 0,
                timingAccuracyPercentage = 0f,
                noteAccuracyPercentage = 0f,
                accuracyPercentage = 0f,
                averageTimingDeviationMs = 0f,
                canonicalMetrics = CanonicalAssessmentMetrics.EMPTY,
                lastFeedback = null,
                calibration = AudioCalibrationSnapshot(),
                isSummaryDialogVisible = false
            )
        }
    }

    private fun restoreTimelineState() {
        val events = timeline.snapshot()
        val results = events.filter { it.eventType != AssessmentEventType.EXPECTED }
        val restoredTargets = events.mapNotNull { event ->
            val targetId = event.targetId ?: return@mapNotNull null
            val patternId = event.patternId ?: return@mapNotNull null
            val loopId = event.loopId ?: return@mapNotNull null
            val timestamp = event.expectedTimestampNanos ?: return@mapNotNull null
            MusicalTarget(
                identity = MusicalTargetIdentity(
                    sessionId = event.sessionId,
                    patternId = patternId,
                    loopId = loopId,
                    sequenceIndex = event.sequenceIndex,
                    targetId = targetId,
                    beatIndex = event.beatPosition?.toInt() ?: 0,
                    subdivisionIndex = event.subdivision?.ordinal ?: 0,
                    expectedTimestampNanos = timestamp,
                    expectedNotes = event.expectedNotes,
                    chordId = targetId,
                    obligations = listOfNotNull(
                        event.obligationId?.let { obligationId ->
                            event.expectedNote?.let { note -> TargetObligation(obligationId, note) }
                        }
                    )
                )
            )
        }.groupBy { it.identity.targetId }.map { (_, grouped) ->
            val first = grouped.first()
            val consumed = events.filter { it.targetId == first.identity.targetId && it.isConsumed }
                .mapNotNull { it.obligationId }
                .toSet()
            first.copy(
                consumedObligationIds = consumed,
                consumedNotes = first.effectiveObligations
                    .filter { it.obligationId in consumed }
                    .mapTo(linkedSetOf()) { it.noteNumber }
            )
        }
        targetRegistry.restore(restoredTargets, results.map { it.eventId })
        val score = PracticeScoreCalculator.calculate(events)
        val metrics = PracticeScoreCalculator.calculateMetrics(events)
        val timingEvents = results.filter { it.timingResult != null }
        val averageDeviation = timingEvents.mapNotNull { it.deviationNanos }
            .average().takeIf { !it.isNaN() }?.div(1_000_000.0)?.toFloat() ?: 0f
        _state.update {
            it.copy(
                totalExpectedNotes = events.count { event -> event.eventType == AssessmentEventType.EXPECTED },
                totalStrikesEvaluated = results.size,
                perfectCount = timingEvents.count { it.timingResult?.status == TimingStatus.PERFECT },
                excellentCount = timingEvents.count { it.timingResult?.status == TimingStatus.EXCELLENT },
                goodCount = timingEvents.count { it.timingResult?.status == TimingStatus.GOOD },
                earlyCount = timingEvents.count { it.timingResult?.status == TimingStatus.EARLY },
                lateCount = timingEvents.count { it.timingResult?.status == TimingStatus.LATE },
                wrongNoteCount = score.wrongCount,
                unknownNoteCount = score.unknownCount,
                extraStrikeCount = score.extraCount,
                missedCount = score.missedCount,
                timingAccuracyPercentage = score.timingAccuracyPercentage,
                noteAccuracyPercentage = score.noteAccuracyPercentage,
                accuracyPercentage = score.overallAccuracyPercentage,
                averageTimingDeviationMs = averageDeviation,
                canonicalMetrics = metrics
            )
        }
    }

    /**
     * Called by PracticeEngine on each slice with monotonic timestamp in nanoseconds.
     */
    @Synchronized
    fun notifyExpectedTarget(target: MusicalTarget) {
        if (!_state.value.isEnabled) return
        if (assessmentSessionId != target.identity.sessionId) {
            return
        }
        expirePendingEvents(clock.nowNanos())
        targetRegistry.register(target)
        target.effectiveObligations.forEach { obligation ->
            timeline.append(
                AssessmentTimelineEvent(
                    eventId = "${obligation.obligationId}-expected",
                    sessionId = target.identity.sessionId,
                    loopId = target.identity.loopId,
                    sequenceIndex = target.identity.sequenceIndex,
                    expectedNote = obligation.noteNumber,
                    detectedNote = null,
                    eventType = AssessmentEventType.EXPECTED,
                    expectedTimestampNanos = target.identity.expectedTimestampNanos,
                    detectedTimestampNanos = null,
                    deviationNanos = null,
                    timingResult = null,
                    confidence = 0f,
                    targetId = target.identity.targetId,
                    source = "pattern-scheduler",
                    durationNanos = null,
                    isConsumed = false,
                    assessmentSessionId = target.identity.sessionId,
                    patternId = target.identity.patternId,
                    obligationId = obligation.obligationId,
                    expectedNotes = target.identity.expectedNotes
                )
            )
        }
        _state.update {
            it.copy(
                totalExpectedNotes = it.totalExpectedNotes + target.identity.expectedNotes.size,
                canonicalMetrics = PracticeScoreCalculator.calculateMetrics(timeline.snapshot())
            )
        }
    }

    @Synchronized
    private fun handleStrikeDetected(event: DetectedStrikeEvent) {
        if (!_state.value.isEnabled || !practiceRunning) return
        if (event.sessionId != assessmentSessionId) return

        if (!targetRegistry.markProcessed(event.id)) return

        val pitch = event.toPitchResult()
        val strikeTimestampNanos = event.monotonicTimestampNanos

        val candidate = targetMatcher.selectCandidate(
            targets = targetRegistry.activeTargets(),
            event = event,
            sessionId = assessmentSessionId,
            policy = timingPolicy
        )
        val decision = targetMatcher.classify(candidate, event, timingPolicy)
        if (decision.type == TargetMatchType.EXTRA || decision.target == null) {
            registerExtraStrike(pitch, strikeTimestampNanos)
            return
        }

        val targetNanos = decision.target.identity.expectedTimestampNanos
        val currentExpected = decision.target.remainingNotes
        val isWithinTargetWindow = true
        val deviationNanos = strikeTimestampNanos - targetNanos
        val deviationMs = deviationNanos / 1_000_000L

        val expectedNoteNumbers = currentExpected.toList()
        val isPitchMatch = decision.type == TargetMatchType.CORRECT
        targetRegistry.apply(decision)

        val status = when {
            decision.type == TargetMatchType.UNKNOWN -> StrikeAccuracyStatus.UNKNOWN_NOTE
            decision.type == TargetMatchType.WRONG -> StrikeAccuracyStatus.WRONG_NOTE
            abs(deviationNanos) <= timingPolicy.perfectWindowNanos -> StrikeAccuracyStatus.PERFECT
            abs(deviationNanos) <= timingPolicy.goodWindowNanos -> StrikeAccuracyStatus.GOOD
            deviationNanos < -timingPolicy.goodWindowNanos -> StrikeAccuracyStatus.EARLY
            else -> StrikeAccuracyStatus.LATE
        }
        val timingStatus = when {
            abs(deviationNanos) <= timingPolicy.perfectWindowNanos -> TimingAccuracyStatus.PERFECT
            abs(deviationNanos) <= timingPolicy.goodWindowNanos -> TimingAccuracyStatus.GOOD
            deviationNanos < -timingPolicy.goodWindowNanos -> TimingAccuracyStatus.EARLY
            else -> TimingAccuracyStatus.LATE
        }

        strikeProcessedForCurrentBeat = true
        timingWindowList.add(abs(deviationMs))

        val feedback = StrikeFeedback(
            status = status,
            timingStatus = timingStatus,
            deviationMs = deviationMs,
            expectedNotes = expectedNoteNumbers,
            detectedNote = pitch.matchedNoteNumber,
            detectedFreqHz = pitch.frequencyHz,
            detectedCentsOffset = pitch.centsOffset,
            confidence = pitch.confidence,
            expectedTimestampNanos = targetNanos,
            monotonicTimestampNanos = strikeTimestampNanos,
            noteCorrect = isPitchMatch
        )

        val eventType = when (status) {
            StrikeAccuracyStatus.PERFECT,
            StrikeAccuracyStatus.EXCELLENT,
            StrikeAccuracyStatus.GOOD,
            StrikeAccuracyStatus.EARLY,
            StrikeAccuracyStatus.LATE -> AssessmentEventType.CORRECT
            StrikeAccuracyStatus.WRONG_NOTE -> AssessmentEventType.WRONG
            StrikeAccuracyStatus.UNKNOWN_NOTE -> AssessmentEventType.UNKNOWN
            StrikeAccuracyStatus.EXTRA_STRIKE -> AssessmentEventType.EXTRA
            StrikeAccuracyStatus.MISSED -> AssessmentEventType.MISSED
        }
        timeline.append(
            AssessmentTimelineEvent(
                eventId = "${event.id}-assessment",
                sessionId = event.sessionId,
                loopId = decision.target.identity.loopId,
                sequenceIndex = decision.target.identity.sequenceIndex,
                expectedNote = expectedNoteNumbers.firstOrNull(),
                detectedNote = event.detectedNote,
                eventType = eventType,
                expectedTimestampNanos = if (isWithinTargetWindow) targetNanos else null,
                detectedTimestampNanos = strikeTimestampNanos,
                deviationNanos = if (isWithinTargetWindow) deviationNanos else null,
                timingResult = feedback.timingResult,
                confidence = event.pitchConfidence
                    .let { pitch ->
                        if (event.signalQuality > 0f) {
                            (pitch * event.signalQuality).coerceIn(0f, 1f)
                        } else {
                            pitch
                        }
                    },
                targetId = decision.target.identity.targetId,
                source = event.source,
                durationNanos = event.durationNanos,
                isConsumed = isPitchMatch,
                assessmentSessionId = decision.target.identity.sessionId,
                patternId = decision.target.identity.patternId,
                obligationId = decision.consumedObligationId ?: "${decision.target.identity.targetId}-unmatched",
                expectedNotes = decision.target.identity.expectedNotes,
                measuredAmplitude = event.onsetStrength.coerceIn(0f, 1f),
                measuredVelocity = event.energy.coerceIn(0f, 1f),
                accentStrength = event.onsetStrength.coerceIn(0f, 1f),
                expectedTechnique = expectedNoteNumbers.firstOrNull()?.toTechnique(),
                detectedTechnique = event.detectedNote?.toTechnique(),
                targetNoteId = decision.consumedObligationId ?: "${decision.target.identity.targetId}-unmatched",
                subdivision = decision.target.identity.subdivisionIndex.toSubdivision(),
                beatPosition = decision.target.identity.beatIndex.toDouble() +
                    decision.target.identity.subdivisionIndex.toSubdivisionFraction(),
                expectedTimingWindow = timingPolicy.toToleranceProfile(),
                targetBpm = (60_000_000_000L / beatDurationNanos).toInt(),
                sessionValidity = AssessmentSessionValidity.VALID,
                signalQuality = event.audioQuality?.signalConfidence ?: event.signalQuality
            )
        )

        updateStatsWithFeedback(feedback, isPitchMatch, abs(deviationMs))
    }

    private fun expirePendingEvents(nowNanos: Long) {
        val finalized = targetMatcher.finalizeCandidates(targetRegistry.activeTargets(), nowNanos, timingPolicy)
        finalized.forEach { pendingTarget ->
            val target = targetRegistry.finalize(pendingTarget.identity.targetId) ?: return@forEach
            registerMissedNote(
                expectedNotes = target.remainingNotes.toList(),
                expectedTimestampNanos = target.identity.expectedTimestampNanos,
                targetId = target.identity.targetId,
                patternId = target.identity.patternId,
                sequenceIndex = target.identity.sequenceIndex,
                loopId = target.identity.loopId,
                targetBpm = (60_000_000_000L / beatDurationNanos).toInt(),
                subdivision = target.identity.subdivisionIndex.toSubdivision(),
                beatPosition = target.identity.beatIndex.toDouble() +
                    target.identity.subdivisionIndex.toSubdivisionFraction()
            )
        }
    }

    @Synchronized
    private fun finalizePendingEvents() {
        val finalized = targetMatcher.finalizeCandidates(targetRegistry.activeTargets(), Long.MAX_VALUE, timingPolicy)
        finalized.forEach { pendingTarget ->
            val target = targetRegistry.finalize(pendingTarget.identity.targetId) ?: return@forEach
            registerMissedNote(
                expectedNotes = target.remainingNotes.toList(),
                expectedTimestampNanos = target.identity.expectedTimestampNanos,
                targetId = target.identity.targetId,
                patternId = target.identity.patternId,
                sequenceIndex = target.identity.sequenceIndex,
                loopId = target.identity.loopId,
                targetBpm = (60_000_000_000L / beatDurationNanos).toInt(),
                subdivision = target.identity.subdivisionIndex.toSubdivision(),
                beatPosition = target.identity.beatIndex.toDouble() +
                    target.identity.subdivisionIndex.toSubdivisionFraction()
            )
        }
        expectedNoteEvents = emptyList()
        expectedBeatTargetTimestampNanos = 0L
    }

    private fun registerMissedNote(
        expectedNotes: List<Int>,
        expectedTimestampNanos: Long,
        targetId: String? = null,
        patternId: String? = null,
        sequenceIndex: Int = -1,
        loopId: String,
        targetBpm: Int,
        subdivision: com.example.model.Subdivision,
        beatPosition: Double
    ) {
        val feedback = StrikeFeedback(
            status = StrikeAccuracyStatus.MISSED,
            timingStatus = TimingAccuracyStatus.MISSED,
            deviationMs = 0L,
            expectedNotes = expectedNotes,
            detectedNote = null,
            detectedFreqHz = 0f,
            detectedCentsOffset = 0,
            confidence = 0f,
            expectedTimestampNanos = expectedTimestampNanos,
            monotonicTimestampNanos = clock.nowNanos(),
            noteCorrect = false
        )
        expectedNotes.forEachIndexed { index, noteNumber ->
            timeline.append(
                AssessmentTimelineEvent(
                    eventId = "$assessmentSessionId-missed-${expectedTimestampNanos}-$index",
                    sessionId = assessmentSessionId,
                    loopId = loopId,
                    sequenceIndex = sequenceIndex,
                    expectedNote = noteNumber,
                    detectedNote = null,
                    eventType = AssessmentEventType.MISSED,
                    expectedTimestampNanos = expectedTimestampNanos,
                    detectedTimestampNanos = null,
                    deviationNanos = null,
                    timingResult = null,
                    confidence = 0f,
                    targetId = targetId,
                    source = "evaluator",
                    durationNanos = null,
                    isConsumed = false,
                    assessmentSessionId = assessmentSessionId,
                    patternId = patternId,
                    obligationId = targetId?.let { "$it-$noteNumber" },
                    expectedNotes = expectedNotes.toSet(),
                    targetNoteId = targetId?.let { "$it-$noteNumber" },
                    subdivision = subdivision,
                    beatPosition = beatPosition,
                    expectedTimingWindow = timingPolicy.toToleranceProfile(),
                    targetBpm = targetBpm,
                    expectedTechnique = noteNumber.toTechnique(),
                    sessionValidity = AssessmentSessionValidity.VALID
                )
            )
        }
        _state.update {
            val newMissed = it.missedCount + expectedNotes.size
            val newTotal = it.totalStrikesEvaluated + expectedNotes.size

            val score = PracticeScoreCalculator.calculate(timeline)

            it.copy(
                missedCount = newMissed,
                totalStrikesEvaluated = newTotal,
                timingAccuracyPercentage = score.timingAccuracyPercentage,
                noteAccuracyPercentage = score.noteAccuracyPercentage,
                accuracyPercentage = score.overallAccuracyPercentage,
                consistencyPercentage = calculateConsistency(),
                lastFeedback = feedback,
                canonicalMetrics = PracticeScoreCalculator.calculateMetrics(timeline.snapshot())
            )
        }
    }

    private fun registerExtraStrike(pitch: DetectedPitchResult, timestampNanos: Long) {
        val feedback = StrikeFeedback(
            status = StrikeAccuracyStatus.EXTRA_STRIKE,
            timingStatus = TimingAccuracyStatus.UNKNOWN,
            deviationMs = 0L,
            expectedNotes = emptyList(),
            detectedNote = pitch.matchedNoteNumber,
            detectedFreqHz = pitch.frequencyHz,
            detectedCentsOffset = pitch.centsOffset,
            confidence = pitch.confidence,
            expectedTimestampNanos = 0L,
            monotonicTimestampNanos = timestampNanos,
            noteCorrect = false
        )
        timeline.append(
            AssessmentTimelineEvent(
                eventId = "extra-$timestampNanos-${pitch.frequencyHz}",
                sessionId = assessmentSessionId,
                loopId = null,
                sequenceIndex = -1,
                expectedNote = null,
                detectedNote = pitch.matchedNoteNumber,
                eventType = AssessmentEventType.EXTRA,
                expectedTimestampNanos = null,
                detectedTimestampNanos = timestampNanos,
                deviationNanos = null,
                timingResult = null,
                confidence = pitch.confidence,
                targetId = null,
                source = "evaluator",
                durationNanos = null,
                isConsumed = false
            )
        )
        _state.update {
            it.copy(
                totalStrikesEvaluated = it.totalStrikesEvaluated + 1,
                extraStrikeCount = it.extraStrikeCount + 1,
                lastFeedback = feedback,
                canonicalMetrics = PracticeScoreCalculator.calculateMetrics(timeline.snapshot())
            )
        }
    }

    private fun updateStatsWithFeedback(feedback: StrikeFeedback, isPitchMatch: Boolean, absDeviation: Long) {
        _state.update { current ->
            var perfect = current.perfectCount
            var excellent = current.excellentCount
            var good = current.goodCount
            var early = current.earlyCount
            var late = current.lateCount
            var wrong = current.wrongNoteCount
            var unknown = current.unknownNoteCount
            var wrongTimingPoints = current.wrongNoteTimingPoints

            when (feedback.status) {
                StrikeAccuracyStatus.PERFECT -> perfect++
                StrikeAccuracyStatus.EXCELLENT -> excellent++
                StrikeAccuracyStatus.GOOD -> good++
                StrikeAccuracyStatus.EARLY -> early++
                StrikeAccuracyStatus.LATE -> late++
                StrikeAccuracyStatus.WRONG_NOTE -> wrong++
                StrikeAccuracyStatus.UNKNOWN_NOTE -> unknown++
                    StrikeAccuracyStatus.EXTRA_STRIKE -> {}
                StrikeAccuracyStatus.MISSED -> {}
            }

            val totalEvaluated = perfect + good + early + late + wrong + unknown + current.missedCount + current.extraStrikeCount
            if (feedback.status == StrikeAccuracyStatus.WRONG_NOTE || feedback.status == StrikeAccuracyStatus.UNKNOWN_NOTE) {
                wrongTimingPoints += timingPoints(feedback.timingStatus)
            }
            val score = PracticeScoreCalculator.calculate(timeline)

            val avgDeviation = if (timingWindowList.isNotEmpty()) {
                timingWindowList.average().toFloat()
            } else 0f

            current.copy(
                totalStrikesEvaluated = totalEvaluated,
                perfectCount = perfect,
                excellentCount = excellent,
                goodCount = good,
                earlyCount = early,
                lateCount = late,
                wrongNoteCount = wrong,
                unknownNoteCount = unknown,
                wrongNoteTimingPoints = wrongTimingPoints,
                timingAccuracyPercentage = score.timingAccuracyPercentage,
                noteAccuracyPercentage = score.noteAccuracyPercentage,
                accuracyPercentage = score.overallAccuracyPercentage,
                consistencyPercentage = calculateConsistency(absDeviation),
                averageTimingDeviationMs = avgDeviation,
                lastFeedback = feedback,
                canonicalMetrics = PracticeScoreCalculator.calculateMetrics(timeline.snapshot())
            )
        }
    }

    private fun calculateConsistency(latestDeviation: Long? = null): Float {
        val deviations = timingWindowList.toMutableList()
        latestDeviation?.let { deviations += it }
        if (deviations.size < 2) return 100f
        val mean = deviations.average()
        val standardDeviation = kotlin.math.sqrt(
            deviations.map { (it - mean) * (it - mean) }.average()
        )
        return (100.0 - standardDeviation.coerceAtMost(100.0))
            .toFloat()
            .coerceIn(0f, 100f)
    }

    private fun timingPoints(status: TimingAccuracyStatus): Int = when (status) {
        TimingAccuracyStatus.PERFECT -> 100
        TimingAccuracyStatus.EXCELLENT -> 90
        TimingAccuracyStatus.GOOD -> 80
        TimingAccuracyStatus.EARLY, TimingAccuracyStatus.LATE -> 50
        TimingAccuracyStatus.MISSED, TimingAccuracyStatus.UNKNOWN -> 0
    }

    private fun Int.toTechnique(): com.example.model.HandpanTechnique = when (this) {
        com.example.model.NotePitchConfig.NOTE_DING -> com.example.model.HandpanTechnique.DING
        com.example.model.NotePitchConfig.NOTE_SLAP -> com.example.model.HandpanTechnique.SLAP
        else -> com.example.model.HandpanTechnique.TONE
    }

    private fun Int.toSubdivision(): com.example.model.Subdivision = when {
        this == 0 -> com.example.model.Subdivision.QUARTER
        this % 8 == 0 -> com.example.model.Subdivision.EIGHTH
        this % 4 == 0 -> com.example.model.Subdivision.SIXTEENTH
        else -> com.example.model.Subdivision.TRIPLET
    }

    private fun Int.toSubdivisionFraction(): Double = when {
        this == 0 -> 0.0
        else -> this.toDouble() / 16.0
    }

    private fun TimingPolicy.toToleranceProfile(): com.example.model.TimingToleranceProfile =
        com.example.model.TimingToleranceProfile(
            perfectWindowNanos = perfectWindowNanos,
            excellentWindowNanos = excellentWindowNanos,
            goodWindowNanos = goodWindowNanos,
            missWindowNanos = maxOf(earlyWindowNanos, lateWindowNanos)
        )
}
