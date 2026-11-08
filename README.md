# Comment Companion (GoLand plugin)

**English** | [简体中文](README.zh-CN.md)

A comment-driven coding companion for Go. Write `// ai：...` above a function, and the AI rewrites the function body — no chat window, all communication happens in comments. After a successful round the instruction comment is normalized into an ordinary Go doc comment.

Originally built as the coding-companion body for [Ayane](docs/comment-companion-mvp-design.md); the plugin is also usable standalone.

---

## Status

**v0.1.0 — MVP complete.** Single-file, function-body editing for GoLand 2026.2.x.

| Area | Status |
|---|---|
| Comment protocol (`ai：` / `ai？`, full/half-width, block comments, continuation, CRLF) | ✅ 79 tests |
| 2s idle scan + serial queue (coordinates + fingerprint) | ✅ |
| PSI anchoring (comment → enclosing func/method, 0–1 blank lines) | ✅ |
| Minimal-diff write-back (body, then closing doc-comment normalization) | ✅ |
| Engines: OpenAI Chat Completions / OpenAI Responses / Anthropic Messages | ✅ |
| **Config read from DSH by default** (`~/.dsh` profile + credentials) | ✅ real model verified |
| Verification: `go build ./...` (default) or GoLand daemon diagnostics | ✅ both modes |
| Repair loop (≤5 rounds / 60s) + reverse-plan rollback + crash journal | ✅ |
| `ai？` question flow: trailing comment → panel/notification → answer → regenerate → cleanup | ✅ |
| Auto-imports suggested by the model (PSI `addImport`) | ✅ real model verified |
| Tool window: dashboard (run/pause + status + pending) / details / settings | ✅ |
| Status bar, persistent settings, persistent round ledger, trust levels | ✅ |

Details and evidence: [docs/acceptance-mvp.md](docs/acceptance-mvp.md) · [docs/evidence/](docs/evidence/)

---

## Install

1. Download `comment-companion-0.1.0.zip` from [Releases](https://github.com/Kuaghara/comment-companion/releases).
2. GoLand: `Settings | Plugins | Install Plugin from Disk…`, pick the zip, restart.
3. By default the plugin reads model config from `~/.dsh` (DeepSeek Harness). To configure inside the plugin instead, set **Engine source = Plugin** in the plugin settings and fill in protocol / base URL / model / API key.

Requirements: GoLand 2026.2.x (developed against 2026.2.3, build `GO-262.10968.67`), macOS (not yet tested on Windows/Linux).

---

## Usage

```go
// Add returns the sum of two ints.
// ai：implement addition, int only
func Add(a, b int) int {
	return 0
}
```

Save (IntelliJ auto-saves). Within ~2 seconds the plugin scans, anchors the comment to `Add`, asks the model for a body, writes it with a minimal diff, runs `go build ./...`, repairs if needed, and finally normalizes the comment:

```go
// Add returns the sum of two ints.
func Add(a, b int) int {
	return a + b
}
```

Open the **Comment Companion** tool window to see the queue, the active round, pending items and settings.

---

## Comment protocol

| Form | Meaning | Position rule |
|---|---|---|
| `// ai：...` / `// ai: ...` | user instruction | inside a `//` comment block, the marker must start a line; lines above it are the method description (preserved), lines below are instruction continuation |
| `// ai？...` / `// ai? ...` | AI question | same position rules |
| `/* ... */` block comment | instruction | marker must start the first non-empty content line |

- Case-insensitive marker; full-width or half-width colon/question mark; optional spaces around punctuation and after `//`.
- After normalization the marker **must be gone** — this is the structural guarantee against re-triggering (Aider's `AI?` residue and pi-watcher's over-eager deletion both stem from marker cleanup being unreliable).
- Comment lines outside the instruction segment (method description) are preserved verbatim.

---

## Build from source

```bash
./scripts/bootstrap-toolchain.sh   # once: download offline Kotlin/JUnit toolchain (~70 MB)
./scripts/test.sh                  # 79 unit tests + architecture check
./scripts/build.sh                 # -> build/comment-companion-0.1.0.zip
./scripts/dev-sandbox.sh           # isolated GoLand sandbox (does not touch your daily config)
```

The sandbox uses a fake engine by default. Set `SANDBOX_REAL_ENGINE=1 ./scripts/dev-sandbox.sh` to use the real model configured in DSH (consumes API quota).

No system JDK/Gradle/Maven is required: the scripts use the JBR bundled with GoLand and download Kotlin from Maven Central on first run.

---

## Project layout

```
comment-companion/
├── src/main/kotlin/dev/commentcompanion/
│   ├── core/          # pure Kotlin domain (protocol/model/plan/queue/policy/prompt)
│   ├── ports/         # capability interfaces (workspace/engine/verifier/settings/ledger…)
│   ├── app/           # orchestrator (scan cycle, round executor, controller)
│   ├── ide/           # GoLand adapters (PSI, inlays, panel, settings, verifier)
│   ├── engine/        # HTTP engines (OpenAI / Anthropic), fake engine
│   └── plugin/        # legacy entry points used by the sandbox verifier
├── scripts/           # offline build/test/sandbox scripts (bash 3.2 compatible)
├── tools/             # compile-time platform probe (not packaged)
├── docs/              # design, architecture, evidence, release notes
└── build.gradle.kts   # future standard build (not used locally)
```

---

## Documentation

- Design: [docs/comment-companion-mvp-design.md](docs/comment-companion-mvp-design.md)
- Architecture: [docs/architecture/structure-design.md](docs/architecture/structure-design.md)
- Acceptance: [docs/acceptance-mvp.md](docs/acceptance-mvp.md)
- Evidence: [docs/evidence/](docs/evidence/) — GoLand environment, real-model e2e, imports, daemon verifier, question flow, prior-art reviews
- Release notes: [docs/releases/v0.1.0.md](docs/releases/v0.1.0.md)

---

## Known limitations

- Single-file MVP: no cross-file edits; function signature changes are explicitly rejected.
- Ayane integration is not done yet (`EnginePort` is the seam for it).
- Verified in an isolated sandbox only; not yet installed into a daily GoLand instance.
- Real-model runs cover a small sample (simple int/string functions); complex code, large files and unusual outputs need real-world testing.
- macOS only so far; Windows/Linux untested.

---

## License

[MIT](LICENSE) © 2026 Kuaghara
