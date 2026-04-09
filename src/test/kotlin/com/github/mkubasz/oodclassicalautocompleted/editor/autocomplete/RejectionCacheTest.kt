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

        cache.recordRejection(key, AutocompleteService.DismissReason.ESCAPE)
        assertTrue(cache.shouldShow(key))

        cache.recordRejection(key, AutocompleteService.DismissReason.ESCAPE)
        assertFalse(cache.shouldShow(key))
    }
}
