package com.github.mkubasz.oodclassicalautocompleted.completion.providers

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ProviderRequest
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ProviderResponse
import com.github.mkubasz.oodclassicalautocompleted.completion.providers.shared.ContextBudgetPacker
import com.github.mkubasz.oodclassicalautocompleted.completion.providers.inception.InceptionLabsGenerationOptions
import com.github.mkubasz.oodclassicalautocompleted.completion.providers.inception.InceptionLabsNextEditContextOptions
import com.github.mkubasz.oodclassicalautocompleted.completion.providers.inception.InceptionLabsRequestBodyBuilder
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineCompletionCandidate
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.NextEditCompletionCandidate
import com.github.mkubasz.oodclassicalautocompleted.completion.providers.shared.NextEditInlineAdapter
import com.github.mkubasz.oodclassicalautocompleted.completion.providers.shared.PromptContextFormatter
import com.intellij.openapi.diagnostic.logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.CancellationException
import kotlin.math.exp

internal class AnthropicProviderRuntime(
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
    private val contextBudgetChars: Int,
    private val httpClient: HttpClient = defaultHttpClient(),
) {
    private val json = defaultJson()
    private val log = logger<AnthropicProviderRuntime>()

    suspend fun complete(request: ProviderRequest): ProviderResponse? {
        if (apiKey.isBlank()) return null

        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            put("stream", false)
            put("system", AUTOCOMPLETE_SYSTEM_PROMPT)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildPrompt(request))
                })
            }
        }

        return try {
            val response = httpClient.post(baseUrl) {
                contentType(ContentType.Application.Json)
                header("x-api-key", apiKey)
                header("anthropic-version", API_VERSION)
                setBody(json.encodeToString(JsonObject.serializer(), body))
            }
            if (response.status != HttpStatusCode.OK) return null

            val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val text = payload["content"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.content
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null

            ProviderResponse(
                inlineCandidates = listOf(
                    InlineCompletionCandidate(
                        text = text,
                        insertionOffset = request.cursorOffset ?: request.prefix.length,
                    )
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Anthropic autocomplete failed", e)
            null
        }
    }

    suspend fun completeStreaming(request: ProviderRequest): Flow<String>? {
        if (apiKey.isBlank()) return null

        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            put("stream", true)
            put("system", AUTOCOMPLETE_SYSTEM_PROMPT)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildPrompt(request))
                })
            }
        }
        val bodyText = json.encodeToString(JsonObject.serializer(), body)

        return channelFlow {
            try {
                httpClient.preparePost(baseUrl) {
                    contentType(ContentType.Application.Json)
                    header("x-api-key", apiKey)
                    header("anthropic-version", API_VERSION)
                    setBody(bodyText)
                }.execute { response ->
                    if (response.status != HttpStatusCode.OK) return@execute
                    val channel = response.bodyAsChannel()
                    while (!channel.isClosedForRead) {
                        val line = channel.readUTF8Line() ?: break
                        if (!line.startsWith("data: ")) continue
                        val data = line.removePrefix("data: ").trim()
                        parseAnthropicSseChunk(data)?.takeIf(String::isNotEmpty)?.let { send(it) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Anthropic streaming failed", e)
            }
        }
    }

    fun dispose() {
        httpClient.close()
    }

    private fun parseAnthropicSseChunk(data: String): String? = try {
        val event = json.parseToJsonElement(data).jsonObject
        when (event["type"]?.jsonPrimitive?.content) {
            "content_block_delta" -> event["delta"]?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun buildPrompt(request: ProviderRequest): String {
        val semanticContext = PromptContextFormatter.formatForInstructionPrompt(request)
        val packed = ContextBudgetPacker.pack(
            semanticContext = semanticContext,
            fullPrefix = request.prefix,
            fullSuffix = request.suffix,
            budget = ContextBudgetPacker.anthropicBudget(contextBudgetChars),
        )
        return buildString {
            appendLine("Complete the code at the cursor.")
            appendLine("Return only the text to insert, with no markdown, explanations, or code fences.")
            appendLine("Prefer a short continuation that fits naturally before the provided suffix.")
            if (packed.semanticPrefix.isNotBlank()) {
                appendLine("<ide_context>")
                append(packed.semanticPrefix)
                appendLine("</ide_context>")
            }
            if (!request.filePath.isNullOrBlank()) {
                appendLine("File: ${request.filePath}")
            }
            if (!request.language.isNullOrBlank()) {
                appendLine("Language: ${request.language}")
            }
            appendLine("<prefix>")
            appendLine(packed.localPrefix)
            appendLine("</prefix>")
            appendLine("<suffix>")
            appendLine(packed.localSuffix)
            appendLine("</suffix>")
        }
    }

    companion object {
        private const val API_VERSION = "2023-06-01"
        private const val MAX_TOKENS = 96
        private const val AUTOCOMPLETE_SYSTEM_PROMPT = """
            You are a JetBrains IDE autocomplete engine.
            Continue the code at the cursor.
            Output only the completion text to insert.
            Do not repeat the existing prefix unless necessary.
            Stop before repeating the provided suffix.
        """
    }
}

internal class InceptionLabsFimRuntime(
    private val apiKey: String,
    baseUrl: String,
    private val model: String,
    private val generationOptions: InceptionLabsGenerationOptions,
    private val contextBudgetChars: Int,
    private val httpClient: HttpClient = defaultHttpClient(),
) {
    private val endpoint = "${baseUrl.trimEnd('/')}/fim/completions"
    private val json = defaultJson()
    private val log = logger<InceptionLabsFimRuntime>()

    suspend fun complete(request: ProviderRequest): ProviderResponse? {
        if (apiKey.isBlank()) return null

        val body = InceptionLabsRequestBodyBuilder.buildFimBody(
            model = model,
            request = request,
            options = generationOptions,
            contextBudget = ContextBudgetPacker.inceptionFimBudget(contextBudgetChars),
        )

        return try {
            log.info("FIM request to $endpoint")
            val response = httpClient.post(endpoint) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(json.encodeToString(JsonObject.serializer(), body))
            }
            if (response.status != HttpStatusCode.OK) {
                log.warn("FIM response status: ${response.status} body: ${response.bodyAsText().take(200)}")
                return null
            }

            val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val choice = payload["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?: return null
            val text = choice["text"]
                ?.jsonPrimitive
                ?.content
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val confidenceScore = parseLogprobConfidence(choice)

            ProviderResponse(
                inlineCandidates = listOf(
                    InlineCompletionCandidate(
                        text = text,
                        insertionOffset = request.cursorOffset ?: request.prefix.length,
                        confidenceScore = confidenceScore,
                    )
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("FIM request failed", e)
            null
        }
    }

    suspend fun completeStreaming(request: ProviderRequest): Flow<String>? {
        if (apiKey.isBlank()) return null

        val body = InceptionLabsRequestBodyBuilder.buildFimBody(
            model = model,
            request = request,
            options = generationOptions,
            contextBudget = ContextBudgetPacker.inceptionFimBudget(contextBudgetChars),
        )
        val streamBody = buildJsonObject {
            body.forEach { (k, v) -> put(k, v) }
            put("stream", JsonPrimitive(true))
        }
        val bodyText = json.encodeToString(JsonObject.serializer(), streamBody)

        return channelFlow {
            try {
                httpClient.preparePost(endpoint) {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer $apiKey")
                    setBody(bodyText)
                }.execute { response ->
                    if (response.status != HttpStatusCode.OK) {
                        log.warn("FIM streaming status: ${response.status}")
                        return@execute
                    }
                    val channel = response.bodyAsChannel()
                    while (!channel.isClosedForRead) {
                        val line = channel.readUTF8Line() ?: break
                        if (!line.startsWith("data: ")) continue
                        val data = line.removePrefix("data: ").trim()
                        if (data == "[DONE]") break
                        parseSseChunk(data)?.takeIf(String::isNotEmpty)?.let { send(it) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("FIM streaming failed", e)
            }
        }
    }

    fun dispose() {
        httpClient.close()
    }

    private fun parseLogprobConfidence(choice: JsonObject): Double? {
        val logprobs = choice["logprobs"]?.jsonObject ?: return null
        val tokenLogprobs = logprobs["token_logprobs"]?.jsonArray ?: return null
        if (tokenLogprobs.isEmpty()) return null

        val values = tokenLogprobs.mapNotNull { it.jsonPrimitive.doubleOrNull }
        if (values.isEmpty()) return null

        val meanLogprob = values.sum() / values.size
        return exp(meanLogprob).coerceIn(0.0, 1.0)
    }

    private fun parseSseChunk(data: String): String? = try {
        val payload = json.parseToJsonElement(data).jsonObject
        payload["choices"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("text")
            ?.jsonPrimitive
            ?.content
    } catch (_: Exception) {
        null
    }
}

internal class InceptionLabsNextEditRuntime(
    private val apiKey: String,
    baseUrl: String,
    private val model: String,
    private val generationOptions: InceptionLabsGenerationOptions,
    private val contextOptions: InceptionLabsNextEditContextOptions,
    private val httpClient: HttpClient = defaultHttpClient(),
) {
    private val endpoint = "${baseUrl.trimEnd('/')}/edit/completions"
    private val json = defaultJson()
    private val log = logger<InceptionLabsNextEditRuntime>()

    suspend fun complete(request: ProviderRequest): ProviderResponse? {
        if (apiKey.isBlank()) return null

        val editableRegion = NextEditInlineAdapter.extractRegion(
            request = request,
            linesAboveCursor = contextOptions.linesAboveCursor,
            linesBelowCursor = contextOptions.linesBelowCursor,
        )
        val prompt = buildNextEditPrompt(request, editableRegion)
        val body = InceptionLabsRequestBodyBuilder.buildNextEditBody(
            model = model,
            prompt = prompt,
            options = generationOptions,
        )

        return try {
            log.info("NextEdit request to $endpoint")
            val response = httpClient.post(endpoint) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(json.encodeToString(JsonObject.serializer(), body))
            }
            if (response.status != HttpStatusCode.OK) {
                log.warn("NextEdit response status: ${response.status} body: ${response.bodyAsText().take(200)}")
                return null
            }

            val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val updatedRegionText = payload["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.content
                ?.trim()
                ?.let(::extractCodeFromResponse)
                ?: return null

            val edit = NextEditInlineAdapter.deriveEdit(editableRegion, updatedRegionText)
                ?.takeIf { it.startOffset <= it.endOffset }
            val inlineCandidates = inlineCandidatesFor(
                editableRegion = editableRegion,
                updatedRegionText = updatedRegionText,
                cursorOffset = request.cursorOffset,
            )

            if (edit == null && inlineCandidates.isEmpty()) return null

            ProviderResponse(
                inlineCandidates = inlineCandidates,
                nextEditCandidates = edit?.let {
                    listOf(
                        NextEditCompletionCandidate(
                            startOffset = it.startOffset,
                            endOffset = it.endOffset,
                            replacementText = it.replacementText,
                        )
                    )
                }.orEmpty()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("NextEdit request failed", e)
            null
        }
    }

    suspend fun completeStreaming(request: ProviderRequest): Flow<String>? {
        if (apiKey.isBlank()) return null

        val editableRegion = NextEditInlineAdapter.extractRegion(
            request = request,
            linesAboveCursor = contextOptions.linesAboveCursor,
            linesBelowCursor = contextOptions.linesBelowCursor,
        )
        val prompt = buildNextEditPrompt(request, editableRegion)
        val body = InceptionLabsRequestBodyBuilder.buildNextEditBody(
            model = model,
            prompt = prompt,
            options = generationOptions,
        )
        val streamBody = buildJsonObject {
            body.forEach { (k, v) -> put(k, v) }
            put("stream", JsonPrimitive(true))
        }
        val bodyText = json.encodeToString(JsonObject.serializer(), streamBody)

        return channelFlow {
            try {
                httpClient.preparePost(endpoint) {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer $apiKey")
                    setBody(bodyText)
                }.execute { response ->
                    if (response.status != HttpStatusCode.OK) {
                        log.warn("NextEdit streaming status: ${response.status}")
                        return@execute
                    }
                    val channel = response.bodyAsChannel()
                    while (!channel.isClosedForRead) {
                        val line = channel.readUTF8Line() ?: break
                        if (!line.startsWith("data: ")) continue
                        val data = line.removePrefix("data: ").trim()
                        if (data == "[DONE]") break
                        parseSseChunk(data)?.takeIf(String::isNotEmpty)?.let { send(it) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("NextEdit streaming failed", e)
            }
        }
    }

    fun dispose() {
        httpClient.close()
    }

    private fun parseSseChunk(data: String): String? = try {
        val event = json.parseToJsonElement(data).jsonObject
        event["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("delta")?.jsonObject
            ?.get("content")?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }

    private fun buildNextEditPrompt(
        request: ProviderRequest,
        editableRegion: NextEditInlineAdapter.EditableRegion,
    ): String = buildString {
        appendLine("<|recently_viewed_code_snippets|>")
        request.recentlyViewedSnippets?.forEach { snippet ->
            appendLine("<|recently_viewed_code_snippet|>")
            appendLine("code_snippet_file_path: ${snippet.filePath}")
            appendLine(snippet.content)
            appendLine("<|/recently_viewed_code_snippet|>")
        }
        appendLine("<|/recently_viewed_code_snippets|>")

        appendLine("<|current_file_content|>")
        if (!request.filePath.isNullOrBlank()) {
            appendLine("current_file_path: ${request.filePath}")
        }
        if (editableRegion.before.isNotEmpty()) {
            append(editableRegion.before)
            if (!editableRegion.before.endsWith("\n")) {
                appendLine()
            }
        }
        appendLine("<|code_to_edit|>")
        append(NextEditInlineAdapter.renderWithCursor(editableRegion))
        if (!editableRegion.text.endsWith("\n")) {
            appendLine()
        }
        appendLine("<|/code_to_edit|>")
        if (editableRegion.after.isNotEmpty()) {
            append(editableRegion.after)
            if (!editableRegion.after.endsWith("\n")) {
                appendLine()
            }
        }
        appendLine("<|/current_file_content|>")

        appendLine("<|retrieved_context|>")
        request.retrievedChunks?.forEach { chunk ->
            appendLine("retrieved_file_path: ${chunk.filePath}")
            appendLine(chunk.content)
        }
        appendLine("<|/retrieved_context|>")

        appendLine("<|packed_context|>")
        request.packedContextSummary?.takeIf { it.isNotBlank() }?.let(::appendLine)
        appendLine("<|/packed_context|>")

        appendLine("<|git_diff|>")
        request.gitDiff?.takeIf { it.isNotBlank() }?.let(::appendLine)
        appendLine("<|/git_diff|>")

        appendLine("<|edit_diff_history|>")
        request.editDiffHistory?.forEach(::appendLine)
        appendLine("<|/edit_diff_history|>")
    }

    private fun extractCodeFromResponse(response: String): String {
        val trimmed = response.trim()
        if (!trimmed.startsWith("```")) return trimmed

        val firstNewline = trimmed.indexOf('\n')
        val lastBackticks = trimmed.lastIndexOf("```")
        if (firstNewline >= 0 && lastBackticks > firstNewline) {
            return trimmed.substring(firstNewline + 1, lastBackticks).trim()
        }
        return trimmed
    }

    private fun inlineCandidatesFor(
        editableRegion: NextEditInlineAdapter.EditableRegion,
        updatedRegionText: String,
        cursorOffset: Int?,
    ): List<InlineCompletionCandidate> {
        val absoluteCursorOffset = cursorOffset ?: return emptyList()
        val insertion = NextEditInlineAdapter.deriveInlineInsertion(editableRegion, updatedRegionText)
            ?: return emptyList()
        if (insertion.offset != absoluteCursorOffset) return emptyList()
        if (insertion.text.isBlank()) return emptyList()

        return listOf(
            InlineCompletionCandidate(
                text = insertion.text,
                insertionOffset = absoluteCursorOffset,
                isExactInsertion = true,
            )
        )
    }
}

private fun defaultJson(): Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
    engine {
        requestTimeout = 30_000
    }
}
