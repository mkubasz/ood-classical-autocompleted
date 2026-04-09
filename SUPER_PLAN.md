# Super Plan

## Goal

Finish the refactor so the completion system is fully language-owned where it should be, with generic fallback only for unsupported languages.

Current state:

- Provider architecture is already migrated to the new `completion` pipeline.
- Java, Python, and Go now own retrieval profiles, correctness profiles, and baseline language context extraction.
- The build is green.

What remains is mostly depth work, not rescue work.

## Priority 1: Add Kotlin As A First-Class Language Module

This is the highest-value missing piece.

Why:

- Kotlin is a primary JVM language in this plugin context.
- `.kt` and `.kts` files still fall back to generic support.
- We already fixed Kotlin regressions incrementally, which is a signal that Kotlin should stop living in fallback heuristics.

Deliverables:

- Add `completion/languages/kotlin/KotlinLanguageSupport.kt`.
- Register Kotlin in `LanguageSupportRegistry.default()`.
- Give Kotlin its own:
  - retrieval profile
  - correctness profile
  - inline context extraction
  - header/declaration continuation behavior
  - type/base-list handling
  - receiver/member extraction

Kotlin-specific coverage should include:

- `data class`
- `sealed class`
- `interface`
- `object`
- `companion object`
- `fun`
- constructor parameters
- supertype lists
- extension receivers
- property declarations

Acceptance criteria:

- Kotlin no longer resolves through generic fallback for normal `.kt` files.
- Existing Kotlin completion regressions stay green.
- Retrieval chunks for Kotlin are structure-aware.

## Priority 2: Replace Shared PSI/LSP Resolution With Per-Language Resolvers

Current issue:

- `InlineContextResolver`, `PsiInlineContextBuilder`, and `LspInlineContextProvider` still act as shared language orchestration.
- Language modules own contracts, but not enough of the underlying extraction pipeline.

Target direction:

- Introduce language-owned resolver components behind `LanguageSupport`.
- Keep generic fallback only as the last resort.

Proposed split:

- `LanguageContextResolver`
- `LanguageSymbolExtractor`
- `LanguageVisibleScopeExtractor`
- `LanguageDependencyContextExtractor`
- `LanguageCorrectnessPolicy`
- `LanguageRetrievalChunker`

Then:

- Java module uses Java PSI-first extraction.
- Python module uses PSI/LSP where available, then Python heuristics.
- Go module uses LSP/structure-aware extraction where available, then Go heuristics.
- Kotlin module uses Kotlin PSI-first extraction.

Acceptance criteria:

- Shared editor-level classes stop containing language-specific branching.
- Language modules become the primary owners of symbol extraction.

## Priority 3: Move Remaining Shared Language Logic Out Of `editor/autocomplete`

Current issue:

- Some of the new architecture still depends on old package placement.

Main leftovers:

- `editor/autocomplete/WorkspaceRetrievalService.kt`
- `editor/autocomplete/InlineCorrectnessFilter.kt`
- `editor/autocomplete/HeuristicContextFallback.kt`
- parts of `InlineContextResolver.kt`

Target direction:

- Move retrieval/correctness/fallback code under `completion/languages/common` or `completion/pipeline`.
- Leave `editor/autocomplete` focused on IDE lifecycle, editor state, preview, and apply behavior.

Acceptance criteria:

- `editor/autocomplete` is mostly editor integration.
- language/runtime logic lives under `completion`.

## Priority 4: Make Retrieval Truly Language-Aware

Current issue:

- Retrieval is still mostly lexical-first.
- Language modules now provide chunking profiles, but not deep semantic structure.

Next improvements:

- PSI/LSP-driven chunk boundaries
- symbol-aware chunk extraction
- import/library-aware dependency context
- language-aware reranking boosts
- better same-symbol and same-type matching
- optional semantic retrieval backend behind the current interface

Important constraint:

- Keep lexical retrieval as the safe default until offline evaluation proves the semantic path is better.

Acceptance criteria:

- Retrieved chunks align with real symbols instead of mostly regex windows.
- Ranking improves for current symbol, receiver type, imports, and nearby edited files.

## Priority 5: Move Correctness From Generic Validation To Language Policies

Current issue:

- The filter now consumes language-owned profiles, but the PSI validation engine is still shared.

Next improvements:

- Language-specific bypass rules
- language-specific introduced-identifier detection
- language-specific unresolved-reference tolerances
- language-specific structural validators
- language-specific declaration-header continuation logic

Stretch goal:

- Allow some languages to provide their own correctness checker implementation instead of only a profile.

Acceptance criteria:

- Correctness behavior differs by policy, not by hardcoded generic branching.
- Kotlin/Java/Python/Go declaration-header completion behaves predictably.

## Priority 6: Language-Owned Post-Apply Actions

Current issue:

- Post-apply behavior is still thin and not strongly language-specific.

Next improvements:

- imports and using-statements
- package/module fixes
- short-name to fully-qualified import resolution
- optional reformat/reorganize hooks
- language-specific next-edit follow-up actions

Likely integration points:

- `NextEditImportActionResolver`
- next-edit apply pipeline

Acceptance criteria:

- Accepting a completion can trigger the right import/fixup path per language.

## Priority 7: Turn Package Boundaries Into Real Modules

Current issue:

- The architecture is modular by package, not yet by Gradle module.

Target split:

- `completion-domain`
- `completion-pipeline`
- `completion-providers`
- `completion-languages-common`
- `completion-language-java`
- `completion-language-python`
- `completion-language-go`
- `completion-language-kotlin`
- `editor-integration`

Why:

- Better dependency boundaries
- Easier testing
- Easier future provider/language additions
- Less accidental coupling

Acceptance criteria:

- Language modules depend on shared contracts, not on editor orchestration internals.

## Priority 8: Evaluation And Telemetry Tightening

Needed before more aggressive optimization:

- compare old vs new retrieval quality
- compare old vs new inline correctness pass rate
- compare latency by stage and by language
- measure acceptance and rejection by language/module/provider

Add or tighten:

- contributor-level metrics
- language module source metrics
- retrieval hit quality metrics
- correctness rejection reason metrics

Acceptance criteria:

- architecture decisions can be judged with offline and runtime data, not intuition.

## Suggested Execution Order

1. Add `KotlinLanguageSupport`.
2. Move shared retrieval/correctness/fallback logic into `completion`.
3. Introduce per-language resolver interfaces.
4. Replace shared PSI/LSP orchestration with language-owned implementations.
5. Upgrade retrieval to PSI/LSP-aware chunking.
6. Add language-owned post-apply actions.
7. Split package boundaries into real Gradle modules.

## Definition Of Done

The refactor is truly complete when:

- Kotlin, Java, Python, and Go are all first-class language modules.
- Generic fallback is only used for unsupported languages.
- Language modules own symbol extraction, retrieval chunking, and correctness behavior.
- `editor/autocomplete` mostly contains editor integration only.
- Retrieval and correctness quality are backed by tests and metrics.
- Module boundaries are enforceable, not just conventional.
