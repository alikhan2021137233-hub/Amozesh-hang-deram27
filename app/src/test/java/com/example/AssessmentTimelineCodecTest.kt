package com.example

import com.example.model.AssessmentEventType
import com.example.model.AssessmentSessionValidity
import com.example.model.AssessmentTimeline
import com.example.model.AssessmentTimelineCodec
import com.example.model.AssessmentTimelineDecodeResult
import com.example.model.HandpanTechnique
import com.example.model.StrikeClassification
import com.example.model.Subdivision
import com.example.model.TimingResult
import com.example.model.TimingStatus
import com.example.model.TimingToleranceProfile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AssessmentTimelineCodecTest {
    @Test
    fun emptyTimelineRoundTrips() {
        val timeline = AssessmentTimeline()
        val decoded = decode(AssessmentTimelineCodec.encode(timeline))

        assertEquals(0, decoded.snapshot().size)
    }

    @Test
    fun singleCorrectEventRoundTripsAllDurableFields() {
        val event = fullEvent("correct", 0, AssessmentEventType.CORRECT)
        val decoded = decode(timelineOf(event))

        assertSemanticEventEquals(event, decoded.snapshot().single())
        assertTrue(decoded.snapshot().single().isConsumed)
    }

    @Test
    fun missRoundTripsWithoutDetectedFields() {
        val event = fullEvent("miss", 0, AssessmentEventType.MISSED).copy(
            detectedNote = null,
            detectedTimestampNanos = null,
            deviationNanos = null,
            timingResult = null,
            confidence = 0f,
            signalQuality = null,
            measuredAmplitude = null,
            measuredVelocity = null,
            accentStrength = null,
            detectedTechnique = null
        )
        assertSemanticEventEquals(event, decode(timelineOf(event)).snapshot().single())
    }

    @Test
    fun extraRoundTripsWithoutExpectedTargetFields() {
        val event = fullEvent("extra", 0, AssessmentEventType.EXTRA).copy(
            loopId = null,
            patternId = null,
            targetId = null,
            obligationId = null,
            targetNoteId = null,
            expectedNote = null,
            expectedNotes = emptySet(),
            expectedTimestampNanos = null,
            expectedTechnique = null,
            targetBpm = null,
            subdivision = null,
            beatPosition = null,
            expectedTimingWindow = null
        )
        assertSemanticEventEquals(event, decode(timelineOf(event)).snapshot().single())
    }

    @Test
    fun unknownRoundTripsWithIncompleteEvidence() {
        val event = fullEvent("unknown", 0, AssessmentEventType.UNKNOWN).copy(
            detectedNote = null,
            confidence = 0f,
            signalQuality = null,
            measuredAmplitude = null,
            measuredVelocity = null,
            accentStrength = null,
            detectedTechnique = null
        )
        assertSemanticEventEquals(event, decode(timelineOf(event)).snapshot().single())
    }

    @Test
    fun simultaneousEventsPreserveDistinctTargetsAndObligations() {
        val first = fullEvent("chord-1", 0, AssessmentEventType.CORRECT).copy(
            obligationId = "obligation-1",
            targetNoteId = "target-note-1"
        )
            val second = fullEvent("chord-2", 1, AssessmentEventType.WRONG).copy(
                eventOrdinal = 1L,
            obligationId = "obligation-2",
            targetNoteId = "target-note-2",
            expectedNote = 64,
            expectedNotes = setOf(62, 64),
            detectedNote = 65
        )
        val decoded = decode(timelineOf(first, second)).snapshot()

        assertEquals(listOf("chord-1", "chord-2"), decoded.map { it.eventId })
        assertEquals(listOf("obligation-1", "obligation-2"), decoded.map { it.obligationId })
        assertEquals(listOf(1.25, 1.25), decoded.map { it.beatPosition })
        assertSemanticEventEquals(first, decoded[0])
        assertSemanticEventEquals(second, decoded[1])
    }

    @Test
    fun optionalFieldsAndEventOrderingUseEventOrdinal() {
        val second = fullEvent("second", 0, AssessmentEventType.CORRECT).copy(
            eventOrdinal = 1L,
            loopId = null,
            patternId = null,
            targetId = null,
            obligationId = null,
            targetNoteId = null,
            expectedNote = null,
            expectedNotes = emptySet(),
            expectedTimestampNanos = null,
            detectedTimestampNanos = null,
            deviationNanos = null,
            timingResult = null,
            expectedTechnique = null,
            detectedTechnique = null,
            subdivision = null,
            beatPosition = null,
            expectedTimingWindow = null,
            targetBpm = null,
            durationNanos = null
        )
        val first = fullEvent("first", 0, AssessmentEventType.CORRECT).copy(eventOrdinal = 0L)
        val timeline = AssessmentTimeline().also {
            it.append(first)
            it.append(second)
        }
        val encoded = AssessmentTimelineCodec.encode(timeline)
        val decoded = decode(encoded).snapshot()

        assertEquals(encoded, AssessmentTimelineCodec.encode(timeline))
        assertEquals(listOf(0, 0), decoded.map { it.sequenceIndex })
        assertEquals(listOf("first", "second"), decoded.map { it.eventId })
        assertSemanticEventEquals(first, decoded[0])
        assertSemanticEventEquals(second, decoded[1])
    }

    @Test
    fun semanticEquivalentAppendOrdersAndExpectedNoteOrdersHaveIdenticalEncoding() {
        val first = fullEvent("first", 0, AssessmentEventType.CORRECT).copy(
            expectedNotes = linkedSetOf(2, 1)
        )
        val second = fullEvent("second", 1, AssessmentEventType.WRONG).copy(
            expectedNotes = linkedSetOf(4, 3)
        )
        val timelineA = AssessmentTimeline().also {
            it.append(first)
            it.append(second)
        }
        val timelineB = AssessmentTimeline().also {
            it.append(first.copy(expectedNotes = linkedSetOf(1, 2)))
            it.append(second.copy(expectedNotes = linkedSetOf(3, 4)))
        }

        assertEquals(AssessmentTimelineCodec.encode(timelineA), AssessmentTimelineCodec.encode(timelineB))
    }

    @Test
    fun consumedFalseRoundTripsWithoutBeingRecomputed() {
        val event = fullEvent("not-consumed", 0, AssessmentEventType.CORRECT)
            .copy(isConsumed = false)

        assertFalse(decode(timelineOf(event)).snapshot().single().isConsumed)
    }

    @Test
    fun unsupportedVersionFailsExplicitly() {
        val payload = JSONObject(AssessmentTimelineCodec.encode(AssessmentTimeline()))
            .put("eventSchemaVersion", 99)

        val result = AssessmentTimelineCodec.decode(payload.toString())

        assertTrue(result is AssessmentTimelineDecodeResult.Failure)
        assertTrue((result as AssessmentTimelineDecodeResult.Failure).reason.contains("Unsupported"))
    }

    @Test
    fun corruptionIsRejectedExplicitly() {
        assertFailure("not-json")
        assertFailure(payloadWithoutEvents())

        val duplicateEventId = JSONObject(validPayload())
        duplicateEventId.getJSONArray("events").getJSONObject(1)
            .put("eventId", "one")
        assertFailure(duplicateEventId.toString())

        val duplicateSequence = JSONObject(validPayload())
        duplicateSequence.getJSONArray("events").getJSONObject(1)
            .put("eventOrdinal", 0)
        assertFailure(duplicateSequence.toString())

        val nonContiguousSequence = JSONObject(validPayload())
        nonContiguousSequence.getJSONArray("events").getJSONObject(1)
            .put("eventOrdinal", 2)
        assertFailure(nonContiguousSequence.toString())

        val wrongSession = JSONObject(validPayload())
        wrongSession.getJSONArray("events").getJSONObject(0)
            .put("sessionId", "other")
        assertFailure(wrongSession.toString())

        val invalidEnum = JSONObject(validPayload())
        invalidEnum.getJSONArray("events").getJSONObject(0)
            .put("eventType", "INVALID")
        assertFailure(invalidEnum.toString())
    }

    @Test
    fun removingEachRequiredEventFieldFailsExplicitly() {
        listOf(
            "eventId", "sessionId", "assessmentSessionId", "eventOrdinal", "sequenceIndex", "eventType",
            "expectedNotes", "confidence", "source", "sessionValidity"
        ).forEach { field ->
            val payload = JSONObject(singleEventPayload())
            payload.getJSONArray("events").getJSONObject(0).remove(field)
            assertFailure(payload.toString())
        }
    }

    @Test
    fun invalidShapesAndRangesFailExplicitly() {
        val eventsObject = JSONObject(singleEventPayload())
            .put("events", JSONObject())
        assertFailure(eventsObject.toString())

        val wrongItemType = JSONObject(singleEventPayload())
        wrongItemType.getJSONArray("events").put(0, "not-an-event")
        assertFailure(wrongItemType.toString())

        val missingEventObject = JSONObject(singleEventPayload())
        missingEventObject.getJSONArray("events").put(0, JSONObject.NULL)
        assertFailure(missingEventObject.toString())

        val invalidConfidence = JSONObject(singleEventPayload())
        invalidConfidence.getJSONArray("events").getJSONObject(0).put("confidence", 1.01)
        assertFailure(invalidConfidence.toString())

        val negativeSequence = JSONObject(singleEventPayload())
        negativeSequence.getJSONArray("events").getJSONObject(0).put("eventOrdinal", -1)
        assertFailure(negativeSequence.toString())
    }

    @Test
    fun importantFloatBoundariesRoundTripWithoutSemanticLoss() {
        listOf(0f, 1f, 0.375f).forEach { confidence ->
            val event = fullEvent("confidence-$confidence", 0, AssessmentEventType.CORRECT)
                .copy(
                    confidence = confidence,
                    signalQuality = confidence,
                    measuredAmplitude = confidence,
                    measuredVelocity = confidence,
                    accentStrength = confidence
                )
            val decoded = decode(timelineOf(event)).snapshot().single()
            assertEquals(confidence, decoded.confidence)
            assertEquals(confidence, decoded.signalQuality)
            assertEquals(confidence, decoded.measuredAmplitude)
            assertEquals(confidence, decoded.measuredVelocity)
            assertEquals(confidence, decoded.accentStrength)
        }
    }

    @Test
    fun outOfOrderPayloadDecodesInCanonicalSequenceOrder() {
        val payload = JSONObject(AssessmentTimelineCodec.encode(AssessmentTimeline().also {
            it.append(fullEvent("first", 0, AssessmentEventType.CORRECT))
            it.append(fullEvent("second", 1, AssessmentEventType.WRONG))
        }))
        val events = payload.getJSONArray("events")
        val first = events.get(0)
        val second = events.get(1)
        events.put(0, second)
        events.put(1, first)

        val decoded = decode(payload.toString()).snapshot()

        assertEquals(listOf(0, 1), decoded.map { it.sequenceIndex })
        assertEquals(listOf("first", "second"), decoded.map { it.eventId })
    }

    @Test
    fun decodeUsesEventOrdinalWhenSequenceIndexDiffers() {
        val first = fullEvent("first", 99, AssessmentEventType.CORRECT).copy(eventOrdinal = 0L)
        val second = fullEvent("second", 1, AssessmentEventType.WRONG).copy(eventOrdinal = 1L)
        val payload = JSONObject(AssessmentTimelineCodec.encode(AssessmentTimeline().also {
            it.append(first)
            it.append(second)
        }))
        val events = payload.getJSONArray("events")
        val firstJson = events.getJSONObject(0)
        val secondJson = events.getJSONObject(1)
        events.put(0, secondJson)
        events.put(1, firstJson)

        val decoded = decode(payload.toString()).snapshot()

        assertEquals(listOf(0L, 1L), decoded.map { it.eventOrdinal })
        assertEquals(listOf("first", "second"), decoded.map { it.eventId })
    }

    @Test
    fun consumedStateRoundTripsExactly() {
        val consumed = fullEvent("consumed", 0, AssessmentEventType.CORRECT).copy(isConsumed = true)
        val pending = fullEvent("pending", 1, AssessmentEventType.EXPECTED).copy(isConsumed = false)
        val decoded = decode(timelineOf(consumed, pending)).snapshot()

        assertEquals(listOf(true, false), decoded.map { it.isConsumed })
    }

    @Test
    fun codecHandlesFiveHundredEventsWithoutChangingOrder() {
        val timeline = AssessmentTimeline().also { timeline ->
            repeat(500) { index -> timeline.append(fullEvent("event-$index", index, AssessmentEventType.CORRECT)) }
        }

        val decoded = decode(AssessmentTimelineCodec.encode(timeline)).snapshot()

        assertEquals(500, decoded.size)
        assertEquals((0 until 500).toList(), decoded.map { it.sequenceIndex })
    }

    private fun timelineOf(vararg events: com.example.model.AssessmentTimelineEvent): String =
        AssessmentTimeline().also { timeline -> events.forEach(timeline::append) }
            .let(AssessmentTimelineCodec::encode)

    private fun decode(json: String): AssessmentTimeline =
        (AssessmentTimelineCodec.decode(json) as AssessmentTimelineDecodeResult.Success).timeline

    private fun assertFailure(json: String) {
        assertTrue(AssessmentTimelineCodec.decode(json) is AssessmentTimelineDecodeResult.Failure)
    }

    private fun validPayload(): String = AssessmentTimelineCodec.encode(AssessmentTimeline().also {
        it.append(fullEvent("one", 0, AssessmentEventType.CORRECT))
        it.append(fullEvent("two", 1, AssessmentEventType.WRONG))
    })

    private fun singleEventPayload(): String = timelineOf(fullEvent("single", 0, AssessmentEventType.CORRECT))

    private fun payloadWithoutEvents(): String = JSONObject(validPayload()).also {
        it.remove("events")
    }.toString()

    private fun fullEvent(eventId: String, sequenceIndex: Int, type: AssessmentEventType) =
        com.example.model.AssessmentTimelineEvent(
            eventId = eventId,
            sessionId = "session-1",
            eventOrdinal = sequenceIndex.toLong(),
            loopId = "loop-1",
            sequenceIndex = sequenceIndex,
            expectedNote = 1,
            detectedNote = 1,
            eventType = type,
            expectedTimestampNanos = 1_000_000_000L,
            detectedTimestampNanos = 1_010_000_000L,
            deviationNanos = 10_000_000L,
            timingResult = TimingResult(TimingStatus.GOOD, 10_000_000L),
            confidence = 0.9f,
            targetId = "target-1",
            source = "microphone",
            durationNanos = 250_000_000L,
            isConsumed = true,
            patternId = "pattern-1",
            obligationId = "obligation-$eventId",
            expectedNotes = setOf(1, 2),
            classification = StrikeClassification.CORRECT_NOTE,
            measuredAmplitude = 0.7f,
            measuredVelocity = 0.8f,
            accentStrength = 0.6f,
            expectedTechnique = HandpanTechnique.DING,
            detectedTechnique = HandpanTechnique.DING,
            targetBpm = 80,
            targetNoteId = "target-note-$eventId",
            subdivision = Subdivision.SIXTEENTH,
            beatPosition = 1.25,
            expectedTimingWindow = TimingToleranceProfile(45_000_000L, 70_000_000L, 90_000_000L, 160_000_000L),
            sessionValidity = AssessmentSessionValidity.VALID
        )

    private fun assertSemanticEventEquals(expected: com.example.model.AssessmentTimelineEvent, actual: com.example.model.AssessmentTimelineEvent) {
        assertEquals(expected.eventId, actual.eventId)
        assertEquals(expected.sessionId, actual.sessionId)
        assertEquals(expected.eventOrdinal, actual.eventOrdinal)
        assertEquals(expected.assessmentSessionId, actual.assessmentSessionId)
        assertEquals(expected.loopId, actual.loopId)
        assertEquals(expected.sequenceIndex, actual.sequenceIndex)
        assertEquals(expected.patternId, actual.patternId)
        assertEquals(expected.targetId, actual.targetId)
        assertEquals(expected.obligationId, actual.obligationId)
        assertEquals(expected.targetNoteId, actual.targetNoteId)
        assertEquals(expected.expectedNote, actual.expectedNote)
        assertEquals(expected.expectedNotes, actual.expectedNotes)
        assertEquals(expected.detectedNote, actual.detectedNote)
        assertEquals(expected.eventType, actual.eventType)
        assertEquals(expected.expectedTimestampNanos, actual.expectedTimestampNanos)
        assertEquals(expected.detectedTimestampNanos, actual.detectedTimestampNanos)
        assertEquals(expected.deviationNanos, actual.deviationNanos)
        assertEquals(expected.timingResult, actual.timingResult)
        assertEquals(expected.confidence, actual.confidence)
        assertEquals(expected.targetId, actual.targetId)
        assertEquals(expected.source, actual.source)
        assertEquals(expected.durationNanos, actual.durationNanos)
        assertEquals(expected.patternId, actual.patternId)
        assertEquals(expected.signalQuality, actual.signalQuality)
        assertEquals(expected.measuredAmplitude, actual.measuredAmplitude)
        assertEquals(expected.measuredVelocity, actual.measuredVelocity)
        assertEquals(expected.accentStrength, actual.accentStrength)
        assertEquals(expected.expectedTechnique, actual.expectedTechnique)
        assertEquals(expected.detectedTechnique, actual.detectedTechnique)
        assertEquals(expected.targetBpm, actual.targetBpm)
        assertEquals(expected.targetNoteId, actual.targetNoteId)
        assertEquals(expected.subdivision, actual.subdivision)
        assertEquals(expected.beatPosition, actual.beatPosition)
        assertEquals(expected.expectedTimingWindow, actual.expectedTimingWindow)
        assertEquals(expected.sessionValidity, actual.sessionValidity)
        assertEquals(expected.classification, actual.classification)
    }
}
