# OOD Autocomplete Plan

## Status Snapshot

- Scope remains inline autocomplete for JetBrains editors.
- The plugin logic is language-agnostic at the editor layer. It uses document text, caret context, file path, and provider responses, so it is not Kotlin-only.
- Latest verification: `./gradlew test --rerun-tasks` and `./gradlew buildPlugin` are passing.

## Done

### 1. Progressive Suggestion Updates (First Pass)

Implemented:

- matching typed text trims the active ghost text in place
- full suggestion consumption clears the ghost text and allows the next request
- divergence rejects the stale suggestion and rerequests
- focused coverage exists in `ProgressiveSuggestionUpdateTest`

Current limit:

- progressive trimming only works when the suggestion inserts at the caret
- off-caret `NextEdit` suggestions still fall back to dismiss-and-rerequest

### 2. Better Trigger Heuristics (First Pass)

Implemented:

- suppression in comments, strings, and import contexts
- suppression in oversized files and generated files
- suppression in low-signal whitespace regions
- eager triggering in higher-signal structural / newline contexts
- focused coverage exists in `AutocompleteTriggerHeuristicsTest`

### 3. Alternative Suggestions (First Pass)

Implemented:

- active suggestion state now supports multiple alternatives instead of only one suggestion
- cycle-next and cycle-previous actions are available
- cycling swaps the rendered ghost text without corrupting inlay state
- focused coverage exists in `SuggestionAlternativesTest`

Current limit:

- cycling only does anything when multiple alternatives are actually available for the same context
- today that mainly depends on multi-result provider flows rather than a broader ranking system

### 4. Acceptance And Navigation (First Pass)

Implemented:

- accept on `Tab`
- optional accept on `Right Arrow`
- optional accept on `End / Fn+Right`
- dismiss on `Escape`
- actions are scoped to the editor that owns the active ghost text

Settings support:

- keyboard shortcut options are exposed in plugin settings
- shortcut parsing accepts friendly forms like `cmd+y`, `cmd+;`, and `cmd+]`
- custom shortcuts are registered per live editor, which is more reliable across macOS keyboard layouts

### 5. Off-Caret NextEdit Progressive Updates (First Pass)

Implemented:

- `ProgressiveSuggestionUpdate` handles off-caret suggestions via `applyOffCaret()`
- edits that do not overlap the suggestion region shift its offsets by the edit delta
- edits that overlap the suggestion region dismiss it
- `AutocompleteService.onDocumentChanged()` preserves NextEdit state when edits are outside the candidate region
- active NextEdit previews are still dismissed on any edit (diff UI may be stale)
- focused coverage in `ProgressiveSuggestionUpdateTest`

### 6. Cache Stability And Cancellation Cleanup

Implemented:

- removed `documentStamp` and `caretOffset` from `InlineSuggestionCache.Key` — cache now keys on stable prefix/suffix hashes that exclude the volatile 20-char boundary near the caret
- cache can now hit during active typing when the broader context hasn't changed
- removed dead `AtomicBoolean cancelled` from all three providers (`AnthropicAutocompleteProvider`, `InceptionLabsFimProvider`, `InceptionLabsNextEditProvider`)
- cancellation now relies on Kotlin coroutine structured cancellation (Ktor CIO respects it automatically)
- removed redundant `provider.cancel()` calls from `AutocompleteProviderCoordinator` — `Deferred.cancel()` is sufficient

### 7. Deep PSI Context And Cross-File Definition Resolution

Implemented:

- `PsiInlineContextBuilder` now resolves cross-file definitions by scanning identifiers near the caret, following references to other files, and extracting compact signatures
- new `resolvedDefinitions: List<ResolvedDefinition>` field in `InlineModelContext` carries cross-file definitions
- `InlineModelContextFormatter` includes cross-file definitions in both code-comment and instruction-prompt formats
- scan is bounded by `MAX_CROSS_FILE_DEFINITIONS=3`, `MAX_CROSS_FILE_CHARS=1500`, and a scan radius of ~500 chars around the caret
- no unbounded PSI traversals — scan samples elements at stride-3 offsets

### 8. Context Budget Packing

Implemented:

- new `ContextBudgetPacker` allocates a total character budget across semantic context, local prefix, and local suffix with priority-based sharing
- `InceptionLabsRequestBodyBuilder.buildFimBody()` now uses budget packer instead of static char limits
- `AnthropicAutocompleteProvider.buildPrompt()` uses budget packer for prefix/suffix/context allocation
- default budgets: FIM 4000 total (1200 min prefix, 600 min suffix, 1000 max semantic), Anthropic 3500 total
- `contextBudgetChars` setting added for future configurability

### 9. Latency Instrumentation

Implemented:

- `fetchInlineCandidate()` now records `System.nanoTime()` timestamps at method start, after context build, before/after inference
- new `metricLatency()` logs structured timing: `total_ms`, `context_ms`, `inference_ms`, and `source` (cache vs provider)
- visible when `debugMetricsLogging` is enabled in settings

### 10. Confidence Gating

Implemented:

- `confidenceScore: Double?` field added to `InlineCompletionCandidate`
- new `InlineConfidenceScorer` applies heuristic scoring: penalizes very short, whitespace-only, suffix-repeating, and prefix-echoing suggestions; rewards natural boundary endings
- `InlineCandidatePreparation` filters candidates below `minConfidenceScore` setting (default 0.0 = disabled)
- API-provided confidence scores (if present) are preserved and take priority

### 11. Correctness Filter

Implemented:

- new `InlineCorrectnessFilter` inserts the suggestion into a temporary PSI file and walks for `PsiErrorElement` within the inserted range
- time-limited to 50ms with `MAX_TOLERATED_ERRORS=2` threshold
- opt-in via `correctnessFilterEnabled` setting (default false, adds latency)
- wired into `InlineCandidatePreparation` pipeline after confidence scoring

### 12. PSI Header Validation Fix For Incomplete Headers

Fixed:

- `InlineHeaderPsiValidator.shouldValidate()` now skips validation when the current line is an incomplete header (`def p`, `class M`, `async def f`) — i.e., the user is still typing the function/class name
- previously, typing `def p` would reject completions because the pre-existing line already had PSI errors ('(' expected)
- validation still applies once the header has at least an opening paren

### 13. Streaming Token Delivery

Implemented:

- `AutocompleteProvider` interface now has `completeStreaming(request): Flow<String>?` with default null
- `InceptionLabsFimProvider` implements SSE streaming via `channelFlow` + Ktor `preparePost` + `bodyAsChannel`
- parses `data: {...}\n\n` SSE events, yields token chunks, stops on `data: [DONE]`
- `AutocompleteService.fetchSuggestionFlow()` returns a `Flow<String>` that supports streaming, cache, and pending suggestions
- `OodInlineCompletionProvider` now uses `fetchSuggestionFlow` and maps chunks to `InlineCompletionGrayTextElement`
- streaming tokens are accumulated and stored in cache/state after the flow completes

### 14. Word-Level And Line-Level Partial Acceptance

Implemented:

- `AutocompleteService.acceptNextWord(editor)` inserts text up to the next word boundary, stores remainder as pending suggestion
- `AutocompleteService.acceptNextLine(editor)` inserts text up to the next newline, stores remainder as pending
- new `AcceptNextWordAction` and `AcceptNextLineAction` registered in plugin.xml
- `OodActionPromoter` updated to promote both actions above IDE defaults
- `pendingSuggestions` map in the service allows immediate re-display of remainders without API calls
- `fetchSuggestionFlow` checks `pendingSuggestions` first and returns them instantly (no debounce)

### 15. Cycling Alternatives Wired

Implemented:

- `cycleToNextSuggestion(editor)` and `cycleToPreviousSuggestion(editor)` now work
- cycling stores the next candidate as a pending suggestion and cancels the current inline completion
- `InlineState` has `currentIndex` field for tracking the active alternative
- `pendingSuggestions` mechanism ensures the cycled suggestion is shown immediately on re-trigger

### 16. Boundary Adjuster Newline Side-Effect Fix

Fixed:

- `InlineSuggestionBoundaryAdjuster.needsLeadingNewline()` now checks `isContinuationOfLastToken()`: if the line ends with a letter/underscore and the suggestion starts with an identifier character, no newline is added
- handles cases like `metadata=RecordedMessageMetad` + `ata(` and `def proje` + `ct_streaming_workflow(`
- added `=` to `EXPRESSION_CONTINUATIONS` so `name=value` patterns are not treated as closed statements
- `isIncompleteDefinitionHeader()` still provides safety net for `def`/`class` lines

### 17. Normalizer Bracket Duplication And Short Suggestion Fixes

Fixed:

- `stripLeadingSuffixDuplication()` strips leading characters from the suggestion that duplicate the suffix start (e.g., suggestion `):` when suffix is `)\n...` → strips the `)`)
- `InlineHeaderCompletionAdjuster.sanitizePythonHeaderContinuation()` now accounts for closing parens in suffix — decrements `roundBalance` for suffix parens before processing
- `MIN_DISPLAY_LENGTH = 2` filter rejects single-character suggestions that are likely noise

### 18. Multiline Ghost Text Indentation

Implemented:

- `BlockGhostRenderer.paint()` now computes per-line x-offset based on leading whitespace
- each block line is trimmed of leading whitespace and rendered at the correct pixel indent (tabs use `editor.settings.getTabSize()`)
- blank lines render correctly instead of all lines stacking at `targetRegion.x`

### 19. Token Healing For Mid-Token Caret Positions

Implemented:

- new `TokenBoundaryDetector.heal()` detects when the caret is mid-identifier and backs up the FIM split point to the last non-identifier character
- `InceptionLabsRequestBodyBuilder.buildFimBody()` uses the healed split so the model gets a clean token boundary between prefix and suffix
- prevents the model from generating redundant characters that overlap with the partial token before the caret

### 20. Proximity Cache Lookup

Implemented:

- `InlineSuggestionCache` now has a `ProximityKey` index and `getProximity()` method
- when exact cache key misses, proximity lookup finds entries where the first 40 chars of the prefix tail match
- cached suggestions are trimmed by the characters the user typed since the cache entry was stored
- both `fetchInlineCandidate()` and `fetchSuggestionFlow()` try proximity lookup before making API calls
- logged as `inline.cache.proximity_hit` in metrics

### 21. Acceptance Flow And State Cleanup

Implemented:

- `pendingSuggestions` are cleaned up on reject, focus loss, editor unregister, and dispose
- prevents stale pending suggestions from being served after the user navigated away
- Tab-Tab flow for NextEdit already works (first Tab previews, second applies) — no changes needed

### 22. Enhanced Suffix Matching

Implemented:

- `stripSuffixOverlap` now falls back to `longestNormalizedOverlap` when exact match fails
- normalized matching trims whitespace from both ends, catching cases like suggestion ending with `)\n` vs suffix starting with `  )\n`

## Still To Do

### 1. Future Enhancements

- LSP fallback for non-PSI languages (JetBrains LSP API available since 2025.2)
- terminal inline completion (2025.3 feature)
- N-gram lookup / speculative decoding hints (server-side optimization)
- confidence scoring tuning and API logprob integration
- correctness filter coverage for non-Python languages

## Testing Roadmap

Covered now:

- progressive trimming while typing at the caret
- off-caret NextEdit offset shifting (edits before, after, overlapping the suggestion region)
- trigger suppression in comments, strings, imports, generated files, and low-signal whitespace
- alternative suggestion cycling state
- shortcut normalization and acceptance shortcut options
- budget-constrained context packing
- incomplete header skip in PSI validation
- token continuation detection prevents spurious newlines
- partial identifier completions inside function calls
- keyword argument completions (`name=value` patterns)
- leading suffix bracket duplication stripping

Still needed:

- proximity cache hit-rate verification under typing scenarios
- streaming delivery end-to-end testing
- partial acceptance (word/line) integration testing
- more manual IDE verification across multiple languages and keyboard layouts

## Suggested Next Step

All planned phases are complete. The highest-value next steps are:

1. manual IDE verification across Python, Kotlin, JavaScript, and Go
2. enable `debugMetricsLogging` and measure latency/cache hit rates in real usage
3. tune `minConfidenceScore` based on observed accept/reject patterns
4. explore LSP fallback for languages with weak PSI support
