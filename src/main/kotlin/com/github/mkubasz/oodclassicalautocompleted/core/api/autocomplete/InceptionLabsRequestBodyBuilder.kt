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
        contextBudget: ContextBudgetPacker.Budget = DEFAULT_BUDGET,
    ): JsonObject {
        val packed = packContext(request, contextBudget)
        val healed = TokenBoundaryDetector.heal(packed.localPrefix, packed.localSuffix)
        val prompt = packed.semanticPrefix + healed.prefix + healed.healedPrefix
        val suffix = healed.suffix
        return buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            put("suffix", suffix)
            putGenerationOptions(request, options)
            mergeExtraBody(options.extraBodyJson)
        }
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

    private fun packContext(
        request: AutocompleteRequest,
        budget: ContextBudgetPacker.Budget,
    ): ContextBudgetPacker.PackedContext {
        val semanticContext = request.inlineContext
            ?.let { InlineModelContextFormatter.formatForCodePrefix(it, request.language) }
            .orEmpty()

        return ContextBudgetPacker.pack(
            semanticContext = semanticContext,
            fullPrefix = request.prefix,
            fullSuffix = request.suffix,
            budget = budget,
        )
    }

    private val DEFAULT_BUDGET = ContextBudgetPacker.Budget(
        totalChars = 4_000,
        minPrefixChars = 1_200,
        minSuffixChars = 600,
        maxSemanticChars = 1_000,
    )
}
