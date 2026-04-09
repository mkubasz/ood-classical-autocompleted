package com.github.mkubasz.oodclassicalautocompleted.evaluation

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ProviderRequest
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.RetrievedContextChunk
import com.github.mkubasz.oodclassicalautocompleted.completion.providers.AnthropicProviderRuntime
import com.github.mkubasz.oodclassicalautocompleted.completion.providers.InceptionLabsFimRuntime
import com.github.mkubasz.oodclassicalautocompleted.completion.providers.InceptionLabsProviderDefaults
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.CompletionContextSnapshot
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.InlineCandidatePreparation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.math.ceil
import kotlin.system.measureNanoTime

@Serializable
data class OfflineInlineEvaluationSuite(
    val version: Int = 1,
    val cases: List<OfflineInlineEvaluationCase>,
)

@Serializable
data class OfflineInlineEvaluationCase(
    val id: String,
    val document: String,
    val expected: String,
    val filePath: String? = null,
    val language: String? = null,
    val retrievedChunks: List<RetrievedContextChunk> = emptyList(),
    val expectedRetrievedFiles: List<String> = emptyList(),
    val expectedRetrievedSymbols: List<String> = emptyList(),
)

@Serializable
data class OfflineInlineEvaluationReplay(
    val predictions: List<OfflineInlineEvaluationReplayPrediction>,
)

@Serializable
data class OfflineInlineEvaluationReplayPrediction(
    val caseId: String,
    val text: String,
    val latencyMs: Long = 0,
)

@Serializable
data class OfflineInlineEvaluationCaseResult(
    val id: String,
    val source: String,
    val expected: String,
    val predicted: String,
    val latencyMs: Long,
    val matchedRatio: Double,
    val perfectLines: Int,
    val totalExpectedLines: Int,
    val exactMatch: Boolean,
    val retrievalChunkCount: Int = 0,
    val retrievalExpectedFiles: Int = 0,
    val retrievalMatchedFiles: Int = 0,
    val retrievalExpectedSymbols: Int = 0,
    val retrievalMatchedSymbols: Int = 0,
    val retrievalMatchedRatioDelta: Double? = null,
    val retrievalLatencyDeltaMs: Long? = null,
)

@Serializable
data class OfflineInlineEvaluationSummary(
    val caseCount: Int,
    val exactMatchRate: Double,
    val meanMatchedRatio: Double,
    val perfectLineRate: Double,
    val latencyMsP50: Long? = null,
    val latencyMsP90: Long? = null,
    val latencyMsP95: Long? = null,
    val retrievalFileHitRate: Double? = null,
    val retrievalSymbolHitRate: Double? = null,
    val retrievalMatchedRatioDeltaMean: Double? = null,
    val retrievalLatencyDeltaMeanMs: Double? = null,
)

@Serializable
data class OfflineInlineEvaluationReport(
    val generatedAt: String,
    val summary: OfflineInlineEvaluationSummary,
    val cases: List<OfflineInlineEvaluationCaseResult>,
)

data class OfflineInlineEvaluationPrediction(
    val text: String,
    val latencyMs: Long,
    val source: String,
    val retrievedChunks: List<RetrievedContextChunk> = emptyList(),
)

data class OfflineInlineEvaluationConfig(
    val minConfidenceScore: Double = 0.0,
    val maxSuggestionChars: Int = 400,
)

internal interface OfflineInlineEvaluationEngine : AutoCloseable {
    suspend fun predict(case: OfflineInlineEvaluationCase): OfflineInlineEvaluationPrediction

    override fun close() = Unit
}

internal interface OfflineRequestProvider : AutoCloseable {
    suspend fun complete(request: ProviderRequest): com.github.mkubasz.oodclassicalautocompleted.completion.domain.ProviderResponse?
}

internal class ReplayInlineEvaluationEngine(
    replay: OfflineInlineEvaluationReplay,
) : OfflineInlineEvaluationEngine {
    private val predictionsByCaseId = replay.predictions.associateBy { it.caseId }

    override suspend fun predict(case: OfflineInlineEvaluationCase): OfflineInlineEvaluationPrediction {
        val prediction = predictionsByCaseId[case.id]
            ?: throw IllegalArgumentException("Missing replay prediction for case `${case.id}`")
        return OfflineInlineEvaluationPrediction(
            text = prediction.text,
            latencyMs = prediction.latencyMs,
            source = "replay",
            retrievedChunks = case.retrievedChunks,
        )
    }
}

internal class ProviderInlineEvaluationEngine(
    private val provider: OfflineRequestProvider,
    private val sourceName: String,
    private val config: OfflineInlineEvaluationConfig = OfflineInlineEvaluationConfig(),
) : OfflineInlineEvaluationEngine {

    override suspend fun predict(case: OfflineInlineEvaluationCase): OfflineInlineEvaluationPrediction {
        val split = CursorMarkedDocument.parse(case.document)
        val request = ProviderRequest(
            prefix = split.prefix,
            suffix = split.suffix,
            filePath = case.filePath,
            language = case.language,
            cursorOffset = split.prefix.length,
            retrievedChunks = case.retrievedChunks.takeIf { it.isNotEmpty() },
        )
        val snapshot = CompletionContextSnapshot(
            filePath = case.filePath,
            language = case.language,
            documentText = split.prefix + split.suffix,
            documentStamp = 0L,
            caretOffset = split.prefix.length,
            prefix = split.prefix,
            suffix = split.suffix,
            prefixWindow = split.prefix.takeLast(CONTEXT_WINDOW_CHARS),
            suffixWindow = split.suffix.take(CONTEXT_WINDOW_CHARS),
        )

        var responseText = ""
        val latencyMs = measureNanoTime {
            val response = provider.complete(request)
            val prepared = InlineCandidatePreparation.prepareWithDiagnostics(
                rawCandidates = response?.inlineCandidates.orEmpty(),
                request = request,
                snapshot = snapshot,
                maxSuggestionChars = config.maxSuggestionChars,
                options = InlineCandidatePreparation.Options(
                    minConfidenceScore = config.minConfidenceScore,
                    correctnessFilterEnabled = false,
                ),
            )
            responseText = prepared.candidates.firstOrNull()?.text.orEmpty()
        } / 1_000_000

        return OfflineInlineEvaluationPrediction(
            text = responseText,
            latencyMs = latencyMs,
            source = sourceName,
            retrievedChunks = case.retrievedChunks,
        )
    }

    override fun close() {
        provider.close()
    }
}

internal object OfflineInlineEvaluationRunner {

    suspend fun evaluate(
        suite: OfflineInlineEvaluationSuite,
        engine: OfflineInlineEvaluationEngine,
    ): OfflineInlineEvaluationReport {
        val caseResults = suite.cases.map { evaluationCase ->
            val prediction = engine.predict(evaluationCase)
            val score = OfflineInlineEvaluationScorer.score(
                expected = evaluationCase.expected,
                actual = prediction.text,
            )
            val retrievalScore = OfflineInlineRetrievalScorer.score(
                expectedFiles = evaluationCase.expectedRetrievedFiles,
                expectedSymbols = evaluationCase.expectedRetrievedSymbols,
                actualChunks = prediction.retrievedChunks,
            )
            val retrievalAblation = if (evaluationCase.retrievedChunks.isNotEmpty()) {
                val baselinePrediction = engine.predict(evaluationCase.copy(retrievedChunks = emptyList()))
                val baselineScore = OfflineInlineEvaluationScorer.score(
                    expected = evaluationCase.expected,
                    actual = baselinePrediction.text,
                )
                RetrievalAblationResult(
                    matchedRatioDelta = score.matchedRatio - baselineScore.matchedRatio,
                    latencyDeltaMs = prediction.latencyMs - baselinePrediction.latencyMs,
                )
            } else {
                null
            }
            OfflineInlineEvaluationCaseResult(
                id = evaluationCase.id,
                source = prediction.source,
                expected = evaluationCase.expected,
                predicted = prediction.text,
                latencyMs = prediction.latencyMs,
                matchedRatio = score.matchedRatio,
                perfectLines = score.perfectLines,
                totalExpectedLines = score.totalExpectedLines,
                exactMatch = score.exactMatch,
                retrievalChunkCount = prediction.retrievedChunks.size,
                retrievalExpectedFiles = retrievalScore.expectedFiles,
                retrievalMatchedFiles = retrievalScore.matchedFiles,
                retrievalExpectedSymbols = retrievalScore.expectedSymbols,
                retrievalMatchedSymbols = retrievalScore.matchedSymbols,
                retrievalMatchedRatioDelta = retrievalAblation?.matchedRatioDelta,
                retrievalLatencyDeltaMs = retrievalAblation?.latencyDeltaMs,
            )
        }

        return OfflineInlineEvaluationReport(
            generatedAt = Instant.now().toString(),
            summary = OfflineInlineEvaluationScorer.summarize(caseResults),
            cases = caseResults,
        )
    }
}

object OfflineInlineEvaluationScorer {

    data class Score(
        val matchedRatio: Double,
        val perfectLines: Int,
        val totalExpectedLines: Int,
        val exactMatch: Boolean,
    )

    fun score(expected: String, actual: String): Score {
        val matchedChars = commonPrefixLength(expected, actual)
        val matchedRatio = when {
            expected.isEmpty() && actual.isEmpty() -> 1.0
            expected.isEmpty() -> 0.0
            else -> matchedChars.toDouble() / expected.length
        }
        val expectedLines = nonTerminalLines(expected)
        val actualLines = nonTerminalLines(actual)
        val perfectLines = expectedLines.indices.count { index ->
            actualLines.getOrNull(index) == expectedLines[index]
        }
        return Score(
            matchedRatio = matchedRatio,
            perfectLines = perfectLines,
            totalExpectedLines = expectedLines.size,
            exactMatch = expected == actual,
        )
    }

    fun summarize(caseResults: List<OfflineInlineEvaluationCaseResult>): OfflineInlineEvaluationSummary {
        val latencies = caseResults.map { it.latencyMs }.sorted()
        val totalExpectedLines = caseResults.sumOf { it.totalExpectedLines }
        val totalPerfectLines = caseResults.sumOf { it.perfectLines }
        return OfflineInlineEvaluationSummary(
            caseCount = caseResults.size,
            exactMatchRate = caseResults.rate { it.exactMatch },
            meanMatchedRatio = caseResults.map { it.matchedRatio }.averageOrZero(),
            perfectLineRate = if (totalExpectedLines == 0) 1.0 else totalPerfectLines.toDouble() / totalExpectedLines,
            latencyMsP50 = latencies.percentile(50),
            latencyMsP90 = latencies.percentile(90),
            latencyMsP95 = latencies.percentile(95),
            retrievalFileHitRate = caseResults.hitRate(
                expectedSelector = OfflineInlineEvaluationCaseResult::retrievalExpectedFiles,
                matchedSelector = OfflineInlineEvaluationCaseResult::retrievalMatchedFiles,
            ),
            retrievalSymbolHitRate = caseResults.hitRate(
                expectedSelector = OfflineInlineEvaluationCaseResult::retrievalExpectedSymbols,
                matchedSelector = OfflineInlineEvaluationCaseResult::retrievalMatchedSymbols,
            ),
            retrievalMatchedRatioDeltaMean = caseResults.mapNotNull { it.retrievalMatchedRatioDelta }.averageOrNull(),
            retrievalLatencyDeltaMeanMs = caseResults.mapNotNull { it.retrievalLatencyDeltaMs?.toDouble() }.averageOrNull(),
        )
    }

    private fun commonPrefixLength(first: String, second: String): Int {
        val limit = minOf(first.length, second.length)
        for (index in 0 until limit) {
            if (first[index] != second[index]) return index
        }
        return limit
    }

    private fun nonTerminalLines(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.lines()
}

object OfflineInlineEvaluationJson {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun loadSuite(path: Path): OfflineInlineEvaluationSuite =
        json.decodeFromString(OfflineInlineEvaluationSuite.serializer(), Files.readString(path))

    fun loadReplay(path: Path): OfflineInlineEvaluationReplay =
        json.decodeFromString(OfflineInlineEvaluationReplay.serializer(), Files.readString(path))

    fun writeReport(path: Path, report: OfflineInlineEvaluationReport) {
        Files.createDirectories(path.parent ?: path.toAbsolutePath().parent)
        Files.writeString(path, json.encodeToString(OfflineInlineEvaluationReport.serializer(), report))
    }
}

object OfflineInlineEvaluationFormatter {
    fun humanSummary(report: OfflineInlineEvaluationReport): String = buildString {
        appendLine("Offline inline evaluation")
        appendLine("cases=${report.summary.caseCount}")
        appendLine("exact_match_rate=${formatRate(report.summary.exactMatchRate)}")
        appendLine("mean_matched_ratio=${formatRate(report.summary.meanMatchedRatio)}")
        appendLine("perfect_line_rate=${formatRate(report.summary.perfectLineRate)}")
        appendLine(
            "latency_ms=" +
                "p50=${report.summary.latencyMsP50 ?: "n/a"}, " +
                "p90=${report.summary.latencyMsP90 ?: "n/a"}, " +
                "p95=${report.summary.latencyMsP95 ?: "n/a"}"
        )
        report.summary.retrievalFileHitRate?.let { appendLine("retrieval_file_hit_rate=${formatRate(it)}") }
        report.summary.retrievalSymbolHitRate?.let { appendLine("retrieval_symbol_hit_rate=${formatRate(it)}") }
        report.summary.retrievalMatchedRatioDeltaMean?.let { appendLine("retrieval_matched_ratio_delta_mean=${formatRate(it)}") }
        report.summary.retrievalLatencyDeltaMeanMs?.let { appendLine("retrieval_latency_delta_mean_ms=${"%.1f".format(it)}") }
    }.trimEnd()

    private fun formatRate(value: Double): String = "%.3f".format(value)
}

internal object OfflineInlineEvaluationEngineFactory {
    fun create(options: OfflineInlineEvaluationCliOptions): OfflineInlineEvaluationEngine =
        when (options.provider) {
            OfflineInlineEvaluationProvider.REPLAY -> ReplayInlineEvaluationEngine(
                replay = OfflineInlineEvaluationJson.loadReplay(options.predictionsPath
                    ?: throw IllegalArgumentException("Replay mode requires --predictions")),
            )

            OfflineInlineEvaluationProvider.ANTHROPIC -> ProviderInlineEvaluationEngine(
                provider = object : OfflineRequestProvider {
                    private val runtime = AnthropicProviderRuntime(
                        apiKey = options.apiKey ?: throw IllegalArgumentException("Anthropic evaluation requires --api-key"),
                        baseUrl = options.baseUrl ?: DEFAULT_ANTHROPIC_BASE_URL,
                        model = options.model ?: DEFAULT_ANTHROPIC_MODEL,
                        contextBudgetChars = options.contextBudgetChars,
                    )

                    override suspend fun complete(request: ProviderRequest) = runtime.complete(request)

                    override fun close() {
                        runtime.dispose()
                    }
                },
                sourceName = "anthropic",
                config = OfflineInlineEvaluationConfig(
                    minConfidenceScore = options.minConfidenceScore,
                ),
            )

            OfflineInlineEvaluationProvider.INCEPTION -> ProviderInlineEvaluationEngine(
                provider = object : OfflineRequestProvider {
                    private val runtime = InceptionLabsFimRuntime(
                        apiKey = options.apiKey ?: throw IllegalArgumentException("Inception evaluation requires --api-key"),
                        baseUrl = options.baseUrl ?: InceptionLabsProviderDefaults.BASE_URL,
                        model = options.model ?: InceptionLabsProviderDefaults.MODEL,
                        generationOptions = com.github.mkubasz.oodclassicalautocompleted.completion.providers.inception.InceptionLabsGenerationOptions(),
                        contextBudgetChars = options.contextBudgetChars,
                    )

                    override suspend fun complete(request: ProviderRequest) = runtime.complete(request)

                    override fun close() {
                        runtime.dispose()
                    }
                },
                sourceName = "inception",
                config = OfflineInlineEvaluationConfig(
                    minConfidenceScore = options.minConfidenceScore,
                ),
            )
        }

    private const val DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1/messages"
    private const val DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-20250514"
}

enum class OfflineInlineEvaluationProvider {
    REPLAY,
    ANTHROPIC,
    INCEPTION,
}

data class OfflineInlineEvaluationCliOptions(
    val casesPath: Path,
    val provider: OfflineInlineEvaluationProvider = OfflineInlineEvaluationProvider.REPLAY,
    val predictionsPath: Path? = null,
    val outputPath: Path? = null,
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val model: String? = null,
    val contextBudgetChars: Int = DEFAULT_CONTEXT_BUDGET_CHARS,
    val minConfidenceScore: Double = 0.0,
)

internal data class CursorMarkedDocument(
    val prefix: String,
    val suffix: String,
) {
    companion object {
        private const val CURSOR_MARKER = "<|cursor|>"

        fun parse(document: String): CursorMarkedDocument {
            val markerIndex = document.indexOf(CURSOR_MARKER)
            require(markerIndex >= 0) { "Document must contain $CURSOR_MARKER exactly once" }
            require(document.indexOf(CURSOR_MARKER, markerIndex + CURSOR_MARKER.length) < 0) {
                "Document must contain $CURSOR_MARKER exactly once"
            }
            return CursorMarkedDocument(
                prefix = document.substring(0, markerIndex),
                suffix = document.substring(markerIndex + CURSOR_MARKER.length),
            )
        }
    }
}

private data class RetrievalAblationResult(
    val matchedRatioDelta: Double,
    val latencyDeltaMs: Long,
)

private data class OfflineInlineRetrievalScore(
    val expectedFiles: Int,
    val matchedFiles: Int,
    val expectedSymbols: Int,
    val matchedSymbols: Int,
)

private object OfflineInlineRetrievalScorer {
    fun score(
        expectedFiles: List<String>,
        expectedSymbols: List<String>,
        actualChunks: List<RetrievedContextChunk>,
    ): OfflineInlineRetrievalScore {
        val actualFiles = actualChunks.map { it.filePath.substringAfterLast('/') }.toSet()
        val actualSymbols = actualChunks.mapNotNull { it.symbolName }.toSet()
        return OfflineInlineRetrievalScore(
            expectedFiles = expectedFiles.size,
            matchedFiles = expectedFiles.count { expected ->
                actualFiles.any { actual -> actual.equals(expected.substringAfterLast('/'), ignoreCase = true) }
            },
            expectedSymbols = expectedSymbols.size,
            matchedSymbols = expectedSymbols.count { it in actualSymbols },
        )
    }
}

private fun List<OfflineInlineEvaluationCaseResult>.rate(predicate: (OfflineInlineEvaluationCaseResult) -> Boolean): Double =
    if (isEmpty()) 0.0 else count(predicate).toDouble() / size

private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

private fun List<OfflineInlineEvaluationCaseResult>.hitRate(
    expectedSelector: (OfflineInlineEvaluationCaseResult) -> Int,
    matchedSelector: (OfflineInlineEvaluationCaseResult) -> Int,
): Double? {
    val expected = sumOf(expectedSelector)
    if (expected == 0) return null
    val matched = sumOf(matchedSelector)
    return matched.toDouble() / expected
}

private fun List<Long>.percentile(percentile: Int): Long? {
    if (isEmpty()) return null
    val rank = ceil(percentile / 100.0 * size).toInt().coerceIn(1, size) - 1
    return this[rank]
}

private const val CONTEXT_WINDOW_CHARS = 1_500
const val DEFAULT_CONTEXT_BUDGET_CHARS = 4_000
