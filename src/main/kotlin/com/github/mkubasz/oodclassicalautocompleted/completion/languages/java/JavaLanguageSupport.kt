package com.github.mkubasz.oodclassicalautocompleted.completion.languages.java

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

internal object JavaLanguageSupport : DocumentBackedLanguageSupport() {
    override val id: String = "java"
    override val languageIds: Set<String> = setOf("JAVA", "java")
    override val fileExtensions: Set<String> = setOf("java")

    override fun retrieval(): WorkspaceRetrievalProfile = WorkspaceRetrievalProfile(
        importPrefixes = listOf("package ", "import "),
        structuralPatterns = listOf(
            WorkspaceStructurePattern(
                Regex("""^\s*(?:public|protected|private|abstract|final|static|\s)*(?:class|interface|enum|record)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
                RetrievedContextChunkKind.SYMBOL,
            ),
            WorkspaceStructurePattern(
                Regex("""^\s*(?:public|protected|private|static|final|abstract|synchronized|default|native|\s)*[A-Za-z_<>\[\], ?@]+\s+([A-Za-z_][A-Za-z0-9_]*)\s*\("""),
                RetrievedContextChunkKind.SYMBOL,
            ),
            WorkspaceStructurePattern(
                Regex("""^\s*(?:public|protected|private|static|final)\s+[A-Za-z_<>\[\], ?]+\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:=|;)"""),
                RetrievedContextChunkKind.CONSTANT,
            ),
        ),
        structuralChunkMaxLines = 22,
        lineWindowSize = 12,
        contextLinesBeforeHit = 1,
    )

    override fun correctnessProfile(
        languageId: String?,
        filePath: String?,
    ): LanguageCorrectnessProfile = LanguageCorrectnessProfile(
        family = InlineCorrectnessFilter.LanguageFamily.JVM,
        maxSyntaxErrors = 0,
        maxUnresolvedReferences = 1,
        builtIns = JVM_BUILT_INS,
        declarationPatterns = listOf(
            Regex("""\b(?:class|interface|enum|record)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
            Regex("""\b(?:final)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
            Regex("""\bcatch\s*\(\s*[^)]*\s+([A-Za-z_][A-Za-z0-9_]*)\s*\)"""),
        ),
        functionSignaturePatterns = listOf(JAVA_FUNCTION_SIGNATURE),
        parameterStyle = CorrectnessParameterStyle.SPACE_OR_TYPE_PREFIX,
        definitionHeaderBypassPrefixes = listOf(JAVA_DECLARATION_HEADER_PREFIX),
    )

    override fun buildLanguageContext(request: InlineContextRequest): InlineModelContext {
        val safeOffset = request.caretOffset.coerceIn(0, request.documentText.length)
        val prefix = request.documentText.substring(0, safeOffset)
        val currentLinePrefix = prefix.substringAfterLast('\n')
        val declarations = collectDeclarations(prefix)
        val currentDeclaration = parseDeclaration(currentLinePrefix.trimStart())
        val activeDeclaration = currentDeclaration ?: declarations.lastOrNull { it.kind == "Method" }
        val parentChain = declarations.takeLast(MAX_ENCLOSING).map { it.name }.asReversed()
        val parentKinds = declarations.takeLast(MAX_ENCLOSING).map { it.kind }.asReversed()
        val parentContext = extractParentContext(currentLinePrefix)
        val parentPrefix = parentContext?.prefix
        val matchingTypes = if (parentContext != null) {
            findTypeNamesMatchingPrefix(request.documentText, parentPrefix)
        } else {
            emptyList()
        }

        return InlineModelContext(
            enclosingNames = parentChain,
            enclosingKinds = parentKinds,
            currentDefinitionName = activeDeclaration?.name,
            currentParameterNames = activeDeclaration?.parameters.orEmpty(),
            isClassBaseListLikeContext = parentContext != null,
            isAfterMemberAccess = currentLinePrefix.trimEnd().endsWith("."),
            receiverExpression = ReceiverContextHeuristics.extractReceiverExpression(currentLinePrefix),
            isInParameterListLikeContext = looksLikeMethodHeader(currentLinePrefix.trimStart()) &&
                currentLinePrefix.count { it == '(' } > currentLinePrefix.count { it == ')' },
            isDefinitionHeaderLikeContext = currentDeclaration != null || looksLikeMethodHeader(currentLinePrefix.trimStart()),
            classBaseReferencePrefix = parentPrefix,
            matchingTypeNames = matchingTypes,
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
        if (trimmed.isBlank() || trimmed.startsWith("@")) return null

        TYPE_DECLARATION.find(trimmed)?.let { match ->
            val kind = match.groupValues[1].replaceFirstChar { it.uppercase() }
            val name = match.groupValues[2]
            return ScopedDeclaration(name = name, kind = kind, parameters = emptyList())
        }
        METHOD_DECLARATION.find(trimmed)?.let { match ->
            val name = match.groupValues[1]
            if (name in CONTROL_KEYWORDS) return null
            return ScopedDeclaration(
                name = name,
                kind = "Method",
                parameters = extractParameters(match.groupValues[2]),
            )
        }
        return null
    }

    private fun extractParentContext(currentLinePrefix: String): ParentContext? {
        val trimmed = currentLinePrefix.trimStart()
        val parentSegment = when {
            " extends " in trimmed -> trimmed.substringAfter(" extends ")
            " implements " in trimmed -> trimmed.substringAfter(" implements ")
            else -> return null
        }
        val parentPrefix = parentSegment
            .substringAfterLast(',')
            .trim()
            .takeIf(String::isNotBlank)
            ?.substringAfterLast(' ')
            ?: return null
        return ParentContext(prefix = parentPrefix)
    }

    private fun findTypeNamesMatchingPrefix(
        documentText: String,
        prefix: String?,
    ): List<String> {
        if (prefix.isNullOrBlank()) return emptyList()
        return TYPE_DECLARATION.findAll(documentText)
            .map { it.groupValues[2] }
            .filter { it.startsWith(prefix, ignoreCase = true) && it != prefix }
            .distinct()
            .take(MAX_MATCHING_TYPES)
            .toList()
    }

    private fun extractParameters(signature: String): List<String> = signature.split(',')
        .mapNotNull { parameter ->
            val normalized = parameter
                .substringBefore('=')
                .replace(ANNOTATION_PATTERN, " ")
                .trim()
            normalized.substringAfterLast(' ')
                .substringAfterLast("...")
                .takeIf(IDENTIFIER::matches)
        }
        .filter { it !in JVM_BUILT_INS }
        .distinct()

    private fun looksLikeMethodHeader(trimmed: String): Boolean =
        METHOD_DECLARATION.containsMatchIn(trimmed) || trimmed.startsWith("class ") || TYPE_DECLARATION.containsMatchIn(trimmed)

    private data class ScopedDeclaration(
        val name: String,
        val kind: String,
        val parameters: List<String>,
        val scopeDepth: Int = 0,
    )

    private data class ParentContext(
        val prefix: String,
    )

    private const val MAX_ENCLOSING = 6
    private const val MAX_MATCHING_TYPES = 12
    private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
    private val ANNOTATION_PATTERN = Regex("""@\w+(?:\([^)]*\))?""")
    private val TYPE_DECLARATION = Regex(
        """^\s*(?:public|protected|private|abstract|final|static|sealed|non-sealed|\s)*(class|interface|enum|record)\s+([A-Za-z_][A-Za-z0-9_]*)"""
    )
    private val METHOD_DECLARATION = Regex(
        """^\s*(?:public|protected|private|static|final|abstract|synchronized|default|native|strictfp|\s)*[A-Za-z_<>\[\], ?@]+\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)"""
    )
    private val CONTROL_KEYWORDS = setOf("if", "for", "while", "switch", "catch", "return", "new")
    private val JVM_BUILT_INS = setOf(
        "this", "super", "true", "false", "null",
        "String", "Int", "Long", "Double", "Boolean", "List", "Map", "Set",
        "int", "long", "double", "boolean", "void", "char", "byte", "short", "float",
    )
    private val JAVA_FUNCTION_SIGNATURE = Regex(
        """\b[A-Za-z_<>\[\], ?@]+\s+[A-Za-z_][A-Za-z0-9_]*\s*\(([^)]*)\)"""
    )
    private val JAVA_DECLARATION_HEADER_PREFIX = Regex(
        """^(?:(?:public|private|protected|abstract|final|static|sealed|non-sealed)\s+)*(?:class|interface|record|enum)\b.*$"""
    )
}
