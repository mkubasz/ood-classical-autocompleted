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

class InceptionLabsNextEditProvider(
    private val apiKey: String,
    baseUrl: String = DEFAULT_BASE_URL,
    private val model: String = DEFAULT_MODEL,
    private val generationOptions: InceptionLabsGenerationOptions = InceptionLabsGenerationOptions(),
    private val contextOptions: InceptionLabsNextEditContextOptions = InceptionLabsNextEditContextOptions(),
    private val httpClient: HttpClient = defaultHttpClient(),
) : AutocompleteProvider {

    override val capabilities: Set<AutocompleteCapability> = setOf(
        AutocompleteCapability.NEXT_EDIT,
        AutocompleteCapability.INLINE,
    )

    private val endpoint = "${baseUrl.trimEnd('/')}/edit/completions"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val log = logger<InceptionLabsNextEditProvider>()

    override suspend fun complete(request: AutocompleteRequest): CompletionResponse? {
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

            CompletionResponse(
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

    override suspend fun completeStreaming(request: AutocompleteRequest): Flow<String>? {
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
                        val chunk = parseSseChunk(data)
                        if (!chunk.isNullOrEmpty()) {
                            send(chunk)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("NextEdit streaming failed", e)
            }
        }
    }

    private fun parseSseChunk(data: String): String? = try {
        val event = json.parseToJsonElement(data).jsonObject
        event["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("delta")?.jsonObject
            ?.get("content")?.jsonPrimitive?.content
    } catch (_: Exception) { null }

    override fun cancel() {}

    override fun dispose() {
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
        appendLine("<|retrieved_context|>")
        request.retrievedChunks?.forEach { chunk ->
            appendLine("retrieved_file_path: ${chunk.filePath}")
            appendLine(chunk.content)
        }
        appendLine("<|/retrieved_context|>")

        // Section 4: Git diff
        appendLine("<|git_diff|>")
        request.gitDiff?.takeIf { it.isNotBlank() }?.let(::appendLine)
        appendLine("<|/git_diff|>")

        // Section 5: Edit diff history
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

    companion object {
        const val DEFAULT_BASE_URL = "https://api.inceptionlabs.ai/v1"
        const val DEFAULT_MODEL = "mercury-edit-2"

        private fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            engine {
                requestTimeout = 30_000
            }
        }
    }
}
