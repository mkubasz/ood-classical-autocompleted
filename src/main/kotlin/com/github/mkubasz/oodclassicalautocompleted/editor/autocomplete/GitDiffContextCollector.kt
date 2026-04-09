package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal object GitDiffContextCollector {

    private val log = logger<GitDiffContextCollector>()
    private val cache = ConcurrentHashMap<CacheKey, CachedDiff>()

    fun collect(project: Project, maxChars: Int): String? {
        val basePath = project.basePath?.takeIf { it.isNotBlank() } ?: return null
        val cacheKey = CacheKey(basePath = basePath, maxChars = maxChars)
        val now = System.currentTimeMillis()
        cache[cacheKey]
            ?.takeIf { now - it.timestampMs <= CACHE_TTL_MS }
            ?.value
            ?.let { return it }

        val snapshot = runCatching {
            val workingTree = runGit(basePath, maxChars, "diff", "--unified=0", "--no-ext-diff", "--relative")
            val staged = runGit(basePath, maxChars, "diff", "--cached", "--unified=0", "--no-ext-diff", "--relative")
            combineDiffs(workingTree, staged, maxChars)
        }.onFailure { error ->
            log.debug("Failed to collect git diff context", error)
        }.getOrNull()

        cache[cacheKey] = CachedDiff(now, snapshot)
        return snapshot
    }

    internal fun combineDiffs(workingTree: String, staged: String, maxChars: Int): String? {
        val sections = buildList {
            val trimmedWorkingTree = workingTree.trim()
            if (trimmedWorkingTree.isNotEmpty()) {
                add("## Working tree\n$trimmedWorkingTree")
            }
            val trimmedStaged = staged.trim()
            if (trimmedStaged.isNotEmpty()) {
                add("## Staged\n$trimmedStaged")
            }
        }
        if (sections.isEmpty()) return null

        val combined = sections.joinToString("\n\n")
        if (combined.length <= maxChars) return combined
        return combined.take(maxChars).trimEnd() + "\n...[truncated]"
    }

    private fun runGit(basePath: String, maxChars: Int, vararg args: String): String {
        val process = ProcessBuilder("git", *args)
            .directory(File(basePath))
            .redirectErrorStream(true)
            .start()
        val output = StringBuilder()
        val reader = thread(start = true, isDaemon = true, name = "ood-git-diff-reader") {
            process.inputStream.bufferedReader().use { stream ->
                val buffer = CharArray(1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (output.length < maxChars) {
                        val remaining = maxChars - output.length
                        output.append(buffer, 0, read.coerceAtMost(remaining))
                    }
                }
            }
        }
        if (!process.waitFor(PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            reader.join(READER_JOIN_TIMEOUT_MS)
            return ""
        }
        reader.join(READER_JOIN_TIMEOUT_MS)
        if (process.exitValue() != 0) return ""
        return output.toString()
    }

    private data class CacheKey(
        val basePath: String,
        val maxChars: Int,
    )

    private data class CachedDiff(
        val timestampMs: Long,
        val value: String?,
    )

    private const val CACHE_TTL_MS = 5_000L
    private const val PROCESS_TIMEOUT_MS = 1_500L
    private const val READER_JOIN_TIMEOUT_MS = 250L
}
