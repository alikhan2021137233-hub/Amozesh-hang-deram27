package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.model.DifficultyLevel
import com.example.model.HandpanPattern
import com.example.model.NoteEvent
import com.example.model.HandpanTechnique
import com.example.model.PatternCategory
import com.example.model.Subdivision
import com.example.model.TimeSignature
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "patterns")
data class PatternEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val description: String,
    val bpm: Int,
    val timeSignatureNumerator: Int,
    val timeSignatureDenominator: Int,
    val bars: Int,
    val eventsJson: String,
    val difficulty: String,
    val category: String,
    val isCustom: Boolean,
    val recommendedSubdivision: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): HandpanPattern {
        val events = parseEventsJson(eventsJson)
        return HandpanPattern(
            id = id,
            title = title,
            description = description,
            bpm = bpm,
            timeSignature = TimeSignature(timeSignatureNumerator, timeSignatureDenominator),
            bars = bars,
            events = events,
            difficulty = try { DifficultyLevel.valueOf(difficulty) } catch (_: Exception) { DifficultyLevel.BEGINNER },
            category = try { PatternCategory.valueOf(category) } catch (_: Exception) { PatternCategory.CUSTOM },
            isCustom = isCustom,
            recommendedSubdivision = try { Subdivision.valueOf(recommendedSubdivision) } catch (_: Exception) { Subdivision.QUARTER }
        )
    }

    companion object {
        fun fromDomain(pattern: HandpanPattern): PatternEntity {
            return PatternEntity(
                id = pattern.id,
                title = pattern.title,
                description = pattern.description,
                bpm = pattern.bpm,
                timeSignatureNumerator = pattern.timeSignature.numerator,
                timeSignatureDenominator = pattern.timeSignature.denominator,
                bars = pattern.bars,
                eventsJson = encodeEventsJson(pattern.events),
                difficulty = pattern.difficulty.name,
                category = pattern.category.name,
                isCustom = pattern.isCustom,
                recommendedSubdivision = pattern.recommendedSubdivision.name
            )
        }

        fun encodeEventsJson(events: List<NoteEvent>): String {
            val array = JSONArray()
            for (e in events) {
                val obj = JSONObject()
                obj.put("n", e.noteNumber)
                obj.put("b", e.beatPosition)
                obj.put("d", e.duration)
                obj.put("v", e.velocity.toDouble())
                obj.put("a", e.accent)
                obj.put("r", e.isRest)
                if (e.hand != null) obj.put("h", e.hand)
                obj.put("t", e.technique.name)
                array.put(obj)
            }
            return array.toString()
        }

        fun parseEventsJson(json: String): List<NoteEvent> {
            val list = mutableListOf<NoteEvent>()
            try {
                val array = JSONArray(json)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        NoteEvent(
                            noteNumber = obj.optInt("n", 1),
                            beatPosition = obj.optDouble("b", i.toDouble()),
                            duration = obj.optDouble("d", 1.0),
                            velocity = obj.optDouble("v", 0.85).toFloat(),
                            accent = obj.optBoolean("a", false),
                            isRest = obj.optBoolean("r", false),
                            hand = if (obj.has("h")) obj.getString("h") else null,
                            technique = runCatching {
                                HandpanTechnique.valueOf(obj.optString("t"))
                            }.getOrElse {
                                when {
                                    obj.optBoolean("r", false) -> HandpanTechnique.REST
                                    obj.optInt("n", 1) == 9 -> HandpanTechnique.SLAP
                                    obj.optInt("n", 1) == 0 -> HandpanTechnique.DING
                                    else -> HandpanTechnique.TONE
                                }
                            }
                        )
                    )
                }
            } catch (_: Exception) {
                // Return empty list if malformed
            }
            return list
        }
    }
}
