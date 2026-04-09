package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RejectionCacheTest : BasePlatformTestCase() {

    fun testAlternativeRequestedSuppressesSuggestionImmediately() {
        val cache = RejectionCache()
        val key = RejectionCache.SuggestionKey(
            documentHash = 1,
            offset = 10,
            suggestionHash = 99,
        )

        cache.recordRejection(key, AutocompleteService.DismissReason.ALTERNATIVE_REQUESTED)

        assertFalse(cache.shouldShow(key))
    }

    fun testRepeatedStandardRejectionsEventuallySuppressSuggestion() {
        val cache = RejectionCache()
        val key = RejectionCache.SuggestionKey(
            documentHash = 1,
            offset = 10,
            suggestionHash = 99,
        )
        val baseNow = System.currentTimeMillis()

        cache.recordRejection(
            key = key,
            reason = AutocompleteService.DismissReason.ESCAPE,
            shownAt = baseNow - 2_800L,
            now = baseNow - 1_000L,
        )
        assertTrue(cache.shouldShow(key))

        cache.recordRejection(
            key = key,
            reason = AutocompleteService.DismissReason.ESCAPE,
            shownAt = baseNow - 2_300L,
            now = baseNow - 500L,
        )
        assertFalse(cache.shouldShow(key))
    }

    fun testShortLivedSuggestionDoesNotEnterRejectionCache() {
        val cache = RejectionCache()
        val key = RejectionCache.SuggestionKey(
            documentHash = 1,
            offset = 10,
            suggestionHash = 99,
        )
        val now = System.currentTimeMillis()

        cache.recordRejection(
            key = key,
            reason = AutocompleteService.DismissReason.ESCAPE,
            shownAt = now - 500L,
            now = now,
        )

        assertTrue(cache.shouldShow(key))
    }
}
