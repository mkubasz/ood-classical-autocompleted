package com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete

import com.intellij.openapi.diagnostic.logger
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.*
import java.util.concurrent.CancellationException

class AnthropicAutocompleteProvider(
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
    private val contextBudgetChars: Int = DEFAULT_CONTEXT_BUDGET_CHARS,
) : AutocompleteProvider {

    override val capabilities: Set<AutocompleteCapability> = setOf(AutocompleteCapability.INLINE)

    private val httpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 30_000
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun complete(request: AutocompleteRequest): CompletionResponse? {
        if (apiKey.isBlank()) return null

        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            put("stream", false)
            put("system", AUTOCOMPLETE_SYSTEM_PROMPT)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", buildPrompt(request))
                }
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

            CompletionResponse(
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
            logger<AnthropicAutocompleteProvider>().warn("Anthropic autocomplete failed", e)
            null
        }
    }

    override suspend fun completeStreaming(request: AutocompleteRequest): Flow<String>? {
        if (apiKey.isBlank()) return null

        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            put("stream", true)
            put("system", AUTOCOMPLETE_SYSTEM_PROMPT)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", buildPrompt(request))
                }
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
                        val chunk = parseAnthropicSseChunk(data)
                        if (!chunk.isNullOrEmpty()) {
                            send(chunk)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger<AnthropicAutocompleteProvider>().warn("Anthropic streaming failed", e)
            }
        }
    }

    private fun parseAnthropicSseChunk(data: String): String? = try {
        val event = json.parseToJsonElement(data).jsonObject
        val type = event["type"]?.jsonPrimitive?.content
        when (type) {
            "content_block_delta" -> event["delta"]?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
            else -> null
        }
    } catch (_: Exception) { null }

    override fun cancel() {}

    override fun dispose() {
        httpClient.close()
    }

    private fun buildPrompt(request: AutocompleteRequest): String {
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
        private const val DEFAULT_CONTEXT_BUDGET_CHARS = 3_500
        private const val AUTOCOMPLETE_SYSTEM_PROMPT = """
            You are a JetBrains IDE autocomplete engine.
            Continue the code at the cursor.
            Output only the completion text to insert.
            Do not repeat the existing prefix unless necessary.
            Stop before repeating the provided suffix.
        """
    }
}
