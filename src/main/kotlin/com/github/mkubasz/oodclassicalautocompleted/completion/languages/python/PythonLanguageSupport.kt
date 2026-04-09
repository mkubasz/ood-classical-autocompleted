package com.github.mkubasz.oodclassicalautocompleted.completion.languages.python

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineModelContext
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.RetrievedContextChunkKind
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.CorrectnessParameterStyle
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.CorrectnessValidationContext
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.LanguageCorrectnessProfile
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.WorkspaceRetrievalProfile
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.WorkspaceStructurePattern
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.common.DocumentBackedLanguageSupport
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.HeuristicContextFallback
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.InlineContextRequest
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.InlineCorrectnessFilter
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.LocalScopeContextHeuristics
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.ReceiverContextHeuristics

internal object PythonLanguageSupport : DocumentBackedLanguageSupport() {
    override val id: String = "python"
    override val languageIds: Set<String> = setOf("Python", "python", "py")
    override val fileExtensions: Set<String> = setOf("py")

    override fun retrieval(): WorkspaceRetrievalProfile = WorkspaceRetrievalProfile(
        importPrefixes = listOf("import ", "from "),
        structuralPatterns = listOf(
            WorkspaceStructurePattern(
                Regex("""^\s*(?:async\s+def|def|class)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
                RetrievedContextChunkKind.SYMBOL,
            ),
            WorkspaceStructurePattern(
                Regex("""^\s*([A-Za-z_][A-Za-z0-9_]*)\s*="""),
                RetrievedContextChunkKind.CONSTANT,
            ),
        ),
        structuralChunkMaxLines = 20,
        lineWindowSize = 12,
        contextLinesBeforeHit = 2,
    )

    override fun correctnessProfile(
        languageId: String?,
        filePath: String?,
    ): LanguageCorrectnessProfile = LanguageCorrectnessProfile(
        family = InlineCorrectnessFilter.LanguageFamily.PYTHON,
        maxSyntaxErrors = 0,
        maxUnresolvedReferences = 1,
        builtIns = PYTHON_BUILT_INS,
        declarationPatterns = listOf(
            Regex("""\b(?:async\s+def|def)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
            Regex("""\bclass\s+([A-Za-z_][A-Za-z0-9_]*)"""),
            Regex("""\bfrom\s+[A-Za-z0-9_.]+\s+import\s+[A-Za-z0-9_.*]+\s+as\s+([A-Za-z_][A-Za-z0-9_]*)"""),
            Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*="""),
        ),
        functionSignaturePatterns = listOf(PYTHON_FUNCTION_SIGNATURE),
        parameterStyle = CorrectnessParameterStyle.PYTHON,
        structuralValidator = { context, candidateText ->
            if (hasIndentationIssue(context, candidateText)) "indentation" else null
        },
    )

    override fun buildLanguageContext(request: InlineContextRequest): InlineModelContext {
        val safeOffset = request.caretOffset.coerceIn(0, request.documentText.length)
        val prefix = request.documentText.substring(0, safeOffset)
        val currentLinePrefix = prefix.substringAfterLast('\n')
        val lines = prefix.replace("\r", "").lines()
        val declarations = collectDeclarations(lines)
        val localScope = LocalScopeContextHeuristics.analyze(
            documentText = request.documentText,
            caretOffset = request.caretOffset,
            languageId = request.languageId,
        )
        val classBasePrefix = extractClassBaseReferencePrefix(currentLinePrefix)
        val matchingTypes = findTypeNamesMatchingPrefix(request.documentText, classBasePrefix)

        return InlineModelContext(
            enclosingNames = declarations.takeLast(MAX_ENCLOSING).map { it.name }.asReversed(),
            enclosingKinds = declarations.takeLast(MAX_ENCLOSING).map { it.kind }.asReversed(),
            currentDefinitionName = localScope.currentDefinitionName ?: declarations.lastOrNull()?.name,
            currentParameterNames = localScope.currentParameterNames.ifEmpty { declarations.lastOrNull()?.parameters.orEmpty() },
            isFreshBlockBodyContext = localScope.isFreshBlockBodyContext,
            isDecoratorLikeContext = currentLinePrefix.trimStart().startsWith("@"),
            isClassBaseListLikeContext = classBasePrefix != null,
            isAfterMemberAccess = currentLinePrefix.trimEnd().endsWith("."),
            receiverExpression = ReceiverContextHeuristics.extractReceiverExpression(currentLinePrefix),
            isInParameterListLikeContext = looksLikeDefinitionHeader(currentLinePrefix.trimStart()) &&
                currentLinePrefix.count { it == '(' } > currentLinePrefix.count { it == ')' },
            isDefinitionHeaderLikeContext = looksLikeDefinitionHeader(currentLinePrefix.trimStart()),
            classBaseReferencePrefix = classBasePrefix,
            matchingTypeNames = matchingTypes,
            resolvedDefinitions = if (!localScope.isFreshBlockBodyContext) {
                HeuristicContextFallback.extractDefinitions(request.documentText, request.caretOffset)
            } else {
                emptyList()
            },
        )
    }

    private fun collectDeclarations(lines: List<String>): List<PythonDeclaration> {
        val stack = mutableListOf<PythonDeclaration>()
        lines.forEach { rawLine ->
            val trimmed = rawLine.trimStart()
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("@")) return@forEach

            val indent = rawLine.takeWhile { it == ' ' || it == '\t' }.length
            while (stack.isNotEmpty() && indent <= stack.last().indent) {
                stack.removeLast()
            }

            parseDeclaration(trimmed, indent)?.let { declaration ->
                stack += declaration
            }
        }
        return stack
    }

    private fun parseDeclaration(
        trimmedLine: String,
        indent: Int,
    ): PythonDeclaration? {
        FUNCTION_DECLARATION.find(trimmedLine)?.let { match ->
            return PythonDeclaration(
                name = match.groupValues[2],
                kind = "Function",
                indent = indent,
                parameters = extractParameters(match.groupValues[3]),
            )
        }
        CLASS_DECLARATION.find(trimmedLine)?.let { match ->
            return PythonDeclaration(
                name = match.groupValues[1],
                kind = "Class",
                indent = indent,
            )
        }
        return null
    }

    private fun extractClassBaseReferencePrefix(currentLinePrefix: String): String? {
        val trimmed = currentLinePrefix.trimStart()
        if (!trimmed.startsWith("class ")) return null
        if (trimmed.count { it == '(' } <= trimmed.count { it == ')' }) return null

        return trimmed.substringAfterLast('(')
            .substringAfterLast(',')
            .trim()
            .takeIf(String::isNotBlank)
    }

    private fun findTypeNamesMatchingPrefix(
        documentText: String,
        prefix: String?,
    ): List<String> {
        if (prefix.isNullOrBlank()) return emptyList()
        return CLASS_DECLARATION.findAll(documentText)
            .map { it.groupValues[1] }
            .filter { it.startsWith(prefix, ignoreCase = true) && it != prefix }
            .distinct()
            .take(MAX_MATCHING_TYPES)
            .toList()
    }

    private fun extractParameters(signature: String): List<String> = signature.split(',')
        .mapNotNull { parameter ->
            parameter.trim()
                .removePrefix("*")
                .removePrefix("*")
                .substringBefore(':')
                .substringBefore('=')
                .trim()
                .takeIf(IDENTIFIER::matches)
        }
        .filter { it !in PYTHON_SELF_NAMES }
        .distinct()

    private fun looksLikeDefinitionHeader(trimmed: String): Boolean =
        trimmed.startsWith("def ") ||
            trimmed.startsWith("async def ") ||
            trimmed.startsWith("class ")

    private fun hasIndentationIssue(
        context: CorrectnessValidationContext,
        candidateText: String,
    ): Boolean {
        if (!candidateText.contains('\n')) return false
        val lines = candidateText.replace("\r", "").lines()
        if (lines.size < 2) return false

        val relevantHeader = context.inlineContext?.isDefinitionHeaderLikeContext == true ||
            context.inlineContext?.isFreshBlockBodyContext == true ||
            context.prefix.trimEnd().endsWith(":") ||
            lines.first().trimEnd().endsWith(":")
        if (!relevantHeader) return false

        val currentIndent = context.prefix
            .substringAfterLast('\n', "")
            .takeWhile { it == ' ' || it == '\t' }
            .length
        val firstBodyLine = lines.drop(1).firstOrNull { it.isNotBlank() } ?: return false
        val candidateIndent = firstBodyLine.takeWhile { it == ' ' || it == '\t' }.length
        return candidateIndent < currentIndent + PYTHON_BLOCK_INDENT
    }

    private data class PythonDeclaration(
        val name: String,
        val kind: String,
        val indent: Int,
        val parameters: List<String> = emptyList(),
    )

    private const val MAX_ENCLOSING = 6
    private const val MAX_MATCHING_TYPES = 12
    private const val PYTHON_BLOCK_INDENT = 4
    private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
    private val FUNCTION_DECLARATION = Regex("""^(async\s+def|def)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:\(([^)]*)\)?)?.*$""")
    private val CLASS_DECLARATION = Regex("""^class\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:\(([^)]*)\)?)?.*$""")
    private val PYTHON_FUNCTION_SIGNATURE = Regex("""\b(?:async\s+def|def)\s+[A-Za-z_][A-Za-z0-9_]*\s*\(([^)]*)\)""")
    private val PYTHON_BUILT_INS = setOf("self", "cls", "True", "False", "None", "len", "sum", "list", "dict", "set", "str", "int", "float", "bool", "range", "print")
    private val PYTHON_SELF_NAMES = setOf("self", "cls")
}
