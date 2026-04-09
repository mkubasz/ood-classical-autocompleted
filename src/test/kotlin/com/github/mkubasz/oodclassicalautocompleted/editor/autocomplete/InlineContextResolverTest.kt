package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineLexicalContext
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineModelContext
import com.github.mkubasz.oodclassicalautocompleted.settings.PluginSettings
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class InlineContextResolverTest {

    @Test
    fun resolvesFirstProviderThatReturnsUsefulSignal() {
        val resolver = InlineContextResolver(
            providers = listOf(
                FakeProvider(InlineContextSource.PSI, null),
                FakeProvider(InlineContextSource.LSP, InlineModelContext(currentDefinitionName = "build_workflow")),
                FakeProvider(
                    InlineContextSource.HEURISTIC,
                    InlineModelContext(isDecoratorLikeContext = true),
                ),
            )
        )

        val resolution = resolver.resolve(fakeRequest())

        assertEquals(InlineContextSource.LSP, resolution.source)
        assertEquals("build_workflow", resolution.context?.currentDefinitionName)
    }

    @Test
    fun ignoresProvidersThatReturnOnlyUnknownOrGenericCodeSignal() {
        val resolver = InlineContextResolver(
            providers = listOf(
                FakeProvider(InlineContextSource.PSI, InlineModelContext(lexicalContext = InlineLexicalContext.CODE)),
                FakeProvider(InlineContextSource.HEURISTIC, null),
            )
        )

        val resolution = resolver.resolve(fakeRequest())

        assertNull(resolution.source)
        assertNull(resolution.context)
    }

    @Test
    fun defaultProvidersInsertLspOnlyWhenEnabled() {
        val withLsp = InlineContextResolver.defaultProviders(
            PluginSettings.State(lspContextFallbackEnabled = true)
        )
        val withoutLsp = InlineContextResolver.defaultProviders(
            PluginSettings.State(lspContextFallbackEnabled = false)
        )

        assertTrue(withLsp.any { it.source == InlineContextSource.LSP })
        assertTrue(withoutLsp.none { it.source == InlineContextSource.LSP })
        assertEquals(
            listOf(InlineContextSource.PSI, InlineContextSource.LSP, InlineContextSource.HEURISTIC),
            withLsp.map { it.source },
        )
        assertEquals(
            listOf(InlineContextSource.PSI, InlineContextSource.HEURISTIC),
            withoutLsp.map { it.source },
        )
    }

    private fun fakeRequest(): InlineContextRequest = InlineContextRequest(
        project = unusedProject(),
        document = unusedDocument(),
        documentText = "",
        caretOffset = 0,
        filePath = null,
        languageId = null,
    )

    private fun unusedProject(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, _, _ -> throw UnsupportedOperationException("Not used") } as Project

    private fun unusedDocument(): Document = Proxy.newProxyInstance(
        Document::class.java.classLoader,
        arrayOf(Document::class.java),
    ) { _, _, _ -> throw UnsupportedOperationException("Not used") } as Document

    private data class FakeProvider(
        override val source: InlineContextSource,
        val context: InlineModelContext?,
    ) : InlineContextProvider {
        override fun build(request: InlineContextRequest): InlineModelContext? = context
    }
}
