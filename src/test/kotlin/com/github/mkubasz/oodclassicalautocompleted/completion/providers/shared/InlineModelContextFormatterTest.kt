package com.github.mkubasz.oodclassicalautocompleted.completion.providers.shared

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineLexicalContext
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineModelContext
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ResolvedDefinition
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineModelContextFormatterTest {

    @Test
    fun usesHashCommentPrefixForPythonAndShellLanguages() {
        listOf("py", "Python", "bash", "Shell Script", "fish", "Fish Shell").forEach { language ->
            val formatted = InlineModelContextFormatter.formatForCodePrefix(sampleContext(), language)
            assertTrue("Expected # comment prefix for $language", formatted.startsWith("# IDE inline context:"))
        }
    }

    @Test
    fun usesSlashSlashCommentPrefixForGoRustJvmJsonAndPhpLanguages() {
        listOf("go", "Go", "rust", "Rust", "JAVA", "kotlin", "JSON", "PHP").forEach { language ->
            val formatted = InlineModelContextFormatter.formatForCodePrefix(sampleContext(), language)
            assertTrue("Expected // comment prefix for $language", formatted.startsWith("// IDE inline context:"))
        }
    }

    @Test
    fun includesReceiverContextWhenPresent() {
        val formatted = InlineModelContextFormatter.formatForCodePrefix(
            sampleContext().copy(
                receiverExpression = "client",
                receiverMemberNames = listOf("query", "close"),
            ),
            "java",
        )

        assertTrue(formatted.contains("// receiver_expression: client"))
        assertTrue(formatted.contains("// receiver_members: query, close"))
    }

    @Test
    fun includesClassBaseHintsWhenPresent() {
        val formatted = InlineModelContextFormatter.formatForCodePrefix(
            sampleContext().copy(
                isClassBaseListLikeContext = true,
                isDefinitionHeaderLikeContext = true,
                isInParameterListLikeContext = true,
                classBaseReferencePrefix = "Czlo",
                matchingTypeNames = listOf("Czlowiek"),
            ),
            "python",
        )

        assertTrue(formatted.contains("# class_base_list_like_context: true"))
        assertTrue(formatted.contains("# class_base_prefix: Czlo"))
        assertTrue(formatted.contains("# matching_types: Czlowiek"))
    }

    @Test
    fun includesHeaderValidationRetryHintsWhenPresent() {
        val formatted = InlineModelContextFormatter.formatForCodePrefix(
            sampleContext().copy(
                headerValidationRetry = true,
                headerValidationError = "Unexpected ')'",
                expectedHeaderContinuation = "wiek):",
            ),
            "python",
        )

        assertTrue(formatted.contains("# header_validation_retry: true"))
        assertTrue(formatted.contains("# header_validation_error: Unexpected ')'"))
        assertTrue(formatted.contains("# expected_header_continuation: wiek):"))
    }

    @Test
    fun includesFreshBlockGuidanceAndNearbyDefinitionLabels() {
        val formatted = InlineModelContextFormatter.formatForCodePrefix(
            sampleContext().copy(
                currentDefinitionName = "my_new_workflow",
                currentParameterNames = listOf("message"),
                isFreshBlockBodyContext = true,
                resolvedDefinitions = listOf(
                    ResolvedDefinition(name = "calculate_average", filePath = null, signature = "def calculate_average(numbers):"),
                    ResolvedDefinition(name = "Workflow", filePath = "/tmp/workflow.py", signature = "class Workflow:"),
                ),
            ),
            "python",
        )

        assertTrue(formatted.contains("# current_definition: my_new_workflow"))
        assertTrue(formatted.contains("# current_parameters: message"))
        assertTrue(formatted.contains("# fresh_block_body_context: true"))
        assertTrue(formatted.contains("# nearby_definition: calculate_average"))
        assertTrue(formatted.contains("# cross_file_definition: Workflow (workflow.py)"))
    }

    private fun sampleContext(): InlineModelContext = InlineModelContext(
        lexicalContext = InlineLexicalContext.CODE,
        isAfterMemberAccess = true,
    )
}
