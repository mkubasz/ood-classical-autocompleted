package com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ContextBudgetPackerTest : BasePlatformTestCase() {

    fun testPackRespectsMinimumPrefixAndSuffix() {
        val packed = ContextBudgetPacker.pack(
            semanticContext = "x".repeat(2000),
            fullPrefix = "a".repeat(5000),
            fullSuffix = "b".repeat(5000),
            budget = ContextBudgetPacker.Budget(totalChars = 3000, minPrefixChars = 800, minSuffixChars = 400),
        )

        assertTrue("Prefix should be at least min: ${packed.localPrefix.length}", packed.localPrefix.length >= 800)
        assertTrue("Suffix should be at least min: ${packed.localSuffix.length}", packed.localSuffix.length >= 400)
    }

    fun testPackWithNoSemanticContextGivesFullBudgetToCode() {
        val packed = ContextBudgetPacker.pack(
            semanticContext = "",
            fullPrefix = "a".repeat(5000),
            fullSuffix = "b".repeat(5000),
            budget = ContextBudgetPacker.Budget(totalChars = 4000),
        )

        assertEquals("", packed.semanticPrefix)
        assertTrue("Total should fit budget", packed.localPrefix.length + packed.localSuffix.length <= 4000)
    }

    fun testPackWithSmallInputsReturnsFullContent() {
        val packed = ContextBudgetPacker.pack(
            semanticContext = "context",
            fullPrefix = "short prefix",
            fullSuffix = "short suffix",
            budget = ContextBudgetPacker.Budget(totalChars = 4000),
        )

        assertEquals("context", packed.semanticPrefix)
        assertEquals("short prefix", packed.localPrefix)
        assertEquals("short suffix", packed.localSuffix)
    }

    fun testSemanticContextIsCappedAtMaxSemanticChars() {
        val packed = ContextBudgetPacker.pack(
            semanticContext = "x".repeat(5000),
            fullPrefix = "a".repeat(100),
            fullSuffix = "b".repeat(100),
            budget = ContextBudgetPacker.Budget(totalChars = 4000, maxSemanticChars = 500),
        )

        assertEquals(500, packed.semanticPrefix.length)
    }
}
