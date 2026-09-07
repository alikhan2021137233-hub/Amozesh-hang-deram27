package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.repository.HandpanRepository
import com.example.model.AssessmentEventType
import com.example.model.AssessmentSessionValidity
import com.example.model.AssessmentTimeline
import com.example.model.AssessmentTimelineEvent
import com.example.model.PracticeInputMode
import com.example.model.PracticeSessionContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActiveAssessmentPersistenceTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: HandpanRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = HandpanRepository(
            database.patternDao(),
            database.practiceProgressDao(),
            database.lessonProgressDao(),
            database.recordingTrackDao(),
            database
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun activeTimelineRoundTripsAndRecoversWithoutChangingEvidence() = runBlocking {
        val context = PracticeSessionContext.start(
            patternId = "pattern-1",
            startTimestampNanos = 1_000_000_000L,
            sessionId = "session-1"
        )
        val timeline = AssessmentTimeline().also { it.bindToSession(context.sessionId) }
        repository.startActiveAssessment(
            session = context,
            patternId = context.patternId,
            bpm = 80,
            inputMode = PracticeInputMode.REAL_HANDPAN,
            timeline = timeline,
            nowEpochMs = 10_000L
        )

        val event = AssessmentTimelineEvent(
            eventId = "event-1",
            sessionId = context.sessionId,
            loopId = "loop-1",
            sequenceIndex = 42,
            expectedNote = 1,
            detectedNote = 1,
            eventType = AssessmentEventType.CORRECT,
            expectedTimestampNanos = 2_000_000_000L,
            detectedTimestampNanos = 2_010_000_000L,
            deviationNanos = 10_000_000L,
            timingResult = null,
            confidence = 0.9f,
            targetId = "target-1",
            source = "microphone",
            durationNanos = null,
            isConsumed = true,
            assessmentSessionId = context.sessionId,
            patternId = context.patternId,
            targetNoteId = "target-note-1",
            sessionValidity = AssessmentSessionValidity.VALID
        )
        timeline.append(event)
        repository.persistActiveTimeline(context.sessionId, timeline, nowEpochMs = 11_000L)

        val recovered = repository.recoverAssessment(
            sessionId = context.sessionId,
            nowTimestampNanos = 20_000_000_000L
        )

        assertNotNull(recovered)
        assertEquals(context.sessionId, recovered?.context?.sessionId)
        assertEquals(PracticeInputMode.REAL_HANDPAN, recovered?.inputMode)
        assertEquals(1, recovered?.timeline?.snapshot()?.size)
        assertEquals(0L, recovered?.timeline?.snapshot()?.single()?.eventOrdinal)
        assertEquals(42, recovered?.timeline?.snapshot()?.single()?.sequenceIndex)
        assertEquals(2_010_000_000L, recovered?.timeline?.snapshot()?.single()?.detectedTimestampNanos)
        assertEquals("UNKNOWN", recovered?.session?.audioContinuityState)
    }

    @Test
    fun pauseAndResumeRemainSessionScoped() = runBlocking {
        val context = PracticeSessionContext.start(
            patternId = "pattern-1",
            startTimestampNanos = 1_000_000_000L,
            sessionId = "session-pause"
        )
        val timeline = AssessmentTimeline().also { it.bindToSession(context.sessionId) }
        repository.startActiveAssessment(context, context.patternId, 80, PracticeInputMode.REAL_HANDPAN, timeline, 10_000L)

        context.pause(2_000_000_000L)
        repository.updateActiveAssessmentLifecycle(context.sessionId, com.example.model.PracticeSessionLifecycle.PAUSED, 12_000L, 12_000L)
        context.resume(3_000_000_000L)
        repository.updateActiveAssessmentLifecycle(context.sessionId, com.example.model.PracticeSessionLifecycle.ACTIVE, null, 13_000L)

        val recovered = repository.recoverAssessment(context.sessionId, 20_000_000_000L)
        assertNotNull(recovered)
        assertEquals(context.sessionId, recovered?.context?.sessionId)
        assertTrue(recovered?.session?.accumulatedPausedDurationMs ?: 0L >= 0L)
    }

    @Test
    fun lifecycleRejectsInvalidTransitionsAndSupportsFinalizing() {
        runBlocking {
        val context = PracticeSessionContext.start("pattern-1", 1_000L, sessionId = "session-life")
        val timeline = AssessmentTimeline().also { it.bindToSession(context.sessionId) }
        repository.startActiveAssessment(context, context.patternId, 80, PracticeInputMode.REAL_HANDPAN, timeline, 10_000L)

        context.pause(2_000L)
        repository.updateActiveAssessmentLifecycle(context.sessionId, com.example.model.PracticeSessionLifecycle.PAUSED, 12_000L, 12_000L)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.updateActiveAssessmentLifecycle(
                    context.sessionId,
                    com.example.model.PracticeSessionLifecycle.FINALIZED,
                    null,
                    13_000L
                )
            }
        }
        repository.updateActiveAssessmentLifecycle(context.sessionId, com.example.model.PracticeSessionLifecycle.ACTIVE, null, 13_000L)
        repository.beginFinalization(context.sessionId, 14_000L)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.updateActiveAssessmentLifecycle(
                    context.sessionId,
                    com.example.model.PracticeSessionLifecycle.ACTIVE,
                    null,
                    15_000L
                )
            }
        }
        }
    }

    @Test
    fun metadataMismatchIsRejectedWithoutOverwritingPayload() {
        runBlocking {
        val context = PracticeSessionContext.start("pattern-1", 1_000L, sessionId = "session-corrupt")
        val timeline = AssessmentTimeline().also { it.bindToSession(context.sessionId) }
        repository.startActiveAssessment(context, context.patternId, 80, PracticeInputMode.REAL_HANDPAN, timeline, 10_000L)
        val original = database.assessmentSessionDao().getBySessionId(context.sessionId)!!.timelinePayload
        database.openHelper.writableDatabase.execSQL(
            "UPDATE assessment_sessions SET eventCount = 1 WHERE sessionId = ?",
            arrayOf(context.sessionId)
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.recoverAssessment(context.sessionId, 20_000L) }
        }
        assertEquals(original, database.assessmentSessionDao().getBySessionId(context.sessionId)!!.timelinePayload)
        }
    }

    @Test
    fun algorithmVersionMismatchIsRejected() {
        runBlocking {
            val context = PracticeSessionContext.start("pattern-1", 1_000L, sessionId = "session-version")
            val timeline = AssessmentTimeline().also { it.bindToSession(context.sessionId) }
            repository.startActiveAssessment(
                context,
                context.patternId,
                80,
                PracticeInputMode.REAL_HANDPAN,
                timeline,
                10_000L
            )
            database.openHelper.writableDatabase.execSQL(
                "UPDATE assessment_sessions SET evaluationAlgorithmVersion = 99 WHERE sessionId = ?",
                arrayOf(context.sessionId)
            )

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.recoverAssessment(context.sessionId, 20_000L) }
            }
        }
    }

    @Test
    fun directFinalizeDaoCannotBypassFinalizing() = runBlocking {
        val context = PracticeSessionContext.start("pattern-1", 1_000L, sessionId = "session-dao")
        val timeline = AssessmentTimeline().also { it.bindToSession(context.sessionId) }
        repository.startActiveAssessment(context, context.patternId, 80, PracticeInputMode.REAL_HANDPAN, timeline, 10_000L)

        assertEquals(
            0,
            database.assessmentSessionDao().markFinalized(context.sessionId, 11_000L, "UNKNOWN")
        )
    }
}
