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

    override suspend fun complete(request: AutocompleteRequest): CompletionResponse? {
        if (apiKey.isBlank()) return null

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

            CompletionResponse(
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

    override suspend fun completeStreaming(request: AutocompleteRequest): Flow<String>? {
        if (apiKey.isBlank()) return null

        val body = InceptionLabsRequestBodyBuilder.buildFimBody(
            model = model,
            request = request,
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
                        log.warn("FIM streaming status: ${response.status}")
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
                log.warn("FIM streaming failed", e)
            }
        }
    }

    private fun parseLogprobConfidence(choice: JsonObject): Double? {
        val logprobs = choice["logprobs"]?.jsonObject ?: return null
        val tokenLogprobs = logprobs["token_logprobs"]?.jsonArray ?: return null
        if (tokenLogprobs.isEmpty()) return null

        val values = tokenLogprobs.mapNotNull { element ->
            element.jsonPrimitive.doubleOrNull
        }
        if (values.isEmpty()) return null

        val meanLogprob = values.sum() / values.size
        return logprobToConfidence(meanLogprob)
    }

    private fun logprobToConfidence(meanLogprob: Double): Double {
        // Convert mean log probability to 0-1 confidence
        // logprob 0.0 → confidence 1.0 (certain)
        // logprob -1.0 → confidence ~0.37
        // logprob -3.0 → confidence ~0.05
        return kotlin.math.exp(meanLogprob).coerceIn(0.0, 1.0)
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
    } catch (_: Exception) { null }

    override fun cancel() {}

    override fun dispose() {
        httpClient.close()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.inceptionlabs.ai/v1"
        const val DEFAULT_MODEL = "mercury-edit-2"
    }
}
