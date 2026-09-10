package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AmbienceEngine
import com.example.audio.AcousticPracticeEvaluator
import com.example.audio.AudioAnalysisSession
import com.example.model.AssessmentTimeline
import com.example.audio.AudioEngine
import com.example.audio.CustomSampleRecorder
import com.example.audio.MetronomeEngine
import com.example.audio.PerformanceRecorder
import com.example.audio.PracticeClock
import com.example.audio.PracticeEngine
import com.example.data.local.AppDatabase
import com.example.data.repository.HandpanRepository
import com.example.data.local.EvidenceEntity
import com.example.model.HandpanPattern
import com.example.model.InstrumentProfile
import com.example.model.NotationSystem
import com.example.model.NotePitchConfig
import com.example.model.PatternCategory
import com.example.model.PracticeInputMode
import com.example.model.PracticeMode
import com.example.model.PracticeProgress
import com.example.model.LearningRecommendation
import com.example.model.PersonalizationEngine
import com.example.model.AdaptationRequest
import com.example.model.ScoreIngestionResult
import com.example.model.ScoreIngestionUseCase
import com.example.model.ScoreSource
import com.example.util.HapticHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking

enum class AppScreen {
    HOME,
    EXERCISE_LIBRARY,
    PRACTICE,
    METRONOME,
    RHYTHM_TRAINER,
    PATTERN_EDITOR,
    SETTINGS
}

data class AppUiState(
    val currentScreen: AppScreen = AppScreen.HOME,
    val selectedCategory: PatternCategory = PatternCategory.BEGINNER,
    val currentPattern: HandpanPattern? = null,
    val
 defaultPracticeInputMode: PracticeInputMode = PracticeInputMode.REAL_HANDPAN,
    val showOnboarding: Boolean = false,
    val masterVolume: Float = 1.0f,
    val metronomeVolume: Float = 0.8f,
    val currentScaleConfig: NotePitchConfig = NotePitchConfig(),
    val currentInstrumentProfile: InstrumentProfile = InstrumentProfile.DEFAULT_D_KURD_9,
    val preferredNotationSystem: NotationSystem = NotationSystem.NUMERIC,
    val isHapticEnabled: Boolean = true,
    val darkTheme: Boolean = true,
    val lastTappedNote: Int = 0,
    val showSamplerDialog: Boolean = false,
    val selectedSamplerNote: Int = 0,
    val isRecordingSample: Boolean = false,
    val recordingAmplitude: Float = 0f,
    val customSamplesMap: Map<Int, Boolean> = emptyMap(),
    val sampleSuccessMessage: String? = null,
    val showImportDialog: Boolean = false,
    val patternToExport: HandpanPattern? = null,
    val showGuideDialog: Boolean = false,
    val showScaleDialog: Boolean = false,
    val showBackingTracksDialog: Boolean = false,
    val showRecorderDialog: Boolean = false,
    val showLessonStudioDialog: Boolean = false
)

data class TranscriptionUiState(
    val isAnalyzing: Boolean = false,
    val result: com.example.audio.TranscriptionResult? = null,
    val errorMessage: String? = null
)

class HandpanViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application.applicationContext
    private val database = AppDatabase.getDatabase(context)
    val repository = HandpanRepository(
        database.patternDao(),
        database.practiceProgressDao(),
        database.lessonProgressDao(),
        database.recordingTrackDao()
        , database
    )
    private val assessmentRecoveryCoordinator =
        com.example.data.repository.AssessmentRecoveryCoordinator(repository)
    val scoreIngestionUseCase = ScoreIngestionUseCase(store = repository)

    val hapticHelper = HapticHelper(context)
    val audioEngine = AudioEngine(context)
    private val practiceClock: PracticeClock = PracticeClock.Default
    val metronomeEngine = MetronomeEngine(audioEngine, hapticHelper, practiceClock)
    private val audioAnalysisSession = AudioAnalysisSession()
    private val assessmentTimeline = AssessmentTimeline()
    val practiceEngine = PracticeEngine(
        audioEngine = audioEngine,
        hapticHelper = hapticHelper,
        clock = practiceClock,
        acousticEvaluator = AcousticPracticeEvaluator(
            clock = practiceClock,
            analysisSession = audioAnalysisSession,
            ownsAnalysisSession = false,
            timeline = assessmentTimeline
        )
    )

    init {
        practiceEngine.onTimelineBeat = { position, _, beatStartNanos ->
            metronomeEngine.consumePracticeBeat(
                com.example.audio.PracticeBeatEvent(
                    beatNumber = position.beatNumber,
                    barNumber = position.barNumber,
                    beatStartNanos = beatStartNanos,
                    beatProgress = position.beatProgress,
                    isDownbeat = position.isDownbeat,
                    bpm = position.bpm
                )
            )
        }
    }
    val ambienceEngine = AmbienceEngine()
    val performanceRecorder = PerformanceRecorder(
        context = context,
        audioEngine = audioEngine,
        repository = repository,
        analysisSession = audioAnalysisSession,
        ownsAnalysisSession = false,
        timeline = assessmentTimeline
    )
    private val customSampleRecorder = CustomSampleRecorder(context)

    private val _appUiState = MutableStateFlow(AppUiState())
    val appUiState: StateFlow<AppUiState> = _appUiState.asStateFlow()
    private val _transcriptionState = MutableStateFlow(TranscriptionUiState())
    val transcriptionState: StateFlow<TranscriptionUiState> = _transcriptionState.asStateFlow()
    private val _recoverableAssessments = MutableStateFlow<List<com.example.data.local.AssessmentSessionEntity>>(emptyList())
    val recoverableAssessments: StateFlow<List<com.example.data.local.AssessmentSessionEntity>> =
        _recoverableAssessments.asStateFlow()
    private var transcriptionJob: kotlinx.coroutines.Job? = null
    private val assessmentWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _assessmentPersistenceFailure = MutableStateFlow<Throwable?>(null)
    val assessmentPersistenceFailure: StateFlow<Throwable?> = _assessmentPersistenceFailure.asStateFlow()
    private val assessmentWriteQueue = AssessmentWriteQueue(
        scope = assessmentWriteScope,
        onFailure = { _assessmentPersistenceFailure.value = it }
    )

    val allPatterns: StateFlow<List<HandpanPattern>> = repository.allPatterns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customPatterns: StateFlow<List<HandpanPattern>> = repository.customPatterns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val practiceStats: StateFlow<Map<String, PracticeProgress>> = repository.allProgress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val nextPracticeRecommendation: StateFlow<LearningRecommendation> = repository.allMasteredSkills
        .map { states -> PersonalizationEngine.recommend(states) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            PersonalizationEngine.recommend(emptyList())
        )

    val lessonProgressMap: StateFlow<Map<String, com.example.data.local.LessonProgressEntity>> = repository.allLessonProgress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val recordedTracks: StateFlow<List<com.example.audio.RecordedTrack>> = repository.allRecordedTracks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        practiceEngine.onRoundCompleted = { pattern, bpm, elapsedSeconds ->
            if (practiceEngine.uiState.value.inputMode == PracticeInputMode.VIRTUAL_HANDPAN) {
                viewModelScope.launch {
                    repository.recordPracticeSession(pattern.id, bpm, elapsedSeconds)
                }
            }
        }
        practiceEngine.onAssessmentFinalized = { assessment ->
            val evidence = EvidenceEntity.fromDomain(
                sessionId = assessment.sessionId,
                validEvidenceCount = assessment.quality.validEventCount,
                validity = assessment.quality.validity.name,
                metrics = assessment.metrics
            )
            val finalTimelinePayload = com.example.model.AssessmentTimelineCodec.encode(
                practiceEngine.acousticEvaluator.timeline
            )
            enqueueAssessmentWrite {
                repository.beginFinalization(assessment.sessionId)
                repository.persistFinalizedAssessment(assessment, evidence, finalTimelinePayload)
            }
        }
        practiceEngine.onAssessmentStarted = { session, bpm, inputMode ->
            enqueueAssessmentWrite {
                repository.startActiveAssessment(
                    session = session,
                    patternId = session.patternId,
                    bpm = bpm,
                    inputMode = inputMode,
                    timeline = assessmentTimeline
                )
            }
        }
        practiceEngine.onAssessmentTimelineEvent = { event ->
            enqueueAssessmentWrite {
                repository.persistActiveTimeline(event.sessionId, assessmentTimeline)
            }
        }
        practiceEngine.onAssessmentLifecycleChanged = { session, lifecycle ->
            enqueueAssessmentWrite {
                repository.updateActiveAssessmentLifecycle(
                    sessionId = session.sessionId,
                    lifecycle = lifecycle,
                    pauseStartedAtEpochMs = if (lifecycle == com.example.model.PracticeSessionLifecycle.PAUSED) {
                        System.currentTimeMillis()
                    } else {
                        null
                    }
                )
            }

        }
        viewModelScope.launch {
            val recoverable = assessmentRecoveryCoordinator.findRecoverable()
            _recoverableAssessments.value = recoverable.map { it.session }
            val selected = recoverable.firstOrNull()
            if (selected != null) {
                repository.getPatternById(selected.session.patternId)?.let { pattern ->
                    practiceEngine.restoreAssessment(
                        pattern = pattern,
                        context = selected.context,
                        timeline = selected.timeline,
                        inputMode = selected.inputMode,
                        bpm = selected.session.bpm
                    )
                    _appUiState.update {
                        it.copy(currentPattern = pattern, currentScreen = AppScreen.PRACTICE)
                    }
                }
            }
        }
        val prefs = context.getSharedPreferences("handpan_prefs", Context.MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean("is_first_launch", true)
        val savedTheme = prefs.getBoolean("dark_theme", true)
        val savedHaptic = prefs.getBoolean("is_haptic_enabled", true)
        val savedMasterVol = prefs.getFloat("master_volume", 1.0f)
        val savedMetroVol = prefs.getFloat("metronome_volume", 0.8f)
        val savedInputMode = try {
            PracticeInputMode.valueOf(
                prefs.getString("practice_input_mode", PracticeInputMode.REAL_HANDPAN.name)
                    ?: PracticeInputMode.REAL_HANDPAN.name
            )
        } catch (_: Exception) {
            PracticeInputMode.REAL_HANDPAN
        }
        val savedScaleName = prefs.getString("scale_name", NotePitchConfig.D_KURD_9.scaleName)
        val savedScale = NotePitchConfig.SCALES.find { it.scaleName == savedScaleName }
            ?: NotePitchConfig.D_KURD_9
        val savedTuning = prefs.getFloat("tuning_reference_hz", 440.0f)
        val savedNotation = try {
            NotationSystem.valueOf(prefs.getString("notation_system", NotationSystem.NUMERIC.name) ?: NotationSystem.NUMERIC.name)
        } catch (_: Exception) {
            NotationSystem.NUMERIC
        }
        val savedProfile = try {
            val profName = prefs.getString("instrument_profile", InstrumentProfile.DEFAULT_D_KURD_9.name)
            InstrumentProfile.STANDARD_PROFILES.find { it.name == profName } ?: InstrumentProfile.DEFAULT_D_KURD_9
        } catch (_: Exception) {
            InstrumentProfile.DEFAULT_D_KURD_9
        }

        _appUiState.update {
            it.copy(
                showOnboarding = isFirstLaunch,
                darkTheme = savedTheme,
                isHapticEnabled = savedHaptic,
                masterVolume = savedMasterVol,
                metronomeVolume = savedMetroVol,
                defaultPracticeInputMode = savedInputMode,
                currentScaleConfig = savedScale.withTuning(savedTuning),
                preferredNotationSystem = savedNotation,
                currentInstrumentProfile = savedProfile
            )
        }

        if (isFirstLaunch) {
            prefs.edit().putBoolean("is_first_launch", false).apply()
        }

        audioEngine.setMasterVolume(savedMasterVol)
        audioEngine.setMetronomeVolume(savedMetroVol)
        audioEngine.loadSamples(savedScale.withTuning(savedTuning))
        metronomeEngine.setHapticEnabled(savedHaptic)

        refreshCustomSamplesMap()
    }

    private fun enqueueAssessmentWrite(write: suspend () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            check(assessmentWriteQueue.tryEnqueue(write)) {
                "Assessment persistence writer is unavailable"
            }
            return
        }
        runBlocking(Dispatchers.IO) {
            assessmentWriteQueue.enqueueAndAwait(write)
        }
    }

    fun recoverAssessment(sessionId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val recovered = runCatching {
                repository.recoverAssessment(sessionId, System.nanoTime())
            }.getOrNull()
            val success = recovered != null && repository.getPatternById(recovered.session.patternId)?.let { pattern ->
                practiceEngine.restoreAssessment(
                    pattern = pattern,
                    context = recovered.context,
                    timeline = recovered.timeline,
                    inputMode = recovered.inputMode,
                    bpm = recovered.session.bpm
                )
                _appUiState.update {
                    it.copy(currentPattern = pattern, currentScreen = AppScreen.PRACTICE)
                }
                true
            } == true
            onResult(success)
        }
    }

    fun refreshCustomSamplesMap() {
        val map = mutableMapOf<Int, Boolean>()
        for (i in 0..8) {
            map[i] = CustomSampleRecorder.hasCustomSample(context, i)
        }
        map[NotePitchConfig.NOTE_SLAP] = CustomSampleRecorder.hasCustomSample(context, NotePitchConfig.NOTE_SLAP)
        _appUiState.update { it.copy(customSamplesMap = map) }
    }

    fun setNotationPreference(system: NotationSystem) {
        _appUiState.update { it.copy(preferredNotationSystem = system) }
        context.getSharedPreferences("handpan_prefs", Context.MODE_PRIVATE)
            .edit().putString("notation_system", system.name).apply()
    }

    fun setInstrumentProfile(profile: InstrumentProfile) {
        val config = NotePitchConfig.fromProfile(profile)
        _appUiState.update {
            it.copy(
                currentInstrumentProfile = profile,
                currentScaleConfig = config
            )
        }
        audioEngine.loadSamples(config)
        practiceEngine.acousticEvaluator.setScaleConfig(config)
        context.getSharedPreferences("handpan_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("instrument_profile", profile.name)
            .putString("scale_name", config.scaleName)
            .putFloat("tuning_reference_hz", config.tuningReferenceHz)
            .apply()
    }

    fun openSamplerDialog(noteNumber: Int = 0) {
        refreshCustomSamplesMap()
        _appUiState.update { 
            it.copy(
                showSamplerDialog = true, 
                selectedSamplerNote = noteNumber,
                sampleSuccessMessage = null,
                isRecordingSample = false,
                recordingAmplitude = 0f
            ) 
        }
    }

    fun selectSamplerNote(noteNumber: Int) {
        _appUiState.update { 
            it.copy(
                selectedSamplerNote = noteNumber,
                sampleSuccessMessage = null,
                isRecordingSample = false,
                recordingAmplitude = 0f
            ) 
        }
    }

    fun dismissSamplerDialog() {
        if (_appUiState.value.isRecordingSample) {
            customSampleRecorder.cancelRecording()
        }
        _appUiState.update { it.copy(showSamplerDialog = false, isRecordingSample = false, recordingAmplitude = 0f) }
    }

    fun startRecordingCustomSample(noteNumber: Int) {
        _appUiState.update { 
            it.copy(
                isRecordingSample = true, 
                selectedSamplerNote = noteNumber,
                recordingAmplitude = 0f,
                sampleSuccessMessage = null
            ) 
        }

        customSampleRecorder.startRecording(
            noteNumber = noteNumber,
            onAmplitudeChange = { amp ->
                _appUiState.update { it.copy(recordingAmplitude = amp) }
            },
            onFinished = { success, file ->
                if (success && file != null) {
                    refreshCustomSamplesMap()
                    audioEngine.reloadNoteSample(noteNumber)
                    _appUiState.update { 
                        it.copy(
                            isRecordingSample = false,
                            recordingAmplitude = 0f,
                            sampleSuccessMessage = "صدای ساز واقعی برای نت $noteNumber با موفقیت کالیبره و ذخیره شد!"
                        ) 
                    }
                } else {
                    _appUiState.update { 
                        it.copy(
                            isRecordingSample = false,
                            sampleSuccessMessage = "خطا در دسترسی به میکروفن یا ذخیره فایل!"
                        ) 
                    }
                }
            },
            onCancelled = {
                _appUiState.update {
                    it.copy(
                        isRecordingSample = false,
                        recordingAmplitude = 0f,
                        sampleSuccessMessage = "ضبط لغو شد و فایل موقت حذف شد."
                    )
                }
            }
        )
    }

    fun stopRecordingCustomSample() {
        customSampleRecorder.stopAndSave()
    }

    fun deleteCustomSample(noteNumber: Int) {
        audioEngine.removeCustomSample(noteNumber)
        refreshCustomSamplesMap()
        _appUiState.update { 
            it.copy(
                sampleSuccessMessage = "صدای اختصاصی حذف شد؛ صدای پیش‌فرض بازنشانی گردید."
            ) 
        }
    }

    fun deleteAllCustomSamples() {
        CustomSampleRecorder.deleteAllCustomSamples(context)
        for (i in 0..8) {
            audioEngine.reloadNoteSample(i)
        }
        audioEngine.reloadNoteSample(NotePitchConfig.NOTE_SLAP)
        refreshCustomSamplesMap()
        _appUiState.update {
            it.copy(
                sampleSuccessMessage = "همه نمونه‌های اختصاصی بازنشانی شدند."
            )
        }
    }

    fun navigateTo(screen: AppScreen) {
        if (_appUiState.value.currentScreen == AppScreen.PRACTICE && screen != AppScreen.PRACTICE) {
            practiceEngine.stop()
        }
        _appUiState.update { it.copy(currentScreen = screen) }
    }

    fun selectCategory(category: PatternCategory) {
        _appUiState.update { it.copy(selectedCategory = category) }
    }

    fun saveLessonResult(lessonId: String, score: Int) {
        val stars = when {
            score >= 85 -> 3
            score >= 65 -> 2
            score >= 40 -> 1
            else -> 0
        }
        viewModelScope.launch {
            repository.saveLessonProgress(
                lessonId = lessonId,
                score = score,
                stars = stars,
                isCompleted = score >= 50
            )
        }
    }

    fun setPracticeInputMode(mode: PracticeInputMode) {
        _appUiState.update { it.copy(defaultPracticeInputMode = mode) }
        practiceEngine.setInputMode(mode)
        context.getSharedPreferences("handpan_prefs", Context.MODE_PRIVATE)
            .edit().putString("practice_input_mode", mode.name).apply()
    }

    fun startPractice(pattern: HandpanPattern, inputMode: PracticeInputMode = _appUiState.value.defaultPracticeInputMode) {
        _appUiState.update { it.copy(currentPattern = pattern, currentScreen = AppScreen.PRACTICE) }
        practiceEngine.setInputMode(inputMode)
        practiceEngine.loadPattern(pattern)
    }

    fun transcribeAudio(uri: Uri) {
        transcriptionJob?.cancel()
        transcriptionJob = viewModelScope.launch {
            _transcriptionState.value = TranscriptionUiState(isAnalyzing = true)
            when (val decoded = com.example.audio.AndroidAudioDecoder().decode(context, uri)) {
                is com.example.audio.AudioDecodeResult.Success -> {
                    val result = com.example.audio.OfflineHandpanTranscriber(
                        pitchConfig = _appUiState.value.currentScaleConfig
                    ).transcribe(decoded.audio)
                    _transcriptionState.value = TranscriptionUiState(result = result)
                }
                is com.example.audio.AudioDecodeResult.Failure -> {
                    _transcriptionState.value = TranscriptionUiState(
                        errorMessage = "فایل صوتی قابل تحلیل نیست: ${decoded.code}"
                    )
                }
            }
        }
    }

    fun acceptTranscription() {
        transcriptionState.value.result?.pattern?.pattern?.let { startPractice(it) }
    }

    fun clearTranscription() {
        transcriptionJob?.cancel()
        _transcriptionState.value = TranscriptionUiState()
    }

    fun playNoteDirect(noteNumber: Int, accent: Boolean = false) {
        _appUiState.update { it.copy(lastTappedNote = noteNumber) }
        if (_appUiState.value.isHapticEnabled) {
            hapticHelper.performClick(accent)
        }
        audioEngine.playNote(noteNumber, accent = accent, velocity = if (accent) 1.0f else 0.85f)
        performanceRecorder.recordStrike(
            noteNumber = noteNumber,
            isAccent = accent,
            velocity = if (accent) 1.0f else 0.85f
        )
    }

    fun saveCustomPattern(pattern: HandpanPattern) {
        viewModelScope.launch {
            repository.saveCustomPattern(pattern)
            navigateTo(AppScreen.EXERCISE_LIBRARY)
        }
    }

    fun deleteCustomPattern(id: String) {
        viewModelScope.launch {
            repository.deleteCustomPattern(id)
        }
    }

    fun setMasterVolume(volume: Float) {
        _appUiState.update { it.copy(masterVolume = volume) }
        audioEngine.setMasterVolume(volume)
        context.getSharedPreferences("handpan_prefs", Context.MODE_PRIVATE)
            .edit().putFloat("master_volume", volume).apply()
    }

    fun setMetronomeVolume(volume: Float) {
        _appUiState.update { it.copy(metronomeVolume = volume) }
        audioEngine.setMetronomeVolume(volume)
        context.getSharedPreferences("handpan_prefs", Context.MODE_PRIVATE)
            .edit().putFloat("metronome_volume", volume).apply()
    }

    fun setScaleTuning(config: NotePitchConfig) {
        _appUiState.update { it.copy(currentScaleConfig = config) }
        audioEngine.loadSamples(config)
        context.getSharedPreferences("handpan_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("scale_name", config.scaleName)
            .putFloat("tuning_reference_hz", config.tuningReferenceHz)
            .apply()
    }

    fun toggleHaptic() {
        val next = !_appUiState.value.isHapticEnabled
        _appUiState.update { it.copy(isHapticEnabled = next) }
        metronomeEngine.setHapticEnabled(next)
        context.getSharedPreferences("handpan_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("is_haptic_enabled", next).apply()
    }

    fun toggleTheme() {
        val next = !_appUiState.value.darkTheme
        _appUiState.update { it.copy(darkTheme = next) }
        context.getSharedPreferences("handpan_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("dark_theme", next).apply()
    }

    fun dismissOnboarding() {
        _appUiState.update { it.copy(showOnboarding = false) }
    }

    fun openOnboarding() {
        _appUiState.update { it.copy(showOnboarding = true) }
    }

    fun openGuideDialog() {
        _appUiState.update { it.copy(showGuideDialog = true) }
    }

    fun dismissGuideDialog() {
        _appUiState.update { it.copy(showGuideDialog = false) }
    }

    fun openScaleDialog() {
        _appUiState.update { it.copy(showScaleDialog = true) }
    }

    fun dismissScaleDialog() {
        _appUiState.update { it.copy(showScaleDialog = false) }
    }

    fun openBackingTracksDialog() {
        _appUiState.update { it.copy(showBackingTracksDialog = true) }
    }

    fun dismissBackingTracksDialog() {
        _appUiState.update { it.copy(showBackingTracksDialog = false) }
    }

    fun openRecorderDialog() {
        _appUiState.update { it.copy(showRecorderDialog = true) }
    }

    fun dismissRecorderDialog() {
        _appUiState.update { it.copy(showRecorderDialog = false) }
    }

    fun openLessonStudioDialog() {
        _appUiState.update { it.copy(showLessonStudioDialog = true) }
    }

    fun dismissLessonStudioDialog() {
        _appUiState.update { it.copy(showLessonStudioDialog = false) }
    }

    fun openImportDialog() {
        _appUiState.update { it.copy(showImportDialog = true) }
    }

    fun dismissImportDialog() {
        _appUiState.update { it.copy(showImportDialog = false) }
    }

    fun openExportDialog(pattern: HandpanPattern) {
        _appUiState.update { it.copy(patternToExport = pattern) }
    }

    fun dismissExportDialog() {
        _appUiState.update { it.copy(patternToExport = null) }
    }

    fun importPattern(pattern: HandpanPattern) {
        viewModelScope.launch {
            repository.saveCustomPattern(pattern)
            _appUiState.update { it.copy(showImportDialog = false) }
        }
    }

    fun importScore(
        source: ScoreSource,
        request: AdaptationRequest,
        exerciseId: String,
        title: String? = null,
        onResult: (ScoreIngestionResult) -> Unit
    ) {
        viewModelScope.launch {
            onResult(scoreIngestionUseCase.ingestAndAdapt(source, request, exerciseId, title))
        }
    }

    fun approveAdaptation(partial: ScoreIngestionResult.Partial, onResult: (ScoreIngestionResult) -> Unit) {
        viewModelScope.launch {
            onResult(scoreIngestionUseCase.approvePartialAdaptation(partial))
        }
    }

    fun rejectAdaptation(partial: ScoreIngestionResult.Partial, onResult: (ScoreIngestionResult) -> Unit) {
        viewModelScope.launch {
            onResult(scoreIngestionUseCase.rejectPartialAdaptation(partial))
        }
    }

    override fun onCleared() {
        super.onCleared()
        practiceEngine.stop()
        practiceEngine.acousticEvaluator.stopAssessment(showSummary = false)
        practiceEngine.acousticEvaluator.release()
        metronomeEngine.stop()
        ambienceEngine.stopAmbience()
        performanceRecorder.release()
        audioAnalysisSession.close()
        performanceRecorder.stopPlayback()
        customSampleRecorder.release()
        audioEngine.release()
        assessmentWriteScope.launch {
            assessmentWriteQueue.closeAndDrain()
            assessmentWriteScope.cancel()
        }
    }
}
