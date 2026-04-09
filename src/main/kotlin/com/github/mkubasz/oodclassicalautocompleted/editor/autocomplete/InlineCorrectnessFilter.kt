package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteRequest
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineCompletionCandidate
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor

internal object InlineCorrectnessFilter {

    enum class FailureReason(val metricValue: String) {
        STRUCTURE("structure"),
        SYNTAX("syntax"),
        UNRESOLVED("unresolved"),
        TIMEOUT("timeout"),
    }

    enum class LanguageFamily(val metricValue: String) {
        PYTHON("python"),
        JVM("jvm"),
        JAVASCRIPT_OR_TYPESCRIPT("javascript_typescript"),
        GO("go"),
        GENERIC("generic");

        companion object {
            fun from(language: String?, filePath: String?): LanguageFamily {
                val normalizedLanguage = language.orEmpty().trim().lowercase()
                val normalizedPath = filePath.orEmpty().trim().lowercase()
                return when {
                    normalizedLanguage.contains("python") || normalizedPath.endsWith(".py") -> PYTHON
                    normalizedLanguage.contains("kotlin") ||
                        normalizedLanguage == "kt" ||
                        normalizedLanguage == "kts" ||
                        normalizedLanguage.contains("java") ||
                        normalizedPath.endsWith(".kt") ||
                        normalizedPath.endsWith(".kts") ||
                        normalizedPath.endsWith(".java") -> JVM
                    normalizedLanguage.contains("typescript") ||
                        normalizedLanguage.contains("javascript") ||
                        normalizedLanguage == "ts" ||
                        normalizedLanguage == "tsx" ||
                        normalizedLanguage == "js" ||
                        normalizedLanguage == "jsx" ||
                        normalizedPath.endsWith(".ts") ||
                        normalizedPath.endsWith(".tsx") ||
                        normalizedPath.endsWith(".js") ||
                        normalizedPath.endsWith(".jsx") -> JAVASCRIPT_OR_TYPESCRIPT
                    normalizedLanguage == "go" || normalizedLanguage == "golang" || normalizedPath.endsWith(".go") -> GO
                    else -> GENERIC
                }
            }
        }
    }

    sealed interface Result {
        val family: LanguageFamily

        data class Pass(
            override val family: LanguageFamily,
        ) : Result

        data class Reject(
            override val family: LanguageFamily,
            val reason: FailureReason,
            val syntaxErrors: Int = 0,
            val unresolvedReferences: Int = 0,
        ) : Result

        data class Timeout(
            override val family: LanguageFamily,
        ) : Result
    }

    fun check(
        candidate: InlineCompletionCandidate,
        request: AutocompleteRequest,
        snapshot: CompletionContextSnapshot?,
        timeLimitMs: Long = DEFAULT_TIME_LIMIT_MS,
    ): Result {
        val family = LanguageFamily.from(request.language, snapshot?.filePath ?: request.filePath)
        val project = snapshot?.project ?: return Result.Pass(family)
        if (project.isDisposed) return Result.Pass(family)

        val insertionOffset = candidate.insertionOffset
        val documentText = snapshot.documentText
        if (insertionOffset !in 0..documentText.length) return Result.Pass(family)

        val profile = profileFor(family)
        val structuralFailure = structuralFailure(profile, snapshot, candidate)
        if (structuralFailure != null) {
            return Result.Reject(
                family = family,
                reason = FailureReason.STRUCTURE,
            )
        }

        val modifiedText = documentText.substring(0, insertionOffset) +
            candidate.text +
            documentText.substring(insertionOffset)

        val insertedRange = insertionOffset until (insertionOffset + candidate.text.length)
        val introducedIdentifiers = introducedIdentifiers(profile, candidate.text)

        return try {
            val startTime = System.nanoTime()
            ApplicationManager.getApplication().runReadAction<Result> {
                val psiFile = TemporaryPsiFileSupport.createTemporaryPsiFile(
                    project = project,
                    text = modifiedText,
                    filePath = snapshot.filePath,
                ) ?: return@runReadAction Result.Pass(family)

                var syntaxErrors = 0
                var unresolvedRefs = 0
                var timedOut = false
                val seenIdentifiers = mutableSetOf<Pair<Int, String>>()

                psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: PsiElement) {
                        val elapsed = (System.nanoTime() - startTime) / 1_000_000
                        if (elapsed > timeLimitMs) {
                            timedOut = true
                            stopWalking()
                            return
                        }

                        val range = element.textRange ?: run { super.visitElement(element); return }
                        val inInsertedRegion = range.startOffset < insertedRange.last + 1 &&
                            range.endOffset > insertedRange.first
                        if (!inInsertedRegion) {
                            super.visitElement(element)
                            return
                        }

                        if (element is PsiErrorElement) {
                            syntaxErrors++
                        }

                        if (shouldInspectIdentifier(element, introducedIdentifiers, profile)) {
                            val key = range.startOffset to element.text
                            if (seenIdentifiers.add(key) && hasUnresolvedReference(element)) {
                                unresolvedRefs++
                            }
                        }

                        super.visitElement(element)
                    }
                })

                when {
                    timedOut -> Result.Timeout(family)
                    syntaxErrors > profile.maxSyntaxErrors -> Result.Reject(
                        family = family,
                        reason = FailureReason.SYNTAX,
                        syntaxErrors = syntaxErrors,
                        unresolvedReferences = unresolvedRefs,
                    )
                    unresolvedRefs > profile.maxUnresolvedReferences -> Result.Reject(
                        family = family,
                        reason = FailureReason.UNRESOLVED,
                        syntaxErrors = syntaxErrors,
                        unresolvedReferences = unresolvedRefs,
                    )
                    else -> Result.Pass(family)
                }
            }
        } catch (_: Exception) {
            Result.Pass(family)
        }
    }

    private fun structuralFailure(
        profile: CorrectnessProfile,
        snapshot: CompletionContextSnapshot,
        candidate: InlineCompletionCandidate,
    ): String? {
        val prefix = snapshot.prefix
        val text = candidate.text
        if (text.isBlank()) return null

        if (BRACKET_PAIRS.any { (open, close) -> causesBracketUnderflow(prefix, text, open, close) }) {
            return "bracket_underflow"
        }
        if (hasDanglingQuote(text, snapshot.suffix)) {
            return "dangling_quote"
        }
        if (profile.family == LanguageFamily.PYTHON && hasPythonIndentationIssue(snapshot, text)) {
            return "indentation"
        }

        return null
    }

    private fun causesBracketUnderflow(prefix: String, candidateText: String, opening: Char, closing: Char): Boolean {
        var balance = 0
        prefix.forEach { char ->
            when (char) {
                opening -> balance++
                closing -> balance = (balance - 1).coerceAtLeast(0)
            }
        }

        candidateText.forEach { char ->
            when (char) {
                opening -> balance++
                closing -> {
                    balance--
                    if (balance < 0) return true
                }
            }
        }
        return false
    }

    private fun hasDanglingQuote(candidateText: String, suffix: String): Boolean {
        val normalized = candidateText.replace("\\\\", "")
        QUOTE_CHARS.forEach { quote ->
            val count = normalized.count { it == quote }
            if (count % 2 != 0 && !suffix.trimStart().startsWith(quote)) {
                return true
            }
        }
        return false
    }

    private fun hasPythonIndentationIssue(snapshot: CompletionContextSnapshot, candidateText: String): Boolean {
        if (!candidateText.contains('\n')) return false
        val lines = candidateText.replace("\r", "").lines()
        if (lines.size < 2) return false

        val relevantHeader = snapshot.inlineContext?.isDefinitionHeaderLikeContext == true ||
            snapshot.inlineContext?.isFreshBlockBodyContext == true ||
            snapshot.prefix.trimEnd().endsWith(":") ||
            lines.first().trimEnd().endsWith(":")
        if (!relevantHeader) return false

        val currentIndent = snapshot.prefix
            .substringAfterLast('\n', "")
            .takeWhile { it == ' ' || it == '\t' }
            .length
        val firstBodyLine = lines.drop(1).firstOrNull { it.isNotBlank() } ?: return false
        val candidateIndent = firstBodyLine.takeWhile { it == ' ' || it == '\t' }.length
        return candidateIndent < currentIndent + PYTHON_BLOCK_INDENT
    }

    private fun shouldInspectIdentifier(
        element: PsiElement,
        introducedIdentifiers: Set<String>,
        profile: CorrectnessProfile,
    ): Boolean {
        val text = element.text
        if (!element.references.any()) return false
        if (!REFERENCE_TEXT.matches(text)) return false
        if (text in introducedIdentifiers) return false
        if (text in profile.builtIns) return false
        return true
    }

    private fun hasUnresolvedReference(element: PsiElement): Boolean =
        element.references.any { reference ->
            runCatching { reference.resolve() }.getOrNull() == null
        }

    private fun introducedIdentifiers(
        profile: CorrectnessProfile,
        candidateText: String,
    ): Set<String> {
        val identifiers = linkedSetOf<String>()
        GENERIC_DECLARATION_PATTERNS.forEach { regex ->
            regex.findAll(candidateText).forEach { match ->
                match.groups.drop(1).mapNotNull { it?.value }
                    .filter { IDENTIFIER.matches(it) }
                    .forEach(identifiers::add)
            }
        }

        when (profile.family) {
            LanguageFamily.PYTHON -> {
                PYTHON_DECLARATION_PATTERNS.forEach { regex ->
                    regex.findAll(candidateText).forEach { match ->
                        match.groups.drop(1).mapNotNull { it?.value }.forEach(identifiers::add)
                    }
                }
                FUNCTION_SIGNATURE.findAll(candidateText)
                    .flatMap { parameterIdentifiers(it.groupValues[1], profile.family).asSequence() }
                    .forEach(identifiers::add)
            }
            LanguageFamily.JVM,
            LanguageFamily.GO,
            LanguageFamily.JAVASCRIPT_OR_TYPESCRIPT,
            LanguageFamily.GENERIC -> {
                FUNCTION_SIGNATURE.findAll(candidateText)
                    .flatMap { parameterIdentifiers(it.groupValues[1], profile.family).asSequence() }
                    .forEach(identifiers::add)
            }
        }

        return identifiers
    }

    private fun parameterIdentifiers(signatureBody: String, family: LanguageFamily): Set<String> {
        if (signatureBody.isBlank()) return emptySet()
        val identifiers = linkedSetOf<String>()
        signatureBody.split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach { parameter ->
                when (family) {
                    LanguageFamily.PYTHON -> {
                        val pythonName = parameter
                            .substringBefore(':')
                            .substringBefore('=')
                            .removePrefix("*")
                            .trim()
                        if (IDENTIFIER.matches(pythonName)) identifiers += pythonName
                    }
                    else -> {
                        val kotlinName = parameter.substringBefore(':').trim().substringAfterLast(' ')
                        val assignmentName = parameter.substringBefore('=').trim().substringAfterLast(' ')
                        val fallback = assignmentName.ifBlank { kotlinName }
                        if (IDENTIFIER.matches(fallback)) identifiers += fallback
                    }
                }
            }
        return identifiers
    }

    private fun profileFor(family: LanguageFamily): CorrectnessProfile =
        when (family) {
            LanguageFamily.PYTHON -> CorrectnessProfile(
                family = family,
                maxSyntaxErrors = 0,
                maxUnresolvedReferences = 1,
                builtIns = PYTHON_BUILT_INS,
            )
            LanguageFamily.JVM -> CorrectnessProfile(
                family = family,
                maxSyntaxErrors = 0,
                maxUnresolvedReferences = 1,
                builtIns = JVM_BUILT_INS,
            )
            LanguageFamily.JAVASCRIPT_OR_TYPESCRIPT -> CorrectnessProfile(
                family = family,
                maxSyntaxErrors = 0,
                maxUnresolvedReferences = 2,
                builtIns = JAVASCRIPT_BUILT_INS,
            )
            LanguageFamily.GO -> CorrectnessProfile(
                family = family,
                maxSyntaxErrors = 0,
                maxUnresolvedReferences = 1,
                builtIns = GO_BUILT_INS,
            )
            LanguageFamily.GENERIC -> CorrectnessProfile(
                family = family,
                maxSyntaxErrors = 1,
                maxUnresolvedReferences = 3,
            )
        }

    private data class CorrectnessProfile(
        val family: LanguageFamily,
        val maxSyntaxErrors: Int,
        val maxUnresolvedReferences: Int,
        val builtIns: Set<String> = emptySet(),
    )

    private const val DEFAULT_TIME_LIMIT_MS = 50L
    private const val PYTHON_BLOCK_INDENT = 4
    private val BRACKET_PAIRS = listOf('(' to ')', '[' to ']', '{' to '}')
    private val QUOTE_CHARS = listOf('"', '\'')
    private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
    private val REFERENCE_TEXT = Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*""")
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
    private val FUNCTION_SIGNATURE = Regex("""\b(?:async\s+def|def|fun|func|function)\s+[A-Za-z_][A-Za-z0-9_]*\s*\(([^)]*)\)""")
    private val PYTHON_BUILT_INS = setOf("self", "cls", "True", "False", "None", "len", "sum", "list", "dict", "set", "str", "int", "float", "bool", "range", "print")
    private val JVM_BUILT_INS = setOf(
        "this", "super", "true", "false", "null",
        "String", "Int", "Long", "Double", "Boolean", "List", "Map", "Set",
        "int", "long", "double", "boolean", "void", "char", "byte", "short", "float",
    )
    private val JAVASCRIPT_BUILT_INS = setOf("this", "true", "false", "null", "undefined", "Promise", "Array", "Object", "console")
    private val GO_BUILT_INS = setOf("nil", "true", "false", "string", "int", "error", "len", "make", "append")
}
