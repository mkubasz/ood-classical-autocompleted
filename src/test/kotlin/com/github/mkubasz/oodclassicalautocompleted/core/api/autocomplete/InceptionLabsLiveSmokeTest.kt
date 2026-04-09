package com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class InceptionLabsLiveSmokeTest {

    @Test
    fun fimReturnsInlineCompletionWhenTokenIsConfigured() = runBlocking {
        val config = liveConfigOrSkip()
        val marker = "codex-live-fim-${System.currentTimeMillis()}"
        println("Inception live smoke FIM marker: $marker")

        val provider = InceptionLabsFimProvider(
            apiKey = config.apiKey,
            baseUrl = config.baseUrl,
            model = config.model ?: InceptionLabsFimProvider.DEFAULT_MODEL,
            generationOptions = InceptionLabsGenerationOptions(
                maxTokens = 64,
                temperature = 0.0,
            ),
        )

        try {
            val request = AutocompleteRequest(
                prefix = "# $marker\nclass Agent:\n    def clear_memory(self) -> None:\n        self.",
                suffix = "",
                filePath = "live_smoke_$marker.py",
                language = "py",
                cursorOffset = "# $marker\nclass Agent:\n    def clear_memory(self) -> None:\n        self.".length,
            )

            val response = provider.complete(request)
            println(
                "Inception live smoke FIM response: inline=${response?.inlineCandidates?.size ?: 0}, " +
                    "nextEdit=${response?.nextEditCandidates?.size ?: 0}"
            )

            assertNotNull("Expected a FIM response for marker $marker", response)
            assertTrue(
                "Expected at least one inline candidate for marker $marker",
                response!!.inlineCandidates.isNotEmpty(),
            )
            assertTrue(
                "Inline candidates should contain non-blank text for marker $marker",
                response.inlineCandidates.all { it.text.isNotBlank() },
            )
        } finally {
            provider.dispose()
        }
    }

    @Test
    fun fimKeepsClassBaseCompletionFocusedWhenTokenIsConfigured() = runBlocking {
        val config = liveConfigOrSkip()
        val marker = "codex-live-class-base-${System.currentTimeMillis()}"
        println("Inception live smoke class-base marker: $marker")

        val provider = InceptionLabsFimProvider(
            apiKey = config.apiKey,
            baseUrl = config.baseUrl,
            model = config.model ?: InceptionLabsFimProvider.DEFAULT_MODEL,
            generationOptions = InceptionLabsGenerationOptions(
                maxTokens = 32,
                temperature = 0.0,
            ),
        )

        try {
            val prefix = """
                # $marker
                class Czlowiek:
                    pass

                class Bartek(Czlo
            """.trimIndent()
            val request = AutocompleteRequest(
                prefix = prefix,
                suffix = "",
                filePath = "live_smoke_$marker.py",
                language = "py",
                cursorOffset = prefix.length,
                inlineContext = InlineModelContext(
                    lexicalContext = InlineLexicalContext.CODE,
                    isClassBaseListLikeContext = true,
                    isInParameterListLikeContext = true,
                    isDefinitionHeaderLikeContext = true,
                    classBaseReferencePrefix = "Czlo",
                    matchingTypeNames = listOf("Czlowiek"),
                ),
            )

            val response = provider.complete(request)
            println(
                "Inception live smoke class-base response: inline=${response?.inlineCandidates?.size ?: 0}, " +
                    "nextEdit=${response?.nextEditCandidates?.size ?: 0}"
            )
            response?.inlineCandidates?.forEachIndexed { index, candidate ->
                println("classBaseInline[$index]=${candidate.text.replace("\n", "\\n")}")
            }

            assertNotNull("Expected a class-base FIM response for marker $marker", response)
            assertTrue(
                "Expected at least one inline candidate for class-base marker $marker",
                response!!.inlineCandidates.isNotEmpty(),
            )
            assertTrue(
                "Class-base inline candidate must stay single-line for marker $marker",
                response.inlineCandidates.none { '\n' in it.text },
            )
        } finally {
            provider.dispose()
        }
    }

    @Test
    fun nextEditProducesSafeCandidatesWhenTokenIsConfigured() = runBlocking {
        val config = liveConfigOrSkip()
        val marker = "codex-live-next-edit-${System.currentTimeMillis()}"
        println("Inception live smoke NextEdit marker: $marker")

        val provider = InceptionLabsNextEditProvider(
            apiKey = config.apiKey,
            baseUrl = config.baseUrl,
            model = config.model ?: InceptionLabsNextEditProvider.DEFAULT_MODEL,
            generationOptions = InceptionLabsGenerationOptions(
                maxTokens = 128,
                temperature = 0.0,
            ),
            contextOptions = InceptionLabsNextEditContextOptions(
                linesAboveCursor = 6,
                linesBelowCursor = 6,
            ),
        )

        try {
            val prefix = "# $marker\na = lambda x: x  # noqa: E731\nclass A:\n    def __init__(self):\n        pass\n\n    def a(self,"
            val request = AutocompleteRequest(
                prefix = prefix,
                suffix = "",
                filePath = "live_smoke_$marker.py",
                language = "py",
                cursorOffset = prefix.length,
            )

            val response = provider.complete(request)
            println(
                "Inception live smoke NextEdit response: inline=${response?.inlineCandidates?.size ?: 0}, " +
                    "nextEdit=${response?.nextEditCandidates?.size ?: 0}"
            )
            response?.inlineCandidates?.forEachIndexed { index, candidate ->
                println("inline[$index]=${candidate.text.replace("\n", "\\n")}")
            }
            response?.nextEditCandidates?.forEachIndexed { index, candidate ->
                println(
                    "nextEdit[$index]=start=${candidate.startOffset}, end=${candidate.endOffset}, " +
                        "replacement=${candidate.replacementText.replace("\n", "\\n")}"
                )
            }

            assertNotNull("Expected a NextEdit response for marker $marker", response)
            assertTrue(
                "Expected at least one inline or next-edit candidate for marker $marker",
                response!!.inlineCandidates.isNotEmpty() || response.nextEditCandidates.isNotEmpty(),
            )
            assertTrue(
                "Inline fallback must stay single-line for marker $marker",
                response.inlineCandidates.none { '\n' in it.text },
            )
            assertFalse(
                "NextEdit should not return blank replacements for marker $marker",
                response.nextEditCandidates.any { it.replacementText.isBlank() },
            )
        } finally {
            provider.dispose()
        }
    }

    private fun liveConfigOrSkip(): LiveConfig {
        val apiKey = System.getenv("INCEPTION_API_KEY").orEmpty().trim()
        assumeTrue("Set INCEPTION_API_KEY to run live Inception smoke tests", apiKey.isNotBlank())

        return LiveConfig(
            apiKey = apiKey,
            baseUrl = System.getenv("INCEPTION_BASE_URL")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: InceptionLabsFimProvider.DEFAULT_BASE_URL,
            model = System.getenv("INCEPTION_MODEL")
                ?.trim()
                ?.takeIf { it.isNotBlank() },
        )
    }

    private data class LiveConfig(
        val apiKey: String,
        val baseUrl: String,
        val model: String?,
    )
}
