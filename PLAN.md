# OOD Autocomplete Plan

## Current Scope

The plugin is now scoped to inline code completion only.

Current supported areas:

- autocomplete triggering and dismissal
- `Tab` accept and `Escape` dismiss
- multiline ghost-text rendering
- `NextEdit` and fill-in-the-middle providers
- provider configuration in settings
- status bar state for autocomplete

`./gradlew test` is passing.

## Main Priorities

### 1. Progressive Suggestion Updates

Goal: make completion feel stable while the user keeps typing.

- trim the active suggestion when typed text matches its prefix
- keep the existing suggestion alive until the user diverges
- avoid full dismiss-and-rerequest cycles on every matching keystroke

Acceptance:

- typing through a suggestion feels incremental
- flicker is noticeably reduced
- `Tab` accept can be followed by immediate new suggestions without recovery steps

### 2. Better Trigger Heuristics

Goal: request completions in high-signal moments and suppress them in low-value ones.

- be more eager after newline, indent, and structural openings
- be more conservative in comments, strings, imports, and noisy edit regions
- suppress requests in oversized or generated files

Acceptance:

- completions appear more consistently during normal coding
- low-value suggestions appear less often

### 3. Stronger Multiline Behavior

Goal: make multiline suggestions read like real code before acceptance.

- preserve indentation more precisely
- improve blank-line handling
- keep block inlays aligned naturally with the insertion point

Acceptance:

- multiline previews are readable
- accepted multiline completions land with correct indentation

### 4. Alternative Suggestions

Goal: allow quick fallback when the first completion is wrong.

- keep a small ranked set of recent suggestions
- add cycle-next and cycle-previous actions
- preserve stable rendering while cycling

Acceptance:

- the user can switch suggestions without forcing a brand new request
- cycling does not corrupt the active inlay state

### 5. Acceptance And Navigation

Goal: make accept/dismiss behavior feel closer to native editor completion.

- optionally support accept on `Right` or `End`
- evaluate double-`Tab` or repeated-accept flows where useful
- keep rejection and caret-move behavior predictable

Acceptance:

- accept flows feel fast and deliberate
- keyboard interactions do not leave stale ghost text behind

### 6. Caching And Latency

Goal: reduce needless provider calls and shorten time-to-hint.

- add a small short-lived cache keyed by nearby prefix/suffix context
- reuse results when the context only changes slightly
- expose lightweight debug logging for cache hits and misses

Acceptance:

- repeated nearby edits feel faster
- network-backed completion is less jittery

## Testing Roadmap

Add tests for:

- progressive suggestion trimming while typing
- trigger suppression in comments, strings, and imports
- multiline placement and indentation
- alternative suggestion cycling
- acceptance after multiline and `NextEdit` suggestions
- cache behavior and stale-response rejection

Manual verification should cover:

- typing, backspace, and newline triggers
- `Tab` accept and `Escape` dismiss
- multiline preview rendering
- `NextEdit` insertion inside editable spans
- provider switching in settings

## Suggested Next Step

The highest-value next slice is:

1. progressive suggestion updates while typing
2. better trigger heuristics
3. alternative suggestion cycling

That order improves the daily typing experience first, then reduces noise, then gives a fallback when the top result is wrong.
