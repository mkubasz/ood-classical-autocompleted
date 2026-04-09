# Cleanup Plan

This document describes the optional follow-up improvements that are useful after the current feature work, but are not blockers for starting manual testing or bug fixing.

## Scope

These items are follow-on quality work for the current branch:

- stronger retrieval beyond the current lexical weighted approach
- broader correctness filtering across more languages
- more end-to-end IDE tests instead of reflection-backed service tests
- cleanup/review of the large dirty worktree and preparing a clean commit

They should be treated as polish, hardening, and maintainability work, not as required functionality to declare the main roadmap slice implemented.

## 1. Stronger Retrieval

### Current State

The current retrieval path is implemented in `WorkspaceRetrievalService` and already does a useful first pass:

- lexical weighted matching
- camelCase and snake_case token splitting
- symbol-derived query terms
- file path matching
- per-file diversity in result selection
- short TTL caching

This is good enough for lightweight local grounding, but it is still a shallow lexical retriever.

### Main Gaps

- chunks are still line-window based rather than symbol-aware
- ranking is still mostly token overlap and path overlap
- there is no second-stage reranking
- there is no semantic retrieval path
- recall quality will degrade in larger workspaces with naming variation

### Follow-up Plan

1. Make chunking structural instead of only line-window based.
   - Prefer chunks anchored on detected definitions, classes, methods, imports, and top-level constants.
   - Keep the current line-window fallback only when no structure is found.

2. Split retrieval into two stages.
   - Stage 1: fast lexical recall over project files and symbol chunks.
   - Stage 2: rerank a small candidate set using richer signals such as symbol-type matches, receiver/member context, same-language preference, and proximity to the current module.

3. Add optional semantic retrieval behind a feature flag.
   - Start with embeddings over structural chunks, stored locally.
   - Keep lexical recall as the primary path and use semantic retrieval only as a supplement.
   - Do not make semantic retrieval part of the hot path by default.

4. Improve budget packing for retrieved context.
   - Reserve separate quotas for local symbols, same-directory files, and wider-project matches.
   - Deduplicate near-identical chunks before prompt packing.

5. Add offline evaluation for retrieval quality.
   - Extend the existing offline evaluation harness with retrieval-aware fixtures.
   - Measure hit rate for expected symbols/files, matched-ratio impact, and latency overhead.

### Exit Criteria

- retrieved chunks are mostly symbol-shaped rather than arbitrary line windows
- top results are more diverse and more relevant in medium-sized projects
- retrieval quality can be measured with repeatable offline fixtures
- semantic retrieval stays opt-in until it has a proven benefit

## 2. Broader Correctness Filtering Across More Languages

### Current State

The current inline preparation pipeline already has useful protection:

- normalization and boundary adjustment
- safety filtering
- PSI-based header validation
- confidence scoring / thresholding
- optional `InlineCorrectnessFilter`
- boilerplate filtering for low-signal suggestions

The current correctness filter is intentionally generic. It builds a temporary PSI file, counts syntax errors and unresolved references in the inserted region, and rejects obviously bad candidates. That is a good baseline, but it is not language-aware enough yet.

### Main Gaps

- unresolved-reference counting is too generic and may over-penalize valid language constructs
- language-specific syntax patterns are not modeled explicitly
- indentation/block-sensitive languages need stronger structural checks
- thresholds are global rather than language-family specific
- failure reasons are not specific enough for tuning

### Follow-up Plan

1. Introduce language-family correctness profiles.
   - Python: indentation, colon/header continuation, import alias patterns, common async/await shapes.
   - Kotlin/Java: braces, generics, constructor/super syntax, imports, member access chains.
   - JavaScript/TypeScript: braces, commas, async/await, object literal boundaries, import/export forms.
   - Go: braces, short variable declarations, import grouping, composite literals.

2. Split correctness checks into layers.
   - Layer 1: cheap structural checks such as bracket balancing, quote balancing, and indentation sanity.
   - Layer 2: PSI parse/error checks.
   - Layer 3: unresolved-reference checks with language-aware exemptions.

3. Make unresolved-reference checks more precise.
   - Ignore identifiers introduced by the candidate itself.
   - Treat parameters, local bindings, import aliases, and common built-ins differently by language.
   - Penalize only references that are both unresolved and relevant to the inserted region.

4. Add reason-coded metrics.
   - Record whether a rejection came from syntax, unresolved refs, structural rules, or timeout.
   - Use that data to tune thresholds instead of guessing.

5. Add focused regression suites by language.
   - Keep examples small and representative.
   - Cover false-positive cases as well as obvious failures.

### Exit Criteria

- correctness filtering rejects more clearly broken candidates without suppressing valid ones
- thresholds differ by language family where needed
- false positives are explainable through reason-coded metrics and tests

## 3. More End-to-End IDE Tests

### Current State

There is already useful service-level coverage, but `AutocompleteServiceInteractionTest` currently seeds service internals through reflection. That is fast, but it does not exercise the real IDE-facing path.

### Main Gaps

- tests bypass real action dispatch
- tests couple directly to private fields and inner classes
- next-edit preview/apply behavior is not validated through the action layer
- action enablement/update behavior is not covered realistically

### Follow-up Plan

1. Replace reflection-heavy setup with explicit test seams.
   - Add small internal test hooks or fixture builders in `AutocompleteService`.
   - Keep them narrow and package-scoped to avoid polluting production APIs.

2. Add action-level integration tests.
   - Exercise `AcceptCompletionAction`
   - Exercise `AcceptNextWordAction`
   - Exercise `AcceptNextLineAction`
   - Exercise `RejectCompletionAction`
   - Exercise `CycleNextSuggestionAction` and `CyclePreviousSuggestionAction`
   - Build real `AnActionEvent` instances with editor/project context instead of calling service methods directly

3. Add inline-provider integration tests.
   - Cover document changes, caret movement, pending suggestion state, and preview lifecycle through the editor/provider surface.
   - Keep network/model calls mocked or replaced with fake providers.

4. Add next-edit UI lifecycle tests where practical.
   - preview shown
   - preview dismissed
   - second-Tab apply path
   - editor disposal / document mutation cleanup

5. Keep only a minimal amount of reflection-based testing.
   - Use reflection only where IntelliJ test APIs make a cleaner seam impractical.
   - Do not let reflection-backed tests be the main confidence layer.

### Exit Criteria

- the main accept/reject/cycle actions are covered through real IDE action events
- service tests no longer depend on private field names for core behavior checks
- next-edit preview/apply behavior is exercised through realistic editor flows

## 4. Worktree Cleanup And Clean Commit Preparation

### Current State

The branch contains a large mixed worktree:

- provider and credential changes
- next-edit and streaming changes
- metrics and evaluation additions
- LSP and retrieval additions
- test expansion
- docs updates

That is workable during development, but it is expensive to review and risky to ship as one opaque change set.

### Follow-up Plan

1. Inventory the worktree carefully.
   - separate staged vs unstaged changes
   - identify partial files marked `MM`
   - identify new files that should be committed together
   - identify docs/test-only changes vs runtime changes

2. Rebuild cleanly before commit preparation.
   - run a clean rebuild to rule out stale packaging artifacts
   - verify the plugin jar/zip contains the newly split DTO classes and other new runtime files
   - re-run `./gradlew test --rerun-tasks`
   - re-run `./gradlew buildPlugin`

3. Group the changes into reviewable commit slices.
   - provider/settings/credentials foundation
   - inline and next-edit lifecycle fixes
   - metrics and offline evaluation
   - context resolution, LSP, and retrieval
   - tests and docs

4. Review for dead code and naming drift.
   - remove stale compatibility fields once migration is proven safe
   - remove no-longer-used helpers
   - make sure settings labels still match actual behavior
   - check for old comments or roadmap notes that no longer reflect the code

5. Prepare a release-quality summary.
   - user-visible behavior changes
   - new feature flags and defaults
   - known limitations
   - manual verification results

### Exit Criteria

- the worktree is understandable in coherent slices
- packaging is verified from a clean rebuild
- the final commit or PR summary explains the branch without requiring code archaeology

## Recommended Order

1. Run the manual verification matrix and collect real bugs first.
2. Fix any runtime regressions from that pass.
3. Clean up the worktree and prepare coherent commits.
4. Then take on the optional improvements in this order:
   - end-to-end IDE tests
   - broader correctness filtering
   - stronger retrieval

This order keeps correctness and maintainability ahead of extra model-context sophistication.
