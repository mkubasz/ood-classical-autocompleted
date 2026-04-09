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

class InceptionLabsFimProvider(
    private val apiKey: String,
    baseUrl: String = DEFAULT_BASE_URL,
    private val model: String = DEFAULT_MODEL,
    private val generationOptions: InceptionLabsGenerationOptions = InceptionLabsGenerationOptions(),
) : AutocompleteProvider {

    override val capabilities: Set<AutocompleteCapability> = setOf(AutocompleteCapability.INLINE)

    private val endpoint = "${baseUrl.trimEnd('/')}/fim/completions"

    private val httpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 30_000
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val log = logger<InceptionLabsFimProvider>()
    private val cancelled = AtomicBoolean(false)

    override suspend fun complete(request: AutocompleteRequest): CompletionResponse? {
        if (apiKey.isBlank()) return null
        cancelled.set(false)

        val body = InceptionLabsRequestBodyBuilder.buildFimBody(
            model = model,
            request = request,
            options = generationOptions,
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
            val text = payload["choices"]
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
            log.warn("FIM request failed", e)
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

    companion object {
        const val DEFAULT_BASE_URL = "https://api.inceptionlabs.ai/v1"
        const val DEFAULT_MODEL = "mercury-edit-2"
    }
}
