package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ProviderRequest
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineCompletionCandidate
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.CorrectnessParameterStyle
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.CorrectnessValidationContext
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.LanguageCorrectnessProfile
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.LanguageSupportRegistry
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
        request: ProviderRequest,
        snapshot: CompletionContextSnapshot?,
        timeLimitMs: Long = DEFAULT_TIME_LIMIT_MS,
    ): Result {
        val filePath = snapshot?.filePath ?: request.filePath
        val languageId = snapshot?.language ?: request.language
        val languageSupport = LanguageSupportRegistry.default().forLanguage(languageId, filePath)
        val profile = languageSupport.correctnessProfile(languageId, filePath)
        val family = profile.family
        val project = snapshot?.project ?: return Result.Pass(family)
        if (project.isDisposed) return Result.Pass(family)

        val insertionOffset = candidate.insertionOffset
        val documentText = snapshot.documentText
        if (insertionOffset !in 0..documentText.length) return Result.Pass(family)

        val structuralFailure = structuralFailure(profile, snapshot, candidate)
        if (structuralFailure != null) {
            return Result.Reject(
                family = family,
                reason = FailureReason.STRUCTURE,
            )
        }
        if (shouldBypassPsiValidation(profile, request, snapshot, candidate)) {
            return Result.Pass(family)
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
        profile: LanguageCorrectnessProfile,
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
        return profile.structuralValidator?.invoke(
            CorrectnessValidationContext(
                prefix = snapshot.prefix,
                suffix = snapshot.suffix,
                inlineContext = snapshot.inlineContext,
            ),
            text,
        )
    }

    private fun shouldBypassPsiValidation(
        profile: LanguageCorrectnessProfile,
        request: ProviderRequest,
        snapshot: CompletionContextSnapshot,
        candidate: InlineCompletionCandidate,
    ): Boolean {
        if (candidate.text.isBlank() || candidate.text.contains('\n')) return false

        val context = snapshot.inlineContext ?: request.inlineContext ?: return false
        if (!context.isDefinitionHeaderLikeContext && !context.isClassBaseListLikeContext) return false

        val linePrefix = request.prefix.substringAfterLast('\n').trimStart()
        return profile.definitionHeaderBypassPrefixes.any { it.matches(linePrefix) }
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

    private fun shouldInspectIdentifier(
        element: PsiElement,
        introducedIdentifiers: Set<String>,
        profile: LanguageCorrectnessProfile,
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
        profile: LanguageCorrectnessProfile,
        candidateText: String,
    ): Set<String> {
        val identifiers = linkedSetOf<String>()
        profile.declarationPatterns.forEach { regex ->
            regex.findAll(candidateText).forEach { match ->
                match.groups.drop(1).mapNotNull { it?.value }
                    .filter { IDENTIFIER.matches(it) }
                    .forEach(identifiers::add)
            }
        }

        profile.functionSignaturePatterns.forEach { regex ->
            regex.findAll(candidateText)
                .mapNotNull { match -> match.groupValues.getOrNull(1) }
                .flatMap { parameterIdentifiers(it, profile.parameterStyle).asSequence() }
                .forEach(identifiers::add)
        }

        return identifiers
    }

    private fun parameterIdentifiers(
        signatureBody: String,
        style: CorrectnessParameterStyle,
    ): Set<String> {
        if (signatureBody.isBlank()) return emptySet()
        val identifiers = linkedSetOf<String>()
        signatureBody.split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach { parameter ->
                when (style) {
                    CorrectnessParameterStyle.PYTHON -> {
                        val pythonName = parameter
                            .substringBefore(':')
                            .substringBefore('=')
                            .removePrefix("*")
                            .trim()
                        if (IDENTIFIER.matches(pythonName)) identifiers += pythonName
                    }
                    CorrectnessParameterStyle.SPACE_OR_TYPE_PREFIX -> {
                        val kotlinName = parameter.substringBefore(':').trim().substringAfterLast(' ')
                        val assignmentName = parameter.substringBefore('=').trim().substringAfterLast(' ')
                        val fallback = assignmentName.ifBlank { kotlinName }
                        if (IDENTIFIER.matches(fallback)) identifiers += fallback
                    }
                }
            }
        return identifiers
    }

    private const val DEFAULT_TIME_LIMIT_MS = 50L
    private val BRACKET_PAIRS = listOf('(' to ')', '[' to ']', '{' to '}')
    private val QUOTE_CHARS = listOf('"', '\'')
    private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
    private val REFERENCE_TEXT = Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*""")
}
