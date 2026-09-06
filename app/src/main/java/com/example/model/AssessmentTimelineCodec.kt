package com.example.model

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

sealed class AssessmentTimelineDecodeResult {
    data class Success(val timeline: AssessmentTimeline) : AssessmentTimelineDecodeResult()
    data class Failure(val reason: String) : AssessmentTimelineDecodeResult()
}

/** Versioned, deterministic representation for finalized assessment timelines. */
object AssessmentTimelineCodec {
    private const val CURRENT_SCHEMA_VERSION = 1
    private const val CURRENT_EVENT_SCHEMA_VERSION = 2

    fun encode(timeline: AssessmentTimeline, timelineRevision: Long? = null): String {
        val events = timeline.snapshot()
        validateEvents(events)
        val sessionId = timeline.sessionId()
        return JSONObject().apply {
            put("schemaVersion", CURRENT_SCHEMA_VERSION)
            put("eventSchemaVersion", CURRENT_EVENT_SCHEMA_VERSION)
            timelineRevision?.let { put("timelineRevision", it) }
            put("sessionId", sessionId ?: JSONObject.NULL)
            put("events", JSONArray().apply {
                events.sortedBy { it.eventOrdinal }.forEach { put(eventJson(it)) }
            })
        }.toString()
    }

    fun checksum(payload: String): String = MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun decode(json: String): AssessmentTimelineDecodeResult = runCatching {
        val root = JSONObject(json)
        if (root.optInt("schemaVersion", -1) != CURRENT_SCHEMA_VERSION) {
            return AssessmentTimelineDecodeResult.Failure("Unsupported assessment timeline schema version")
        }
        if (root.optInt("eventSchemaVersion", -1) != CURRENT_EVENT_SCHEMA_VERSION) {
            return AssessmentTimelineDecodeResult.Failure("Unsupported assessment event schema version")
        }
        val sessionId = nullableString(root, "sessionId")
        val array = root.optJSONArray("events")
            ?: throw IllegalArgumentException("Missing assessment timeline events")
        val events = (0 until array.length()).map { index -> eventFromJson(array.getJSONObject(index)) }
            .sortedBy { it.eventOrdinal }
        validateEvents(events, sessionId)

        AssessmentTimeline().also { timeline ->
            sessionId?.let(timeline::bindToSession)
            events.forEach(timeline::append)
        }.let(AssessmentTimelineDecodeResult::Success)
    }.getOrElse { AssessmentTimelineDecodeResult.Failure(it.message ?: "Malformed assessment timeline") }

    private fun eventJson(event: AssessmentTimelineEvent) = JSONObject().apply {
        put("eventId", event.eventId)
        put("sessionId", event.sessionId)
        put("assessmentSessionId", event.assessmentSessionId)
        put("eventOrdinal", event.eventOrdinal)
        putNullable("loopId", event.loopId)
        put("sequenceIndex", event.sequenceIndex)
        putNullable("patternId", event.patternId)
        putNullable("targetId", event.targetId)
        putNullable("obligationId", event.obligationId)
        putNullable("targetNoteId", event.targetNoteId)
        putNullable("expectedNote", event.expectedNote)
        put("expectedNotes", JSONArray(event.expectedNotes.toList().sorted()))
        putNullable("detectedNote", event.detectedNote)
        put("eventType", event.eventType.name)
        putNullable("expectedTimestampNanos", event.expectedTimestampNanos)
        putNullable("detectedTimestampNanos", event.detectedTimestampNanos)
        putNullable("deviationNanos", event.deviationNanos)
        putNullable("timingResult", event.timingResult?.let {
            JSONObject().put("status", it.status.name).put("deviationNanos", it.deviationNanos)
        })
        put("confidence", event.confidence)
        put("source", event.source)
        put("isConsumed", event.isConsumed)
        putNullable("signalQuality", event.signalQuality)
        putNullable("measuredAmplitude", event.measuredAmplitude)
        putNullable("measuredVelocity", event.measuredVelocity)
        putNullable("accentStrength", event.accentStrength)
        putNullable("expectedTechnique", event.expectedTechnique?.name)
        putNullable("detectedTechnique", event.detectedTechnique?.name)
        putNullable("subdivision", event.subdivision?.name)
        putNullable("beatPosition", event.beatPosition)
        putNullable("expectedTimingWindow", event.expectedTimingWindow?.let {
            JSONObject()
                .put("perfectWindowNanos", it.perfectWindowNanos)
                .put("excellentWindowNanos", it.excellentWindowNanos)
                .put("goodWindowNanos", it.goodWindowNanos)
                .put("missWindowNanos", it.missWindowNanos)
        })
        putNullable("targetBpm", event.targetBpm)
        putNullable("durationNanos", event.durationNanos)
        put("sessionValidity", event.sessionValidity.name)
        putNullable("classification", event.classification?.name)
    }

    private fun eventFromJson(json: JSONObject) = AssessmentTimelineEvent(
        eventId = json.getString("eventId"),
        sessionId = json.getString("sessionId"),
        eventOrdinal = json.getLong("eventOrdinal"),
        assessmentSessionId = json.getString("assessmentSessionId"),
        loopId = nullableString(json, "loopId"),
        sequenceIndex = json.getInt("sequenceIndex"),
        patternId = nullableString(json, "patternId"),
        targetId = nullableString(json, "targetId"),
        obligationId = nullableString(json, "obligationId"),
        targetNoteId = nullableString(json, "targetNoteId"),
        expectedNote = nullableInt(json, "expectedNote"),
        expectedNotes = requiredArray(json, "expectedNotes").let { array ->
            (0 until array.length()).mapTo(linkedSetOf()) { array.getInt(it) }
        },
        detectedNote = nullableInt(json, "detectedNote"),
        eventType = enumValue(json.getString("eventType")),
        expectedTimestampNanos = nullableLong(json, "expectedTimestampNanos"),
        detectedTimestampNanos = nullableLong(json, "detectedTimestampNanos"),
        deviationNanos = nullableLong(json, "deviationNanos"),
        timingResult = nullableObject(json, "timingResult")?.let {
            TimingResult(enumValue(it.getString("status")), it.getLong("deviationNanos"))
        },
        confidence = json.getDouble("confidence").toFloat(),
        source = json.getString("source"),
        signalQuality = nullableFloat(json, "signalQuality"),
        measuredAmplitude = nullableFloat(json, "measuredAmplitude"),
        measuredVelocity = nullableFloat(json, "measuredVelocity"),
        accentStrength = nullableFloat(json, "accentStrength"),
        expectedTechnique = nullableString(json, "expectedTechnique")?.let { enumValue<HandpanTechnique>(it) },
        detectedTechnique = nullableString(json, "detectedTechnique")?.let { enumValue<HandpanTechnique>(it) },
        subdivision = nullableString(json, "subdivision")?.let { enumValue<Subdivision>(it) },
        beatPosition = nullableDouble(json, "beatPosition"),
        expectedTimingWindow = nullableObject(json, "expectedTimingWindow")?.let {
            TimingToleranceProfile(
                perfectWindowNanos = it.getLong("perfectWindowNanos"),
                excellentWindowNanos = it.getLong("excellentWindowNanos"),
                goodWindowNanos = it.getLong("goodWindowNanos"),
                missWindowNanos = it.getLong("missWindowNanos")
            )
        },
        targetBpm = nullableInt(json, "targetBpm"),
        durationNanos = nullableLong(json, "durationNanos"),
        sessionValidity = enumValue(json.getString("sessionValidity")),
        classification = nullableString(json, "classification")?.let { enumValue<StrikeClassification>(it) },
        isConsumed = json.optBoolean("isConsumed", false)
    )

    private fun validateEvents(events: List<AssessmentTimelineEvent>, sessionId: String? = null) {
        if (events.isEmpty()) {
            require(sessionId == null || sessionId.isNotBlank()) { "Assessment session id must not be blank" }
            return
        }
        val expectedSessionId = sessionId ?: events.first().sessionId
        require(expectedSessionId.isNotBlank()) { "Assessment session id must not be blank" }
        require(events.all { it.sessionId == expectedSessionId }) { "Assessment events must belong to one session" }
        require(events.all { it.assessmentSessionId == it.sessionId }) {
            "Assessment event session identity mismatch"
        }
        require(events.map { it.eventId }.distinct().size == events.size) {
            "Duplicate assessment event id"
        }
        require(events.all { it.eventOrdinal >= 0L }) {
            "Assessment event ordinal must not be negative"
        }
        require(events.map { it.eventOrdinal }.distinct().size == events.size) {
            "Duplicate assessment event ordinal"
        }
        require(events.map { it.eventOrdinal }.sorted() == (0L until events.size.toLong()).toList()) {
            "Assessment event ordinals must be contiguous from zero"
        }
        require(events.all { it.confidence in 0f..1f }) {
            "Assessment confidence must be between 0 and 1"
        }
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

    private fun nullableString(json: JSONObject, key: String): String? =
        if (json.isNull(key)) null else json.getString(key)

    private fun nullableInt(json: JSONObject, key: String): Int? =
        if (json.isNull(key)) null else json.getInt(key)

    private fun nullableLong(json: JSONObject, key: String): Long? =
        if (json.isNull(key)) null else json.getLong(key)

    private fun nullableDouble(json: JSONObject, key: String): Double? =
        if (json.isNull(key)) null else json.getDouble(key)

    private fun nullableFloat(json: JSONObject, key: String): Float? =
        if (json.isNull(key)) null else json.getDouble(key).toFloat()

    private fun nullableObject(json: JSONObject, key: String): JSONObject? =
        if (json.isNull(key)) null else json.getJSONObject(key)

    private fun requiredArray(json: JSONObject, key: String): JSONArray =
        json.optJSONArray(key) ?: throw IllegalArgumentException("Missing assessment event field: $key")

    private inline fun <reified T : Enum<T>> enumValue(value: String): T = enumValueOf(value)
}
