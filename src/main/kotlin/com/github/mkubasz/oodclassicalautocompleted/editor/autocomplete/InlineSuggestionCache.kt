package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineCompletionCandidate
import java.util.LinkedHashMap

internal class InlineSuggestionCache(
    private val maxEntries: Int,
    private val ttlMs: Long,
) {
    data class Key(
        val providerKey: String,
        val settingsHash: Int,
        val filePath: String?,
        val documentStamp: Long,
        val caretOffset: Int,
        val prefixHash: Int,
        val suffixHash: Int,
    )

    private data class Entry(
        val candidates: List<InlineCompletionCandidate>,
        val expiresAt: Long,
    )

    private val store = object : LinkedHashMap<Key, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(key: Key, now: Long = System.currentTimeMillis()): List<InlineCompletionCandidate>? {
        val entry = store[key] ?: return null
        if (entry.expiresAt <= now) {
            store.remove(key)
            return null
        }
        return entry.candidates
    }

    @Synchronized
    fun put(key: Key, candidates: List<InlineCompletionCandidate>, now: Long = System.currentTimeMillis()) {
        if (candidates.isEmpty()) return
        store[key] = Entry(
            candidates = candidates,
            expiresAt = now + ttlMs,
        )
    }

    @Synchronized
    fun clear() {
        store.clear()
    }
}
