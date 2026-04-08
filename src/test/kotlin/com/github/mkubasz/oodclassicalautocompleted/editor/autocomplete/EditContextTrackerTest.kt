package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EditContextTrackerTest : BasePlatformTestCase() {

    fun testTracksViewedFiles() {
        val tracker = project.service<EditContextTracker>()
        tracker.onFileViewed("track_a.kt", "fun a() = 1")
        tracker.onFileViewed("track_b.kt", "fun b() = 2")

        val snippets = tracker.recentSnippets
        assertTrue(snippets.any { it.filePath == "track_a.kt" })
        assertTrue(snippets.any { it.filePath == "track_b.kt" })
    }

    fun testDeduplicatesSameFile() {
        val tracker = project.service<EditContextTracker>()
        tracker.onFileViewed("dedup_x.kt", "version 1")
        tracker.onFileViewed("dedup_x.kt", "version 2")

        val matching = tracker.recentSnippets.filter { it.filePath == "dedup_x.kt" }
        assertEquals(1, matching.size)
        assertTrue(matching[0].content.contains("version 2"))
    }

    fun testLimitsSnippetsToMax() {
        val tracker = project.service<EditContextTracker>()
        for (i in 1..10) {
            tracker.onFileViewed("limit_$i.kt", "content $i")
        }
        assertTrue(tracker.recentSnippets.size <= 5)
        assertEquals("limit_10.kt", tracker.recentSnippets.last().filePath)
    }

    fun testTracksDocumentChanges() {
        val tracker = project.service<EditContextTracker>()
        val countBefore = tracker.recentDiffs.size
        tracker.onDocumentChanged("change_a.kt", "val x = 1", "val x = 2")

        assertEquals(countBefore + 1, tracker.recentDiffs.size)
        val lastDiff = tracker.recentDiffs.last()
        assertTrue(lastDiff.contains("--- change_a.kt"))
        assertTrue(lastDiff.contains("+++ change_a.kt"))
        assertTrue(lastDiff.contains("-val x = 1"))
        assertTrue(lastDiff.contains("+val x = 2"))
    }

    fun testIgnoresIdenticalChanges() {
        val tracker = project.service<EditContextTracker>()
        val countBefore = tracker.recentDiffs.size
        tracker.onDocumentChanged("noop.kt", "same", "same")
        assertEquals(countBefore, tracker.recentDiffs.size)
    }

    fun testLimitsDiffsToMax() {
        val tracker = project.service<EditContextTracker>()
        for (i in 1..20) {
            tracker.onDocumentChanged("flood.kt", "line $i", "line ${i + 100}")
        }
        assertTrue(tracker.recentDiffs.size <= 10)
    }

    // --- computeSimpleDiff ---

    fun testComputeSimpleDiffSingleLineChange() {
        val diff = EditContextTracker.computeSimpleDiff("test.kt", "hello", "world")
        assertTrue(diff.contains("--- test.kt"))
        assertTrue(diff.contains("+++ test.kt"))
        assertTrue(diff.contains("-hello"))
        assertTrue(diff.contains("+world"))
    }

    fun testComputeSimpleDiffMultiLineInsertion() {
        val old = "line1\nline2\nline3"
        val new = "line1\nline2\ninserted\nline3"
        val diff = EditContextTracker.computeSimpleDiff("f.kt", old, new)
        assertTrue(diff.contains("+inserted"))
    }

    fun testComputeSimpleDiffMultiLineDeletion() {
        val old = "line1\nline2\nline3"
        val new = "line1\nline3"
        val diff = EditContextTracker.computeSimpleDiff("f.kt", old, new)
        assertTrue(diff.contains("-line2"))
    }

    fun testComputeSimpleDiffNoDifference() {
        val diff = EditContextTracker.computeSimpleDiff("f.kt", "same\ncontent", "same\ncontent")
        assertEquals("", diff)
    }
}
