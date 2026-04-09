package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.junit.Test

class GitDiffContextCollectorTest {

    @Test
    fun combineDiffsReturnsNullWhenBothSourcesEmpty() {
        assertNull(GitDiffContextCollector.combineDiffs("", "", maxChars = 200))
    }

    @Test
    fun combineDiffsMergesWorkingTreeAndStagedSections() {
        val combined = GitDiffContextCollector.combineDiffs(
            workingTree = "diff --git a/A.kt b/A.kt",
            staged = "diff --git a/B.kt b/B.kt",
            maxChars = 500,
        )

        assertEquals(
            "## Working tree\ndiff --git a/A.kt b/A.kt\n\n## Staged\ndiff --git a/B.kt b/B.kt",
            combined,
        )
    }

    @Test
    fun combineDiffsTruncatesOversizedSnapshots() {
        val longDiff = "x".repeat(120)

        val combined = GitDiffContextCollector.combineDiffs(
            workingTree = longDiff,
            staged = "",
            maxChars = 60,
        )

        assertTrue(combined!!.startsWith("## Working tree\n"))
        assertTrue(combined.endsWith("\n...[truncated]"))
    }
}
