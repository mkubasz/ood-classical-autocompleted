package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineCompletionCandidate
import java.util.LinkedHashMap

internal class InlineSuggestionCache(
    private val maxEntries: Int,
    private val ttlMs: Long,
) {
    data class Key(
        val providerKey: String,
        val settingsHash: Int,
        val filePath: String?,
        val prefixStableHash: Int,
        val suffixStableHash: Int,
    )

    data class ProximityKey(
        val providerKey: String,
        val settingsHash: Int,
        val filePath: String?,
        val prefixTail: String,
    )

    private data class Entry(
        val candidates: List<InlineCompletionCandidate>,
        val expiresAt: Long,
        val prefixTail: String = "",
    )

    private val store = object : LinkedHashMap<Key, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?): Boolean =
            size > maxEntries
    }

    private val proximityIndex = object : LinkedHashMap<ProximityKey, Key>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ProximityKey, Key>?): Boolean =
            size > maxEntries * 2
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
    fun getProximity(
        providerKey: String,
        settingsHash: Int,
        filePath: String?,
        prefixTail: String,
        now: Long = System.currentTimeMillis(),
    ): List<InlineCompletionCandidate>? {
        if (prefixTail.length < PROXIMITY_MIN_PREFIX) return null

        val stablePrefix = prefixTail.dropLast(PROXIMITY_VOLATILE_CHARS.coerceAtMost(prefixTail.length))
        if (stablePrefix.length < PROXIMITY_MIN_PREFIX) return null
        val searchTail = stablePrefix.takeLast(PROXIMITY_MATCH_CHARS)
        val proxKey = ProximityKey(providerKey, settingsHash, filePath, searchTail)
        val cacheKey = proximityIndex[proxKey] ?: return null
        val entry = store[cacheKey] ?: run {
            proximityIndex.remove(proxKey)
            return null
        }
        if (entry.expiresAt <= now) {
            store.remove(cacheKey)
            proximityIndex.remove(proxKey)
            return null
        }

        val candidates = entry.candidates.mapNotNull { candidate ->
            val text = candidate.text
            val typed = prefixTail.removePrefix(entry.prefixTail.take(prefixTail.length))
            if (typed.isEmpty()) return@mapNotNull candidate
            if (text.startsWith(typed)) {
                candidate.copy(
                    text = text.removePrefix(typed),
                    insertionOffset = candidate.insertionOffset + typed.length,
                )
            } else null
        }
        return candidates.takeIf { it.isNotEmpty() }
    }

    @Synchronized
    fun put(
        key: Key,
        candidates: List<InlineCompletionCandidate>,
        prefixTail: String = "",
        now: Long = System.currentTimeMillis(),
    ) {
        if (candidates.isEmpty()) return
        store[key] = Entry(
            candidates = candidates,
            expiresAt = now + ttlMs,
            prefixTail = prefixTail,
        )
        if (prefixTail.length >= PROXIMITY_MIN_PREFIX) {
            val stablePrefix = prefixTail.dropLast(PROXIMITY_VOLATILE_CHARS.coerceAtMost(prefixTail.length))
            if (stablePrefix.length >= PROXIMITY_MIN_PREFIX) {
                val proxKey = ProximityKey(key.providerKey, key.settingsHash, key.filePath, stablePrefix.takeLast(PROXIMITY_MATCH_CHARS))
                proximityIndex[proxKey] = key
            }
        }
    }

    @Synchronized
    fun clear() {
        store.clear()
        proximityIndex.clear()
    }

    companion object {
        private const val PROXIMITY_MIN_PREFIX = 10
        private const val PROXIMITY_MATCH_CHARS = 30
        private const val PROXIMITY_VOLATILE_CHARS = 10
    }
}
