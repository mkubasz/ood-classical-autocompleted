package com.github.mkubasz.oodclassicalautocompleted.completion.languages.common

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.EditorSnapshot
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineLexicalContext
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineModelContext
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ResolvedDefinition
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.CorrectnessValidationContext
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.LanguageSupport
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.LanguageCorrectnessProfile
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.CorrectnessParameterStyle
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.WorkspaceRetrievalProfile
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.WorkspaceStructurePattern
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.BaseInlineContextHeuristics
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.HeuristicInlineContextProvider
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.InlineContextRequest
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.InlineContextResolution
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.InlineContextResolver
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.InlineContextSource
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.InlineCorrectnessFilter
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.hasUsefulSignal

internal abstract class DocumentBackedLanguageSupport : LanguageSupport {
    protected abstract fun buildLanguageContext(request: InlineContextRequest): InlineModelContext?

    protected abstract fun retrieval(): WorkspaceRetrievalProfile

    override fun resolveInlineContext(snapshot: EditorSnapshot): InlineContextResolution {
        val request = InlineContextRequest(
            project = snapshot.project,
            document = snapshot.document,
            documentText = snapshot.documentText,
            caretOffset = snapshot.caretOffset,
            filePath = snapshot.filePath,
            languageId = snapshot.languageId,
        )
        val base = InlineContextResolver.fromSettings().resolve(request)
        val languageContext = buildLanguageContext(request)
        val mergedContext = mergeContexts(base.context, languageContext)
            ?.takeIf { it.hasUsefulSignal() }

        return InlineContextResolution(
            context = mergedContext,
            source = when {
                base.source != null && mergedContext != null -> base.source
                languageContext != null -> InlineContextSource.HEURISTIC
                else -> base.source
            },
        )
    }

    override fun retrievalProfile(): WorkspaceRetrievalProfile = retrieval()

    private fun mergeContexts(
        base: InlineModelContext?,
        languageSpecific: InlineModelContext?,
    ): InlineModelContext? {
        if (base == null) return languageSpecific
        if (languageSpecific == null) return base

        return base.copy(
            lexicalContext = if (base.lexicalContext != InlineLexicalContext.UNKNOWN) {
                base.lexicalContext
            } else {
                languageSpecific.lexicalContext
            },
            enclosingNames = preferredNames(base.enclosingNames, languageSpecific.enclosingNames),
            enclosingKinds = preferredNames(base.enclosingKinds, languageSpecific.enclosingKinds),
            currentDefinitionName = languageSpecific.currentDefinitionName ?: base.currentDefinitionName,
            currentParameterNames = mergeDistinct(base.currentParameterNames, languageSpecific.currentParameterNames),
            isFreshBlockBodyContext = base.isFreshBlockBodyContext || languageSpecific.isFreshBlockBodyContext,
            isDecoratorLikeContext = base.isDecoratorLikeContext || languageSpecific.isDecoratorLikeContext,
            headerValidationRetry = base.headerValidationRetry || languageSpecific.headerValidationRetry,
            isClassBaseListLikeContext = base.isClassBaseListLikeContext || languageSpecific.isClassBaseListLikeContext,
            isAfterMemberAccess = base.isAfterMemberAccess || languageSpecific.isAfterMemberAccess,
            receiverExpression = base.receiverExpression ?: languageSpecific.receiverExpression,
            receiverMemberNames = mergeDistinct(base.receiverMemberNames, languageSpecific.receiverMemberNames),
            isInParameterListLikeContext = base.isInParameterListLikeContext || languageSpecific.isInParameterListLikeContext,
            isDefinitionHeaderLikeContext = base.isDefinitionHeaderLikeContext || languageSpecific.isDefinitionHeaderLikeContext,
            classBaseReferencePrefix = base.classBaseReferencePrefix ?: languageSpecific.classBaseReferencePrefix,
            matchingTypeNames = mergeDistinct(base.matchingTypeNames, languageSpecific.matchingTypeNames),
            headerValidationError = base.headerValidationError ?: languageSpecific.headerValidationError,
            expectedHeaderContinuation = base.expectedHeaderContinuation ?: languageSpecific.expectedHeaderContinuation,
            resolvedReferenceName = base.resolvedReferenceName ?: languageSpecific.resolvedReferenceName,
            resolvedFilePath = base.resolvedFilePath ?: languageSpecific.resolvedFilePath,
            resolvedSnippet = base.resolvedSnippet ?: languageSpecific.resolvedSnippet,
            resolvedDefinitions = mergeDefinitions(base.resolvedDefinitions, languageSpecific.resolvedDefinitions),
        )
    }

    private fun preferredNames(
        base: List<String>,
        languageSpecific: List<String>,
    ): List<String> = when {
        languageSpecific.isNotEmpty() -> languageSpecific
        else -> base
    }

    private fun mergeDistinct(
        base: List<String>,
        languageSpecific: List<String>,
    ): List<String> = (base + languageSpecific).distinct()

    private fun mergeDefinitions(
        base: List<ResolvedDefinition>,
        languageSpecific: List<ResolvedDefinition>,
    ): List<ResolvedDefinition> = (base + languageSpecific)
        .distinctBy { definition -> listOf(definition.name, definition.filePath.orEmpty(), definition.signature) }
}

internal object GenericLanguageSupport : LanguageSupport {
    override val id: String = "generic"
    override val languageIds: Set<String> = emptySet()

    override fun supports(languageId: String?, filePath: String?): Boolean = false

    override fun resolveInlineContext(snapshot: EditorSnapshot): InlineContextResolution {
        val request = InlineContextRequest(
            project = snapshot.project,
            document = snapshot.document,
            documentText = snapshot.documentText,
            caretOffset = snapshot.caretOffset,
            filePath = snapshot.filePath,
            languageId = snapshot.languageId,
        )
        return BaseInlineContextHeuristics.build(
            request = request,
            includeResolvedDefinitions = true,
        ).let { context ->
            InlineContextResolution(context = context.takeIf { it.hasUsefulSignal() }, source = HeuristicInlineContextProvider.source)
        }
    }

    override fun retrievalProfile(): WorkspaceRetrievalProfile = WorkspaceRetrievalProfile(
        importPrefixes = listOf("package ", "import ", "from ", "using "),
        structuralPatterns = listOf(
            WorkspaceStructurePattern(
                Regex("""^\s*(?:async\s+def|def|class)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
                com.github.mkubasz.oodclassicalautocompleted.completion.domain.RetrievedContextChunkKind.SYMBOL,
            ),
            WorkspaceStructurePattern(
                Regex("""^\s*(?:fun|class|interface|enum|object|data\s+class)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
                com.github.mkubasz.oodclassicalautocompleted.completion.domain.RetrievedContextChunkKind.SYMBOL,
            ),
            WorkspaceStructurePattern(
                Regex("""^\s*(?:function|class)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
                com.github.mkubasz.oodclassicalautocompleted.completion.domain.RetrievedContextChunkKind.SYMBOL,
            ),
            WorkspaceStructurePattern(
                Regex("""^\s*func\s+(?:\([^)]+\)\s*)?([A-Za-z_][A-Za-z0-9_]*)"""),
                com.github.mkubasz.oodclassicalautocompleted.completion.domain.RetrievedContextChunkKind.SYMBOL,
            ),
            WorkspaceStructurePattern(
                Regex("""^\s*type\s+([A-Za-z_][A-Za-z0-9_]*)\s+"""),
                com.github.mkubasz.oodclassicalautocompleted.completion.domain.RetrievedContextChunkKind.SYMBOL,
            ),
            WorkspaceStructurePattern(
                Regex("""^\s*(?:const|val|var|let)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
                com.github.mkubasz.oodclassicalautocompleted.completion.domain.RetrievedContextChunkKind.CONSTANT,
            ),
        ),
    )

    override fun correctnessProfile(
        languageId: String?,
        filePath: String?,
    ): LanguageCorrectnessProfile {
        val family = InlineCorrectnessFilter.LanguageFamily.from(languageId, filePath)
        return when (family) {
            InlineCorrectnessFilter.LanguageFamily.PYTHON -> LanguageCorrectnessProfile(
                family = family,
                maxSyntaxErrors = 0,
                maxUnresolvedReferences = 1,
                builtIns = PYTHON_BUILT_INS,
                declarationPatterns = GENERIC_DECLARATION_PATTERNS + PYTHON_DECLARATION_PATTERNS,
                functionSignaturePatterns = listOf(PYTHON_FUNCTION_SIGNATURE),
                parameterStyle = CorrectnessParameterStyle.PYTHON,
                structuralValidator = { context, candidateText ->
                    if (hasPythonIndentationIssue(context, candidateText)) "indentation" else null
                },
            )
            InlineCorrectnessFilter.LanguageFamily.JVM -> LanguageCorrectnessProfile(
                family = family,
                maxSyntaxErrors = 0,
                maxUnresolvedReferences = 1,
                builtIns = JVM_BUILT_INS,
                declarationPatterns = GENERIC_DECLARATION_PATTERNS + JVM_DECLARATION_PATTERNS,
                functionSignaturePatterns = listOf(JVM_FUNCTION_SIGNATURE),
                definitionHeaderBypassPrefixes = listOf(JVM_DECLARATION_HEADER_PREFIX),
            )
            InlineCorrectnessFilter.LanguageFamily.JAVASCRIPT_OR_TYPESCRIPT -> LanguageCorrectnessProfile(
                family = family,
                maxSyntaxErrors = 0,
                maxUnresolvedReferences = 2,
                builtIns = JAVASCRIPT_BUILT_INS,
                declarationPatterns = GENERIC_DECLARATION_PATTERNS + JAVASCRIPT_DECLARATION_PATTERNS,
                functionSignaturePatterns = listOf(JAVASCRIPT_FUNCTION_SIGNATURE),
            )
            InlineCorrectnessFilter.LanguageFamily.GO -> LanguageCorrectnessProfile(
                family = family,
                maxSyntaxErrors = 0,
                maxUnresolvedReferences = 1,
                builtIns = GO_BUILT_INS,
                declarationPatterns = GENERIC_DECLARATION_PATTERNS + GO_DECLARATION_PATTERNS,
                functionSignaturePatterns = listOf(GO_FUNCTION_SIGNATURE),
            )
            InlineCorrectnessFilter.LanguageFamily.GENERIC -> LanguageCorrectnessProfile(
                family = family,
                declarationPatterns = GENERIC_DECLARATION_PATTERNS,
                functionSignaturePatterns = listOf(GENERIC_FUNCTION_SIGNATURE),
            )
        }
    }

    private fun hasPythonIndentationIssue(
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

    private const val PYTHON_BLOCK_INDENT = 4
    private val GENERIC_DECLARATION_PATTERNS = listOf(
            Regex("""\b(?:class|interface|enum|object|struct|type|trait)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
            Regex("""\b(?:val|var|const|let)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
            Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*:="""),
            Regex("""\bfor\s+([A-Za-z_][A-Za-z0-9_]*)\b"""),
            Regex("""\bimport\s+[A-Za-z0-9_.*]+\s+as\s+([A-Za-z_][A-Za-z0-9_]*)"""),
            Regex("""\bcatch\s*\(\s*[^)]*\s+([A-Za-z_][A-Za-z0-9_]*)\s*\)"""),
    )
    private val PYTHON_DECLARATION_PATTERNS = listOf(
            Regex("""\b(?:async\s+def|def)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
            Regex("""\bclass\s+([A-Za-z_][A-Za-z0-9_]*)"""),
            Regex("""\bfrom\s+[A-Za-z0-9_.]+\s+import\s+[A-Za-z0-9_.*]+\s+as\s+([A-Za-z_][A-Za-z0-9_]*)"""),
            Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*="""),
    )
    private val JVM_DECLARATION_PATTERNS = listOf(
            Regex("""\b(?:class|interface|enum|object|record)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
            Regex("""\b(?:val|var|const|final)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
    )
    private val JAVASCRIPT_DECLARATION_PATTERNS = listOf(
            Regex("""\b(?:function|class)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
            Regex("""\b(?:const|let|var)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
            Regex("""\bimport\s+[A-Za-z0-9_.*]+\s+as\s+([A-Za-z_][A-Za-z0-9_]*)"""),
    )
    private val GO_DECLARATION_PATTERNS = listOf(
            Regex("""\bfunc\s+(?:\([^)]+\)\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*\("""),
            Regex("""\btype\s+([A-Za-z_][A-Za-z0-9_]*)\s+"""),
            Regex("""\b(?:const|var)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
            Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*:="""),
    )
    private val PYTHON_FUNCTION_SIGNATURE = Regex("""\b(?:async\s+def|def)\s+[A-Za-z_][A-Za-z0-9_]*\s*\(([^)]*)\)""")
    private val JVM_FUNCTION_SIGNATURE = Regex("""\b(?:fun|function|class)\s+[A-Za-z_][A-Za-z0-9_]*\s*\(([^)]*)\)""")
    private val JAVASCRIPT_FUNCTION_SIGNATURE = Regex("""\b(?:function)\s+[A-Za-z_][A-Za-z0-9_]*\s*\(([^)]*)\)""")
    private val GO_FUNCTION_SIGNATURE = Regex("""\bfunc\s+(?:\([^)]+\)\s*)?[A-Za-z_][A-Za-z0-9_]*\s*\(([^)]*)\)""")
    private val GENERIC_FUNCTION_SIGNATURE = Regex("""\b(?:async\s+def|def|fun|func|function)\s+[A-Za-z_][A-Za-z0-9_]*\s*\(([^)]*)\)""")
    private val PYTHON_BUILT_INS = setOf("self", "cls", "True", "False", "None", "len", "sum", "list", "dict", "set", "str", "int", "float", "bool", "range", "print")
    private val JVM_BUILT_INS = setOf(
            "this", "super", "true", "false", "null",
            "String", "Int", "Long", "Double", "Boolean", "List", "Map", "Set",
            "int", "long", "double", "boolean", "void", "char", "byte", "short", "float",
    )
    private val JAVASCRIPT_BUILT_INS = setOf("this", "true", "false", "null", "undefined", "Promise", "Array", "Object", "console")
    private val GO_BUILT_INS = setOf("nil", "true", "false", "string", "int", "error", "len", "make", "append")
    private val JVM_DECLARATION_MODIFIERS = listOf(
            "public",
            "private",
            "protected",
            "internal",
            "abstract",
            "open",
            "sealed",
            "data",
            "enum",
            "annotation",
            "value",
            "inner",
            "override",
            "expect",
            "actual",
            "operator",
            "infix",
            "tailrec",
            "suspend",
            "external",
            "inline",
            "final",
            "const",
            "lateinit",
    )
    private val JVM_DECLARATION_HEADER_PREFIX = Regex(
        """^(?:(?:${JVM_DECLARATION_MODIFIERS.joinToString("|")})\s+)*(?:class|fun|interface|object)\b.*$"""
    )
}
