package com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete

internal object InlineModelContextFormatter {

    fun formatForCodePrefix(context: InlineModelContext, language: String?): String {
        val lines = buildContentLines(context)
        if (lines.isEmpty()) return ""

        val commentPrefix = commentPrefix(language)
        return lines.joinToString(separator = "\n", postfix = "\n") { line ->
            if (line.isBlank()) commentPrefix else "$commentPrefix $line"
        }
    }

    fun formatForInstructionPrompt(context: InlineModelContext): String {
        val lines = buildContentLines(context)
        if (lines.isEmpty()) return ""

        return lines.joinToString(separator = "\n", postfix = "\n")
    }

    private fun buildContentLines(context: InlineModelContext): List<String> {
        val lines = mutableListOf<String>()
        lines += "IDE inline context:"

        if (context.lexicalContext != InlineLexicalContext.UNKNOWN) {
            lines += "lexical_context: ${context.lexicalContext.name.lowercase()}"
        }
        if (context.isDecoratorLikeContext) {
            lines += "decorator_like_context: true"
        }
        if (context.headerValidationRetry) {
            lines += "header_validation_retry: true"
            lines += "header_retry_instruction: return only a syntactically valid single-line header continuation with no extra closing delimiters or body"
        }
        if (context.isClassBaseListLikeContext) {
            lines += "class_base_list_like_context: true"
        }
        if (context.isAfterMemberAccess) {
            lines += "after_member_access: true"
        }
        if (!context.receiverExpression.isNullOrBlank()) {
            lines += "receiver_expression: ${context.receiverExpression}"
        }
        if (context.receiverMemberNames.isNotEmpty()) {
            lines += "receiver_members: ${context.receiverMemberNames.joinToString(", ")}"
        }
        if (context.isInParameterListLikeContext) {
            lines += "parameter_list_like_context: true"
        }
        if (context.isDefinitionHeaderLikeContext) {
            lines += "definition_header_like_context: true"
        }
        if (!context.classBaseReferencePrefix.isNullOrBlank()) {
            lines += "class_base_prefix: ${context.classBaseReferencePrefix}"
        }
        if (context.matchingTypeNames.isNotEmpty()) {
            lines += "matching_types: ${context.matchingTypeNames.joinToString(", ")}"
        }
        if (!context.headerValidationError.isNullOrBlank()) {
            lines += "header_validation_error: ${context.headerValidationError}"
        }
        if (!context.expectedHeaderContinuation.isNullOrBlank()) {
            lines += "expected_header_continuation: ${context.expectedHeaderContinuation}"
        }
        if (context.enclosingNames.isNotEmpty()) {
            lines += "enclosing_symbols: ${context.enclosingNames.joinToString(", ")}"
        }
        if (context.enclosingKinds.isNotEmpty()) {
            lines += "enclosing_kinds: ${context.enclosingKinds.joinToString(", ")}"
        }
        if (!context.resolvedReferenceName.isNullOrBlank()) {
            lines += "resolved_reference: ${context.resolvedReferenceName}"
        }
        if (!context.resolvedFilePath.isNullOrBlank()) {
            lines += "resolved_file: ${context.resolvedFilePath}"
        }
        if (!context.resolvedSnippet.isNullOrBlank()) {
            lines += "resolved_definition:"
            lines += context.resolvedSnippet.lineSequence().toList()
        }

        return lines.takeIf { it.size > 1 }.orEmpty()
    }

    private fun commentPrefix(language: String?): String {
        val normalized = language.orEmpty().trim().lowercase()
        return when {
            normalized.isBlank() -> "//"
            normalized in hashCommentLanguages ||
                normalized.contains("python") ||
                normalized.contains("shell") ||
                normalized.contains("bash") ||
                normalized.contains("fish") -> "#"
            normalized in doubleDashCommentLanguages -> "--"
            normalized in slashSlashCommentLanguages ||
                normalized == "golang" ||
                normalized.contains("rust") ||
                normalized.contains("java") ||
                normalized.contains("kotlin") ||
                normalized.contains("json") ||
                normalized.contains("php") -> "//"
            else -> "//"
        }
    }

    private val hashCommentLanguages = setOf(
        "py",
        "python",
        "rb",
        "ruby",
        "sh",
        "shell",
        "shell script",
        "bash",
        "fish",
        "fish shell",
        "yaml",
        "yml",
        "toml",
        "dockerfile",
    )

    private val doubleDashCommentLanguages = setOf(
        "sql",
        "lua",
        "haskell",
    )

    private val slashSlashCommentLanguages = setOf(
        "go",
        "rust",
        "java",
        "kotlin",
        "kt",
        "json",
        "json5",
        "php",
    )
}
