package com.example

import com.example.audio.MetronomeEngine
import com.example.audio.MusicalTiming
import com.example.data.local.PatternEntity
import com.example.model.DifficultyLevel
import com.example.model.HandpanPattern
import com.example.model.NoteEvent
import com.example.model.PatternCategory
import com.example.model.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.model.Subdivision
import com.example.model.HandpanTechnique
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HandpanTimingAndPatternTest {

    @Test
    fun musicalTimingUsesBpmAndSubdivision() {
        assertEquals(1_000_000_000L, MusicalTiming.beatDurationNanos(60))
        assertEquals(500_000_000L, MusicalTiming.beatDurationNanos(120))
        assertEquals(250_000_000L, MusicalTiming.subdivisionDurationNanos(120, Subdivision.EIGHTH))
        assertEquals(1_500_000_000L, MusicalTiming.beatToNanos(1.5, 60))
    }

    @Test
    fun testBpmBeatIntervalCalculation() {
        // At 60 BPM -> 1 beat = 1000 ms
        val interval60 = MetronomeEngine.calculateBeatIntervalMs(60)
        assertEquals(1000L, interval60)

        // At 120 BPM -> 1 beat = 500 ms
        val interval120 = MetronomeEngine.calculateBeatIntervalMs(120)
        assertEquals(500L, interval120)

        // At 90 BPM -> 1 beat = 666 ms
        val interval90 = MetronomeEngine.calculateBeatIntervalMs(90)
        assertEquals(666L, interval90)
    }

    @Test
    fun testSubdivisionIntervalCalculation() {
        // 60 BPM with Quarter subdivision (1 div) -> 1,000,000,000 ns
        val quarterNanos = MetronomeEngine.calculateTickIntervalNanos(60, Subdivision.QUARTER.divisionsPerBeat)
        assertEquals(1_000_000_000L, quarterNanos)

        // 60 BPM with Eighth subdivision (2 divs) -> 500,000,000 ns
        val eighthNanos = MetronomeEngine.calculateTickIntervalNanos(60, Subdivision.EIGHTH.divisionsPerBeat)
        assertEquals(500_000_000L, eighthNanos)

        // 60 BPM with Sixteenth subdivision (4 divs) -> 250,000,000 ns
        val sixteenthNanos = MetronomeEngine.calculateTickIntervalNanos(60, Subdivision.SIXTEENTH.divisionsPerBeat)
        assertEquals(250_000_000L, sixteenthNanos)
    }

    @Test
    fun signatureBeatDurationUsesDenominator() {
        assertEquals(1_000_000_000L, MusicalTiming.signatureBeatDurationNanos(60, TimeSignature.Common44))
        assertEquals(500_000_000L, MusicalTiming.signatureBeatDurationNanos(60, TimeSignature.SixEight68))
    }

    @Test
    fun compoundAndAsymmetricMetersExposeMusicalGrouping() {
        assertEquals(listOf(3, 3), TimeSignature.SixEight68.grouping)
        assertTrue(TimeSignature.SixEight68.isGroupedAccent(1))
        assertTrue(TimeSignature.SixEight68.isGroupedAccent(4))
        assertEquals(listOf(2, 2, 3), TimeSignature.SevenEight272.grouping)
        assertTrue(TimeSignature.SevenEight272.isGroupedAccent(5))
        assertFalse(TimeSignature.SevenEight272.isGroupedAccent(6))
    }

    @Test
    fun testPatternTiming_1_3_5_3_at_60_BPM() {
        // Pattern: 1 - 3 - 5 - 3 in 4/4 time signature
        val pattern = HandpanPattern(
            id = "test_arpeggio",
            title = "آرپژ ۱-۳-۵-۳",
            description = "تست زمان‌بندی الگو",
            bpm = 60,
            timeSignature = TimeSignature.Common44,
            bars = 1,
            events = listOf(
                NoteEvent(noteNumber = 1, beatPosition = 0.0, accent = true),
                NoteEvent(noteNumber = 3, beatPosition = 1.0, accent = false),
                NoteEvent(noteNumber = 5, beatPosition = 2.0, accent = false),
                NoteEvent(noteNumber = 3, beatPosition = 3.0, accent = false)
            )
        )

        val beatIntervalMs = MetronomeEngine.calculateBeatIntervalMs(pattern.bpm) // 1000ms

        // Expected timestamps in milliseconds from start:
        // Beat 1 (Note 1) -> 0 ms
        // Beat 2 (Note 3) -> 1000 ms
        // Beat 3 (Note 5) -> 2000 ms
        // Beat 4 (Note 3) -> 3000 ms
        val expectedTimestamps = listOf(0L, 1000L, 2000L, 3000L)

        for (i in pattern.events.indices) {
            val event = pattern.events[i]
            val timestampMs = (event.beatPosition * beatIntervalMs).toLong()
            assertEquals(expectedTimestamps[i], timestampMs)
        }
    }

    @Test
    fun testPatternTiming_1_3_5_3_at_120_BPM() {
        // Pattern: 1 - 3 - 5 - 3 at 120 BPM
        val pattern = HandpanPattern(
            id = "test_arpeggio_fast",
            title = "آرپژ ۱-۳-۵-۳ سریع",
            description = "تست زمان‌بندی در ۱۲۰ تمپو",
            bpm = 120,
            timeSignature = TimeSignature.Common44,
            bars = 1,
            events = listOf(
                NoteEvent(noteNumber = 1, beatPosition = 0.0, accent = true),
                NoteEvent(noteNumber = 3, beatPosition = 1.0, accent = false),
                NoteEvent(noteNumber = 5, beatPosition = 2.0, accent = false),
                NoteEvent(noteNumber = 3, beatPosition = 3.0, accent = false)
            )
        )

        val beatIntervalMs = MetronomeEngine.calculateBeatIntervalMs(pattern.bpm) // 500ms

        // Expected timestamps at 120 BPM:
        // Beat 1 (Note 1) -> 0 ms
        // Beat 2 (Note 3) -> 500 ms
        // Beat 3 (Note 5) -> 1000 ms
        // Beat 4 (Note 3) -> 1500 ms
        val expectedTimestamps = listOf(0L, 500L, 1000L, 1500L)

        for (i in pattern.events.indices) {
            val event = pattern.events[i]
            val timestampMs = (event.beatPosition * beatIntervalMs).toLong()
            assertEquals(expectedTimestamps[i], timestampMs)
        }
    }

    @Test
    fun testPatternJsonSerializationAndParsing() {
        val originalEvents = listOf(
            NoteEvent(noteNumber = 1, beatPosition = 0.0, duration = 1.0, velocity = 1.0f, accent = true, hand = "R"),
            NoteEvent(noteNumber = 3, beatPosition = 1.0, duration = 1.0, velocity = 0.85f, accent = false, hand = "L"),
            NoteEvent(noteNumber = 0, beatPosition = 2.0, duration = 1.0, isRest = true),
            NoteEvent(noteNumber = 5, beatPosition = 3.0, duration = 1.0, velocity = 0.85f, accent = false, hand = "R", technique = HandpanTechnique.TAK)
        )

        val json = PatternEntity.encodeEventsJson(originalEvents)
        val parsed = PatternEntity.parseEventsJson(json)

        assertEquals(4, parsed.size)
        assertEquals(1, parsed[0].noteNumber)
        assertTrue(parsed[0].accent)
        assertEquals("R", parsed[0].hand)

        assertEquals(3, parsed[1].noteNumber)
        assertFalse(parsed[1].accent)

        assertTrue(parsed[2].isRest)
        assertEquals(5, parsed[3].noteNumber)
        assertEquals(HandpanTechnique.TAK, parsed[3].technique)
    }

    @Test
    fun legacyPatternJsonInfersTechniqueWithoutTechniqueField() {
        val parsed = PatternEntity.parseEventsJson("[{\"n\":9,\"b\":0.0}]")

        assertEquals(HandpanTechnique.SLAP, parsed.single().technique)
    }

    @Test
    fun testLoopRangeCalculation() {
        val pattern = HandpanPattern(
            id = "loop_test",
            title = "تست لوپ",
            description = "تست",
            bpm = 80,
            timeSignature = TimeSignature.Common44,
            bars = 4,
            events = (0..15).map { beat ->
                NoteEvent(noteNumber = (beat % 8) + 1, beatPosition = beat.toDouble())
            }
        )

        // Loop Bar 2 to Bar 3 in 4/4:
        // Bar 2 starts at beat 4.0, Bar 3 ends at beat 12.0
        val startBar = 2
        val endBar = 3
        val startBeat = ((startBar - 1) * pattern.timeSignature.beatsPerBar).toDouble() // 4.0
        val endBeat = (endBar * pattern.timeSignature.beatsPerBar).toDouble()           // 12.0

        val loopEvents = pattern.events.filter {
            it.beatPosition >= startBeat && it.beatPosition < endBeat
        }

        assertEquals(8, loopEvents.size)
        assertEquals(4.0, loopEvents.first().beatPosition, 0.01)
        assertEquals(11.0, loopEvents.last().beatPosition, 0.01)
    }

    @Test
    fun testAccentAndRestHandling() {
        val restEvent = NoteEvent(noteNumber = 0, beatPosition = 0.0, isRest = true)
        assertTrue(restEvent.isRest)

        val accentedDing = NoteEvent(noteNumber = 1, beatPosition = 0.0, velocity = 1.0f, accent = true)
        assertTrue(accentedDing.accent)
        assertEquals(1.0f, accentedDing.velocity)
    }
}
