package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineCompletionCandidate
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.NextEditCompletionCandidate
import com.github.mkubasz.oodclassicalautocompleted.settings.PluginSettings
import com.intellij.openapi.editor.Editor
import kotlinx.coroutines.Job
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

internal class SuggestionSessionStore {
    val rejectionCache: RejectionCache = RejectionCache()
    val inlineStates = ConcurrentHashMap<Editor, InlineSessionState>()
    val nextEditStates = ConcurrentHashMap<Editor, NextEditSessionState>()
    val supportedEditors = Collections.newSetFromMap(IdentityHashMap<Editor, Boolean>())
    val caretSuppression = Collections.newSetFromMap(IdentityHashMap<Editor, Boolean>())
    val pendingSuggestions = ConcurrentHashMap<Editor, InlineCompletionCandidate>()
    val latestRequestIds = ConcurrentHashMap<Editor, Long>()
    val nextEditJobs = ConcurrentHashMap<Editor, Job>()
    val acceptedSuggestionTrackers = ConcurrentHashMap<Editor, AcceptedSuggestionTracker>()

    private var inlineCache = createInlineCache(PluginSettings.getInstance().state)
    private var cacheStateHash: Int? = null

    fun ensureInlineCache(settings: PluginSettings.State) {
        val stateHash = settings.hashCode()
        if (cacheStateHash == stateHash) return
        inlineCache = createInlineCache(settings)
        cacheStateHash = stateHash
    }

    fun inlineCache(): InlineSuggestionCache = inlineCache

    fun clear() {
        pendingSuggestions.clear()
        latestRequestIds.clear()
        nextEditJobs.values.forEach { it.cancel() }
        nextEditJobs.clear()
        acceptedSuggestionTrackers.values.forEach { it.clear() }
        acceptedSuggestionTrackers.clear()
        inlineStates.clear()
        nextEditStates.values.forEach { it.preview?.dispose() }
        nextEditStates.clear()
        inlineCache.clear()
    }

    private fun createInlineCache(settings: PluginSettings.State): InlineSuggestionCache = InlineSuggestionCache(
        maxEntries = settings.suggestionCacheMaxEntries,
        ttlMs = settings.suggestionCacheTtlMs,
    )
}

internal data class InlineSessionState(
    val requestId: Long,
    val candidates: List<InlineCompletionCandidate>,
    val rejectionKey: RejectionCache.SuggestionKey?,
    val visible: Boolean = false,
    val shownAt: Long = 0L,
    val currentIndex: Int = 0,
)

internal data class NextEditSessionState(
    val requestId: Long,
    val candidate: NextEditCompletionCandidate,
    val preview: NextEditPreview? = null,
)
