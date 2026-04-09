package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.CodeSnippet
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentLinkedDeque

@Service(Service.Level.PROJECT)
class EditContextTracker(@Suppress("unused") private val project: Project) : Disposable {

    private val _recentSnippets = ConcurrentLinkedDeque<CodeSnippet>()
    private val _recentDiffs = ConcurrentLinkedDeque<String>()

    val recentSnippets: List<CodeSnippet>
        get() = _recentSnippets.toList().takeLast(MAX_SNIPPETS)

    val recentDiffs: List<String>
        get() = _recentDiffs.toList().takeLast(MAX_DIFFS)

    fun onFileViewed(filePath: String, content: String, caretOffset: Int? = null) {
        // Remove existing snippet for same file to avoid duplicates
        _recentSnippets.removeIf { it.filePath == filePath }

        val snippet = extractSnippetAroundCaret(content, caretOffset)
        _recentSnippets.addLast(CodeSnippet(filePath, snippet.take(MAX_SNIPPET_CHARS)))

        // Evict oldest if over capacity
        while (_recentSnippets.size > MAX_SNIPPETS) {
            _recentSnippets.pollFirst()
        }
    }

    fun onDocumentChanged(filePath: String, oldText: String, newText: String) {
        if (oldText == newText) return

        val diff = computeSimpleDiff(filePath, oldText, newText)
        if (diff.isNotBlank()) {
            _recentDiffs.addLast(diff)
            while (_recentDiffs.size > MAX_DIFFS) {
                _recentDiffs.pollFirst()
            }
        }
    }

    override fun dispose() {
        _recentSnippets.clear()
        _recentDiffs.clear()
    }

    companion object {
        private const val MAX_SNIPPETS = 5
        private const val MAX_DIFFS = 10
        private const val SNIPPET_LINES = 20
        private const val MAX_SNIPPET_CHARS = 2000
        private const val MAX_DIFF_CONTEXT = 3

        internal fun extractSnippetAroundCaret(content: String, caretOffset: Int?): String {
            val lines = content.lines()
            if (lines.size <= SNIPPET_LINES) return content

            val boundedOffset = (caretOffset ?: (content.length / 2)).coerceIn(0, content.length)
            val caretLine = content
                .take(boundedOffset)
                .count { it == '\n' }
                .coerceAtMost(lines.lastIndex)

            val preferredStart = maxOf(0, caretLine - (SNIPPET_LINES / 2))
            val start = minOf(preferredStart, maxOf(0, lines.size - SNIPPET_LINES))
            val end = minOf(lines.size, start + SNIPPET_LINES)
            return lines.subList(start, end).joinToString("\n")
        }

        internal fun computeSimpleDiff(filePath: String, oldText: String, newText: String): String {
            val oldLines = oldText.lines()
            val newLines = newText.lines()

            // Find first differing line
            var firstDiff = 0
            while (firstDiff < oldLines.size && firstDiff < newLines.size && oldLines[firstDiff] == newLines[firstDiff]) {
                firstDiff++
            }

            // Find last differing line (from the end)
            var oldEnd = oldLines.size
            var newEnd = newLines.size
            while (oldEnd > firstDiff && newEnd > firstDiff && oldLines[oldEnd - 1] == newLines[newEnd - 1]) {
                oldEnd--
                newEnd--
            }

            if (firstDiff == oldEnd && firstDiff == newEnd) return ""

            val contextStart = maxOf(0, firstDiff - MAX_DIFF_CONTEXT)
            val oldContextEnd = minOf(oldLines.size, oldEnd + MAX_DIFF_CONTEXT)
            val newContextEnd = minOf(newLines.size, newEnd + MAX_DIFF_CONTEXT)

            return buildString {
                appendLine("--- $filePath")
                appendLine("+++ $filePath")
                appendLine("@@ -${contextStart + 1},${oldContextEnd - contextStart} +${contextStart + 1},${newContextEnd - contextStart} @@")
                // Context before
                for (i in contextStart until firstDiff) {
                    appendLine(" ${oldLines[i]}")
                }
                // Removed lines
                for (i in firstDiff until oldEnd) {
                    appendLine("-${oldLines[i]}")
                }
                // Added lines
                for (i in firstDiff until newEnd) {
                    appendLine("+${newLines[i]}")
                }
                // Context after
                val afterEnd = minOf(oldLines.size, oldEnd + MAX_DIFF_CONTEXT)
                for (i in oldEnd until afterEnd) {
                    appendLine(" ${oldLines[i]}")
                }
            }
        }
    }
}
