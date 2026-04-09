package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineLexicalContext
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineModelContext

internal object BaseInlineContextHeuristics {

    fun build(
        request: InlineContextRequest,
        includeResolvedDefinitions: Boolean,
        receiverMemberNames: List<String> = emptyList(),
    ): InlineModelContext {
        val safeOffset = request.caretOffset.coerceIn(0, request.documentText.length)
        val prefix = request.documentText.substring(0, safeOffset)
        val currentLinePrefix = prefix.substringAfterLast('\n')
        val localScope = LocalScopeContextHeuristics.analyze(
            documentText = request.documentText,
            caretOffset = request.caretOffset,
            languageId = request.languageId,
        )
        val isClassBaseListLikeContext = isClassBaseListLikeContext(currentLinePrefix)
        val classBaseReferencePrefix = extractClassBaseReferencePrefix(currentLinePrefix)
        val matchingTypeNames = if (isClassBaseListLikeContext) {
            findTypeNamesMatchingPrefix(request.documentText, classBaseReferencePrefix)
        } else {
            emptyList()
        }

        return InlineModelContext(
            lexicalContext = classifyLexicalContext(currentLinePrefix),
            enclosingNames = extractEnclosingNames(prefix),
            enclosingKinds = extractEnclosingKinds(prefix),
            currentDefinitionName = localScope.currentDefinitionName,
            currentParameterNames = localScope.currentParameterNames,
            isFreshBlockBodyContext = localScope.isFreshBlockBodyContext,
            isDecoratorLikeContext = currentLinePrefix.trimStart().startsWith("@"),
            isClassBaseListLikeContext = isClassBaseListLikeContext,
            isAfterMemberAccess = currentLinePrefix.trimEnd().endsWith("."),
            receiverExpression = ReceiverContextHeuristics.extractReceiverExpression(currentLinePrefix),
            receiverMemberNames = receiverMemberNames,
            isInParameterListLikeContext = isParameterListLikeContext(currentLinePrefix),
            isDefinitionHeaderLikeContext = isDefinitionHeaderLikeContext(currentLinePrefix),
            classBaseReferencePrefix = classBaseReferencePrefix,
            matchingTypeNames = matchingTypeNames,
            resolvedDefinitions = if (includeResolvedDefinitions && !localScope.isFreshBlockBodyContext) {
                HeuristicContextFallback.extractDefinitions(
                    request.documentText,
                    request.caretOffset,
                )
            } else {
                emptyList()
            },
        )
    }

    private fun classifyLexicalContext(currentLinePrefix: String): InlineLexicalContext {
        val trimmed = currentLinePrefix.trimStart()
        if (COMMENT_PREFIXES.any(trimmed::startsWith)) return InlineLexicalContext.COMMENT
        if (appearsInsideString(currentLinePrefix)) return InlineLexicalContext.STRING
        return if (currentLinePrefix.isBlank()) InlineLexicalContext.UNKNOWN else InlineLexicalContext.CODE
    }

    private fun appearsInsideString(currentLinePrefix: String): Boolean {
        val escaped = currentLinePrefix.replace("\\\\", "")
        return escaped.count { it == '"' } % 2 == 1 || escaped.count { it == '\'' } % 2 == 1
    }

    private fun isParameterListLikeContext(currentLinePrefix: String): Boolean =
        currentLinePrefix.count { it == '(' } > currentLinePrefix.count { it == ')' }

    private fun isDefinitionHeaderLikeContext(currentLinePrefix: String): Boolean {
        val trimmed = currentLinePrefix.trimStart()
        return DEFINITION_PREFIXES.any(trimmed::startsWith) ||
            (trimmed.startsWith("class ") && '(' in trimmed)
    }

    private fun isClassBaseListLikeContext(currentLinePrefix: String): Boolean {
        val trimmed = currentLinePrefix.trimStart()
        if (!trimmed.startsWith("class ")) return false
        return trimmed.count { it == '(' } > trimmed.count { it == ')' }
    }

    private fun extractClassBaseReferencePrefix(currentLinePrefix: String): String? {
        if (!isClassBaseListLikeContext(currentLinePrefix)) return null
        val segment = currentLinePrefix
            .substringAfterLast('(')
            .substringAfterLast(',')
            .trimStart()
        return CLASS_BASE_REFERENCE_PATTERN.find(segment)?.value
    }

    private fun findTypeNamesMatchingPrefix(documentText: String, prefix: String?): List<String> {
        if (prefix.isNullOrBlank()) return emptyList()
        return CLASS_DECLARATION_PATTERN.findAll(documentText)
            .mapNotNull { match -> match.groupValues.getOrNull(1) }
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .distinct()
            .take(MAX_MATCHING_TYPES)
            .toList()
    }

    private fun extractEnclosingNames(prefix: String): List<String> =
        DEFINITION_NAME_PATTERN.findAll(prefix)
            .mapNotNull { match -> match.groupValues.getOrNull(2)?.takeIf(String::isNotBlank) }
            .toList()
            .takeLast(MAX_ENCLOSING_NAMES)
            .reversed()

    private fun extractEnclosingKinds(prefix: String): List<String> =
        DEFINITION_NAME_PATTERN.findAll(prefix)
            .mapNotNull { match ->
                when (match.groupValues.getOrNull(1)?.lowercase()) {
                    "def", "fun", "function" -> "Function"
                    "class" -> "Class"
                    "interface" -> "Interface"
                    "struct" -> "Struct"
                    "enum" -> "Enum"
                    else -> null
                }
            }
            .toList()
            .takeLast(MAX_ENCLOSING_KINDS)
            .reversed()

    private val COMMENT_PREFIXES = listOf("#", "//", "/*", "*")
    private val DEFINITION_PREFIXES = listOf("def ", "fun ", "function ", "class ", "interface ", "struct ", "enum ")
    private val CLASS_BASE_REFERENCE_PATTERN = Regex("""[A-Za-z_][A-Za-z0-9_\.]*$""")
    private val CLASS_DECLARATION_PATTERN = Regex("""\bclass\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val DEFINITION_NAME_PATTERN =
        Regex("""\b(def|fun|function|class|interface|struct|enum)\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private const val MAX_MATCHING_TYPES = 12
    private const val MAX_ENCLOSING_NAMES = 4
    private const val MAX_ENCLOSING_KINDS = 6
}
