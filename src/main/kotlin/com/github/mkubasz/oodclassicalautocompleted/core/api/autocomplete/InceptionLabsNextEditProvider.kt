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

class InceptionLabsNextEditProvider(
    private val apiKey: String,
    baseUrl: String = DEFAULT_BASE_URL,
    private val model: String = DEFAULT_MODEL,
) : AutocompleteProvider {

    private val endpoint = "${baseUrl.trimEnd('/')}/edit/completions"

    private val httpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 30_000
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val log = logger<InceptionLabsNextEditProvider>()
    private val cancelled = AtomicBoolean(false)

    override suspend fun complete(request: AutocompleteRequest): AutocompleteResult? {
        if (apiKey.isBlank()) return null
        cancelled.set(false)

        val editableRegion = NextEditInlineAdapter.extractRegion(request, EDIT_REGION_RADIUS)
        val prompt = buildNextEditPrompt(request, editableRegion)

        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            }
        }

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
            val insertion = payload["choices"]
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
                ?.let { NextEditInlineAdapter.deriveInsertion(editableRegion, it) }
                ?.takeIf { it.text.isNotBlank() }
                ?: return null

            AutocompleteResult(
                text = insertion.text,
                insertionOffset = insertion.offset,
                isExactInsertion = true,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("NextEdit request failed", e)
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

    private fun buildNextEditPrompt(
        request: AutocompleteRequest,
        editableRegion: NextEditInlineAdapter.EditableRegion,
    ): String = buildString {
        // Section 1: Recently viewed code snippets
        appendLine("<|recently_viewed_code_snippets|>")
        request.recentlyViewedSnippets?.forEach { snippet ->
            appendLine("<|recently_viewed_code_snippet|>")
            appendLine("code_snippet_file_path: ${snippet.filePath}")
            appendLine(snippet.content)
            appendLine("<|/recently_viewed_code_snippet|>")
        }
        appendLine("<|/recently_viewed_code_snippets|>")

        // Section 2: Current file content with editable region
        appendLine("<|current_file_content|>")
        if (!request.filePath.isNullOrBlank()) {
            appendLine("current_file_path: ${request.filePath}")
        }

        // Before editable region
        if (editableRegion.before.isNotEmpty()) {
            append(editableRegion.before)
            if (!editableRegion.before.endsWith("\n")) {
                appendLine()
            }
        }

        // Editable region with cursor marker
        appendLine("<|code_to_edit|>")
        append(NextEditInlineAdapter.renderWithCursor(editableRegion))
        if (!editableRegion.text.endsWith("\n")) {
            appendLine()
        }
        appendLine("<|/code_to_edit|>")

        // After editable region
        if (editableRegion.after.isNotEmpty()) {
            append(editableRegion.after)
            if (!editableRegion.after.endsWith("\n")) {
                appendLine()
            }
        }
        appendLine("<|/current_file_content|>")

        // Section 3: Edit diff history
        appendLine("<|edit_diff_history|>")
        request.editDiffHistory?.forEach { diff ->
            appendLine(diff)
        }
        appendLine("<|/edit_diff_history|>")
    }

    private fun extractCodeFromResponse(response: String): String {
        // The response may contain the updated editable region wrapped in backticks
        val trimmed = response.trim()
        if (trimmed.startsWith("```")) {
            val firstNewline = trimmed.indexOf('\n')
            val lastBackticks = trimmed.lastIndexOf("```")
            if (firstNewline >= 0 && lastBackticks > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastBackticks).trim()
            }
        }
        return trimmed
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.inceptionlabs.ai/v1"
        const val DEFAULT_MODEL = "mercury-edit-2"
        private const val EDIT_REGION_RADIUS = 7
    }
}
