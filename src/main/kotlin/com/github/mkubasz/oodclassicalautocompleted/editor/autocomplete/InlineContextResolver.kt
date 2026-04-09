package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineLexicalContext
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineModelContext
import com.github.mkubasz.oodclassicalautocompleted.settings.PluginSettings
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project

internal data class InlineContextRequest(
    val project: Project,
    val document: Document,
    val documentText: String,
    val caretOffset: Int,
    val filePath: String?,
    val languageId: String?,
)

internal enum class InlineContextSource {
    PSI,
    LSP,
    HEURISTIC,
}

internal data class InlineContextResolution(
    val context: InlineModelContext?,
    val source: InlineContextSource? = null,
)

internal interface InlineContextProvider {
    val source: InlineContextSource

    fun build(request: InlineContextRequest): InlineModelContext?
}

internal class InlineContextResolver(
    private val providers: List<InlineContextProvider>,
) {
    fun resolve(request: InlineContextRequest): InlineContextResolution {
        providers.forEach { provider ->
            val context = provider.build(request)?.takeIf { it.hasUsefulSignal() } ?: return@forEach
            return InlineContextResolution(context = context, source = provider.source)
        }
        return InlineContextResolution(context = null, source = null)
    }

    companion object {
        fun fromSettings(
            state: PluginSettings.State = PluginSettings.getInstance().state,
        ): InlineContextResolver = InlineContextResolver(defaultProviders(state))

        internal fun defaultProviders(state: PluginSettings.State): List<InlineContextProvider> = buildList {
            add(PsiInlineContextProvider)
            if (state.lspContextFallbackEnabled) {
                add(LspInlineContextProvider())
            }
            add(HeuristicInlineContextProvider)
        }
    }
}

internal object PsiInlineContextProvider : InlineContextProvider {
    override val source: InlineContextSource = InlineContextSource.PSI

    override fun build(request: InlineContextRequest): InlineModelContext? = PsiInlineContextBuilder.build(
        project = request.project,
        document = request.document,
        documentText = request.documentText,
        caretOffset = request.caretOffset,
    )
}

internal object HeuristicInlineContextProvider : InlineContextProvider {
    override val source: InlineContextSource = InlineContextSource.HEURISTIC

    override fun build(request: InlineContextRequest): InlineModelContext? = BaseInlineContextHeuristics.build(
        request = request,
        includeResolvedDefinitions = true,
    ).takeIf { it.hasUsefulSignal() }
}
