# Manual Verification Matrix

Use this checklist after substantial autocomplete changes. Run it against a 2025.2+ IDE build with the plugin installed from the current workspace.

## Setup

- Enable `debugMetricsLogging` when collecting latency and cache observations.
- For LSP fallback checks, use a file type that is handled by an active JetBrains LSP integration rather than rich native PSI.
- For retrieval checks, enable `Enable local workspace retrieval` and keep at least one related symbol in a different project file.

## Matrix

| Area | Language / Surface | Scenario | Expected |
| --- | --- | --- | --- |
| Inline ghost text | Python | Type inside a method body after `self.` | Suggestion stays member-focused and can be accepted with `Tab`, next-word, and next-line |
| Inline ghost text | Kotlin | Type inside a function body with local variables in scope | Suggestion uses local names and does not duplicate the suffix |
| Inline ghost text | JavaScript | Type after `client.` or inside an object method | Suggestion remains single-line when appropriate and respects suffix overlap |
| Inline ghost text | Go | Type in a function body and after a struct receiver | Suggestion uses receiver members and does not degrade to unrelated boilerplate |
| Streaming | Python / Kotlin | Trigger a streamed suggestion and keep typing slowly | Suggestion waits for dwell before showing and stays accept/reject-safe while chunks arrive |
| Partial accept | Python / JavaScript | Accept next word, then next line | Accepted text inserts correctly and the remainder stays available as the next pending suggestion |
| Alternative cycling | Kotlin / Go | Produce 2+ suggestions and cycle forward/backward | Current alternative changes without leaving stale ghost text behind |
| Next Edit preview | Python | First `Tab` previews, second `Tab` applies | Preview stays anchored to the editor and respects the configured preview line cap |
| Next Edit preview | Kotlin / JavaScript | Reject preview with `Esc` after first `Tab` | Preview disposes cleanly and no edit is applied |
| Next Edit post-apply | Java / Go | Apply a preview that introduces a missing import or a fully qualified Java type | Best-effort cleanup runs after apply: imports resolve when available, Java fully-qualified class names shorten, and the changed range is reformatted |
| LSP fallback | LSP-managed language | Disable PSI-only assumptions, enable `Enable LSP semantic context fallback`, trigger completion near a known symbol | Context uses active LSP server data when available and falls back cleanly when no running server is present |
| Local retrieval | Python / Kotlin / JavaScript | Enable retrieval and trigger completion using a symbol defined in another project file | Prompt grounding pulls related external snippets from other workspace files, not only the current file |
| Terminal | Shell | Enable terminal completion and type a command fragment in the integrated terminal | Terminal suggestions stay isolated from editor caches and do not leak file-only context |
| Keyboard layout | macOS US | Verify `Tab`, Right Arrow, End/Fn+Right, and alternative-cycle shortcuts | All configured accept/cycle actions fire correctly |
| Keyboard layout | Non-US layout | Repeat the same checks on at least one non-US layout such as Polish Programmer or German QWERTZ | Shortcut normalization still resolves correctly and no action silently stops working |

## Notes To Record

- IDE build and plugin build under test
- Language / file type used for each row
- Whether PSI, LSP, or heuristic context won
- Whether retrieval was enabled and which files were surfaced
- Any latency spikes, stale previews, or accept/reject mismatches
