# Comment Companion

> **Status: MVP (v0.1.0)** — single-file, function-level editing for GoLand 2026.2.x.
> Cross-file edits and Ayane integration are not implemented yet. See [Roadmap](#roadmap).

**English** | [简体中文](README.zh-CN.md)

A comment-driven coding companion for Go. Write `// ai：...` above a function and the AI rewrites the body — no chat window, all communication happens in comments. A successful round ends by turning the instruction comment into an ordinary Go doc comment.

Part of the [Ayane](https://github.com/Kuaghara/Ayane) project, usable standalone.

```go
// BEFORE
// Add returns the sum of two ints.
// ai：implement addition, int only
func Add(a, b int) int {
	return 0
}

// AFTER (saved ~2s later)
// Add returns the sum of two ints.
func Add(a, b int) int {
	return a + b
}
```

**Features** · [Install](#install) · [Usage](#usage) · [Configuration](#configuration) · [How it works](#how-it-works) · [Roadmap](#roadmap) · [Docs](#documentation)

---

## Features

- **Comment-driven, no chat UI** — `ai：` instructions and `ai？` questions live in your code; the editing happens where you're already looking.
- **Automatic, idle-triggered** — scans every 2 seconds, processes only changed files, and only when its queue is empty.
- **PSI-precise** — anchors instructions to the enclosing Go function/method and edits only that function body with a minimal diff.
- **Verified before it stays** — runs `go build ./...` (or GoLand daemon diagnostics) and repairs compile errors for up to 5 rounds / 60s.
- **Safe failure** — reverse-plan rollback, crash journal, one-command "undo this round".
- **Config from DSH by default** — reuses the model configuration in `~/.dsh`; no API key to copy. Plugin-local BYOK is also supported.
- **Three protocols** — OpenAI Chat Completions, OpenAI Responses, Anthropic Messages.
- **Ask-back flow** — if the model needs clarification it writes a trailing `ai？` comment; answering the original `ai：` comment continues the round and cleans the question up.
- **Auto-imports** — if the generated body needs a package, the plugin adds the import via PSI.
- **Tool window** — dashboard (run/pause, status, pending), details (current round, queue, history), settings; plus a status-bar entry.

---

## Install

1. Download `comment-companion-0.1.0.zip` from [Releases](https://github.com/Kuaghara/comment-companion/releases).
2. GoLand: `Settings | Plugins | Install Plugin from Disk…`, select the zip, restart.
3. Default: the plugin reads model config from `~/.dsh` (DeepSeek Harness). To use your own provider, set **Engine source = Plugin** in the plugin settings.

Requirements: GoLand 2026.2.x (developed against 2026.2.3, build `GO-262.10968.67`), macOS (Windows/Linux untested).

---

## Usage

1. Put a comment above a Go function:

   ```go
   // Add returns the sum of two ints.
   // ai：implement addition, int only
   func Add(a, b int) int {
   	return 0
   }
   ```

2. Save (IntelliJ auto-saves). Within ~2 seconds the plugin scans, generates, verifies and normalizes the comment.
3. Open the **Comment Companion** tool window to watch the queue, the current round and pending items.
4. If the AI asks you something (`ai？`), edit the original `ai：` comment to answer — the next round picks it up.

---

## Configuration

| What | Where |
|---|---|
| Engine source | `DSH` (default) or `Plugin` — plugin settings → **Engine source** |
| Model config (DSH) | `~/.dsh/profiles/<profile>/cordis.patch.yml` + `~/.dsh/.credentials.yaml` |
| Model config (Plugin) | settings → protocol / Base URL / model / API key (stored in PasswordSafe) |
| Verification | `go build ./...` (default) or GoLand daemon diagnostics |
| Scan interval / retries / trust level | settings page |

DSH is read-only: the plugin parses the active profile for provider, protocol, base URL and model, and resolves the API key from the credentials file.

---

## How it works

```
  2s tick (idle only)
        │
        ▼
  scan changed Go files ──► text scan for `ai：` ──► serial queue
                                                       │
                                        PSI anchor ◄───┘
                                             │
                                   snapshot (version + hashes)
                                             │
                                     engine.generate()
                                             │
                        apply body ──► go build ──► repair loop
                                             │
                             normalize comment (closing step)
                                             │
                                      ledger + panel
```

Design notes: [docs/architecture/structure-design.md](docs/architecture/structure-design.md).

---

## Build from source

```bash
./scripts/bootstrap-toolchain.sh   # once: offline Kotlin + JUnit toolchain
./scripts/test.sh                  # 79 unit tests + architecture check
./scripts/build.sh                 # -> build/comment-companion-0.1.0.zip
./scripts/dev-sandbox.sh           # isolated GoLand sandbox
```

The sandbox uses a deterministic fake engine by default. `SANDBOX_REAL_ENGINE=1 ./scripts/dev-sandbox.sh` uses the real model configured in DSH (consumes API quota).

No system JDK / Gradle / Maven required: the scripts use the JBR bundled with GoLand.

---

## Comment protocol

| Form | Meaning | Position rule |
|---|---|---|
| `// ai：...` / `// ai: ...` | user instruction | must start a line inside a `//` comment block; lines above it are the method description (preserved), lines below are instruction continuation |
| `// ai？...` / `// ai? ...` | AI question | same position rules |
| `/* ... */` | instruction | marker must start the first non-empty content line |

- Case-insensitive marker; full-width or half-width colon/question mark; spaces are optional.
- After normalization the marker **must be gone** — that is the structural guarantee against re-triggering.
- Comment lines outside the instruction segment (the method description) are preserved verbatim.

---

## Project layout

```
comment-companion/
├── src/main/kotlin/dev/commentcompanion/
│   ├── core/      # pure Kotlin: protocol, model, plan, queue, policy, prompt
│   ├── ports/     # capability interfaces (workspace, engine, verifier, settings, ledger)
│   ├── app/       # orchestrator: scan cycle, round executor, controller
│   ├── ide/       # GoLand adapters: PSI, inlays, panel, settings, verifiers
│   ├── engine/    # HTTP engines (OpenAI/Anthropic) + fake engine
│   └── plugin/    # legacy sandbox entry points
├── scripts/       # offline build / test / sandbox scripts
├── tools/         # compile-time platform probe (not packaged)
└── docs/          # design, architecture, evidence, release notes
```

---

## Roadmap

- [x] Single-file MVP: scan → generate → verify → normalize
- [x] DSH config, three protocols, tool window, question flow, imports
- [ ] Cross-file context (read related files for better generation)
- [ ] Same-package declarations (struct fields, helpers) in other files
- [ ] Signature changes + call-site updates (with confirmation)
- [ ] Ayane integration (`EnginePort` backend swap)
- [ ] Trust-level UI polish, panel history filters

---

## Documentation

- Design: [docs/comment-companion-mvp-design.md](docs/comment-companion-mvp-design.md)
- Architecture: [docs/architecture/structure-design.md](docs/architecture/structure-design.md)
- Acceptance: [docs/acceptance-mvp.md](docs/acceptance-mvp.md)
- Evidence: [docs/evidence/](docs/evidence/) — GoLand environment, real-model e2e, imports, daemon verifier, question flow, prior-art reviews
- Release notes: [docs/releases/v0.1.0.md](docs/releases/v0.1.0.md)

---

## Known limitations

- Single-file MVP: no cross-file edits; function signature changes are rejected.
- Verified in an isolated sandbox; not yet installed into a daily GoLand instance.
- Real-model runs cover a small sample; complex code and large files need real-world testing.
- macOS only so far.

---

## License

[MIT](LICENSE) © 2026 Kuaghara
