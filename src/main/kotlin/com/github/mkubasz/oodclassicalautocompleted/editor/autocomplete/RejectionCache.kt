package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import java.util.concurrent.ConcurrentLinkedDeque

class RejectionCache(
    private val maxSize: Int = 20,
    private val rejectionWindowMs: Long = 30_000,
) {
    data class SuggestionKey(
        val documentHash: Int,
        val offset: Int,
        val suggestionHash: Int,
    )

    private data class RejectionEntry(
        val key: SuggestionKey,
        val timestamp: Long,
        val reason: AutocompleteService.DismissReason,
    )

    private val rejections = ConcurrentLinkedDeque<RejectionEntry>()

    fun recordRejection(key: SuggestionKey, reason: AutocompleteService.DismissReason) {
        val minShowTime = when (reason) {
            AutocompleteService.DismissReason.CARET_MOVED -> 750L
            AutocompleteService.DismissReason.FOCUS_LOST -> 500L
            AutocompleteService.DismissReason.ESCAPE -> 1500L
            AutocompleteService.DismissReason.TYPING -> 500L
            AutocompleteService.DismissReason.SELECTION_CHANGED -> 500L
            AutocompleteService.DismissReason.ALTERNATIVE_REQUESTED -> 0L
        }

        // Only cache if the suggestion was shown long enough
        rejections.addFirst(RejectionEntry(key, System.currentTimeMillis(), reason))

        // Evict old entries
        while (rejections.size > maxSize) {
            rejections.removeLast()
        }
    }

    fun shouldShow(key: SuggestionKey): Boolean {
        val now = System.currentTimeMillis()
        val recentRejections = rejections.filter {
            it.key == key && (now - it.timestamp) < rejectionWindowMs
        }
        if (recentRejections.any { it.reason == AutocompleteService.DismissReason.ALTERNATIVE_REQUESTED }) {
            return false
        }
        return recentRejections.size < 2
    }

    fun clear() {
        rejections.clear()
    }
}
