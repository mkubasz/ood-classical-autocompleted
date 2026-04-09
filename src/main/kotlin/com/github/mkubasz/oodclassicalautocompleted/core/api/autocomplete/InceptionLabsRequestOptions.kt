package com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete

import kotlinx.serialization.json.JsonObject

data class InceptionLabsGenerationOptions(
    val maxTokens: Int? = null,
    val presencePenalty: Double? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val stopSequences: List<String> = emptyList(),
    val extraBodyJson: JsonObject? = null,
)

data class InceptionLabsNextEditContextOptions(
    val linesAboveCursor: Int = DEFAULT_LINES_ABOVE_CURSOR,
    val linesBelowCursor: Int = DEFAULT_LINES_BELOW_CURSOR,
) {
    companion object {
        const val DEFAULT_LINES_ABOVE_CURSOR = 5
        const val DEFAULT_LINES_BELOW_CURSOR = 10
    }
}
