package com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class InceptionLabsRequestBodyBuilderTest : BasePlatformTestCase() {

    fun testBuildFimBodyOmitsUnsetOptionalFields() {
        val body = InceptionLabsRequestBodyBuilder.buildFimBody(
            model = "mercury-edit-2",
            request = AutocompleteRequest(
                prefix = "fun fib(\n",
                suffix = "",
                filePath = "Main.kt",
                language = "kt",
            ),
            options = InceptionLabsGenerationOptions(),
        )

        assertEquals("mercury-edit-2", body["model"]?.jsonPrimitive?.content)
        assertEquals("fun fib(\n", body["prompt"]?.jsonPrimitive?.content)
        assertEquals("", body["suffix"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("max_tokens"))
        assertFalse(body.containsKey("presence_penalty"))
        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("top_p"))
        assertFalse(body.containsKey("stop"))
    }

    fun testBuildFimBodyIncludesAdvancedFieldsAndExtraJson() {
        val body = InceptionLabsRequestBodyBuilder.buildFimBody(
            model = "mercury-edit-2",
            request = AutocompleteRequest(
                prefix = "abc\n",
                suffix = "",
                filePath = "Main.kt",
                language = "kt",
            ),
            options = InceptionLabsGenerationOptions(
                maxTokens = 123,
                presencePenalty = 1.5,
                temperature = 0.0,
                topP = 1.0,
                stopSequences = listOf("\n\n", "\n \n"),
                extraBodyJson = buildJsonObject {
                    put("reasoning_effort", "low")
                },
            ),
        )

        assertEquals(123, body["max_tokens"]?.jsonPrimitive?.content?.toInt())
        assertEquals(1.5, body["presence_penalty"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(0.0, body["temperature"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(1.0, body["top_p"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(listOf("\n\n", "\n \n"), body["stop"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("low", body["reasoning_effort"]?.jsonPrimitive?.content)
    }

    fun testBuildFimBodyUsesBoundedLocalContextWindows() {
        val prefix = "a".repeat(3_000)
        val suffix = "b".repeat(2_000)

        val body = InceptionLabsRequestBodyBuilder.buildFimBody(
            model = "mercury-edit-2",
            request = AutocompleteRequest(
                prefix = prefix,
                suffix = suffix,
                filePath = "Main.kt",
                language = "kt",
            ),
            options = InceptionLabsGenerationOptions(),
        )

        val promptLength = body["prompt"]?.jsonPrimitive?.content?.length ?: 0
        val suffixLength = body["suffix"]?.jsonPrimitive?.content?.length ?: 0
        assertTrue("Prompt should be bounded: $promptLength", promptLength <= 4_000)
        assertTrue("Suffix should be bounded: $suffixLength", suffixLength <= 2_000)
        assertTrue("Total should fit budget", promptLength + suffixLength <= 4_500)
        assertTrue("Prompt should be substantial: $promptLength", promptLength >= 1_200)
        assertTrue("Suffix should be substantial: $suffixLength", suffixLength >= 600)
    }

    fun testBuildFimBodyIncludesInlineContextComments() {
        val body = InceptionLabsRequestBodyBuilder.buildFimBody(
            model = "mercury-edit-2",
            request = AutocompleteRequest(
                prefix = "client.",
                suffix = "",
                filePath = "app.py",
                language = "py",
                inlineContext = InlineModelContext(
                    lexicalContext = InlineLexicalContext.CODE,
                    isAfterMemberAccess = true,
                    receiverExpression = "client",
                    receiverMemberNames = listOf("query", "close"),
                    enclosingNames = listOf("Agent", "run"),
                    resolvedReferenceName = "DatabaseClient",
                    resolvedFilePath = "/repo/database.py",
                    resolvedSnippet = "class DatabaseClient:\n    def query(self, sql): ...",
                ),
            ),
            options = InceptionLabsGenerationOptions(),
        )

        val prompt = body["prompt"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(prompt.startsWith("# IDE inline context:"))
        assertTrue(prompt.contains("# after_member_access: true"))
        assertTrue(prompt.contains("# receiver_expression: client"))
        assertTrue(prompt.contains("# receiver_members: query, close"))
        assertTrue(prompt.contains("# resolved_reference: DatabaseClient"))
        assertTrue(prompt.contains("# class DatabaseClient:"))
        assertTrue(prompt.endsWith("client."))
    }

    fun testBuildFimBodyAddsSingleLineStopForMemberAccess() {
        val body = InceptionLabsRequestBodyBuilder.buildFimBody(
            model = "mercury-edit-2",
            request = AutocompleteRequest(
                prefix = "client.",
                suffix = "",
                filePath = "Main.java",
                language = "java",
                inlineContext = InlineModelContext(
                    lexicalContext = InlineLexicalContext.CODE,
                    isAfterMemberAccess = true,
                ),
            ),
            options = InceptionLabsGenerationOptions(
                stopSequences = listOf("<END>"),
            ),
        )

        assertEquals(listOf("<END>", "\n", "\r\n"), body["stop"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    fun testBuildFimBodyAddsSingleLineStopWhenCompletingInsideLine() {
        val body = InceptionLabsRequestBodyBuilder.buildFimBody(
            model = "mercury-edit-2",
            request = AutocompleteRequest(
                prefix = "return ",
                suffix = "value",
                filePath = "Main.kt",
                language = "kotlin",
            ),
            options = InceptionLabsGenerationOptions(),
        )

        assertEquals(listOf("\n", "\r\n"), body["stop"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    fun testBuildFimBodyAddsSingleLineStopForPropertySetterSignature() {
        val body = InceptionLabsRequestBodyBuilder.buildFimBody(
            model = "mercury-edit-2",
            request = AutocompleteRequest(
                prefix = """
                    @property
                    def history(self) -> History:
                        return self._history

                    @history.setter
                    def history(self
                """.trimIndent(),
                suffix = "",
                filePath = "agent.py",
                language = "Python",
                inlineContext = InlineModelContext(
                    lexicalContext = InlineLexicalContext.CODE,
                    isInParameterListLikeContext = true,
                    isDefinitionHeaderLikeContext = true,
                ),
            ),
            options = InceptionLabsGenerationOptions(),
        )

        assertEquals(listOf("\n", "\r\n"), body["stop"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    fun testBuildFimBodyIncludesClassBaseContextAndMatchingTypes() {
        val prefix = """
            class Czlowiek:
                pass

            class Bartek(Czlo
        """.trimIndent()

        val body = InceptionLabsRequestBodyBuilder.buildFimBody(
            model = "mercury-edit-2",
            request = AutocompleteRequest(
                prefix = prefix,
                suffix = "",
                filePath = "agent.py",
                language = "Python",
                inlineContext = InlineModelContext(
                    lexicalContext = InlineLexicalContext.CODE,
                    isClassBaseListLikeContext = true,
                    isInParameterListLikeContext = true,
                    isDefinitionHeaderLikeContext = true,
                    classBaseReferencePrefix = "Czlo",
                    matchingTypeNames = listOf("Czlowiek"),
                ),
            ),
            options = InceptionLabsGenerationOptions(),
        )

        val prompt = body["prompt"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(prompt.contains("# class_base_list_like_context: true"))
        assertTrue(prompt.contains("# class_base_prefix: Czlo"))
        assertTrue(prompt.contains("# matching_types: Czlowiek"))
        assertTrue(prompt.endsWith("class Bartek(Czlo"))
        assertEquals(listOf("\n", "\r\n"), body["stop"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    fun testBuildNextEditBodyUsesMessagesEnvelope() {
        val body = InceptionLabsRequestBodyBuilder.buildNextEditBody(
            model = "mercury-edit-2",
            prompt = "<|current_file_content|>...",
            options = InceptionLabsGenerationOptions(
                extraBodyJson = buildJsonObject {
                    put("reasoning_effort", "medium")
                },
            ),
        )

        val firstMessage = body["messages"]!!.jsonArray.single().jsonObject
        assertEquals("user", firstMessage["role"]?.jsonPrimitive?.content)
        assertEquals("<|current_file_content|>...", firstMessage["content"]?.jsonPrimitive?.content)
        assertEquals("medium", body["reasoning_effort"]?.jsonPrimitive?.content)
    }
}
