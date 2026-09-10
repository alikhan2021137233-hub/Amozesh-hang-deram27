package com.example.model

/**
 * Represents a musical note, percussive slap strike, or rest event in a Handpan pattern.
 *
 * @param noteNumber The numeric note index:
 *   - 0: Ding (نت بم مرکزی)
 *   - 1..8: Surrounding tonefields (نت‌های ۱ تا ۸ دور دایره ساز)
 *   - 9: Slap / Tak (ضربه اسلپ روی بدنه/شانه ساز با علامت S)
 * @param beatPosition Position in the pattern in beat units (e.g. 0.0, 1.0, 2.5).
 * @param duration Duration in beat units (1.0 = quarter note in 4/4).
 * @param velocity Dynamic volume factor from 0.0 to 1.0.
 * @param accent If true, played with louder dynamic velocity and visual accent.
 * @param isRest If true, indicates a silent rest in this beat slot.
 * @param hand Optional playing hand indicator ("R" for راست / Right, "L" for چپ / Left).
 */
data class NoteEvent(
    val noteNumber: Int,
    val beatPosition: Double,
    val duration: Double = 1.0,
    val velocity: Float = 0.85f,
    val accent: Boolean = false,
    val isRest: Boolean = false,
    val hand: String? = null,
    val technique: HandpanTechnique = when {
        isRest -> HandpanTechnique.REST
        noteNumber == HandpanNote.SLAP_NUMBER -> HandpanTechnique.SLAP
        noteNumber == HandpanNote.DING_NUMBER -> HandpanTechnique.DING
        else -> HandpanTechnique.TONE
    }
) {
    init {
        require(isRest || (noteNumber in 0..9)) {
            "Invalid note number: $noteNumber. Note must be between 0 (Ding) and 8 (High tone) or 9 (Slap), or marked as rest."
        }
        require(beatPosition >= 0.0) { "Beat position must be non-negative" }
        require(duration > 0.0) { "Duration must be positive" }
        require(velocity in 0.0f..1.0f) { "Velocity must be between 0.0 and 1.0" }
    }

    val isDing: Boolean get() = (noteNumber == HandpanNote.DING_NUMBER && !isRest)
    val isSlap: Boolean get() = (noteNumber == HandpanNote.SLAP_NUMBER && !isRest)

    val displaySymbol: String
        get() = NotationRenderer.render(noteNumber, technique = technique)

    val persianLabel: String
        get() = HandpanNote.getPersianLabel(noteNumber, isRest)
}
