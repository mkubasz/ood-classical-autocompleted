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

## Still To Do

### 1. Progressive Updates For Off-Caret `NextEdit`

Need to implement:

- trimming and keeping suggestions whose insertion offset differs from the caret
- smoother `NextEdit` behavior without full dismiss-and-rerequest loops
- tests for edit spans away from the caret

### 2. Stronger Multiline Behavior

Need to implement:

- more precise indentation preservation for multiline suggestions
- better blank-line handling
- more natural multiline inlay alignment near the insertion point
- focused tests for multiline rendering and multiline acceptance

### 3. Caching And Latency

Need to implement:

- a small short-lived cache keyed by nearby prefix/suffix context
- reuse when context changes only slightly
- lightweight debug logging for cache hits and misses
- tests for cache behavior and stale-response rejection

### 4. Acceptance Flow Polish

Need to evaluate:

- double-`Tab` or repeated-accept flows where they help `NextEdit`
- whether alternative suggestion generation should be widened beyond the current multi-result path
- additional stale ghost-text cleanup edge cases during fast caret or navigation changes

## Testing Roadmap

Covered now:

- progressive trimming while typing at the caret
- trigger suppression in comments, strings, imports, generated files, and low-signal whitespace
- alternative suggestion cycling state
- shortcut normalization and acceptance shortcut options

Still needed:

- multiline placement and indentation
- acceptance after multiline and `NextEdit` suggestions
- cache behavior
- more manual IDE verification across multiple languages and keyboard layouts

## Suggested Next Step

The highest-value next slice is:

1. off-caret `NextEdit` progressive updates
2. multiline rendering and acceptance polish
3. short-lived caching and latency instrumentation
