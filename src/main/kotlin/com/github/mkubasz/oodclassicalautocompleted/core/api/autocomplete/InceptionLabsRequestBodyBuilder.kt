package com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal object InceptionLabsRequestBodyBuilder {

    fun buildFimBody(
        model: String,
        request: AutocompleteRequest,
        options: InceptionLabsGenerationOptions,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("prompt", buildFimPrompt(request))
        put("suffix", request.suffix.take(FIM_SUFFIX_CHARS))
        putGenerationOptions(request, options)
        mergeExtraBody(options.extraBodyJson)
    }

    fun buildNextEditBody(
        model: String,
        prompt: String,
        options: InceptionLabsGenerationOptions,
    ): JsonObject = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            add(
                buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            )
        }
        putGenerationOptions(request = null, options = options)
        mergeExtraBody(options.extraBodyJson)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putGenerationOptions(
        request: AutocompleteRequest?,
        options: InceptionLabsGenerationOptions,
    ) {
        options.maxTokens?.let { put("max_tokens", it) }
        options.presencePenalty?.let { put("presence_penalty", it) }
        options.temperature?.let { put("temperature", it) }
        options.topP?.let { put("top_p", it) }
        val stopSequences = request
            ?.let { InceptionLabsFimStopSequencePolicy.merge(it, options.stopSequences) }
            ?: options.stopSequences
        if (stopSequences.isNotEmpty()) {
            putJsonArray("stop") {
                stopSequences.forEach { add(JsonPrimitive(it)) }
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.mergeExtraBody(extraBody: JsonObject?) {
        extraBody?.forEach { (key, value) -> put(key, value) }
    }

    private fun buildFimPrompt(request: AutocompleteRequest): String {
        val contextPrefix = request.inlineContext
            ?.let { InlineModelContextFormatter.formatForCodePrefix(it, request.language) }
            ?.take(MAX_INLINE_CONTEXT_CHARS)
            .orEmpty()

        if (contextPrefix.isEmpty()) {
            return request.prefix.takeLast(FIM_PREFIX_CHARS)
        }

        val localPrefixBudget = (FIM_PREFIX_CHARS - contextPrefix.length).coerceAtLeast(MIN_LOCAL_PREFIX_CHARS)
        return contextPrefix + request.prefix.takeLast(localPrefixBudget)
    }

    private const val FIM_PREFIX_CHARS = 2_500
    private const val FIM_SUFFIX_CHARS = 1_500
    private const val MAX_INLINE_CONTEXT_CHARS = 900
    private const val MIN_LOCAL_PREFIX_CHARS = 1_200
}
