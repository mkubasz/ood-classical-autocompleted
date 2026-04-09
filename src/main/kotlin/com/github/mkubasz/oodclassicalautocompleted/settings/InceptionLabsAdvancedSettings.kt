package com.github.mkubasz.oodclassicalautocompleted.settings

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InceptionLabsGenerationOptions
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InceptionLabsNextEditContextOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal object InceptionLabsAdvancedSettings {

    const val FIM_DEFAULT_MAX_TOKENS = 512
    const val FIM_DEFAULT_PRESENCE_PENALTY = 1.5
    const val FIM_DEFAULT_TEMPERATURE = 0.0
    const val FIM_DEFAULT_TOP_P = 1.0

    const val NEXT_EDIT_DEFAULT_MAX_TOKENS = 8192
    const val NEXT_EDIT_DEFAULT_PRESENCE_PENALTY = 1.0
    const val NEXT_EDIT_DEFAULT_TEMPERATURE = 0.3
    const val NEXT_EDIT_DEFAULT_TOP_P = 0.8

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val fimExtraJsonDisallowedKeys = setOf(
        "model",
        "prompt",
        "suffix",
        "max_tokens",
        "presence_penalty",
        "temperature",
        "top_p",
        "stop",
        "stream",
        "stream_options",
        "diffusing",
    )

    private val nextEditExtraJsonDisallowedKeys = setOf(
        "model",
        "messages",
        "max_tokens",
        "presence_penalty",
        "temperature",
        "top_p",
        "stop",
        "stream",
        "stream_options",
        "diffusing",
    )

    fun validateState(state: PluginSettings.State) {
        validateOptionalInt(
            value = state.inceptionLabsFimMaxTokens,
            fieldName = "Inception FIM max tokens",
            min = 1,
            max = NEXT_EDIT_DEFAULT_MAX_TOKENS,
        )
        validateOptionalDouble(
            value = state.inceptionLabsFimPresencePenalty,
            fieldName = "Inception FIM presence penalty",
            min = -2.0,
            max = 2.0,
        )
        validateOptionalDouble(
            value = state.inceptionLabsFimTemperature,
            fieldName = "Inception FIM temperature",
            min = 0.0,
            max = 1.0,
        )
        validateOptionalDouble(
            value = state.inceptionLabsFimTopP,
            fieldName = "Inception FIM top-p",
            min = 0.0,
            max = 1.0,
        )
        parseStopSequences(
            rawValue = state.inceptionLabsFimStopSequences,
            fieldName = "Inception FIM stop sequences",
        )
        parseExtraBodyJson(
            rawValue = state.inceptionLabsFimExtraBodyJson,
            fieldName = "Inception FIM extra JSON",
            disallowedKeys = fimExtraJsonDisallowedKeys,
        )

        validateOptionalInt(
            value = state.inceptionLabsNextEditMaxTokens,
            fieldName = "Inception Next Edit max tokens",
            min = 1,
            max = NEXT_EDIT_DEFAULT_MAX_TOKENS,
        )
        validateOptionalDouble(
            value = state.inceptionLabsNextEditPresencePenalty,
            fieldName = "Inception Next Edit presence penalty",
            min = -2.0,
            max = 2.0,
        )
        validateOptionalDouble(
            value = state.inceptionLabsNextEditTemperature,
            fieldName = "Inception Next Edit temperature",
            min = 0.0,
            max = 1.0,
        )
        validateOptionalDouble(
            value = state.inceptionLabsNextEditTopP,
            fieldName = "Inception Next Edit top-p",
            min = 0.0,
            max = 1.0,
        )
        parseStopSequences(
            rawValue = state.inceptionLabsNextEditStopSequences,
            fieldName = "Inception Next Edit stop sequences",
        )
        parseExtraBodyJson(
            rawValue = state.inceptionLabsNextEditExtraBodyJson,
            fieldName = "Inception Next Edit extra JSON",
            disallowedKeys = nextEditExtraJsonDisallowedKeys,
        )

        validateRequiredInt(
            value = state.inceptionLabsNextEditLinesAboveCursor,
            fieldName = "Next Edit lines above cursor",
            min = 1,
        )
        validateRequiredInt(
            value = state.inceptionLabsNextEditLinesBelowCursor,
            fieldName = "Next Edit lines below cursor",
            min = 1,
        )
    }

    fun fimOptionsFromState(state: PluginSettings.State): InceptionLabsGenerationOptions {
        validateState(state)
        return InceptionLabsGenerationOptions(
            maxTokens = state.inceptionLabsFimMaxTokens,
            presencePenalty = state.inceptionLabsFimPresencePenalty,
            temperature = state.inceptionLabsFimTemperature,
            topP = state.inceptionLabsFimTopP,
            stopSequences = parseStopSequences(
                rawValue = state.inceptionLabsFimStopSequences,
                fieldName = "Inception FIM stop sequences",
            ),
            extraBodyJson = parseExtraBodyJson(
                rawValue = state.inceptionLabsFimExtraBodyJson,
                fieldName = "Inception FIM extra JSON",
                disallowedKeys = fimExtraJsonDisallowedKeys,
            ),
        )
    }

    fun nextEditOptionsFromState(state: PluginSettings.State): InceptionLabsGenerationOptions {
        validateState(state)
        return InceptionLabsGenerationOptions(
            maxTokens = state.inceptionLabsNextEditMaxTokens,
            presencePenalty = state.inceptionLabsNextEditPresencePenalty,
            temperature = state.inceptionLabsNextEditTemperature,
            topP = state.inceptionLabsNextEditTopP,
            stopSequences = parseStopSequences(
                rawValue = state.inceptionLabsNextEditStopSequences,
                fieldName = "Inception Next Edit stop sequences",
            ),
            extraBodyJson = parseExtraBodyJson(
                rawValue = state.inceptionLabsNextEditExtraBodyJson,
                fieldName = "Inception Next Edit extra JSON",
                disallowedKeys = nextEditExtraJsonDisallowedKeys,
            ),
            diffusing = state.inceptionLabsNextEditDiffusing,
            reasoningEffort = state.inceptionLabsNextEditReasoningEffort.takeIf { it.isNotBlank() },
        )
    }

    fun nextEditContextOptionsFromState(state: PluginSettings.State): InceptionLabsNextEditContextOptions {
        validateState(state)
        return InceptionLabsNextEditContextOptions(
            linesAboveCursor = state.inceptionLabsNextEditLinesAboveCursor,
            linesBelowCursor = state.inceptionLabsNextEditLinesBelowCursor,
        )
    }

    fun parseStopSequences(rawValue: String, fieldName: String): List<String> {
        val sequences = rawValue
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()

        if (sequences.size > MAX_STOP_SEQUENCES) {
            throw IllegalArgumentException("$fieldName supports at most $MAX_STOP_SEQUENCES sequences.")
        }

        return sequences
    }

    fun parseExtraBodyJson(
        rawValue: String,
        fieldName: String,
        disallowedKeys: Set<String>,
    ): JsonObject? {
        val normalized = rawValue.trim()
        if (normalized.isEmpty()) return null

        val element = try {
            json.parseToJsonElement(normalized)
        } catch (e: Exception) {
            throw IllegalArgumentException("$fieldName must be a valid JSON object.", e)
        }

        val jsonObject = element as? JsonObject
            ?: throw IllegalArgumentException("$fieldName must be a JSON object.")

        val conflictingKeys = jsonObject.keys.intersect(disallowedKeys)
        if (conflictingKeys.isNotEmpty()) {
            throw IllegalArgumentException(
                "$fieldName cannot override reserved keys: ${conflictingKeys.sorted().joinToString(", ")}.",
            )
        }

        return jsonObject
    }

    fun validateOptionalInt(
        value: Int?,
        fieldName: String,
        min: Int,
        max: Int? = null,
    ) {
        if (value == null) return
        if (value < min || (max != null && value > max)) {
            throw IllegalArgumentException(buildRangeMessage(fieldName, min.toDouble(), max?.toDouble()))
        }
    }

    fun validateOptionalDouble(
        value: Double?,
        fieldName: String,
        min: Double,
        max: Double,
    ) {
        if (value == null) return
        if (value < min || value > max) {
            throw IllegalArgumentException(buildRangeMessage(fieldName, min, max))
        }
    }

    fun validateRequiredInt(
        value: Int,
        fieldName: String,
        min: Int,
    ) {
        if (value < min) {
            throw IllegalArgumentException(buildRangeMessage(fieldName, min.toDouble(), null))
        }
    }

    private fun buildRangeMessage(fieldName: String, min: Double, max: Double?): String = when (max) {
        null -> "$fieldName must be at least ${formatNumber(min)}."
        else -> "$fieldName must be between ${formatNumber(min)} and ${formatNumber(max)}."
    }

    private fun formatNumber(value: Double): String =
        if (value == value.toInt().toDouble()) {
            value.toInt().toString()
        } else {
            value.toString()
        }

    private const val MAX_STOP_SEQUENCES = 4
}
