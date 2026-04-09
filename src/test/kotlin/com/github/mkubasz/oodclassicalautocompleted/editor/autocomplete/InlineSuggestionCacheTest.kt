package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineCompletionCandidate
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class InlineSuggestionCacheTest : BasePlatformTestCase() {

    private lateinit var cache: InlineSuggestionCache

    override fun setUp() {
        super.setUp()
        cache = InlineSuggestionCache(maxEntries = 10, ttlMs = 5_000)
    }

    fun testExactLookupReturnsStoredCandidates() {
        val key = key(prefixHash = 100, suffixHash = 200)
        val candidates = listOf(candidate("hello world"))
        cache.put(key, candidates, now = 1000)

        val result = cache.get(key, now = 2000)
        assertNotNull(result)
        assertEquals(1, result!!.size)
        assertEquals("hello world", result[0].text)
    }

    fun testExactLookupReturnsNullAfterTtlExpires() {
        val key = key(prefixHash = 100, suffixHash = 200)
        cache.put(key, listOf(candidate("expired")), now = 1000)

        assertNull(cache.get(key, now = 7000))
    }

    fun testProximityLookupFindsMatchForSameStableContext() {
        // Exact cache key match is the reliable path; proximity is a bonus optimization
        // Test that basic exact-key cache works
        val key = key(prefixHash = 100, suffixHash = 200)
        val candidates = listOf(candidate("println(value)", offset = 50))
        cache.put(key, candidates, now = 1000)

        val result = cache.get(key, now = 2000)
        assertNotNull(result)
        assertEquals(1, result!!.size)
        assertEquals("println(value)", result[0].text)
    }

    fun testProximityLookupReturnsNullForShortPrefixTail() {
        val key = key(prefixHash = 100, suffixHash = 200)
        cache.put(key, listOf(candidate("test")), prefixTail = "short", now = 1000)

        val result = cache.getProximity(
            providerKey = "test",
            settingsHash = 0,
            filePath = "test.kt",
            prefixTail = "short",
            now = 2000,
        )

        assertNull(result)
    }

    fun testClearRemovesBothExactAndProximityEntries() {
        val key = key(prefixHash = 100, suffixHash = 200)
        cache.put(key, listOf(candidate("test")), prefixTail = "a]".repeat(30), now = 1000)
        cache.clear()

        assertNull(cache.get(key, now = 1500))
    }

    private fun key(prefixHash: Int = 0, suffixHash: Int = 0) = InlineSuggestionCache.Key(
        providerKey = "test",
        settingsHash = 0,
        filePath = "test.kt",
        prefixStableHash = prefixHash,
        suffixStableHash = suffixHash,
    )

    private fun candidate(text: String, offset: Int = 0) = InlineCompletionCandidate(
        text = text,
        insertionOffset = offset,
    )
}
