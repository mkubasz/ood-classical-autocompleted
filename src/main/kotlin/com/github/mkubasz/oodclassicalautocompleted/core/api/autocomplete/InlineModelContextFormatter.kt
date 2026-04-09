package com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete

internal object InlineModelContextFormatter {

    fun formatForCodePrefix(context: InlineModelContext, language: String?): String {
        val lines = buildContentLines(context)
        if (lines.isEmpty()) return ""

        val commentPrefix = PromptCommentPrefix.forLanguage(language)
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
        if (!context.currentDefinitionName.isNullOrBlank()) {
            lines += "current_definition: ${context.currentDefinitionName}"
        }
        if (context.currentParameterNames.isNotEmpty()) {
            lines += "current_parameters: ${context.currentParameterNames.joinToString(", ")}"
        }
        if (context.isFreshBlockBodyContext) {
            lines += "fresh_block_body_context: true"
            lines += "body_guidance: continue the current block with implementation details only; avoid tutorial/example scaffolding and unrelated helper usage"
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
        if (context.resolvedDefinitions.isNotEmpty()) {
            context.resolvedDefinitions.forEach { def ->
                val header = buildString {
                    append(if (def.filePath.isNullOrBlank()) "nearby_definition: ${def.name}" else "cross_file_definition: ${def.name}")
                    def.filePath?.let { append(" (${it.substringAfterLast('/')})") }
                }
                lines += header
                lines += def.signature.lineSequence().toList()
            }
        }

        return lines.takeIf { it.size > 1 }.orEmpty()
    }
}
