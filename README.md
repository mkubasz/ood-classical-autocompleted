# OOD Autocomplete

![Build](https://github.com/mkubasz/ood-classical-autocompleted/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

<!-- Plugin description -->
OOD Autocomplete is an IntelliJ plugin focused only on inline code completion.

It provides:

- inline ghost-text completion
- multiline completion previews
- `NextEdit` and fill-in-the-middle providers
- keyboard-driven accept and dismiss flows

The plugin is intentionally limited to inline completion and next-edit workflows.
<!-- Plugin description end -->

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "ood-classical-autocompleted"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/mkubasz/ood-classical-autocompleted/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

## Offline Evaluation

The repo now includes a small offline inline-evaluation harness for fixed caret-position datasets.

Case file format:

```json
{
  "version": 1,
  "cases": [
    {
      "id": "kotlin-println",
      "language": "kotlin",
      "filePath": "src/App.kt",
      "document": "fun main() {\n    pri<|cursor|>\n}",
      "expected": "ntln(\"hi\")"
    }
  ]
}
```

Replay evaluation:

```bash
./gradlew runOfflineInlineEvaluation \
  -Pcases=/abs/path/cases.json \
  -PevaluationProvider=replay \
  -Ppredictions=/abs/path/predictions.json \
  -PevaluationOutput=/abs/path/report.json
```

The report includes exact-match rate, mean matched-ratio, perfect-line rate, and latency percentiles.

## Manual Verification

The current manual IDE verification checklist lives in [MANUAL_VERIFICATION.md](MANUAL_VERIFICATION.md).

It covers:

- inline ghost text across Python, Kotlin, JavaScript, and Go
- streaming, partial accept, cycling, and Next Edit preview/apply flows
- next-edit post-apply cleanup for imports, Java type shortening, and range reformatting
- LSP semantic fallback checks for LSP-managed languages
- local workspace retrieval checks
- terminal isolation and non-US keyboard layout verification


[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation
