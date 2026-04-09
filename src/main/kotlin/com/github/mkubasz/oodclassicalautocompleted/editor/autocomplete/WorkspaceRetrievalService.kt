package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.RetrievedContextChunk
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.RetrievedContextChunkKind
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class WorkspaceRetrievalService(
    private val project: Project,
) {

    private val semanticIndex: WorkspaceSemanticRetrievalIndex = DisabledWorkspaceSemanticRetrievalIndex

    internal fun retrieve(
        snapshot: CompletionContextSnapshot,
        maxChunks: Int,
    ): WorkspaceRetrievalResult {
        if (snapshot.isTerminal || snapshot.project == null || maxChunks <= 0) {
            return WorkspaceRetrievalResult()
        }
        val query = WorkspaceRetrievalQuery.build(snapshot) ?: return WorkspaceRetrievalResult()
        val cacheKey = buildCacheKey(snapshot, query, maxChunks)
        val now = System.currentTimeMillis()
        cache[cacheKey]?.takeIf { it.expiresAt > now }?.let { entry ->
            return WorkspaceRetrievalResult(
                chunks = entry.chunks,
                fromCache = true,
                queryTermCount = query.terms.size,
            )
        }

        val chunks = ApplicationManager.getApplication().runReadAction<List<RetrievedContextChunk>> {
            collect(query = query, snapshot = snapshot, maxChunks = maxChunks)
        }
        cache[cacheKey] = CacheEntry(expiresAt = now + CACHE_TTL_MS, chunks = chunks)
        trimCache()
        return WorkspaceRetrievalResult(
            chunks = chunks,
            fromCache = false,
            queryTermCount = query.terms.size,
        )
    }

    private fun collect(
        query: WorkspaceRetrievalQuery,
        snapshot: CompletionContextSnapshot,
        maxChunks: Int,
    ): List<RetrievedContextChunk> {
        val fileIndex = ProjectFileIndex.getInstance(project)
        val candidates = mutableListOf<WorkspaceRetrievalCandidate>()
        var scannedFiles = 0

        fileIndex.iterateContent { file ->
            if (scannedFiles >= MAX_SCANNED_FILES || candidates.size >= MAX_STAGE_ONE_CANDIDATES) {
                return@iterateContent false
            }
            scannedFiles++

            if (!shouldConsider(file, snapshot.filePath)) return@iterateContent true
            val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return@iterateContent true
            candidates += extractCandidates(
                filePath = file.path,
                text = text,
                query = query,
            )
            true
        }

        val lexicalTop = candidates
            .sortedByDescending { it.stageOneScore }
            .take(MAX_RERANK_CANDIDATES)
        val semanticCandidates = semanticIndex.search(query, snapshot, maxChunks)
            .map { chunk ->
                WorkspaceRetrievalCandidate(
                    filePath = chunk.filePath,
                    content = chunk.content,
                    stageOneScore = chunk.score,
                    finalScore = chunk.score,
                    chunkKind = chunk.chunkKind,
                    symbolName = chunk.symbolName,
                    language = chunk.language,
                    retrievalStage = chunk.retrievalStage,
                    selectionBucket = chunk.selectionBucket,
                )
            }

        val reranked = (lexicalTop + semanticCandidates)
            .map { rerank(it, query, snapshot) }
            .sortedByDescending { it.finalScore }

        return selectCandidates(
            candidates = reranked,
            snapshot = snapshot,
            query = query,
            maxChunks = maxChunks,
        ).map { it.toChunk() }
    }

    private fun extractCandidates(
        filePath: String,
        text: String,
        query: WorkspaceRetrievalQuery,
    ): List<WorkspaceRetrievalCandidate> {
        val lowerText = text.lowercase()
        val lowerPath = filePath.lowercase()
        if (query.terms.none { it.value in lowerText } && query.pathTerms.none { it in lowerPath }) {
            return emptyList()
        }

        val lines = text.replace("\r", "").lines()
        val coveredLines = mutableSetOf<Int>()
        val candidates = mutableListOf<WorkspaceRetrievalCandidate>()

        extractImportsCandidate(filePath, lines, query)?.let { candidate ->
            candidates += candidate
            coveredLines += candidate.startLine until candidate.endLineExclusive
        }

        extractStructuralCandidates(filePath, lines, query).forEach { candidate ->
            candidates += candidate
            coveredLines += candidate.startLine until candidate.endLineExclusive
        }

        candidates += extractLineWindowCandidates(
            filePath = filePath,
            lines = lines,
            query = query,
            coveredLines = coveredLines,
        )

        return candidates
            .sortedByDescending { it.stageOneScore }
            .distinctBy { candidateKey(it) }
            .take(MAX_CHUNKS_PER_FILE)
    }

    private fun extractImportsCandidate(
        filePath: String,
        lines: List<String>,
        query: WorkspaceRetrievalQuery,
    ): WorkspaceRetrievalCandidate? {
        val importLineIndices = mutableListOf<Int>()
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            when {
                trimmed.isBlank() && importLineIndices.isEmpty() -> Unit
                IMPORT_PREFIXES.any(trimmed::startsWith) -> importLineIndices += index
                importLineIndices.isNotEmpty() -> return@forEachIndexed
                else -> return@forEachIndexed
            }
        }
        if (importLineIndices.isEmpty()) return null

        val start = importLineIndices.first()
        val endExclusive = importLineIndices.last() + 1
        val content = lines.subList(start, endExclusive)
            .joinToString("\n")
            .trimEnd()
            .take(MAX_CHUNK_CHARS)
        val score = scoreChunk(
            content = content,
            filePath = filePath,
            query = query,
            symbolName = null,
            chunkKind = RetrievedContextChunkKind.IMPORTS,
        )
        if (score <= 0.0) return null

        return WorkspaceRetrievalCandidate(
            filePath = filePath,
            content = content,
            stageOneScore = score,
            finalScore = score,
            chunkKind = RetrievedContextChunkKind.IMPORTS,
            language = languageForPath(filePath),
            startLine = start,
            endLineExclusive = endExclusive,
        )
    }

    private fun extractStructuralCandidates(
        filePath: String,
        lines: List<String>,
        query: WorkspaceRetrievalQuery,
    ): List<WorkspaceRetrievalCandidate> {
        val matches = mutableListOf<HeaderMatch>()
        lines.forEachIndexed { index, line ->
            STRUCTURAL_PATTERNS.firstNotNullOfOrNull { pattern ->
                pattern.regex.find(line)?.groups?.get(1)?.value?.takeIf { it.isNotBlank() }?.let { symbolName ->
                    HeaderMatch(index = index, symbolName = symbolName, kind = pattern.kind)
                }
            }?.let(matches::add)
        }
        if (matches.isEmpty()) return emptyList()

        return matches.mapIndexedNotNull { index, match ->
            val endExclusive = minOf(
                if (index + 1 < matches.size) matches[index + 1].index else lines.size,
                match.index + STRUCTURAL_CHUNK_MAX_LINES,
            )
            val content = lines.subList(match.index, endExclusive)
                .joinToString("\n")
                .trimEnd()
                .take(MAX_CHUNK_CHARS)
                .takeIf(String::isNotBlank)
                ?: return@mapIndexedNotNull null
            val score = scoreChunk(
                content = content,
                filePath = filePath,
                query = query,
                symbolName = match.symbolName,
                chunkKind = match.kind,
            )
            if (score <= 0.0) return@mapIndexedNotNull null

            WorkspaceRetrievalCandidate(
                filePath = filePath,
                content = content,
                stageOneScore = score,
                finalScore = score,
                chunkKind = match.kind,
                symbolName = match.symbolName,
                language = languageForPath(filePath),
                startLine = match.index,
                endLineExclusive = endExclusive,
            )
        }
    }

    private fun extractLineWindowCandidates(
        filePath: String,
        lines: List<String>,
        query: WorkspaceRetrievalQuery,
        coveredLines: Set<Int>,
    ): List<WorkspaceRetrievalCandidate> {
        val candidates = linkedMapOf<String, WorkspaceRetrievalCandidate>()
        lines.forEachIndexed { index, line ->
            if (index in coveredLines) return@forEachIndexed
            val score = scoreLine(line, filePath, query)
            if (score <= 0.0) return@forEachIndexed

            val start = (index - CONTEXT_LINES_BEFORE_HIT).coerceAtLeast(0)
            val endExclusive = (start + CHUNK_LINE_WINDOW).coerceAtMost(lines.size)
            val content = lines.subList(start, endExclusive)
                .joinToString("\n")
                .trimEnd()
                .take(MAX_CHUNK_CHARS)
                .takeIf(String::isNotBlank)
                ?: return@forEachIndexed
            val existing = candidates[content]
            val candidate = WorkspaceRetrievalCandidate(
                filePath = filePath,
                content = content,
                stageOneScore = score * LINE_WINDOW_SCORE_RATIO,
                finalScore = score * LINE_WINDOW_SCORE_RATIO,
                chunkKind = RetrievedContextChunkKind.LINE_WINDOW,
                language = languageForPath(filePath),
                startLine = start,
                endLineExclusive = endExclusive,
            )
            if (existing == null || candidate.stageOneScore > existing.stageOneScore) {
                candidates[content] = candidate
            }
        }

        return candidates.values
            .sortedByDescending { it.stageOneScore }
            .take(MAX_LINE_WINDOWS_PER_FILE)
    }

    private fun rerank(
        candidate: WorkspaceRetrievalCandidate,
        query: WorkspaceRetrievalQuery,
        snapshot: CompletionContextSnapshot,
    ): WorkspaceRetrievalCandidate {
        var score = candidate.stageOneScore
        val currentDir = snapshot.filePath?.substringBeforeLast('/', "")
        val candidateDir = candidate.filePath.substringBeforeLast('/', "")

        if (!snapshot.language.isNullOrBlank() && snapshot.language.equals(candidate.language, ignoreCase = true)) {
            score += SAME_LANGUAGE_BOOST
        }
        if (!currentDir.isNullOrBlank() && currentDir == candidateDir) {
            score += SAME_DIRECTORY_BOOST
        }
        if (sameModule(snapshot.filePath, candidate.filePath)) {
            score += SAME_MODULE_BOOST
        }

        val symbolTokens = WorkspaceRetrievalQuery.tokenize(candidate.symbolName)
        if (symbolTokens.any { it in query.primaryTerms }) {
            score += PRIMARY_SYMBOL_MATCH_BOOST
        } else if (symbolTokens.any { it in query.secondaryTerms }) {
            score += SECONDARY_SYMBOL_MATCH_BOOST
        }

        val context = snapshot.inlineContext
        val contentLower = candidate.content.lowercase()
        if (context?.receiverMemberNames.orEmpty().any { member ->
                WorkspaceRetrievalQuery.tokenize(member).any(contentLower::contains)
            }
        ) {
            score += RECEIVER_MEMBER_BOOST
        }
        if (WorkspaceRetrievalQuery.tokenize(context?.resolvedReferenceName).any(contentLower::contains)) {
            score += RESOLVED_REFERENCE_BOOST
        }
        if (candidate.chunkKind == RetrievedContextChunkKind.SYMBOL) {
            score += SYMBOL_CHUNK_BOOST
        }

        return candidate.copy(
            finalScore = score,
            retrievalStage = if (candidate.retrievalStage == "semantic") candidate.retrievalStage else "reranked",
            selectionBucket = selectionBucket(candidate, query, snapshot),
        )
    }

    private fun selectionBucket(
        candidate: WorkspaceRetrievalCandidate,
        query: WorkspaceRetrievalQuery,
        snapshot: CompletionContextSnapshot,
    ): String {
        val currentDir = snapshot.filePath?.substringBeforeLast('/', "")
        val candidateDir = candidate.filePath.substringBeforeLast('/', "")
        val symbolTokens = WorkspaceRetrievalQuery.tokenize(candidate.symbolName)
        return when {
            symbolTokens.any { it in query.primaryTerms } -> LOCAL_SYMBOL_BUCKET
            !currentDir.isNullOrBlank() && currentDir == candidateDir -> SAME_DIRECTORY_BUCKET
            else -> WIDER_PROJECT_BUCKET
        }
    }

    private fun selectCandidates(
        candidates: List<WorkspaceRetrievalCandidate>,
        snapshot: CompletionContextSnapshot,
        query: WorkspaceRetrievalQuery,
        maxChunks: Int,
    ): List<WorkspaceRetrievalCandidate> {
        val ranked = candidates
            .sortedByDescending { it.finalScore }
            .distinctBy(::normalizedChunkKey)
        val selected = mutableListOf<WorkspaceRetrievalCandidate>()
        val seenFiles = mutableSetOf<String>()

        listOf(LOCAL_SYMBOL_BUCKET, SAME_DIRECTORY_BUCKET, WIDER_PROJECT_BUCKET).forEach { bucket ->
            ranked.firstOrNull { it.selectionBucket == bucket }?.let { candidate ->
                selected += candidate
                seenFiles += candidate.filePath
            }
        }

        ranked.forEach { candidate ->
            if (selected.size >= maxChunks) return@forEach
            if (candidate in selected) return@forEach
            if (seenFiles.add(candidate.filePath)) {
                selected += candidate
            }
        }
        ranked.forEach { candidate ->
            if (selected.size >= maxChunks) return@forEach
            if (candidate !in selected) {
                selected += candidate
            }
        }

        return selected
            .take(maxChunks)
            .map { candidate ->
                val bucket = candidate.selectionBucket ?: selectionBucket(candidate, query, snapshot)
                candidate.copy(selectionBucket = bucket)
            }
    }

    private fun scoreChunk(
        content: String,
        filePath: String,
        query: WorkspaceRetrievalQuery,
        symbolName: String?,
        chunkKind: RetrievedContextChunkKind,
    ): Double {
        val contentLower = content.lowercase()
        val contentTokens = WorkspaceRetrievalQuery.tokenize(content).toSet()
        val symbolTokens = WorkspaceRetrievalQuery.tokenize(symbolName).toSet()
        val pathTokens = WorkspaceRetrievalQuery.tokenize(filePath).toSet()
        var score = 0.0

        query.terms.forEach { term ->
            when {
                term.value in symbolTokens -> score += term.weight * SYMBOL_TOKEN_MULTIPLIER
                term.value in contentTokens -> score += term.weight
                term.value in contentLower -> score += term.weight * SUBSTRING_MATCH_RATIO
            }
        }
        query.pathTerms.forEach { term ->
            when {
                term in pathTokens -> score += PATH_TOKEN_MATCH_BOOST
                term in filePath.lowercase() -> score += PATH_SUBSTRING_MATCH_BOOST
            }
        }
        score += when (chunkKind) {
            RetrievedContextChunkKind.SYMBOL -> STRUCTURAL_CHUNK_BOOST
            RetrievedContextChunkKind.IMPORTS -> IMPORTS_CHUNK_BOOST
            RetrievedContextChunkKind.CONSTANT -> CONSTANT_CHUNK_BOOST
            RetrievedContextChunkKind.LINE_WINDOW -> 0.0
        }
        return score
    }

    private fun scoreLine(line: String, filePath: String, query: WorkspaceRetrievalQuery): Double {
        val normalizedLine = line.lowercase()
        val lineTokens = WorkspaceRetrievalQuery.tokenize(line).toSet()
        val pathTokens = WorkspaceRetrievalQuery.tokenize(filePath).toSet()
        var score = 0.0
        query.terms.forEach { term ->
            when {
                term.value in lineTokens -> score += term.weight
                term.value in normalizedLine -> score += term.weight * SUBSTRING_MATCH_RATIO
            }
        }
        query.pathTerms.forEach { term ->
            when {
                term in pathTokens -> score += PATH_TOKEN_MATCH_BOOST
                term in filePath.lowercase() -> score += PATH_SUBSTRING_MATCH_BOOST
            }
        }
        return score
    }

    private fun buildCacheKey(
        snapshot: CompletionContextSnapshot,
        query: WorkspaceRetrievalQuery,
        maxChunks: Int,
    ): String = buildString {
        append(snapshot.filePath)
        append('|')
        append(snapshot.language)
        append('|')
        append(query.terms.joinToString(",") { "${it.value}:${it.weight}" })
        append('|')
        append(query.pathTerms.joinToString(","))
        append('|')
        append(maxChunks)
    }

    private fun shouldConsider(file: VirtualFile, currentFilePath: String?): Boolean {
        if (file.path == currentFilePath) return false
        if (!file.isValid || file.isDirectory || file.fileType.isBinary) return false
        if (file.length > MAX_FILE_BYTES) return false
        val path = file.path.lowercase()
        if (IGNORED_PATH_SEGMENTS.any { it in path }) return false
        return true
    }

    private fun trimCache() {
        if (cache.size <= MAX_CACHE_ENTRIES) return
        val oldest = cache.entries.minByOrNull { it.value.expiresAt } ?: return
        cache.remove(oldest.key)
    }

    private fun candidateKey(candidate: WorkspaceRetrievalCandidate): String =
        "${candidate.filePath}|${candidate.chunkKind}|${candidate.symbolName.orEmpty()}|${candidate.content.hashCode()}"

    private fun normalizedChunkKey(candidate: WorkspaceRetrievalCandidate): String {
        val normalizedContent = candidate.content
            .replace(Regex("""\s+"""), " ")
            .trim()
        return "${candidate.chunkKind}|${candidate.symbolName.orEmpty()}|$normalizedContent"
    }

    private fun sameModule(currentFilePath: String?, candidateFilePath: String): Boolean {
        val current = currentFilePath?.substringBeforeLast('/', "") ?: return false
        val candidate = candidateFilePath.substringBeforeLast('/', "")
        return current.substringAfterLast('/', "") == candidate.substringAfterLast('/', "")
    }

    private fun languageForPath(filePath: String): String? =
        filePath.substringAfterLast('.', "").lowercase().takeIf(String::isNotBlank)

    private data class CacheEntry(
        val expiresAt: Long,
        val chunks: List<RetrievedContextChunk>,
    )

    private data class HeaderMatch(
        val index: Int,
        val symbolName: String,
        val kind: RetrievedContextChunkKind,
    )

    private data class StructuralPattern(
        val regex: Regex,
        val kind: RetrievedContextChunkKind,
    )

    private data class WorkspaceRetrievalCandidate(
        val filePath: String,
        val content: String,
        val stageOneScore: Double,
        val finalScore: Double,
        val chunkKind: RetrievedContextChunkKind,
        val symbolName: String? = null,
        val language: String? = null,
        val retrievalStage: String = "lexical",
        val selectionBucket: String? = null,
        val startLine: Int = 0,
        val endLineExclusive: Int = 0,
    ) {
        fun toChunk(): RetrievedContextChunk = RetrievedContextChunk(
            filePath = filePath,
            content = content,
            score = finalScore,
            chunkKind = chunkKind,
            symbolName = symbolName,
            language = language,
            retrievalStage = retrievalStage,
            selectionBucket = selectionBucket,
        )
    }

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    companion object {
        private const val CACHE_TTL_MS = 10_000L
        private const val MAX_CACHE_ENTRIES = 64
        private const val MAX_SCANNED_FILES = 250
        private const val MAX_STAGE_ONE_CANDIDATES = 64
        private const val MAX_RERANK_CANDIDATES = 24
        private const val MAX_CHUNKS_PER_FILE = 3
        private const val MAX_LINE_WINDOWS_PER_FILE = 1
        private const val MAX_FILE_BYTES = 200_000L
        private const val MAX_CHUNK_CHARS = 700
        private const val CHUNK_LINE_WINDOW = 14
        private const val STRUCTURAL_CHUNK_MAX_LINES = 18
        private const val CONTEXT_LINES_BEFORE_HIT = 2
        private const val SUBSTRING_MATCH_RATIO = 0.35
        private const val PATH_TOKEN_MATCH_BOOST = 1.25
        private const val PATH_SUBSTRING_MATCH_BOOST = 0.75
        private const val STRUCTURAL_CHUNK_BOOST = 0.8
        private const val IMPORTS_CHUNK_BOOST = 0.35
        private const val CONSTANT_CHUNK_BOOST = 0.5
        private const val LINE_WINDOW_SCORE_RATIO = 0.85
        private const val SYMBOL_TOKEN_MULTIPLIER = 1.2
        private const val SAME_LANGUAGE_BOOST = 0.6
        private const val SAME_DIRECTORY_BOOST = 0.9
        private const val SAME_MODULE_BOOST = 0.4
        private const val PRIMARY_SYMBOL_MATCH_BOOST = 1.4
        private const val SECONDARY_SYMBOL_MATCH_BOOST = 0.7
        private const val RECEIVER_MEMBER_BOOST = 0.8
        private const val RESOLVED_REFERENCE_BOOST = 0.9
        private const val SYMBOL_CHUNK_BOOST = 0.5
        private val IMPORT_PREFIXES = listOf("package ", "import ", "from ", "using ")
        private val STRUCTURAL_PATTERNS = listOf(
            StructuralPattern(Regex("""^\s*(?:async\s+def|def|class)\s+([A-Za-z_][A-Za-z0-9_]*)"""), RetrievedContextChunkKind.SYMBOL),
            StructuralPattern(Regex("""^\s*(?:fun|class|interface|enum|object|data\s+class)\s+([A-Za-z_][A-Za-z0-9_]*)"""), RetrievedContextChunkKind.SYMBOL),
            StructuralPattern(Regex("""^\s*(?:function|class)\s+([A-Za-z_][A-Za-z0-9_]*)"""), RetrievedContextChunkKind.SYMBOL),
            StructuralPattern(Regex("""^\s*func\s+(?:\([^)]+\)\s*)?([A-Za-z_][A-Za-z0-9_]*)"""), RetrievedContextChunkKind.SYMBOL),
            StructuralPattern(Regex("""^\s*type\s+([A-Za-z_][A-Za-z0-9_]*)\s+"""), RetrievedContextChunkKind.SYMBOL),
            StructuralPattern(Regex("""^\s*(?:const|val|var|let)\s+([A-Za-z_][A-Za-z0-9_]*)"""), RetrievedContextChunkKind.CONSTANT),
        )
        private val IGNORED_PATH_SEGMENTS = listOf("/build/", "/out/", "/.git/", "/.idea/", "/node_modules/", "/.gradle/")
        private const val LOCAL_SYMBOL_BUCKET = "local_symbol"
        private const val SAME_DIRECTORY_BUCKET = "same_directory"
        private const val WIDER_PROJECT_BUCKET = "wider_project"
    }
}

internal interface WorkspaceSemanticRetrievalIndex {
    fun search(
        query: WorkspaceRetrievalQuery,
        snapshot: CompletionContextSnapshot,
        maxChunks: Int,
    ): List<RetrievedContextChunk>
}

internal object DisabledWorkspaceSemanticRetrievalIndex : WorkspaceSemanticRetrievalIndex {
    override fun search(
        query: WorkspaceRetrievalQuery,
        snapshot: CompletionContextSnapshot,
        maxChunks: Int,
    ): List<RetrievedContextChunk> = emptyList()
}

internal data class WorkspaceRetrievalResult(
    val chunks: List<RetrievedContextChunk> = emptyList(),
    val fromCache: Boolean = false,
    val queryTermCount: Int = 0,
)

internal data class WorkspaceRetrievalTerm(
    val value: String,
    val weight: Double,
)

internal data class WorkspaceRetrievalQuery(
    val terms: List<WorkspaceRetrievalTerm>,
    val pathTerms: List<String>,
) {
    val primaryTerms: Set<String>
        get() = terms.filter { it.weight >= PRIMARY_TERM_WEIGHT }.mapTo(linkedSetOf(), WorkspaceRetrievalTerm::value)

    val secondaryTerms: Set<String>
        get() = terms.filter { it.weight >= SECONDARY_TERM_WEIGHT }.mapTo(linkedSetOf(), WorkspaceRetrievalTerm::value)

    companion object {
        fun build(snapshot: CompletionContextSnapshot): WorkspaceRetrievalQuery? {
            val context = snapshot.inlineContext
            val terms = linkedMapOf<String, Double>()
            addWeightedTerms(terms, tokenize(context?.resolvedReferenceName), PRIMARY_TERM_WEIGHT)
            addWeightedTerms(terms, tokenize(context?.receiverExpression), PRIMARY_TERM_WEIGHT)
            addWeightedTerms(terms, tokenize(context?.currentDefinitionName), PRIMARY_TERM_WEIGHT)

            context?.currentParameterNames.orEmpty().forEach {
                addWeightedTerms(terms, tokenize(it), SECONDARY_TERM_WEIGHT)
            }
            context?.matchingTypeNames.orEmpty().forEach {
                addWeightedTerms(terms, tokenize(it), SECONDARY_TERM_WEIGHT)
            }
            context?.enclosingNames.orEmpty().forEach {
                addWeightedTerms(terms, tokenize(it), SECONDARY_TERM_WEIGHT)
            }

            addWeightedTerms(
                terms,
                IDENTIFIER.findAll(snapshot.prefix.takeLast(600))
                    .flatMap { tokenize(it.value).asSequence() }
                    .take(8)
                    .toList(),
                CONTEXT_TERM_WEIGHT,
            )
            addWeightedTerms(
                terms,
                IDENTIFIER.findAll(snapshot.suffix.take(240))
                    .flatMap { tokenize(it.value).asSequence() }
                    .take(4)
                    .toList(),
                CONTEXT_TERM_WEIGHT,
            )

            val pathTerms = linkedSetOf<String>()
            pathTerms += tokenize(snapshot.filePath?.substringAfterLast('/'))
            pathTerms += tokenize(context?.resolvedReferenceName)
            pathTerms += tokenize(context?.currentDefinitionName)
            pathTerms += tokenize(context?.receiverExpression)

            val rankedTerms = terms.entries
                .sortedByDescending { it.value }
                .take(MAX_QUERY_TERMS)
                .map { (value, weight) -> WorkspaceRetrievalTerm(value = value, weight = weight) }

            if (rankedTerms.isEmpty()) return null
            return WorkspaceRetrievalQuery(
                terms = rankedTerms,
                pathTerms = pathTerms.take(MAX_PATH_TERMS),
            )
        }

        internal fun tokenize(value: String?): List<String> {
            val raw = value.orEmpty().trim()
            if (raw.isBlank()) return emptyList()

            val tokens = linkedSetOf<String>()
            raw.split(NON_IDENTIFIER)
                .filter(String::isNotBlank)
                .forEach { part ->
                    val normalized = part.lowercase()
                    if (normalized.length >= 3 && normalized !in COMMON_TERMS) {
                        tokens += normalized
                    }
                    CAMEL_SEGMENT.findAll(part)
                        .map { it.value.lowercase() }
                        .filter { it.length >= 3 && it !in COMMON_TERMS }
                        .forEach(tokens::add)
                }
            return tokens.toList()
        }

        private fun addWeightedTerms(
            terms: MutableMap<String, Double>,
            values: List<String>,
            weight: Double,
        ) {
            values.forEach { value ->
                val current = terms[value] ?: 0.0
                terms[value] = maxOf(current, weight)
            }
        }

        private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]{2,}""")
        private val NON_IDENTIFIER = Regex("""[^A-Za-z0-9]+|_+""")
        private val CAMEL_SEGMENT = Regex("""[A-Z]+(?![a-z])|[A-Z]?[a-z]+|\d+""")
        private val COMMON_TERMS = setOf(
            "self", "this", "true", "false", "none", "null", "void", "string", "class",
            "function", "return", "const", "final", "public", "private", "protected",
            "message", "value", "item", "args", "kwargs",
        )
        private const val MAX_QUERY_TERMS = 10
        private const val MAX_PATH_TERMS = 6
    }
}

private const val PRIMARY_TERM_WEIGHT = 5.0
private const val SECONDARY_TERM_WEIGHT = 2.5
private const val CONTEXT_TERM_WEIGHT = 1.0
