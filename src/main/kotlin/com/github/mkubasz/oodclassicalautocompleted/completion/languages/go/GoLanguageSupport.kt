package com.github.mkubasz.oodclassicalautocompleted.completion.languages.go

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineModelContext
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.RetrievedContextChunkKind
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.CorrectnessParameterStyle
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.LanguageCorrectnessProfile
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.WorkspaceRetrievalProfile
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.WorkspaceStructurePattern
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.common.DocumentBackedLanguageSupport
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.HeuristicContextFallback
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.InlineContextRequest
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.InlineCorrectnessFilter
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.ReceiverContextHeuristics

internal object GoLanguageSupport : DocumentBackedLanguageSupport() {
    override val id: String = "go"
    override val languageIds: Set<String> = setOf("go", "Go", "golang")
    override val fileExtensions: Set<String> = setOf("go")

    override fun retrieval(): WorkspaceRetrievalProfile = WorkspaceRetrievalProfile(
        importPrefixes = listOf("package ", "import "),
        structuralPatterns = listOf(
            WorkspaceStructurePattern(
                Regex("""^\s*func\s+(?:\([^)]+\)\s*)?([A-Za-z_][A-Za-z0-9_]*)"""),
                RetrievedContextChunkKind.SYMBOL,
            ),
            WorkspaceStructurePattern(
                Regex("""^\s*type\s+([A-Za-z_][A-Za-z0-9_]*)\s+(?:struct|interface|\w+)"""),
                RetrievedContextChunkKind.SYMBOL,
            ),
            WorkspaceStructurePattern(
                Regex("""^\s*(?:const|var)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
                RetrievedContextChunkKind.CONSTANT,
            ),
        ),
        structuralChunkMaxLines = 18,
        lineWindowSize = 10,
        contextLinesBeforeHit = 1,
    )

    override fun correctnessProfile(
        languageId: String?,
        filePath: String?,
    ): LanguageCorrectnessProfile = LanguageCorrectnessProfile(
        family = InlineCorrectnessFilter.LanguageFamily.GO,
        maxSyntaxErrors = 0,
        maxUnresolvedReferences = 1,
        builtIns = GO_BUILT_INS,
        declarationPatterns = listOf(
            Regex("""\bfunc\s+(?:\([^)]+\)\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*\("""),
            Regex("""\btype\s+([A-Za-z_][A-Za-z0-9_]*)\s+"""),
            Regex("""\b(?:const|var)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
            Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*:="""),
        ),
        functionSignaturePatterns = listOf(GO_FUNCTION_SIGNATURE),
        parameterStyle = CorrectnessParameterStyle.SPACE_OR_TYPE_PREFIX,
    )

    override fun buildLanguageContext(request: InlineContextRequest): InlineModelContext {
        val safeOffset = request.caretOffset.coerceIn(0, request.documentText.length)
        val prefix = request.documentText.substring(0, safeOffset)
        val currentLinePrefix = prefix.substringAfterLast('\n')
        val declarations = collectDeclarations(prefix)
        val currentDeclaration = parseDeclaration(currentLinePrefix.trimStart())
        val activeFunction = currentDeclaration?.takeIf { it.kind == "Function" }
            ?: declarations.lastOrNull { it.kind == "Function" }

        return InlineModelContext(
            enclosingNames = declarations.takeLast(MAX_ENCLOSING).map { it.name }.asReversed(),
            enclosingKinds = declarations.takeLast(MAX_ENCLOSING).map { it.kind }.asReversed(),
            currentDefinitionName = activeFunction?.name ?: declarations.lastOrNull()?.name,
            currentParameterNames = activeFunction?.parameters.orEmpty(),
            isAfterMemberAccess = currentLinePrefix.trimEnd().endsWith("."),
            receiverExpression = ReceiverContextHeuristics.extractReceiverExpression(currentLinePrefix),
            receiverMemberNames = activeFunction?.receiverType?.let(::receiverHints).orEmpty(),
            isInParameterListLikeContext = looksLikeFunctionHeader(currentLinePrefix.trimStart()) &&
                currentLinePrefix.count { it == '(' } > currentLinePrefix.count { it == ')' },
            isDefinitionHeaderLikeContext = currentDeclaration != null || looksLikeFunctionHeader(currentLinePrefix.trimStart()),
            resolvedDefinitions = HeuristicContextFallback.extractDefinitions(
                request.documentText,
                request.caretOffset,
            ),
        )
    }

    private fun collectDeclarations(prefix: String): List<ScopedDeclaration> {
        val declarations = mutableListOf<ScopedDeclaration>()
        var scopeDepth = 0
        val lines = prefix.replace("\r", "").lines()

        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trimStart()
            val closeCount = rawLine.count { it == '}' }
            scopeDepth = (scopeDepth - closeCount).coerceAtLeast(0)
            while (declarations.isNotEmpty() && declarations.last().scopeDepth > scopeDepth) {
                declarations.removeLast()
            }

            val declaration = parseDeclaration(line) ?: run {
                scopeDepth += rawLine.count { it == '{' }
                return@forEachIndexed
            }

            val openCount = rawLine.count { it == '{' }
            val isCurrentLine = index == lines.lastIndex
            if (openCount > closeCount || isCurrentLine) {
                declarations += declaration.copy(
                    scopeDepth = if (openCount > 0) scopeDepth + openCount else scopeDepth + 1,
                )
            }
            scopeDepth += openCount
        }

        return declarations
    }

    private fun parseDeclaration(line: String): ScopedDeclaration? {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return null

        FUNCTION_DECLARATION.find(trimmed)?.let { match ->
            return ScopedDeclaration(
                name = match.groupValues[2],
                kind = "Function",
                parameters = extractParameters(match.groupValues[3]),
                receiverType = extractReceiverType(match.groupValues[1]),
            )
        }
        STRUCT_DECLARATION.find(trimmed)?.let { match ->
            return ScopedDeclaration(
                name = match.groupValues[1],
                kind = match.groupValues[2].replaceFirstChar { it.uppercase() },
                parameters = emptyList(),
            )
        }
        return null
    }

    private fun extractParameters(signature: String): List<String> = signature.split(',')
        .mapNotNull { parameter ->
            val normalized = parameter.trim().substringBefore('=')
            normalized.substringBefore(' ')
                .substringBefore(',')
                .trim()
                .takeIf(IDENTIFIER::matches)
        }
        .filter { it !in GO_BUILT_INS }
        .distinct()

    private fun extractReceiverType(receiver: String): String? {
        if (receiver.isBlank()) return null
        return receiver.trim()
            .substringAfterLast(' ')
            .removePrefix("*")
            .takeIf(IDENTIFIER::matches)
    }

    private fun receiverHints(receiverType: String): List<String> = listOf(receiverType)

    private fun looksLikeFunctionHeader(trimmed: String): Boolean =
        trimmed.startsWith("func ") || trimmed.startsWith("type ")

    private data class ScopedDeclaration(
        val name: String,
        val kind: String,
        val parameters: List<String>,
        val receiverType: String? = null,
        val scopeDepth: Int = 0,
    )

    private const val MAX_ENCLOSING = 6
    private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
    private val FUNCTION_DECLARATION = Regex(
        """^func\s*(?:\(([^)]*)\)\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)"""
    )
    private val STRUCT_DECLARATION = Regex(
        """^type\s+([A-Za-z_][A-Za-z0-9_]*)\s+(struct|interface|type)"""
    )
    private val GO_FUNCTION_SIGNATURE = Regex("""\bfunc\s+(?:\([^)]+\)\s*)?[A-Za-z_][A-Za-z0-9_]*\s*\(([^)]*)\)""")
    private val GO_BUILT_INS = setOf("nil", "true", "false", "string", "int", "error", "len", "make", "append")
}
