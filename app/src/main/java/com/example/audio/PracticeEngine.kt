package com.example.audio

import com.example.model.HandpanPattern
import com.example.model.NoteEvent
import com.example.model.PracticeInputMode
import com.example.model.PracticeMode
import com.example.model.PracticeSessionContext
import com.example.model.PracticeSessionLifecycle
import com.example.util.HapticHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal object AssessmentRecoveryCursor {
    fun startBeat(events: List<com.example.model.AssessmentTimelineEvent>): Double {
        val resolvedObligations = events.asSequence()
            .filter { it.isConsumed || it.eventType == com.example.model.AssessmentEventType.MISSED }
            .mapNotNull { it.obligationId }
            .toSet()
        val pendingBeat = events.asSequence()
            .filter { it.eventType == com.example.model.AssessmentEventType.EXPECTED }
            .filter { event ->
                val obligationKey = event.obligationId ?: event.eventId
                obligationKey !in resolvedObligations && !event.isConsumed
            }
            .sortedWith(compareBy<com.example.model.AssessmentTimelineEvent> { it.beatPosition ?: Double.POSITIVE_INFINITY }
                .thenBy { it.eventOrdinal })
            .mapNotNull { it.beatPosition }
            .firstOrNull()
        return if (pendingBeat != null) {
            (pendingBeat - PatternScheduler.BEAT_EPSILON).coerceAtLeast(0.0)
        } else {
            events.asSequence()
                .sortedBy { it.eventOrdinal }
                .mapNotNull { it.beatPosition }
                .lastOrNull() ?: 0.0
        }
    }
}

data class PracticeUiState(
    val pattern: HandpanPattern? = null,
    val phase: PracticePhase = PracticePhase.IDLE,
    val timelinePosition: PracticeTimelinePosition? = null,
    val targetState: PracticeTargetState? = null,
    val previewEnabled: Boolean = true,
    val previewBeat: Int = 0,
    val previewBeatCount: Int = 0,
    val isPlaying: Boolean = false,
    val isCountIn: Boolean = false,
    val countInBeat: Int = 1,
    val currentBar: Int = 1,
    val currentBeatInBar: Double = 1.0,
    val currentBeatAbsolute: Double = 0.0,
    val currentNoteIndex: Int = -1,
    val activeEvents: List<NoteEvent> = emptyList(),
    val activeNoteEvent: NoteEvent? = null,
    val activeNoteNumber: Int = -1,
    val bpm: Int = 70,
    val speedMultiplier: Float = 1.0f,
    val isLoopEnabled: Boolean = true,
    val loopStartBar: Int = 1,
    val loopEndBar: Int = 1,
    val mode: PracticeMode = PracticeMode.FOLLOW,
    val inputMode: PracticeInputMode = PracticeInputMode.REAL_HANDPAN,
    val metronomeEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val countInEnabled: Boolean = true,
    val hapticEnabled: Boolean = true,
    val totalRoundsCompleted: Int = 0,
    // Speed Ladder
    val speedLadderEnabled: Boolean = false,
    val ladderBpmIncrement: Int = 4,
    val ladderRoundsPerStep: Int = 2,
    val ladderTargetBpm: Int = 110,
    val isStandModeFullscreen: Boolean = false,
    val acousticAssessmentEnabled: Boolean = true
) {
    val effectiveBpm: Int
        get() = (bpm * speedMultiplier).toInt().coerceIn(30, 300)
}

class PracticeEngine(
    private val audioEngine: AudioEngine,
    private val hapticHelper: HapticHelper? = null,
    private val clock: PracticeClock = PracticeClock.Default,
    val acousticEvaluator: AcousticPracticeEvaluator = AcousticPracticeEvaluator(clock = clock)
) {
    private val engineJob = SupervisorJob()
    private val engineScope = CoroutineScope(Dispatchers.Default + engineJob)

    private val _uiState = MutableStateFlow(PracticeUiState())
    val uiState: StateFlow<PracticeUiState> = _uiState.asStateFlow()

    private var playbackJob: Job? = null
    private var sessionStartedNanos: Long = 0L
    private var resumeFromBeat: Double? = null
    private var resumePhase: PracticePhase? = null
    private var practiceTimeline: PracticeTimeline? = null
    private var sessionContext: PracticeSessionContext? = null
    private var restartCount: Int = 0
    private var finalizedCallbackSessionId: String? = null
    private var timelineStartNanos: Long? = null
    private var recoveredTimelinePending = false
    private val deadlineScheduler = DeadlineScheduler(clock)
    private val timelineSubscription = acousticEvaluator.timeline.subscribe { event ->
        onAssessmentTimelineEvent?.invoke(event)
    }

    var onRoundCompleted: ((HandpanPattern, Int, Int) -> Unit)? = null
    var onAssessmentFinalized: ((com.example.model.FinalizedAssessment) -> Unit)? = null
    var onAssessmentStarted: ((PracticeSessionContext, Int, PracticeInputMode) -> Unit)? = null
    var onAssessmentLifecycleChanged: ((PracticeSessionContext, PracticeSessionLifecycle) -> Unit)? = null
    var onAssessmentTimelineEvent: ((com.example.model.AssessmentTimelineEvent) -> Unit)? = null
    var onTimelineBeat: ((PracticeTimelinePosition, List<NoteEvent>, Long) -> Unit)? = null

    fun loadPattern(pattern: HandpanPattern) {
        stop()
        practiceTimeline = PracticeTimeline(pattern, pattern.bpm)
        timelineStartNanos = null
        _uiState.update {
            it.copy(
                pattern = pattern,
                phase = PracticePhase.READY,
                timelinePosition = practiceTimeline?.positionAtBeat(0.0),
                targetState = practiceTimeline?.positionAtBeat(0.0)?.let {
                    PracticeTargetState.from(it, PracticePhase.READY)
                },
                previewBeat = 0,
                previewBeatCount = PracticePreparation.previewBeatCount(pattern),
                bpm = pattern.bpm,
                currentBar = 1,
                currentBeatInBar = 1.0,
                currentBeatAbsolute = 0.0,
                currentNoteIndex = -1,
                activeEvents = emptyList(),
                activeNoteEvent = null,
                activeNoteNumber = -1,
                loopStartBar = 1,
                loopEndBar = pattern.bars,
                totalRoundsCompleted = 0
            )
        }
        restartCount = 0
    }

    fun restoreAssessment(
        pattern: HandpanPattern,
        context: PracticeSessionContext,
        timeline: com.example.model.AssessmentTimeline,
        inputMode: PracticeInputMode,
        bpm: Int
    ) {
        loadPattern(pattern)
        sessionContext = context
        restartCount = context.restartCount
        val restoredBeat = recoveryStartBeat(timeline)
        resumeFromBeat = restoredBeat
        setInputMode(inputMode)
        _uiState.update {
            it.copy(
                phase = PracticePhase.PAUSED,
                bpm = bpm,
                isPlaying = false,
                currentBeatAbsolute = restoredBeat,
                timelinePosition = practiceTimeline?.positionAtBeat(restoredBeat),
                acousticAssessmentEnabled = inputMode == PracticeInputMode.REAL_HANDPAN
            )
        }
        acousticEvaluator.timeline.restore(context.sessionId, timeline.snapshot())
        recoveredTimelinePending = true
    }

    private fun recoveryStartBeat(timeline: com.example.model.AssessmentTimeline): Double {
        return AssessmentRecoveryCursor.startBeat(timeline.snapshot())
    }

    fun togglePlay() {
        if (_uiState.value.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    fun play() {
        val pattern = _uiState.value.pattern ?: return
        if (_uiState.value.isPlaying) return
        val isResuming = _uiState.value.phase == PracticePhase.PAUSED
        val startBeat = if (isResuming) resumeFromBeat else null

        _uiState.update {
            it.copy(
                isPlaying = true,
                phase = when {
                    isResuming -> PracticePhase.RUNNING
                    it.previewEnabled -> PracticePhase.PREVIEW
                    it.countInEnabled -> PracticePhase.COUNTDOWN
                    else -> PracticePhase.RUNNING
                }
            )
        }
        sessionStartedNanos = clock.nowNanos()
        resumeFromBeat = null
        resumePhase = null

        if (isResuming) {
            acousticEvaluator.resumeAssessment()
            sessionContext?.let { onAssessmentLifecycleChanged?.invoke(it, PracticeSessionLifecycle.ACTIVE) }
        }

        playbackJob = engineScope.launch {
            runPracticeLoop(pattern, startBeat)
        }
    }

    fun pause() {
        if (_uiState.value.phase == PracticePhase.RUNNING ||
            _uiState.value.phase == PracticePhase.COUNTDOWN ||
            _uiState.value.phase == PracticePhase.PREVIEW
        ) {
            resumeFromBeat = _uiState.value.currentBeatAbsolute
        }
        if (_uiState.value.phase == PracticePhase.PREVIEW || _uiState.value.phase == PracticePhase.COUNTDOWN) {
            resumePhase = _uiState.value.phase
        }
        playbackJob?.cancel()
        playbackJob = null
        acousticEvaluator.pauseAssessment()
        acousticEvaluator.setPracticeRunning(false)
        sessionContext?.let { onAssessmentLifecycleChanged?.invoke(it, PracticeSessionLifecycle.PAUSED) }
        _uiState.update {
            it.copy(
                isPlaying = false,
                phase = if (it.pattern == null) PracticePhase.IDLE else PracticePhase.PAUSED,
                isCountIn = false,
                activeNoteNumber = -1,
                activeEvents = emptyList(),
                activeNoteEvent = null
            )
        }
    }

    fun stop() {
        pause()
        acousticEvaluator.stopAssessment(showSummary = false)
        notifyAssessmentFinalized()
        acousticEvaluator.setPracticeRunning(false)
        sessionContext = null
        resumeFromBeat = null
        resumePhase = null
        timelineStartNanos = null
        _uiState.update {
            it.copy(
                phase = if (it.pattern == null) PracticePhase.IDLE else PracticePhase.READY,
                currentBar = 1,
                currentBeatInBar = 1.0,
                currentBeatAbsolute = 0.0,
                currentNoteIndex = -1,
                timelinePosition = it.timelinePosition?.copy(
                    elapsedNanos = 0L,
                    elapsedMs = 0L,
                    currentBeat = 0.0,
                    currentBeatInBar = 1.0,
                    beatNumber = 1,
                    barNumber = 1,
                    currentNoteIndex = -1,
                    currentNote = null,
                    nextNote = it.pattern?.activeNotes?.firstOrNull(),
                    beatProgress = 0f,
                    patternProgress = 0f,
                    countdownRemaining = 0
                ),
                targetState = it.timelinePosition?.copy(
                    elapsedNanos = 0L,
                    elapsedMs = 0L,
                    currentBeat = 0.0,
                    currentBeatInBar = 1.0,
                    beatNumber = 1,
                    barNumber = 1,
                    currentNoteIndex = -1,
                    currentNote = null,
                    nextNote = it.pattern?.activeNotes?.firstOrNull(),
                    beatProgress = 0f,
                    barProgress = 0f,
                    patternProgress = 0f,
                    countdownRemaining = 0
                )?.let { position -> PracticeTargetState.from(position, PracticePhase.READY) },
                activeEvents = emptyList(),
                activeNoteEvent = null,
                activeNoteNumber = -1
            )
        }
    }

    fun restart() {
        restartCount++
        _uiState.update { it.copy(phase = PracticePhase.RESTARTING) }
        stop()
        play()
    }

    fun release() {
        stop()
        timelineSubscription.close()
        engineJob.cancel()
    }

    private suspend fun runPracticeLoop(pattern: HandpanPattern, startBeat: Double? = null) {
        val beatsPerBar = pattern.timeSignature.beatsPerBar
        if (practiceTimeline == null || practiceTimeline?.bpm != _uiState.value.effectiveBpm) {
            practiceTimeline = PracticeTimeline(pattern, _uiState.value.effectiveBpm)
        }

        if ((startBeat == null || resumePhase == PracticePhase.PREVIEW) && _uiState.value.previewEnabled) {
            runPreview(pattern, beatsPerBar, if (resumePhase == PracticePhase.PREVIEW) startBeat else null)
            if (playbackJob?.isActive != true) return
        }

        // 1. Count-in with unified monotonic PracticeClock
        if (_uiState.value.countInEnabled && startBeat == null) {
            _uiState.update { it.copy(phase = PracticePhase.COUNTDOWN, isCountIn = true, countInBeat = 1) }

            val countInStartNanos = clock.nowNanos()
            val effectiveBpm = _uiState.value.effectiveBpm
            val beatIntervalNanos = MusicalTiming.beatDurationNanos(effectiveBpm)

            for (c in 1..beatsPerBar) {
                _uiState.update { it.copy(countInBeat = c) }
                val countdownPosition = practiceTimeline?.positionAtBeat(
                    beat = 0.0,
                    countdownRemaining = beatsPerBar - c + 1
                )
                _uiState.update {
                    it.copy(
                        timelinePosition = countdownPosition,
                        targetState = countdownPosition?.let { position ->
                            PracticeTargetState.from(position, PracticePhase.COUNTDOWN)
                        }
                    )
                }
                val isFirst = (c == 1)

                audioEngine.playMetronomeClick(isAccent = isFirst)
                triggerHaptic(isAccent = isFirst)

                val nextTargetNanos = countInStartNanos + (c * beatIntervalNanos).toLong()
                deadlineScheduler.await(nextTargetNanos)
            }

            _uiState.update { it.copy(phase = PracticePhase.RUNNING, isCountIn = false) }
            timelineStartNanos = clock.nowNanos()
            startAcousticAssessment(pattern)
            acousticEvaluator.setPracticeRunning(true)
        } else if (startBeat == null) {
            _uiState.update { it.copy(phase = PracticePhase.RUNNING, isCountIn = false) }
            timelineStartNanos = clock.nowNanos()
            startAcousticAssessment(pattern)
            acousticEvaluator.setPracticeRunning(true)
        } else {
            _uiState.update { it.copy(phase = PracticePhase.RUNNING, isCountIn = false) }
            acousticEvaluator.setPracticeRunning(true)
        }

        if (timelineStartNanos == null) {
            val resumeOffset = startBeat?.let {
                MusicalTiming.beatToNanos(it, _uiState.value.effectiveBpm, pattern.timeSignature)
            } ?: 0L
            timelineStartNanos = clock.nowNanos() - resumeOffset
        }

        // 2. Main Practice Execution using pre-indexed slices
        var currentLoopIteration = 0
        var pendingStartBeat = startBeat

        while (playbackJob?.isActive == true) {
            val currentState = _uiState.value
            val currentBpm = currentState.effectiveBpm
            val startBar = if (currentState.isLoopEnabled) currentState.loopStartBar else 1
            val endBar = if (currentState.isLoopEnabled) currentState.loopEndBar else pattern.bars
            val loopStartBeat = ((startBar - 1) * beatsPerBar).toDouble()
            val loopDurationNanos = MusicalTiming.beatToNanos(
                ((endBar - startBar + 1) * beatsPerBar).toDouble(),
                currentBpm,
                pattern.timeSignature
            )
            val loopStartNanos = (timelineStartNanos ?: clock.nowNanos()) +
                (currentLoopIteration * loopDurationNanos)

            // Build lookahead pre-indexed schedule slices for the current bar loop range
            val schedule = PatternScheduler.buildSchedule(
                events = pattern.events,
                beatsPerBar = beatsPerBar,
                totalBars = pattern.bars,
                startBar = startBar,
                endBar = endBar,
                timeSignature = pattern.timeSignature,
                        assessmentSessionId = sessionContext?.sessionId ?: "practice-${pattern.id}-$sessionStartedNanos",
                patternId = pattern.id,
                loopIndex = currentLoopIteration,
                scheduleStartTimestampNanos = loopStartNanos,
                bpm = currentBpm
            ).filter { slice ->
                pendingStartBeat == null ||
                    slice.beatPosition > pendingStartBeat + PatternScheduler.BEAT_EPSILON
            }
            pendingStartBeat = null

            if (schedule.isEmpty()) {
                deadlineScheduler.await(clock.nowNanos() + 100_000_000L)
                continue
            }

            for (slice in schedule) {
                val sliceOffsetBeats = slice.beatPosition - loopStartBeat
                val targetSliceNanos = loopStartNanos +
                    MusicalTiming.beatToNanos(sliceOffsetBeats, currentBpm, pattern.timeSignature)

                if (acousticEvaluator.state.value.isEnabled) {
                    slice.target?.let(acousticEvaluator::notifyExpectedTarget)
                }

                // Wait until monotonic timestamp for this slice
                val waitNanos = targetSliceNanos - clock.nowNanos()
                if (waitNanos > 0) {
                    deadlineScheduler.await(targetSliceNanos)
                }

                if (playbackJob?.isActive != true) break

                // Metronome click on whole beats / downbeats
                // Play all events in slice (supports multiple simultaneous notes or single hits)
                val nonRestEvents = slice.events.filter { !it.isRest }
                // In REAL_HANDPAN mode, virtual handpan notes MUST be MUTED to prevent speaker feedback loop into the microphone
                val isVirtualAudioMutedForRealHandpan = (currentState.inputMode == PracticeInputMode.REAL_HANDPAN)
                if (nonRestEvents.isNotEmpty() && currentState.soundEnabled && !isVirtualAudioMutedForRealHandpan) {
                    // Practice mode check: in Challenge mode, mute audio so user tests muscle memory
                    if (currentState.mode != PracticeMode.CHALLENGE) {
                        for (ev in nonRestEvents) {
                            audioEngine.playNote(
                                noteNumber = ev.noteNumber,
                                accent = ev.accent,
                                velocity = ev.velocity
                            )
                        }
                    }
                }

                // Haptic feedback
                if (nonRestEvents.isNotEmpty()) {
                    triggerHaptic(isAccent = nonRestEvents.any { it.accent } || slice.isDownbeat)
                } else if (currentState.metronomeEnabled && slice.isDownbeat) {
                    triggerHaptic(isAccent = true)
                }

                val primaryEvent = slice.events.firstOrNull()
                val noteIdx = if (primaryEvent != null) pattern.events.indexOf(primaryEvent) else -1
                val timelinePosition = practiceTimeline?.positionAtLoopElapsed(
                    elapsedNanos = clock.nowNanos() - loopStartNanos,
                    loopStartBeat = loopStartBeat,
                    loopEndBeat = (endBar * beatsPerBar).toDouble()
                )

                // Update UI state for visual tracking
                _uiState.update {
                    it.copy(
                        timelinePosition = timelinePosition,
                        targetState = timelinePosition?.let {
                            PracticeTargetState.from(it, PracticePhase.RUNNING)
                        },
                        currentBar = timelinePosition?.barNumber ?: slice.barIndex,
                        currentBeatInBar = timelinePosition?.currentBeatInBar ?: slice.beatInBar,
                        currentBeatAbsolute = timelinePosition?.currentBeat ?: slice.beatPosition,
                        currentNoteIndex = timelinePosition?.currentNoteIndex ?: noteIdx,
                        activeEvents = slice.events,
                        activeNoteEvent = primaryEvent,
                        activeNoteNumber = timelinePosition?.currentNote?.noteNumber ?: -1
                    )
                }
                timelinePosition?.let {
                    onTimelineBeat?.invoke(it, slice.events, targetSliceNanos)
                }

            }

            currentLoopIteration++
            _uiState.update { it.copy(totalRoundsCompleted = currentLoopIteration) }
            onRoundCompleted?.invoke(
                pattern,
                currentState.effectiveBpm,
                ((clock.nowNanos() - sessionStartedNanos) / 1_000_000_000L).toInt().coerceAtLeast(0)
            )

            // Speed Ladder Progression
            if (currentState.speedLadderEnabled && currentLoopIteration > 0 &&
                currentLoopIteration % currentState.ladderRoundsPerStep == 0
            ) {
                val nextBpm = (currentState.bpm + currentState.ladderBpmIncrement)
                    .coerceAtMost(currentState.ladderTargetBpm)
                if (nextBpm != currentState.bpm) {
                    _uiState.update { it.copy(bpm = nextBpm) }
                    acousticEvaluator.setBpm(nextBpm)
                }
            }

            if (!currentState.isLoopEnabled && endBar == pattern.bars) {
                playbackJob = null
                acousticEvaluator.stopAssessment(showSummary = true)
                notifyAssessmentFinalized()
                sessionContext = null
                _uiState.update {
                    it.copy(
                        phase = PracticePhase.COMPLETED,
                        isPlaying = false,
                        isCountIn = false,
                        targetState = it.targetState?.copy(phase = PracticePhase.COMPLETED),
                        activeEvents = emptyList(),
                        activeNoteEvent = null,
                        activeNoteNumber = -1
                    )
                }
                break
            }
        }
    }

    private suspend fun runPreview(pattern: HandpanPattern, beatsPerBar: Int, resumeBeat: Double? = null) {
        _uiState.update { it.copy(phase = PracticePhase.PREVIEW, isCountIn = false, previewBeat = 0) }
        val previewBeats = PracticePreparation.previewBeats(pattern)
        val schedule = PatternScheduler.buildSchedule(
            events = pattern.events,
            beatsPerBar = beatsPerBar,
            totalBars = pattern.bars,
            startBar = 1,
            endBar = pattern.bars,
            timeSignature = pattern.timeSignature,
            assessmentSessionId = sessionContext?.sessionId ?: "preview-only",
            patternId = pattern.id,
            scheduleStartTimestampNanos = clock.nowNanos(),
            bpm = _uiState.value.effectiveBpm
        ).filter {
            it.beatPosition <= previewBeats + PatternScheduler.BEAT_EPSILON &&
                (resumeBeat == null || it.beatPosition > resumeBeat + PatternScheduler.BEAT_EPSILON)
        }

        val previewStartNanos = clock.nowNanos()
        for (slice in schedule) {
            if (playbackJob?.isActive != true) return
            val targetNanos = previewStartNanos + MusicalTiming.beatToNanos(
                slice.beatPosition,
                _uiState.value.effectiveBpm,
                pattern.timeSignature
            )
            deadlineScheduler.await(targetNanos)
            if (playbackJob?.isActive != true) return

            val notes = slice.events.filter { !it.isRest }
            if (_uiState.value.soundEnabled && _uiState.value.mode != PracticeMode.CHALLENGE) {
                notes.forEach { event ->
                    audioEngine.playNote(event.noteNumber, event.accent, event.velocity)
                }
            }
            if (notes.isNotEmpty() || slice.isDownbeat) {
                triggerHaptic(notes.any { it.accent } || slice.isDownbeat)
            }
            val cursor = practiceTimeline?.positionAtBeat(slice.beatPosition)
            _uiState.update {
                it.copy(
                    phase = PracticePhase.PREVIEW,
                    targetState = cursor?.let { position ->
                        PracticeTargetState.from(position, PracticePhase.PREVIEW)
                    },
                    previewBeat = cursor?.beatNumber ?: (slice.beatPosition.toInt() + 1),
                    timelinePosition = cursor,
                    currentBar = cursor?.barNumber ?: slice.barIndex,
                    currentBeatInBar = cursor?.currentBeatInBar ?: slice.beatInBar,
                    currentBeatAbsolute = cursor?.currentBeat ?: slice.beatPosition,
                    currentNoteIndex = cursor?.currentNoteIndex ?: -1,
                    activeEvents = slice.events,
                    activeNoteEvent = slice.events.firstOrNull(),
                    activeNoteNumber = cursor?.currentNote?.noteNumber ?: -1
                )
            }
        }
        _uiState.update { it.copy(phase = if (it.countInEnabled) PracticePhase.COUNTDOWN else PracticePhase.RUNNING) }
    }

    private fun triggerHaptic(isAccent: Boolean) {
        if (!_uiState.value.hapticEnabled) return
        hapticHelper?.performClick(isAccent = isAccent)
    }

    private fun startAcousticAssessment(pattern: HandpanPattern) {
        if (!acousticEvaluator.state.value.isEnabled) return
        val isNewSession = sessionContext == null
        val context = sessionContext ?: PracticeSessionContext.start(
            patternId = pattern.id,
            startTimestampNanos = clock.nowNanos(),
            restartCount = restartCount
        ).also { sessionContext = it }
        if (isNewSession) {
            onAssessmentStarted?.invoke(context, _uiState.value.effectiveBpm, _uiState.value.inputMode)
        }
        acousticEvaluator.startAssessment(
            context = context,
            pattern = pattern,
            scaleConfig = audioEngine.getPitchConfig(),
            bpm = _uiState.value.effectiveBpm,
            preserveTimeline = recoveredTimelinePending
        )
        recoveredTimelinePending = false
    }

    @Synchronized
    private fun notifyAssessmentFinalized() {
        acousticEvaluator.finalizedAssessment(System.currentTimeMillis())?.let { assessment ->
            if (assessment.sessionId != finalizedCallbackSessionId) {
                finalizedCallbackSessionId = assessment.sessionId
                onAssessmentFinalized?.invoke(assessment)
            }
        }
    }

    fun setBpm(bpm: Int) {
        val nextBpm = bpm.coerceIn(40, 240)
        rebuildTimelinePreservingPosition(nextBpm)
        _uiState.update { it.copy(bpm = nextBpm) }
        acousticEvaluator.setBpm(nextBpm)
    }

    fun setSpeedMultiplier(multiplier: Float) {
        val nextMultiplier = multiplier.coerceIn(0.25f, 3.0f)
        val nextBpm = (_uiState.value.bpm * nextMultiplier).toInt().coerceIn(30, 300)
        rebuildTimelinePreservingPosition(nextBpm)
        _uiState.update { it.copy(speedMultiplier = nextMultiplier) }
        acousticEvaluator.setBpm(nextBpm)
    }

    fun setPracticeMode(mode: PracticeMode) {
        _uiState.update { it.copy(mode = mode) }
    }

    fun playInputNote(noteNumber: Int, accent: Boolean = false) {
        if (_uiState.value.inputMode != PracticeInputMode.VIRTUAL_HANDPAN) return
        audioEngine.playNote(
            noteNumber = noteNumber,
            accent = accent,
            velocity = if (accent) 1.0f else 0.85f
        )
    }

    fun toggleMetronome() {
        _uiState.update { it.copy(metronomeEnabled = !it.metronomeEnabled) }
    }

    fun toggleSound() {
        _uiState.update { it.copy(soundEnabled = !it.soundEnabled) }
    }

    fun toggleCountIn() {
        _uiState.update { it.copy(countInEnabled = !it.countInEnabled) }
    }

    fun setPreviewEnabled(enabled: Boolean) {
        _uiState.update { it.copy(previewEnabled = enabled) }
    }

    fun toggleLoop() {
        _uiState.update { it.copy(isLoopEnabled = !it.isLoopEnabled) }
    }

    fun toggleSpeedLadder() {
        _uiState.update { it.copy(speedLadderEnabled = !it.speedLadderEnabled) }
    }

    fun configureSpeedLadder(increment: Int, roundsPerStep: Int, targetBpm: Int) {
        _uiState.update {
            it.copy(
                ladderBpmIncrement = increment.coerceIn(1, 20),
                ladderRoundsPerStep = roundsPerStep.coerceIn(1, 10),
                ladderTargetBpm = targetBpm.coerceIn(50, 240)
            )
        }
    }

    fun toggleStandMode() {
        _uiState.update { it.copy(isStandModeFullscreen = !it.isStandModeFullscreen) }
    }

    fun toggleAcousticAssessment() {
        val next = !_uiState.value.acousticAssessmentEnabled
        _uiState.update { it.copy(acousticAssessmentEnabled = next) }
        acousticEvaluator.toggleEnabled()
        if (next && _uiState.value.isPlaying) {
            _uiState.value.pattern?.let {
                startAcousticAssessment(it)
            }
        }
    }

    fun setAcousticAssessmentEnabled(enabled: Boolean) {
        _uiState.update { it.copy(acousticAssessmentEnabled = enabled) }
        if (enabled) {
            if (!_uiState.value.isPlaying) {
                acousticEvaluator.setEnabled(true)
            } else {
                _uiState.value.pattern?.let {
                startAcousticAssessment(it)
                }
            }
        } else {
            acousticEvaluator.stopAssessment(showSummary = false)
        }
    }

    fun setInputMode(mode: PracticeInputMode) {
        _uiState.update { it.copy(inputMode = mode) }
        if (mode == PracticeInputMode.REAL_HANDPAN) {
            setAcousticAssessmentEnabled(true)
        } else {
            setAcousticAssessmentEnabled(false)
        }
    }

    fun setLoopRange(startBar: Int, endBar: Int) {
        val totalBars = _uiState.value.pattern?.bars ?: 1
        val s = startBar.coerceIn(1, totalBars)
        val e = endBar.coerceIn(s, totalBars)
        _uiState.update { it.copy(loopStartBar = s, loopEndBar = e) }
    }

    private fun rebuildTimelinePreservingPosition(nextBpm: Int) {
        val pattern = _uiState.value.pattern ?: return
        val currentBeat = _uiState.value.timelinePosition?.currentBeat ?: 0.0
        practiceTimeline = PracticeTimeline(pattern, nextBpm)
        if (timelineStartNanos != null) {
            timelineStartNanos = clock.nowNanos() -
                MusicalTiming.beatToNanos(currentBeat, nextBpm, pattern.timeSignature)
        }
        _uiState.update {
            it.copy(timelinePosition = practiceTimeline?.positionAtBeat(currentBeat))
        }
    }
}
