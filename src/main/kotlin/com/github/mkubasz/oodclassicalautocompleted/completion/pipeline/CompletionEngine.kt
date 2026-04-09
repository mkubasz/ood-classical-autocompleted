package com.github.mkubasz.oodclassicalautocompleted.completion.pipeline

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.CompletionArtifact
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ContextBlock
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ContextKind
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ContextScope
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ContributorTiming
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.EditorSnapshot
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.PackedContext
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.PipelineMode
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.PipelineRequest
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.PipelineTrace
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.PreprocessingFacts
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.StageTiming
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.LanguageSupport
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.LanguageSupportRegistry
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ProviderRequest
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineModelContext
import com.github.mkubasz.oodclassicalautocompleted.completion.providers.shared.NextEditInlineAdapter
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.AutocompleteTriggerHeuristics
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.CompletionContextSnapshot
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.EditContextTracker
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.GitDiffContextCollector
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.InlineContextResolution
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.WorkspaceRetrievalResult
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.WorkspaceRetrievalService
import com.github.mkubasz.oodclassicalautocompleted.settings.PluginSettings
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal class CompletionEngine(
    private val project: Project,
    private val languageSupportRegistry: LanguageSupportRegistry = LanguageSupportRegistry.default(),
    private val preprocessors: List<PreProcessor> = listOf(TriggerHeuristicsPreProcessor),
    private val contextContributors: List<ContextContributor> = listOf(
        CursorWindowContextContributor,
        SemanticContextContributor,
        RecentEditsContextContributor,
        GitDiffContextContributor,
        WorkspaceRetrievalContextContributor,
    ),
    private val postProcessors: List<PostProcessor> = emptyList(),
) {
    suspend fun buildBundle(
        request: InlineCompletionRequest,
        requestId: Long,
        mode: PipelineMode = PipelineMode.INLINE,
    ): BundleResult? {
        val snapshotTimed = timed {
            ApplicationManager.getApplication().runReadAction<EditorSnapshot?> {
                extractSnapshot(request, requestId)
            }
        }
        val snapshot = snapshotTimed.value ?: return null
        val languageSupport = languageSupportRegistry.forLanguage(snapshot.languageId, snapshot.filePath)

        return coroutineScope {
            val preprocessDeferred = async { runPreprocessors(snapshot, mode) }
            val contextDeferred = async { buildContext(snapshot, mode, languageSupport) }

            val preprocess = preprocessDeferred.await()
            val context = contextDeferred.await()

            val trace = PipelineTrace(
                stageTimings = listOf(
                    StageTiming("snapshot", snapshotTimed.elapsedMs),
                    StageTiming("preprocess", preprocess.elapsedMs),
                    StageTiming("context", context.elapsedMs),
                ),
                contributorTimings = context.contributorTimings,
            )

            if (!preprocess.value.shouldContinue) {
                BundleResult.Skip(
                    snapshot = snapshot,
                    facts = preprocess.value,
                    trace = trace,
                    inlineContextSource = context.value.contribution.inlineContextSource,
                )
            } else {
                BundleResult.Ready(
                    PipelineBundle(
                        snapshot = snapshot,
                        preprocessingFacts = preprocess.value,
                        contribution = context.value.contribution,
                        trace = trace,
                    )
                )
            }
        }
    }

    fun toCompletionContextSnapshot(bundle: PipelineBundle): CompletionContextSnapshot = CompletionContextSnapshot(
        filePath = bundle.snapshot.filePath,
        language = bundle.snapshot.languageId,
        documentText = bundle.snapshot.documentText,
        documentStamp = bundle.snapshot.documentVersion,
        caretOffset = bundle.snapshot.caretOffset,
        prefix = bundle.snapshot.prefix,
        suffix = bundle.snapshot.suffix,
        prefixWindow = bundle.snapshot.prefixWindow,
        suffixWindow = bundle.snapshot.suffixWindow,
        inlineContext = bundle.contribution.inlineContext,
        inlineContextSource = bundle.contribution.inlineContextSource
            ?.let { source -> InlineContextSourceCompat.from(source) },
        project = bundle.snapshot.project,
        isTerminal = bundle.snapshot.isTerminal,
    )

    fun buildRequest(bundle: PipelineBundle, mode: PipelineMode): PipelineRequest {
        val settings = PluginSettings.getInstance().state
        val packedContext = ContextRankPacker.pack(
            mode = mode,
            blocks = bundle.contribution.blocks,
            budgetChars = settings.contextBudgetChars,
        )
        return PipelineRequest(
            mode = mode,
            snapshot = bundle.snapshot,
            preprocessingFacts = bundle.preprocessingFacts,
            packedContext = packedContext,
            inlineContext = bundle.contribution.inlineContext,
            retrievedChunks = bundle.contribution.retrievedChunks,
            recentlyViewedSnippets = bundle.contribution.recentlyViewedSnippets.distinctBy { it.filePath to it.content }
                .takeLast(MAX_NEXT_EDIT_SNIPPETS),
            editDiffHistory = bundle.contribution.editDiffHistory.distinct().takeLast(MAX_NEXT_EDIT_DIFFS),
            gitDiff = bundle.contribution.gitDiff,
        )
    }

    suspend fun applyPostProcessors(
        request: PipelineRequest,
        artifact: CompletionArtifact,
    ): CompletionArtifact =
        postProcessors.fold(artifact) { current, processor -> processor.process(request, current) }

    private fun extractSnapshot(
        request: InlineCompletionRequest,
        requestId: Long,
    ): EditorSnapshot? {
        val editor = request.editor
        if (editor.project != project || editor.isViewer) return null
        if (editor.selectionModel.hasSelection()) return null

        val documentText = request.document.text
        val caretOffset = request.endOffset.coerceIn(0, documentText.length)
        if (caretOffset != editor.caretModel.offset) return null

        val isTerminal = com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.TerminalDetector.isTerminalEditor(editor)
        val languageId = if (isTerminal) "shell" else request.file.language.id
        val filePath = if (isTerminal) null else request.file.virtualFile?.path

        return EditorSnapshot(
            project = project,
            document = request.document,
            filePath = filePath,
            languageId = languageId,
            documentText = documentText,
            documentVersion = request.document.modificationStamp,
            caretOffset = caretOffset,
            prefix = documentText.substring(0, caretOffset),
            suffix = documentText.substring(caretOffset),
            prefixWindow = documentText.substring(0, caretOffset).takeLast(CONTEXT_WINDOW_CHARS),
            suffixWindow = documentText.substring(caretOffset).take(CONTEXT_WINDOW_CHARS),
            requestId = requestId,
            isTerminal = isTerminal,
        )
    }

    private suspend fun runPreprocessors(
        snapshot: EditorSnapshot,
        mode: PipelineMode,
    ): TimedValue<PreprocessingFacts> = timedSuspend {
        preprocessors.fold(PreprocessingFacts()) { facts, processor ->
            facts.merge(processor.process(snapshot, mode))
        }
    }

    private suspend fun buildContext(
        snapshot: EditorSnapshot,
        mode: PipelineMode,
        languageSupport: LanguageSupport,
    ): TimedContext = timedContextSuspend {
        var merged = ContextContribution()
        val contributorTimings = mutableListOf<ContributorTiming>()
        contextContributors.forEach { contributor ->
            val timed = timedSuspend {
                contributor.contribute(snapshot, mode, languageSupport, merged)
            }
            contributorTimings += ContributorTiming(
                name = contributor.name,
                elapsedMs = timed.elapsedMs,
                emittedBlocks = timed.value.blocks.size,
            )
            merged = merged.merge(timed.value)
        }
        TimedContextValue(
            contribution = merged,
            contributorTimings = contributorTimings,
        )
    }

    internal sealed interface BundleResult {
        data class Ready(
            val bundle: PipelineBundle,
        ) : BundleResult

        data class Skip(
            val snapshot: EditorSnapshot,
            val facts: PreprocessingFacts,
            val trace: PipelineTrace,
            val inlineContextSource: String?,
        ) : BundleResult
    }

    internal data class PipelineBundle(
        val snapshot: EditorSnapshot,
        val preprocessingFacts: PreprocessingFacts,
        val contribution: ContextContribution,
        val trace: PipelineTrace,
    )

    internal data class TimedValue<T>(
        val value: T,
        val elapsedMs: Long,
    )

    internal data class TimedContextValue(
        val contribution: ContextContribution,
        val contributorTimings: List<ContributorTiming>,
    )

    internal data class TimedContext(
        val value: TimedContextValue,
        val elapsedMs: Long,
        val contributorTimings: List<ContributorTiming>,
    )

    companion object {
        private const val CONTEXT_WINDOW_CHARS = 1_500
        private const val MAX_NEXT_EDIT_SNIPPETS = 5
        private const val MAX_NEXT_EDIT_DIFFS = 5
    }
}

private object TriggerHeuristicsPreProcessor : PreProcessor {
    override val name: String = "trigger_heuristics"

    override suspend fun process(snapshot: EditorSnapshot, mode: PipelineMode): PreprocessingFacts {
        if (mode != PipelineMode.INLINE) return PreprocessingFacts()
        val triggerDecision = AutocompleteTriggerHeuristics.evaluate(
            documentText = snapshot.documentText,
            offset = snapshot.caretOffset,
            filePath = snapshot.filePath,
            maxDocumentChars = MAX_DOCUMENT_CHARS,
            lookbackForNonWhitespace = LOOKBACK_FOR_NON_WHITESPACE,
        )
        val reason = triggerDecision.reason?.name?.lowercase()
        return if (triggerDecision.shouldRequest) {
            PreprocessingFacts(attributes = reason?.let { mapOf("trigger_reason" to it) }.orEmpty())
        } else {
            PreprocessingFacts(
                shouldContinue = false,
                skipReason = reason,
                attributes = reason?.let { mapOf("trigger_reason" to it) }.orEmpty(),
            )
        }
    }

    private const val MAX_DOCUMENT_CHARS = 250_000
    private const val LOOKBACK_FOR_NON_WHITESPACE = 80
}

private object CursorWindowContextContributor : ContextContributor {
    override val name: String = "cursor_window"

    override suspend fun contribute(
        snapshot: EditorSnapshot,
        mode: PipelineMode,
        languageSupport: LanguageSupport,
        existing: ContextContribution,
    ): ContextContribution {
        val settings = PluginSettings.getInstance().state
        val baseRequest = ProviderRequest(
            prefix = snapshot.prefix,
            suffix = snapshot.suffix,
            filePath = snapshot.filePath,
            language = snapshot.languageId,
            cursorOffset = snapshot.caretOffset,
        )
        val editableRegion = NextEditInlineAdapter.extractRegion(
            request = baseRequest,
            linesAboveCursor = settings.inceptionLabsNextEditLinesAboveCursor,
            linesBelowCursor = settings.inceptionLabsNextEditLinesBelowCursor,
        )
        val blocks = buildList {
            add(
                ContextBlock(
                    kind = ContextKind.CURSOR_WINDOW,
                    scope = ContextScope.LOCAL,
                    source = "editor",
                    priority = 100,
                    freshness = 100,
                    language = snapshot.languageId,
                    title = "cursor_window",
                    content = buildString {
                        appendLine("<prefix>")
                        appendLine(snapshot.prefixWindow)
                        appendLine("</prefix>")
                        appendLine("<suffix>")
                        appendLine(snapshot.suffixWindow)
                        appendLine("</suffix>")
                    }.trim(),
                )
            )
            add(
                ContextBlock(
                    kind = ContextKind.EDITABLE_REGION,
                    scope = ContextScope.SAME_FILE,
                    source = "editor",
                    priority = 95,
                    freshness = 100,
                    language = snapshot.languageId,
                    title = "editable_region",
                    content = NextEditInlineAdapter.renderWithCursor(editableRegion),
                )
            )
        }
        return ContextContribution(blocks = blocks)
    }
}

private object SemanticContextContributor : ContextContributor {
    override val name: String = "semantic_context"

    override suspend fun contribute(
        snapshot: EditorSnapshot,
        mode: PipelineMode,
        languageSupport: LanguageSupport,
        existing: ContextContribution,
    ): ContextContribution {
        if (snapshot.isTerminal) return ContextContribution()
        val resolution = languageSupport.resolveInlineContext(snapshot)
        val context = resolution.context ?: return ContextContribution()
        val blocks = mutableListOf<ContextBlock>()

        activeSymbolContent(context)?.let { content ->
            blocks += ContextBlock(
                kind = ContextKind.ACTIVE_SYMBOL,
                scope = ContextScope.SAME_FILE,
                source = resolution.source?.name?.lowercase() ?: languageSupport.id,
                priority = 90,
                freshness = 90,
                language = snapshot.languageId,
                title = "active_symbol",
                content = content,
            )
        }
        visibleSymbolsContent(context)?.let { content ->
            blocks += ContextBlock(
                kind = ContextKind.VISIBLE_SYMBOLS,
                scope = ContextScope.LOCAL,
                source = resolution.source?.name?.lowercase() ?: languageSupport.id,
                priority = 80,
                freshness = 90,
                language = snapshot.languageId,
                title = "visible_symbols",
                content = content,
            )
        }
        dependencySymbolsContent(context)?.let { content ->
            blocks += ContextBlock(
                kind = ContextKind.DEPENDENCY_SYMBOLS,
                scope = ContextScope.LIBRARY,
                source = resolution.source?.name?.lowercase() ?: languageSupport.id,
                priority = 70,
                freshness = 70,
                language = snapshot.languageId,
                title = "dependency_symbols",
                content = content,
            )
        }

        return ContextContribution(
            blocks = blocks,
            inlineContext = context,
            inlineContextSource = resolution.source?.name?.lowercase(),
        )
    }

    private fun activeSymbolContent(context: InlineModelContext): String? {
        val lines = buildList {
            context.currentDefinitionName?.takeIf(String::isNotBlank)?.let { add("current_definition: $it") }
            if (context.enclosingNames.isNotEmpty()) add("enclosing_names: ${context.enclosingNames.joinToString(", ")}")
            if (context.enclosingKinds.isNotEmpty()) add("enclosing_kinds: ${context.enclosingKinds.joinToString(", ")}")
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun visibleSymbolsContent(context: InlineModelContext): String? {
        val lines = buildList {
            if (context.currentParameterNames.isNotEmpty()) add("parameters: ${context.currentParameterNames.joinToString(", ")}")
            if (context.receiverMemberNames.isNotEmpty()) add("receiver_members: ${context.receiverMemberNames.joinToString(", ")}")
            if (context.matchingTypeNames.isNotEmpty()) add("matching_types: ${context.matchingTypeNames.joinToString(", ")}")
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun dependencySymbolsContent(context: InlineModelContext): String? {
        val lines = buildList {
            context.resolvedReferenceName?.takeIf(String::isNotBlank)?.let { add("resolved_reference: $it") }
            context.resolvedFilePath?.takeIf(String::isNotBlank)?.let { add("resolved_file: $it") }
            context.resolvedSnippet?.takeIf(String::isNotBlank)?.let { add(it) }
            context.resolvedDefinitions.forEach { definition ->
                add("definition: ${definition.filePath}")
                add(definition.signature)
            }
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}

private object RecentEditsContextContributor : ContextContributor {
    override val name: String = "recent_edits"

    override suspend fun contribute(
        snapshot: EditorSnapshot,
        mode: PipelineMode,
        languageSupport: LanguageSupport,
        existing: ContextContribution,
    ): ContextContribution {
        if (snapshot.isTerminal) return ContextContribution()
        val tracker = snapshot.project.service<EditContextTracker>()
        val recentSnippets = tracker.recentSnippets.takeLast(MAX_NEXT_EDIT_SNIPPETS)
        val recentDiffs = tracker.recentDiffs.takeLast(MAX_NEXT_EDIT_DIFFS)
        val blocks = buildList {
            if (recentSnippets.isNotEmpty()) {
                add(
                    ContextBlock(
                        kind = ContextKind.RECENT_EDITS,
                        scope = ContextScope.RECENT,
                        source = "edit_tracker",
                        priority = 65,
                        freshness = 95,
                        language = snapshot.languageId,
                        title = "recent_files",
                        content = recentSnippets.joinToString("\n\n") { snippet ->
                            buildString {
                                appendLine("file: ${snippet.filePath}")
                                append(snippet.content)
                            }.trim()
                        },
                    )
                )
            }
        }
        return ContextContribution(
            blocks = blocks,
            recentlyViewedSnippets = recentSnippets,
            editDiffHistory = recentDiffs,
        )
    }

    private const val MAX_NEXT_EDIT_SNIPPETS = 5
    private const val MAX_NEXT_EDIT_DIFFS = 5
}

private object GitDiffContextContributor : ContextContributor {
    override val name: String = "git_diff"

    override suspend fun contribute(
        snapshot: EditorSnapshot,
        mode: PipelineMode,
        languageSupport: LanguageSupport,
        existing: ContextContribution,
    ): ContextContribution {
        val settings = PluginSettings.getInstance().state
        if (snapshot.isTerminal || !settings.gitDiffContextEnabled) return ContextContribution()
        val gitDiff = GitDiffContextCollector.collect(snapshot.project, MAX_GIT_DIFF_CHARS)
            ?.takeIf(String::isNotBlank)
            ?: return ContextContribution()
        return ContextContribution(
            blocks = listOf(
                ContextBlock(
                    kind = ContextKind.GIT_DIFF,
                    scope = ContextScope.RECENT,
                    source = "git",
                    priority = 60,
                    freshness = 90,
                    language = snapshot.languageId,
                    title = "git_diff",
                    content = gitDiff,
                )
            ),
            gitDiff = gitDiff,
        )
    }

    private const val MAX_GIT_DIFF_CHARS = 4_000
}

private object WorkspaceRetrievalContextContributor : ContextContributor {
    override val name: String = "workspace_retrieval"

    override suspend fun contribute(
        snapshot: EditorSnapshot,
        mode: PipelineMode,
        languageSupport: LanguageSupport,
        existing: ContextContribution,
    ): ContextContribution {
        val settings = PluginSettings.getInstance().state
        if (snapshot.isTerminal || !settings.localRetrievalEnabled) return ContextContribution()
        val retrieval = snapshot.project.service<WorkspaceRetrievalService>().retrieve(
            snapshot = CompletionContextSnapshot(
                filePath = snapshot.filePath,
                language = snapshot.languageId,
                documentText = snapshot.documentText,
                documentStamp = snapshot.documentVersion,
                caretOffset = snapshot.caretOffset,
                prefix = snapshot.prefix,
                suffix = snapshot.suffix,
                prefixWindow = snapshot.prefixWindow,
                suffixWindow = snapshot.suffixWindow,
                inlineContext = existing.inlineContext,
                inlineContextSource = existing.inlineContextSource?.let { InlineContextSourceCompat.from(it) },
                project = snapshot.project,
                isTerminal = snapshot.isTerminal,
            ),
            maxChunks = settings.retrievalMaxChunks,
            retrievalProfile = languageSupport.retrievalProfile(),
        )
        return ContextContribution(
            blocks = retrieval.chunks.map { chunk ->
                ContextBlock(
                    kind = ContextKind.WORKSPACE,
                    scope = when (chunk.selectionBucket) {
                        "local_symbol" -> ContextScope.SAME_FILE
                        "same_directory" -> ContextScope.SAME_DIRECTORY
                        else -> ContextScope.PROJECT
                    },
                    source = chunk.source,
                    priority = when (chunk.selectionBucket) {
                        "local_symbol" -> 50
                        "same_directory" -> 40
                        else -> 30
                    },
                    freshness = 50,
                    language = chunk.language,
                    title = chunk.filePath,
                    content = chunk.content,
                )
            },
            retrievedChunks = retrieval.chunks,
        )
    }
}

private object ContextRankPacker : BudgetEstimator {
    fun pack(
        mode: PipelineMode,
        blocks: List<ContextBlock>,
        budgetChars: Int,
    ): PackedContext {
        if (blocks.isEmpty() || budgetChars <= 0) {
            return PackedContext(
                blocks = emptyList(),
                summary = "",
                totalChars = 0,
                budgetChars = budgetChars,
            )
        }

        val quotas = when (mode) {
            PipelineMode.INLINE -> mapOf(
                ContextKind.CURSOR_WINDOW to (budgetChars * 0.50).toInt(),
                ContextKind.ACTIVE_SYMBOL to (budgetChars * 0.10).toInt(),
                ContextKind.VISIBLE_SYMBOLS to (budgetChars * 0.08).toInt(),
                ContextKind.DEPENDENCY_SYMBOLS to (budgetChars * 0.07).toInt(),
                ContextKind.RECENT_EDITS to (budgetChars * 0.10).toInt(),
                ContextKind.GIT_DIFF to (budgetChars * 0.05).toInt(),
                ContextKind.WORKSPACE to (budgetChars * 0.10).toInt(),
            )
            PipelineMode.NEXT_EDIT -> mapOf(
                ContextKind.EDITABLE_REGION to (budgetChars * 0.35).toInt(),
                ContextKind.RECENT_EDITS to (budgetChars * 0.15).toInt(),
                ContextKind.GIT_DIFF to (budgetChars * 0.10).toInt(),
                ContextKind.ACTIVE_SYMBOL to (budgetChars * 0.10).toInt(),
                ContextKind.VISIBLE_SYMBOLS to (budgetChars * 0.05).toInt(),
                ContextKind.DEPENDENCY_SYMBOLS to (budgetChars * 0.05).toInt(),
                ContextKind.WORKSPACE to (budgetChars * 0.20).toInt(),
            )
        }

        val distinctBlocks = blocks
            .filter { it.content.isNotBlank() }
            .distinctBy { listOf(it.kind, it.scope, it.source, it.title, it.content.hashCode()) }
        val selected = mutableListOf<ContextBlock>()
        val remaining = distinctBlocks.toMutableList()

        quotas.forEach { (kind, quota) ->
            var used = 0
            remaining
                .filter { it.kind == kind }
                .sortedByDescending(::score)
                .forEach { block ->
                    val cost = estimate(block)
                    if (used + cost > quota && used > 0) return@forEach
                    selected += block
                    used += cost
                    remaining.remove(block)
                }
        }

        var used = selected.sumOf(::estimate)
        remaining.sortedByDescending(::score).forEach { block ->
            val cost = estimate(block)
            if (used + cost > budgetChars) return@forEach
            selected += block
            used += cost
        }

        val summary = buildSummary(selected, budgetChars)
        return PackedContext(
            blocks = selected,
            summary = summary,
            totalChars = summary.length,
            budgetChars = budgetChars,
        )
    }

    override fun estimate(block: ContextBlock): Int = block.costChars

    private fun score(block: ContextBlock): Int =
        (block.priority * 100) + block.freshness - (block.costChars / 16)

    private fun buildSummary(
        blocks: List<ContextBlock>,
        budgetChars: Int,
    ): String {
        val builder = StringBuilder()
        blocks.forEach { block ->
            val rendered = buildString {
                appendLine("[${block.kind.name.lowercase()} scope=${block.scope.name.lowercase()} source=${block.source}]")
                block.title?.takeIf(String::isNotBlank)?.let { appendLine(it) }
                appendLine(block.content.trim())
            }.trim()
            if (rendered.isBlank()) return@forEach
            if (builder.isNotEmpty()) {
                if (builder.length + 2 > budgetChars) return@forEach
                builder.append("\n\n")
            }
            val remaining = (budgetChars - builder.length).coerceAtLeast(0)
            if (remaining <= 0) return@forEach
            builder.append(rendered.take(remaining))
        }
        return builder.toString()
    }
}

private object InlineContextSourceCompat {
    fun from(value: String): com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.InlineContextSource? =
        runCatching {
            enumValueOf<com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.InlineContextSource>(value.uppercase())
        }.getOrNull()
}

private inline fun <T> timed(block: () -> T): CompletionEngine.TimedValue<T> {
    val started = System.nanoTime()
    return CompletionEngine.TimedValue(
        value = block(),
        elapsedMs = (System.nanoTime() - started) / 1_000_000,
    )
}

private suspend inline fun <T> timedSuspend(crossinline block: suspend () -> T): CompletionEngine.TimedValue<T> {
    val started = System.nanoTime()
    return CompletionEngine.TimedValue(
        value = block(),
        elapsedMs = (System.nanoTime() - started) / 1_000_000,
    )
}

private suspend inline fun timedContextSuspend(
    crossinline block: suspend () -> CompletionEngine.TimedContextValue,
): CompletionEngine.TimedContext {
    val started = System.nanoTime()
    val value = block()
    return CompletionEngine.TimedContext(
        value = value,
        elapsedMs = (System.nanoTime() - started) / 1_000_000,
        contributorTimings = value.contributorTimings,
    )
}
