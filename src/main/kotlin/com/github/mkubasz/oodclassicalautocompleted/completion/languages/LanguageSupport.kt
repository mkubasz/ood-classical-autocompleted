package com.github.mkubasz.oodclassicalautocompleted.completion.languages

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.EditorSnapshot
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineModelContext
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.RetrievedContextChunkKind
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.common.GenericLanguageSupport
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.go.GoLanguageSupport
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.java.JavaLanguageSupport
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.python.PythonLanguageSupport
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.InlineContextRequest
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.InlineContextResolution
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.InlineContextResolver
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.InlineCorrectnessFilter

internal interface LanguageSupport {
    val id: String
    val languageIds: Set<String>
    val fileExtensions: Set<String>
        get() = emptySet()

    fun supports(languageId: String?, filePath: String? = null): Boolean {
        val normalizedLanguageId = languageId?.trim()
        if (normalizedLanguageId != null && languageIds.any { it.equals(normalizedLanguageId, ignoreCase = true) }) {
            return true
        }

        val extension = filePath
            ?.substringAfterLast('.', "")
            ?.takeIf(String::isNotBlank)
            ?.lowercase()
        return extension != null && fileExtensions.any { it.equals(extension, ignoreCase = true) }
    }

    fun resolveInlineContext(snapshot: EditorSnapshot): InlineContextResolution = InlineContextResolver.fromSettings().resolve(
        InlineContextRequest(
            project = snapshot.project,
            document = snapshot.document,
            documentText = snapshot.documentText,
            caretOffset = snapshot.caretOffset,
            filePath = snapshot.filePath,
            languageId = snapshot.languageId,
        )
    )

    fun retrievalProfile(): WorkspaceRetrievalProfile = WorkspaceRetrievalProfile()

    fun correctnessProfile(
        languageId: String?,
        filePath: String?,
    ): LanguageCorrectnessProfile = LanguageCorrectnessProfile()
}

internal data class WorkspaceStructurePattern(
    val regex: Regex,
    val kind: RetrievedContextChunkKind,
)

internal data class WorkspaceRetrievalProfile(
    val importPrefixes: List<String> = emptyList(),
    val structuralPatterns: List<WorkspaceStructurePattern> = emptyList(),
    val structuralChunkMaxLines: Int = DEFAULT_STRUCTURAL_CHUNK_MAX_LINES,
    val lineWindowSize: Int = DEFAULT_LINE_WINDOW_SIZE,
    val contextLinesBeforeHit: Int = DEFAULT_CONTEXT_LINES_BEFORE_HIT,
    val maxLineWindowsPerFile: Int = DEFAULT_MAX_LINE_WINDOWS_PER_FILE,
) {
    fun cacheKey(): String = buildString {
        append(importPrefixes.joinToString(","))
        append('|')
        append(structuralChunkMaxLines)
        append('|')
        append(lineWindowSize)
        append('|')
        append(contextLinesBeforeHit)
        append('|')
        append(maxLineWindowsPerFile)
        append('|')
        append(structuralPatterns.joinToString(",") { "${it.kind}:${it.regex.pattern}" })
    }

    companion object {
        private const val DEFAULT_STRUCTURAL_CHUNK_MAX_LINES = 18
        private const val DEFAULT_LINE_WINDOW_SIZE = 14
        private const val DEFAULT_CONTEXT_LINES_BEFORE_HIT = 2
        private const val DEFAULT_MAX_LINE_WINDOWS_PER_FILE = 1
    }
}

internal data class CorrectnessValidationContext(
    val prefix: String,
    val suffix: String,
    val inlineContext: InlineModelContext?,
)

internal enum class CorrectnessParameterStyle {
    PYTHON,
    SPACE_OR_TYPE_PREFIX,
}

internal data class LanguageCorrectnessProfile(
    val family: InlineCorrectnessFilter.LanguageFamily = InlineCorrectnessFilter.LanguageFamily.GENERIC,
    val maxSyntaxErrors: Int = 1,
    val maxUnresolvedReferences: Int = 3,
    val builtIns: Set<String> = emptySet(),
    val declarationPatterns: List<Regex> = emptyList(),
    val functionSignaturePatterns: List<Regex> = emptyList(),
    val parameterStyle: CorrectnessParameterStyle = CorrectnessParameterStyle.SPACE_OR_TYPE_PREFIX,
    val definitionHeaderBypassPrefixes: List<Regex> = emptyList(),
    val structuralValidator: ((CorrectnessValidationContext, String) -> String?)? = null,
)

internal class LanguageSupportRegistry(
    private val supports: List<LanguageSupport>,
    private val fallback: LanguageSupport,
) {
    fun forLanguage(
        languageId: String?,
        filePath: String? = null,
    ): LanguageSupport = supports.firstOrNull { it.supports(languageId, filePath) } ?: fallback

    companion object {
        fun default(): LanguageSupportRegistry = LanguageSupportRegistry(
            supports = listOf(JavaLanguageSupport, PythonLanguageSupport, GoLanguageSupport),
            fallback = GenericLanguageSupport,
        )
    }
}
