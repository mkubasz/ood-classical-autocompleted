package com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete

import com.intellij.openapi.diagnostic.logger
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class AnthropicAutocompleteProvider(
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
) : AutocompleteProvider {

    private val httpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 30_000
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val cancelled = AtomicBoolean(false)

    override suspend fun complete(request: AutocompleteRequest): AutocompleteResult? {
        if (apiKey.isBlank()) return null
        cancelled.set(false)

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

            AutocompleteResult(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger<AnthropicAutocompleteProvider>().warn("Anthropic autocomplete failed", e)
            null
        }
    }

    override fun cancel() {
        cancelled.set(true)
    }

    override fun dispose() {
        cancel()
        httpClient.close()
    }

    private fun buildPrompt(request: AutocompleteRequest): String = buildString {
        appendLine("Complete the code at the cursor.")
        appendLine("Return only the text to insert, with no markdown, explanations, or code fences.")
        appendLine("Prefer a short continuation that fits naturally before the provided suffix.")
        if (!request.filePath.isNullOrBlank()) {
            appendLine("File: ${request.filePath}")
        }
        if (!request.language.isNullOrBlank()) {
            appendLine("Language: ${request.language}")
        }
        appendLine("<prefix>")
        appendLine(request.prefix.takeLast(CONTEXT_CHARS))
        appendLine("</prefix>")
        appendLine("<suffix>")
        appendLine(request.suffix.take(CONTEXT_CHARS))
        appendLine("</suffix>")
    }

    companion object {
        private const val API_VERSION = "2023-06-01"
        private const val CONTEXT_CHARS = 1_500
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
