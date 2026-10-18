# pi-watcher 源码级证据审查

> 审查对象：GitHub `vedang/pi-watcher`（https://github.com/vedang/pi-watcher）
> 快照：本地 `git clone --depth 1`，默认分支 `main`，HEAD = `501fa80`（2026-07-13，`fix: upgrade watcher to pi 0.80.6`）
> 审查日期：2026-10-18
> 审查方式：逐文件通读全部 `src/`、`__tests__/`、`monitors/`、配置；`grep` 交叉验证；在本机安装依赖后运行单测/类型检查/lint；另写临时 vitest/node 探针复现边界行为（探针未写入仓库）。

证据等级约定：

- **[代码]**：直接读到源码，给出 `file:line`。
- **[运行]**：在本机实际执行（测试/探针）得到的结果。
- **[推断]**：由代码与 pi-coding-agent 类型声明推导，未跑真实 agent 会话。
- **[未核实]**：本环境无法验证。

---

## ① 结论摘要（10 条）

1. **仓库真实存在**：`vedang/pi-watcher`，默认分支 `main`，主语言 TypeScript（约 2,200 行 `src/` + 约 1,300 行测试），HEAD `501fa80`，共 27 个提交、无 tag、无 GitHub Actions（仓库树里没有 `.github/`）。许可为 WTFPL（GitHub API 归类为 "Other"）。**[代码+API]**
2. **标记语法的精确规则**：正则 `(^|\s)(AI(?:[!?]|\.))(?=$|[^A-Za-z0-9_])`，`gi` 全局+忽略大小写；标记必须位于被识别为行注释的文本中，且只接受**注释文本开头**或**注释文本结尾**两种位置，注释中间出现不算。`;` 注释只在“该行前面全是空白”时才被识别，所以 `foo ; AI?` 不触发（对应 open PR #2）。**[代码+运行]**
3. **架构不是编辑器插件**：它是 pi-coding-agent 的**进程内扩展** + `chokidar` 文件监听。文件保存（任何 FS 变更）→ debounce → 解析 → `pi.sendUserMessage(prompt)` 注入当前会话触发一个 turn；没有 spawn 子进程、没有 stdin/stdout 协议、没有 socket。**[代码]**
4. **防回环不靠 mtime**：没有任何 mtime/“忽略下一事件”机制，靠 `sha256(相对路径 + "\n" + 整块连续注释小写化)` 的**意图账本** + 派发中的 `inFlight` 集合 + 默认**删除触发注释**。账本只在内存里（`appendEntry` 只写不读），上限 500，进程重启即丢失。**[代码+运行]**
5. **意图哈希覆盖“整块连续注释”而非单行标记**：agent 或用户只要改动同一注释块里任何一行注释，ID 就变化，会再次派发。探针实测：在已处理标记旁新增一行 `// new note` 后不再被抑制。**[运行]**
6. **清理逻辑会在 `agent_settled` 后无条件执行**：不判断 agent 是否成功、是否真的完成了修改；默认删除触发注释所在连续注释块中的所有“独占一行”的注释行——**会连带删除同一注释块中用户的普通注释**（探针复现）。行内标记只剥离注释、保留代码。写入失败被静默吞掉，但账本仍视为已处理。**[代码+运行]**
7. **队列是“忙时排队、settle 时派发”**：`ctx.isIdle() && !ctx.hasPendingMessages()` 才派发；期间用户再保存则入队；同一 debounce 窗口内对同一文件的多次保存会合并。但队列存在**乱序竞态**：已有一个批次在排队、pi 恰好空闲时，新批次会直接插队派发（探针复现）。无超时、无重试、无队列上限。**[代码+运行]**
8. **上下文 payload**：每个标记生成 `path:line action=...` + 带行号和 `█` 标记的 fenced 代码窗口（默认前后各 12 行，可配 `contextLines`）；整段 prompt 上限 60000 字节，超限降级为“逐标记摘要”并做 UTF-8 安全截断；`AI.` 锚点会附加到下一次 actionable 批次。**[代码+运行]**
9. **忽略/筛选**：13 条内置 glob（`.git/.jj/.pi/.agents/node_modules/dist/build/.cache/coverage/.DS_Store/*.swp/*.swo/*~`）+ 配置 `ignore` + **仅 cwd 根目录的 `.gitignore`** + `include` + roots 必须落在 cwd 内 + 跳过符号链接/超过 `maxFileBytes`(1 MiB)/非 UTF-8 文本。没有内置排除 `vendor/target/.venv/.idea/__pycache__`（同仓库的 Claude 移植版反而有）。**[代码]**
10. **质量状态**：6 个 vitest 文件、44 个测试，本机全部通过；`tsc --noEmit` exit 0；Biome 检查 19 个文件无问题。但**没有 CI**，`integration/llm` 测试目录不存在（配置 `passWithNoTests`），没有覆盖 agent 失败/超时/队列乱序/清理误删等风险点。**[运行+代码]**

---

## ② 逐项证据

### 2.1 标记语法的精确匹配规则

**结论**：`AI!` = edit、`AI?` = ask、`AI.` = context；大小写不敏感；必须出现在 `#`、`//`、`--` 行注释或“行首分号注释”中；标记只允许在注释文本的**开头**或**结尾**；前后要有边界（前面是空白/注释文本开头，后面是空白/标点/行尾，不能紧跟字母数字下划线）。

**证据 1：正则原文** — `src/parser.ts:34-39`

```ts
const DEFAULT_MARKER = "AI";

function buildMarkerRegex(marker: string): RegExp {
  const escaped = marker.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(`(^|\\s)(${escaped}(?:[!?]|\\.))(?=$|[^A-Za-z0-9_])`, "gi");
}
```

即最终正则：`(^|\s)(AI(?:[!?]|\.))(?=$|[^A-Za-z0-9_])`，flags `gi`。`marker` 可配置（默认 `AI`，`src/index.ts:109`），后缀只支持 `!` `?` `.` 三种。

**证据 2：行注释识别** — `src/parser.ts:60-119`

```ts
export function findLineComment(line: string): ParsedCommentLine | null {
  let quote: "'" | '"' | "`" | null = null;
  let escaped = false;
  ...
    if (char === "#") {
      return makeParsedCommentLine("#", i, line.slice(i + 1));
    }
    if (char === "/" && line[i + 1] === "/") {
      return makeParsedCommentLine("//", i, line.slice(i + 2));
    }
    if (char === "-" && line[i + 1] === "-") {
      return makeParsedCommentLine("--", i, line.slice(i + 2));
    }
    if (char === ";") {
      const prefixStart = i;
      let j = i;
      while (j < line.length && line[j] === ";") { j += 1; }
      const preceding = line.slice(0, prefixStart);
      if (preceding.trim() !== "") { continue; }   // 分号必须是“行首注释”
      return makeParsedCommentLine(line.slice(i, j), i, line.slice(j));
    }
```

三点关键：
- 会跟踪 `'` `"` `` ` `` 三种引号与反斜杠转义，所以字符串里的 `// AI!` 不匹配（`src/__tests__/parser.test.ts:80-115` 有回归测试）。
- **不识别块注释 `/* ... */`**：`/* AI! */` 实测 0 个标记。
- **与语言无关**：`--` 在 JS/Go 的递减表达式里也会被当成注释起点；`#` 在 Rust 属性里也会被当成注释起点。实测见 2.11 的坑。

**证据 3：只认“注释开头或结尾”** — `src/parser.ts:133-157`

```ts
for (const match of trimmedText.matchAll(markerPattern)) {
  if (match.index === 0) { selectedMatch = match; break; }      // 优先：注释文本开头
  if (match.index + match[0].length === trimmedText.length) {    // 其次：注释文本结尾
    selectedMatch = match;
  }
}
```

注释文本会先 `trim()`（`src/parser.ts:54`），所以 `// AI!  ` 这类行尾空白不影响。

**证据 4：动作判定** — `src/parser.ts:121-131`

```ts
if (markerText.endsWith("!")) return "edit";
if (markerText.endsWith("?")) return "ask";
return "context";   // 只剩 "AI."
```

**本机探针实测（[运行]）**：

| 行内容 | 结果 | 说明 |
|---|---|---|
| `AI! fix`（无注释前缀） | 不触发 | 不是行注释 |
| `// AI! ok` | edit | 注释开头 |
| `// fix this AI!` | edit | 注释结尾 |
| `// fix AI! now` | **不触发** | 注释中间：最常见的误用坑 |
| `//AI! fix` | edit | 注释紧跟前缀、无空格 |
| `# AI? explain` | ask | `#` 注释开头 |
| `;;; note AI.` | context | 行首分号注释，prefix 保留 `;;;` |
| `x ; note AI?` | 不触发 | 分号前有代码（open PR #2 想修的点） |
| `# -ai! nope` / `# thing-ai?` | 不触发 | 前缀边界不满足 `(^|\s)` |
| `// OpenAI ...` / `// AI` / `// AI,` | 不触发 | 后缀不满足 `[!?\.]` / 后接边界 |
| `// AI!nope` | 不触发 | 后面紧跟字母数字 |
| `// AI!!` | edit | 取前两个字符 `AI!` |
| `# AI! and then AI?` | edit（`AI!`） | 开头优先，第二个标记被忽略 |
| `/* AI! */` | 不触发 | 不识别块注释 |
| `const s = '// AI!';` | 不触发 | 引号内跳过 |

**证据 5：块归并与意图输入** — `src/parser.ts:183-217`

```ts
let start = i;
while (start > 0 && parsedLines[start - 1]?.commentPrefixKind === current.commentPrefixKind) start -= 1;
let end = i;
while (end + 1 < parsedLines.length && parsedLines[end + 1]?.commentPrefixKind === current.commentPrefixKind) end += 1;
...
const normalizedBlock = blockLines.map((value) => value.toLowerCase()).join("\n");
const markerIntentInput = `${path}\n${normalizedBlock}`;
```

- 连续同类注释行合成一个 block；空行/代码行会截断（实测 `// a\n\n// b AI!\n// c` 的 block = 第 3–4 行）。
- `;` 与 `;;;` 归一为同一种 kind（`src/parser.ts:41-43`），所以它们能并入同一块。
- 意图输入 = 相对路径 + 整块注释小写化，**不含行号**（所以移动行号不会重复触发，`src/__tests__/parser.test.ts:117-129`）。

---

### 2.2 架构：如何接收“文件已保存”，与 pi-coding-agent 的接口边界

**结论**：没有编辑器扩展、没有独立进程、没有 socket/RPC。pi-watcher 作为 pi-coding-agent 的扩展，在 pi 进程内用 `chokidar` 监听项目文件系统，解析后通过 `ExtensionAPI.sendUserMessage()` 把 prompt 作为“用户消息”注入当前会话；UI 用 `ctx.ui.setStatus/setWidget`；状态快照用 `appendEntry` 写入 session（但从不读回）。

**证据 1：扩展入口声明** — `package.json:8-11`

```json
"pi": {
  "extensions": ["./src/index.ts"]
}
```

pi 通过该字段加载扩展（`pi install git:github.com/vedang/pi-watcher`，README:9-15）。

**证据 2：注册的事件与命令** — `src/index.ts:578-598`

```ts
pi.on("session_start", async (_event, ctx) => {
  await applyEffectiveConfig(ctx, loadEffectiveConfig(ctx.cwd));
});

pi.on("session_shutdown", async (_event, ctx) => { ... });

pi.on("agent_settled", async (_event, ctx) => {
  const result = router.handleAgentSettled(ctx);
  cleanupHandledMarkerComments(ctx.cwd, result.completed ?? null, loadEffectiveConfig(ctx.cwd));
  refreshUi(ctx);
});

pi.registerCommand("watcher", { ... });
```

**证据 3：文件事件来源是 chokidar，而不是“编辑器保存事件”** — `src/watcher.ts:79-98`

```ts
const watcher = watch(roots, {
  awaitWriteFinish: {
    pollInterval: Math.max(10, Math.min(100, this.options.debounceMs)),
    stabilityThreshold: this.debounceMs(),
  },
  followSymlinks: false,
  ignoreInitial: true,
  ignored: (path, stats) => this.isIgnoredForWatch(path.toString(), stats),
  persistent: this.options.persistent ?? true,
});

watcher.on("add", (path) => this.queuePath(path.toString()));
watcher.on("change", (path) => this.queuePath(path.toString()));
watcher.on("unlink", (path) => { void this.handleDeleted(path.toString()); });
```

因此“保存”只是普通文件写事件：格式化工具、`git checkout`、agent 自己写文件都会触发（这也正是需要防回环的原因）。

**证据 4：与 pi 的唯一交互面** — `src/router.ts:44-48, 169-186`

```ts
interface WatcherRouterApi {
  appendEntry: <T = unknown>(type: string, data?: T) => void;
  sendUserMessage: (prompt: string) => void;
}
...
const prompt = buildWatcherPrompt(batch.files, options);
if (!prompt) return { status: "suppressed" };
...
this.pi.sendUserMessage(prompt.prompt);
this.status = "dispatching";
this.persistSnapshot();
```

pi-coding-agent 0.80.6 的类型声明（`@earendil-works/pi-coding-agent/dist/core/extensions/types.d.ts:899-907`）确认：`sendUserMessage` 会“Always triggers a turn”，`appendEntry` 是“state persistence (not sent to LLM)”。`agent_settled` 的语义是“agent run 完全 settle、不会再自动重试/压缩/队列续跑之后”（同文件 534-536 行）。**[代码+上游类型]**

**证据 5：没有子进程/socket/标准流协议** — 全仓库 `grep`：

```text
grep -rn "spawn|child_process|socket|net\." src/ monitors/
=> 无匹配
```

**证据 6：无 UI 模式不启动实时监听** — `src/index.ts:539-545`

```ts
if (!effectiveConfig.enabled || !ctx.hasUI) {
  await closeRuntimeWatcher();
  runtimeEnabled = false;
  refreshUi(ctx);
  return;
}
```

与 README:17 “In no-UI print mode, status and manual scan work, but live watching does not auto-start” 一致。

**证据 7：状态不持久化（重要）** — `src/router.ts:222-224` 只 `appendEntry`；全仓库没有任何读取 session entries 的代码（`grep sessionManager/getEntries` 无匹配）。所以“已处理账本”只活在内存里，重启后清空。

**时序（[代码]）**：

```
编辑器保存 → chokidar change → awaitWriteFinish(300ms 稳定)
  → pendingPaths + setTimeout(300ms) → flushPending → 同步 readFile+parse
  → buildBatch（含 knownFiles 里的 AI. 锚点）→ router.enqueueBatch
  → ctx.isIdle() && !hasPendingMessages() ? sendUserMessage(prompt) : queue
  → agent 跑完 → agent_settled → 完成 inFlight、写账本、派发队列、清理注释
```

**同一仓库还有第二个实现**：`monitors/watch-markers.mjs` 是 Claude Code 插件端口（`monitors/monitors.json`），用 Node `fs.watch({recursive:true})` 把标记以 **stdout 行**形式交给 Claude Code 当异步通知；语义不同：只认大写、允许标记出现在注释中间、只“报告”不“派发/删除”，debounce 750ms。它不是 pi 扩展的运行路径，但说明该项目对不同宿主的接口是分别适配的。`monitors/watch-markers.mjs:9-16` 的注释明确写着这是 “Claude port”（pi 扩展仍用 chokidar）。

---

### 2.3 debounce / 稳定性阈值

**结论**：默认 300ms，可配置（全局/项目 JSON）；实际是**两级 debounce 串联**，因此从保存到派发通常 ≥600ms 量级。

**证据 1：默认值** — `src/index.ts:99-112`

```ts
export const DEFAULT_CONFIG: EffectiveWatcherConfig = {
  ...
  debounceMs: 300,
  contextLines: 12,
  maxPromptBytes: 60_000,
  ...
};
```

**证据 2：第一级（chokidar 写稳定阈值）** — `src/watcher.ts:79-83`

```ts
awaitWriteFinish: {
  pollInterval: Math.max(10, Math.min(100, this.options.debounceMs)),
  stabilityThreshold: this.debounceMs(),
},
```

**证据 3：第二级（自己的待处理路径聚合）** — `src/watcher.ts:192-210`

```ts
private queuePath(path: string): void {
  this.pendingPaths.add(resolve(this.options.cwd, path));
  this.scheduleFlush();
}

private scheduleFlush(): void {
  if (this.flushTimer) clearTimeout(this.flushTimer);
  this.flushTimer = setTimeout(() => {
    this.flushTimer = null;
    void this.flushPending();
  }, this.debounceMs());
}
```

**证据 4：非法值兜底** — `src/watcher.ts:166-169`

```ts
private debounceMs(): number {
  const value = Math.trunc(this.options.debounceMs);
  return Number.isFinite(value) && value >= 0 ? value : DEFAULT_DEBOUNCE_MS;
}
```

- 可配置：是（`debounceMs`，project 覆盖 global 覆盖 default）。
- 无上限（配成 60000 也生效）；0 允许（近似关闭）。
- 注意 `awaitWriteFinish.pollInterval` 被夹在 10–100ms，即使 `debounceMs` 很大。
- 同仓库 Claude 端口用的是 750ms（`monitors/watch-markers.mjs:22`，环境变量 `WATCH_MARKERS_DEBOUNCE_MS`），与 pi 扩展无关。

**[推断]** 总延迟 ≈ `debounceMs`(写稳定) + `debounceMs`(flush) + 解析/派发，默认约 600ms+；测试里用 `debounceMs: 20` 跑得快（`__tests__/watcher.test.ts:23-34`）。

---

### 2.4 忽略规则

**结论**：三层叠加——内置 glob（不可关闭）→ 配置 `ignore` → 根 `.gitignore`（仅 cwd 根）；再加 `include`、roots 边界、符号链接、大小、UTF-8 检查。

**证据 1：内置忽略（永远生效）** — `src/ignore.ts:6-20`

```ts
const BUILT_IN_IGNORE_GLOBS = [
  "**/.git/**", "**/.jj/**", "**/.pi/**", "**/.agents/**",
  "**/node_modules/**", "**/dist/**", "**/build/**", "**/.cache/**",
  "**/coverage/**", "**/.DS_Store", "**/*.swp", "**/*.swo", "**/*~",
] as const;
```

**证据 2：配置 ignore 与 .gitignore** — `src/ignore.ts:115-134`

```ts
const includePatterns = normalizePatterns(options.include);
const ignorePatterns = [
  ...BUILT_IN_IGNORE_GLOBS,
  ...normalizePatterns(options.ignore),
];
const gitignore = ignoreFactory();
const gitignorePath = join(cwd, ".gitignore");

if (existsSync(gitignorePath)) {
  try { gitignore.add(readFileSync(gitignorePath, "utf-8")); } catch { ... }
}
```

- 配置里的 `ignore` 会**替换**默认配置的 8 条（`src/index.ts:88-97`），但内置 13 条仍强制生效；用户无法 un-ignore 内置项。
- `.gitignore` **只读 cwd 根目录那一个文件**，不解析子目录 `.gitignore`。
- 用的是 `ignore` npm 包（v7.0.5），按 gitignore 语义匹配。

**证据 3：判定顺序** — `src/ignore.ts:150-169`（`isIgnoredPath`）与 `180-231`（`classifyFile`）

```ts
if (rel === ".") return false;
if (!isWithinRoots(path)) return true;
if (globMatches(rel, ignorePatterns, isDirectory)) return true;
const gitignoreCandidate = isDirectory ? `${rel}/` : rel;
return canUseGitignore(gitignoreCandidate) ? gitignore.ignores(gitignoreCandidate) : false;
...
if (isIgnoredPath(path, false)) return { reason: "ignored", ... };
if (!isIncludedFile(path)) return { reason: "not_included", ... };
if (stats.size > maxFileBytes) return { reason: "too_large", ... };
if (!isUtf8Text(buffer)) return { reason: "binary", ... };
```

- roots 只能落在 cwd 内：`resolveRootPaths` 过滤 `isWithin(base, rootPath)`（`src/ignore.ts:69-79`）；测试 `__tests__/watcher.test.ts:121-132`。
- 符号链接：扫描时 `lstatSync().isSymbolicLink()` 直接跳过（`src/ignore.ts:193-195`、`src/watcher.ts:254-256`），chokidar `followSymlinks: false`。
- 大小上限默认 1 MiB（`maxFileBytes: 1_048_576`）。
- “二进制”判定 = 含 `0x00` 字节**或** UTF-8 严格解码失败（`src/ignore.ts:102-113`），所以 Latin-1 等非 UTF-8 文本也会被当二进制跳过。
- `minimatch` 用 `{ dot: true }`（`src/ignore.ts:51`）。

**和 README 的差异**：README:80-106 的“default config”只列了 8 条 ignore；实际生效的是内置 13 条 + 配置数组，README 还漏了“读取根 `.gitignore`”这个行为。`vendor/target/.venv/.idea/__pycache__` 不在 pi 扩展的内置忽略里（Claude 端口 `monitors/watch-markers.mjs:27-31` 有），需要用户自己配 `ignore` 或靠 `.gitignore`。

---

### 2.5 防回环机制

**结论**：三层，全部基于“内容”而非 mtime：

1. **已处理意图账本**：`processedMarkerIntentIds`（`Set<sha256>`）+ 插入顺序数组，上限 500。
2. **派发中抑制**：`inFlight.markerIds`，防止同一标记在 agent 运行期间再次入队。
3. **默认删除触发注释**：`agent_settled` 后清理，从源头上消除标记（下一节）。

**证据 1：意图 ID** — `src/state.ts:33-41`

```ts
export function createMarkerIntentId(marker: ParsedAiMarker): string {
  // [tag:marker_intent_suppression] Suppress actionable comments by normalized
  // comment intent, not line number, so edits around a marker do not loop.
  return sha256(marker.markerIntentInput);
}

export function createMarkerContextId(marker: ParsedAiMarker): string {
  return sha256(marker.markerContextInput);
}
```

`markerIntentInput = 相对路径 + "\n" + 整块注释(小写)`（`src/parser.ts:208-217`）。`createMarkerContextId` 在运行时未被使用（只在 `src/index.ts:62` 再导出和 `__tests__/state.test.ts:38-39` 测试），属于“诊断用但闲置”的代码。

**证据 2：过滤** — `src/state.ts:106-130`

```ts
const suppressedIds = new Set([
  ...state.processedMarkerIntentIds,
  ...(state.inFlight?.markerIds ?? []),
]);
const files = batch.files
  .map((file) => ({
    ...file,
    markers: file.markers.filter(
      (marker) => !isActionableMarker(marker) || !suppressedIds.has(createMarkerIntentId(marker)),
    ),
  }))
  .filter((file) => file.markers.length > 0);

if (!hasActionableMarkers(files)) return null;
```

注意：`AI.` context 标记**永不被抑制**（`!isActionableMarker(marker)` 直接放行），但 context 单独不会触发 turn（`src/prompt.ts:271-282` 返回 null）。

**证据 3：账本上限与淘汰** — `src/state.ts:7, 55-71`

```ts
const DEFAULT_LEDGER_LIMIT = 500;
...
while (state.processedMarkerIntentOrder.length > state.ledgerLimit) {
  const oldest = state.processedMarkerIntentOrder.shift();
  if (oldest) state.processedMarkerIntentIds.delete(oldest);
}
```

`ledgerLimit` 只通过构造函数可选参数设置，**没有 config 项**；router 构造时用默认 500（`src/router.ts:93-96`）。

**证据 4：完成时写入账本** — `src/state.ts:147-164`（`agent_settled` 时 `completeInFlightBatch`）。测试 `__tests__/router.test.ts:83-100` 验证完成后同批不再派发。

**证据 5：没有 mtime / 事件屏蔽窗口** — 全仓库 `grep "mtime|statSync|utimes"` 无匹配。因此 agent 写回文件时产生的事件**照常进入解析**，仅靠上面的内容账本挡住。

**关键局限（[运行] 探针）**：

```text
第一次派发后 settle，账本记录 intentId=9b6b1c6b7160
原样再 enqueue            => null（已抑制）
在标记同块新增一行注释后再 enqueue => 不为 null（不再抑制，会再次派发）
```

因为 `normalizedBlock` 包含整块连续注释，只要 agent 在标记所在注释块的相邻行**新增/修改/删除任何注释**，哈希就变，防回环失效。README:123 说“Change the comment text ... to dispatch them again”，对“整块”同样成立，但用户未必预期 agent 自己补一行注释也会再触发。

**其它回环风险（[代码]）**：
- agent 若在代码里**新写入**一个 `AI!` 注释（例如解释性文本），它是新 ID，会被当成用户请求派发——没有“作者/来源”判定。
- 账本重启丢失：若清理被关闭（`removeHandledMarkerComments:false`），重启 pi 后同样的标记会再次触发（README 说“suppress repeat dispatches”，但只在同一进程生命周期内成立）。
- 清理写回本身会触发 change 事件，但此时文件已无标记，不会成批。

---

### 2.6 队列 / 串行 / 合并

**结论**：serialize 依据 `ctx.isIdle() && !ctx.hasPendingMessages()`；pi 忙时入 FIFO 数组，`agent_settled` 时一次派发一个批次；同一 debounce 窗口内的多次保存合并成一个批次；但队列**没有严格排序保证**，也无合并/去重队列项、无上限、无重试。

**证据 1：入口分派** — `src/router.ts:98-118`

```ts
enqueueBatch(batch, context, options?): WatcherRouterResult {
  const filteredBatch = filterBatchForDispatch(this.state, batch);
  if (!filteredBatch) return { status: "suppressed" };

  if (this.isReadyToDispatch(context)) {
    return this.dispatchFilteredBatch(filteredBatch, options);
  }

  this.queue.push({ options, promptBatch: filteredBatch });
  this.status = "queued";
  this.persistSnapshot();
  return { status: "queued" };
}
```

**证据 2：就绪判定** — `src/router.ts:218-220`

```ts
private isReadyToDispatch(context: WatcherAgentContext): boolean {
  return context.isIdle() && !context.hasPendingMessages();
}
```

**证据 3：settle 后出队，一次一个** — `src/router.ts:120-137, 189-216`

```ts
handleAgentSettled(context) {
  const completed = completeInFlightBatch(this.state);
  if (completed) this.lastCompleted = completed;

  if (this.queue.length > 0) {
    const result = this.dispatchFromQueue(context);
    return completed ? { ...result, completed } : result;
  }
  ...
}

private dispatchFromQueue(context) {
  if (!this.isReadyToDispatch(context)) { ... return { status: "queued" }; }
  while (this.queue.length > 0) {
    const next = this.queue.shift();
    ...
    const filteredBatch = filterBatchForDispatch(this.state, next.promptBatch);
    if (!filteredBatch) continue;     // 重复的批次被静默丢弃
    return this.dispatchFilteredBatch(filteredBatch, next.options);  // 只派发一个
  }
  ...
}
```

**证据 4：同一批内的排序** — 文件按相对路径字典序（`src/watcher.ts:44-46`），文件内按行号（`src/prompt.ts:117-127`）。跨文件**不是按行号/时间**排序。

**证据 5：合并只发生在 debounce 层** — `flushPending` 一次把 `pendingPaths` 里所有路径一起扫描成一个 `WatcherBatch`（`src/watcher.ts:212-228`）；之后的多次保存会各自形成新的 queue entry，不会合并。

**证据 6：队列乱序竞态（[运行] 探针）**：

```text
A 在 pi 忙时入队          => queued
B 在 pi 空闲瞬间入队      => dispatched   （B 插队，先派发）
settle                    => dispatched   （A 才被派发）
sent #1 = b.ts:1 ; sent #2 = a.ts:1
```

根因：`enqueueBatch` 在 `isReadyToDispatch()` 为真时直接派发，**不检查 `queue.length > 0`**（`src/router.ts:109-111`）。实践触发条件较窄（需要在 queue 非空且 pi 恰好 idle 时来一次新保存），但确实存在。

**其它（[代码]）**：
- 队列项在出队时用账本再次过滤，重复批次被丢弃（`dispatchFromQueue` 的 `continue`）。
- 无超时：若 `agent_settled` 永不触发（agent 卡死/崩溃且事件不发），队列永久停留。
- 无重试：失败批次没有退避重试；唯一的手动手段是 `/watcher retry`（只重发 `lastCompleted`，`src/router.ts:139-149`）。
- `busyPolicy` 配置是**死配置**：只有 `"queue_until_idle"` 一个合法值，且代码中除解析/默认值外没有任何使用（`grep busyPolicy` 仅 `src/index.ts:46,111,187-188`）。

---

### 2.7 触发注释的清理

**结论**：默认**删除**（不是改写保留）；在 `agent_settled` 后执行；对象是“已处理批次里所有文件的同意图标记”；删除粒度是“标记所在连续注释块中所有独占一行的注释行”，行内标记则剥掉注释保留代码；不检查 agent 是否成功。

**证据 1：触发点** — `src/index.ts:588-596`

```ts
pi.on("agent_settled", async (_event, ctx) => {
  const result = router.handleAgentSettled(ctx);
  cleanupHandledMarkerComments(ctx.cwd, result.completed ?? null, loadEffectiveConfig(ctx.cwd));
  refreshUi(ctx);
});
```

`completed` 只在 `completeInFlightBatch` 拿到 inFlight 时存在；不管 agent 的修改是否成功、是否报错，只要 settle 就清理。**[推断]** 若 pi 在 agent 报错后仍发 `agent_settled`（按类型声明“fully settled”推断会），则失败的标记也会被删掉，且没有“请保留/重试”的提示。

**证据 2：配置开关** — `src/cleanup.ts:161-168`

```ts
export function cleanupHandledMarkerComments(cwd, completed, config) {
  if (!config.removeHandledMarkerComments || !completed) return;
  ...
}
```

默认 `removeHandledMarkerComments: true`（`src/index.ts:110`）。

**证据 3：删除范围（会误删相邻用户注释）** — `src/cleanup.ts:53-75`

```ts
for (const marker of handledMarkers) {
  const startLine = Math.max(1, marker.block.startLine);
  const endLine = Math.min(marker.block.endLine, lines.length);
  for (let lineNumber = startLine; lineNumber <= endLine; lineNumber += 1) {
    const line = lines[lineNumber - 1];
    if (handledMarkerLines.has(lineNumber) || isStandaloneCommentLine(line)) {
      linesToClean.add(lineNumber);
    }
  }
}
```

`isStandaloneCommentLine` = 该行有行注释且注释前只有空白（`src/cleanup.ts:14-17`）。所以**整个 block 里所有独立注释行都会删**，包括用户写的普通说明注释。

**证据 4：行内标记只剥注释** — `src/cleanup.ts:19-31, 118-149`

```ts
function removeLineCommentFromLine(line: string): string | null {
  const comment = findLineComment(line);
  if (!comment) return line;
  const before = line.slice(0, comment.start);
  if (before.trim() === "") return null;      // 独占一行 → 整行删除
  return before.replace(/\s+$/, "");          // 行内 → 保留代码
}
```

**证据 5：只清理“当前内容中意图仍匹配”的标记** — `src/cleanup.ts:118-129`

```ts
const handledMarkers = currentMarkers.filter((marker) =>
  handledMarkerIds.has(createMarkerIntentId(marker)),
);
```

若 agent 已经删掉/改写了注释，则不做任何事；若文件在运行期间被用户改动，按**当前**内容重新解析，只有仍匹配的才清理。

**证据 6：安全防护与失败处理** — `src/cleanup.ts:33-116`

- 拒绝清理 cwd 之外的路径（`isWithinPath`），并用 `realpathSync` 双侧校验防符号链接替换（`isCleanupCandidateFile`）；测试 `__tests__/index.test.ts:486-540` 覆盖“扫描后换成符号链接/父目录符号链接”的场景。
- 读失败/写失败直接 `return` / 吞掉异常（`src/cleanup.ts:111-115`），**没有通知用户**；但账本已把标记记为 processed，注释留在文件里也不会再触发（除非文本变化或 `/watcher retry`）。

**证据 7：清理误删实测（[运行] 探针）**：

| 输入 | 清理后 | 风险 |
|---|---|---|
| `// important note`<br>`// add guard AI!`<br>`const x = 1; // inline AI!`<br>`run();` | `const x = 1;`<br>`run();` | 相邻独立注释 `// important note` 被一并删除 |
| `x--; // AI!` | `x` | `--` 被误判为注释起点，删掉了 `--; // AI!`（JS/Go 递减表达式） |
| `#[cfg(test)] // AI!` | `fn f() {}`（属性行整行被删） | Rust 属性里的 `#` 被当作注释，导致整行删除 |
| `const a = 1; // fix this AI!` | `const a = 1;` | 符合预期：行内标记剥离 |

> 注：仓库自带测试 `__tests__/index.test.ts:347-380` 反而**肯定**了“相邻独立注释一起删”（期望 `explain constraints`、`keep null-safe` 都消失）；`:461-484` 只保护“同一行内的非标记注释”。

**证据 8：清理的“双保险”**：prompt 里也要求 agent 自己删除标记（`src/prompt.ts:24-28`），这是给 agent 的软约束，不是清理的依赖。

---

### 2.8 上下文构造（发给 agent 的 payload）

**结论**：不是整文件发送，而是“每个标记一个代码窗口 + 行号 + 标记符号”，窗口为标记块前后各 `contextLines`（默认 12）行，重叠/相邻窗口会合并；总 prompt 超过 `maxPromptBytes`（默认 60000）时降级为逐标记一行摘要。

**证据 1：指令头** — `src/prompt.ts:24-38`

```ts
const EDIT_INSTRUCTIONS = `You are responding to pi-watcher comments the user added in their editor.
Treat comments marked AI! as targeted code-change requests.
Use read/edit/write tools as needed. Keep changes focused.
After completing requested changes, remove handled AI!, AI?, and AI. comments.
This is a pi-watcher fast-path turn: make the smallest useful edit directly; skip large task workflow unless the marked request clearly requires it.`;

const ASK_INSTRUCTIONS = "You are responding to pi-watcher questions the user added in their editor.\n" +
  "Answer the AI? comments. Do not edit files unless a comment explicitly asks for edits.";
```

同一批既有 `!` 又有 `?` 时，`pickAction` 选 `edit`（`src/prompt.ts:89-101`）。

**证据 2：窗口合并** — `src/prompt.ts:110-145`：按标记块 `startLine-contextLines` 到 `endLine+contextLines` 建窗，按 start 排序后若与前窗重叠/相邻（`window.startLine > previous.endLine + 1`）则合并。

**证据 3：片段格式** — `src/prompt.ts:178-205`

```ts
for (let lineNumber = window.startLine; lineNumber <= window.endLine; lineNumber += 1) {
  const glyph = window.markerLines.has(lineNumber) ? "█" : "|";
  bodyLines.push(`${lineNumber} ${glyph} ${lineAt(fileLines, lineNumber)}`);
}
const fence = fenceFor(language, bodyLines);
return [
  ...sortedMarkers.map((marker) => `${path}:${marker.line} action=${marker.action}`),
  fence.open, ...bodyLines, fence.close,
].join("\n");
```

本机实测输出（`contextLines: 2`）：

````text
You are responding to pi-watcher comments the user added in their editor.
Treat comments marked AI! as targeted code-change requests.
...
Marked comments:
src/label.ts:2 action=edit
```ts
1 | export function label(value: string | null) {
2 █   // handle null safely AI!
3 |   return value.trim();
4 | }
```
````

- 代码围栏长度动态计算（`longestBacktickRun + 1`，至少 3 个反引号，`src/prompt.ts:151-176`），防止内容里的 ``` 提前闭合围栏。
- 语言标记由扩展名推断（ts/js/py，其它用扩展名或 `text`，`src/prompt.ts:66-87`）。

**证据 4：`AI.` 锚点如何用** — 解析时 context 标记同样进入 `WatcherFileState.markers`；`buildBatch` 会把 `knownFiles` 里所有“只有 context 标记的文件”拼进每个 actionable 批次（`src/watcher.ts:35-42, 325-335`）；prompt 里作为 `action=context` 的独立片段发送（测试 `__tests__/prompt.test.ts:79-134` 的 inline snapshot）。锚点本身不会触发 turn（无 actionable 时 `buildWatcherPrompt` 返回 null）。

**证据 5：截断策略** — `src/prompt.ts:215-264, 307-326`

```ts
if (Buffer.byteLength(prompt, "utf8") <= maxPromptBytes) return { ... truncated: false };

const compactPrompt = buildCompactPrompt(
  includedMarkers.map(summarizeMarker),
  maxPromptBytes,
);
return { ..., prompt: compactPrompt, truncated: true };
```

- 摘要格式：`- path:line action=edit instruction="..."`（`src/prompt.ts:207-213`，指令里换行/引号/反斜杠会转义）。
- 截断按字节，且用 `TextDecoder({fatal:true})` 逐字节回退，避免截断多字节字符产生 U+FFFD（`src/prompt.ts:215-237`）。
- `clampContextLines` 只拒绝负数/非有限值，**没有上限**（`src/prompt.ts:40-51`）；`maxPromptBytes` 允许 0（此时除前缀外基本为空）。

**证据 6：不进 prompt 的东西**：文件完整内容只用于在内存里切窗口（`src/prompt.ts:288-299`），不会整体发给 agent；但 `AI.` 锚点文件、以及同一批多个文件的所有窗口都会进入同一条 prompt。没有“每文件标记数量上限”，只有总字节上限。

---

### 2.9 失败与错误处理

**结论**：整体是“尽力而为 + 静默降级”，缺少超时、重试、成功判定、并发冲突检测、watcher 自愈。

| 场景 | 代码行为 | 位置 | 评价 |
|---|---|---|---|
| chokidar 出错 | 只 `onError` 通知（warning），不重启 watcher | `src/watcher.ts:96-98`、`src/index.ts:492-498` | 出错后可能永久失聪 |
| 文件在事件后被删/不可读 | `classifyFile` 返回 `skipped`，从 `knownFiles` 删除 | `src/ignore.ts:187-220`、`src/watcher.ts:289-292` | 合理 |
| 配置文件 JSON 损坏 | `console.error` 后按空配置处理（回退默认） | `src/index.ts:199-211` | 用户可能不知道配置没生效 |
| watcher 启动失败 | catch → 关闭、状态 off、通知 warning | `src/index.ts:535-562` | 合理 |
| `sendUserMessage` 后 agent 失败/超时 | 无任何处理；没有 await、没有回调、没有超时 | `src/router.ts:182` | **无法感知失败** |
| agent 永不 settle | 队列永久停留，`inFlight` 不清 | `src/router.ts:120-137` | 无超时兜底 |
| 清理写失败 | 吞掉异常，不通知 | `src/cleanup.ts:102-116` | 注释残留但账本已记账 |
| 并发修改同一文件 | settle 时重新读文件、按意图哈希只清理仍匹配的标记；`realpath` 防符号链接 | `src/cleanup.ts:77-100, 118-149` | 设计合理，但 `writeFileSync` 非原子，读→写窗口内用户的保存可能被覆盖 |
| 行尾风格 | 用首次匹配到的行尾（`\r\n`/`\n`/`\r`）统一 join | `src/cleanup.ts:131-132` | 混合行尾文件会被统一 |

**额外发现（[代码]，风险）**：`state.inFlight` 是**单槽**（`src/state.ts:141-144` `markBatchInFlight` 直接覆盖）。如果两次派发之间没有 `agent_settled`（例如 `sendUserMessage` 后 `isIdle()` 尚未同步变 false 的竞态），第一批的 markerIds 会被覆盖、永远不会进账本，可能重复派发。**[未核实]** pi 的 `isIdle()` 是否在 `sendUserMessage` 内同步翻转，本环境无法验证。

**并发/失败相关的测试覆盖**：没有。`__tests__` 只手工 `emit("agent_settled")`，不模拟 agent 报错、超时、进程崩溃。

---

### 2.10 测试 / CI

**结论**：有单元测试（vitest），无 CI；测试覆盖解析、忽略、路由、状态、prompt、清理/符号链接等边界，但不覆盖失败路径与队列竞态。

**证据 1：框架与脚本** — `package.json:12-18, 27-36`

```json
"scripts": {
  "test": "vitest run --config vitest.config.unit.ts",
  "test:coverage": "vitest run --config vitest.config.unit.ts --coverage"
}
...
"@vitest/coverage-v8": "^3.2.4", "vitest": "^3.2.4"
```

`vitest.config.unit.ts` 收集 `__tests__/**/*.test.ts`，排除 integration/llm；`vitest.config.integration.ts` 与 `vitest.config.llm.ts` 的 include 目录**在仓库中不存在**，且都配了 `passWithNoTests: true`（即“有配置、无测试”）。

**证据 2：本机执行结果（[运行]）**

```text
$ npx vitest run --config vitest.config.unit.ts
 ✓ __tests__/parser.test.ts (5 tests)
 ✓ __tests__/state.test.ts (2 tests)
 ✓ __tests__/prompt.test.ts (5 tests)
 ✓ __tests__/router.test.ts (4 tests)
 ✓ __tests__/watcher.test.ts (6 tests)
 ✓ __tests__/index.test.ts (22 tests)
 Test Files  6 passed (6)
      Tests  44 passed (44)
```

```text
$ npx tsc --noEmit          → exit 0
$ npx @biomejs/biome check src __tests__ ...
                            → Checked 19 files. No fixes applied.
```

（本机没有 `bun`，用 npm 安装依赖；因此实际跑的是 npm 解析到的 vitest 3.2.7，`bun.lock` 固定的是 3.2.4，不影响源码结论。）

**证据 3：测试覆盖的边界（[代码]）**

| 文件 | 测试数 | 覆盖点 |
|---|---|---|
| `__tests__/parser.test.ts` | 5 | 三种前缀、行首/行尾位置、连续注释块、误报（OpenAI/-ai!/some-ai?/字符串/裸 AI）、ID 行号无关性 |
| `__tests__/watcher.test.ts` | 6 | 临时项目中的 change 检测、伪标记不触发、忽略目录+根 `.gitignore`、roots 越界、大文件/二进制、close 后不响应 |
| `__tests__/router.test.ts` | 4 | 空闲立即派发、忙时排队+settle 派发、完成后去重、`/watcher retry` |
| `__tests__/state.test.ts` | 2 | 意图 ID 行号稳定/上下文 ID 变化、完成标记抑制与清除 |
| `__tests__/prompt.test.ts` | 5 | edit/ask 指令、`AI.` 锚点合并、超字节降级摘要、纯 context 返回 null |
| `__tests__/index.test.ts` | 22 | 配置路径/默认值/优先级、命令解析、session 启停、实时派发、清理（块/行内/`AI.`/关闭开关/符号链接×2/不重复触发）、`AI?` 指令、队列 UI、无 UI 保护 |

**证据 4：无 CI（[代码+API]）**：本地 clone 无 `.github/`；GitHub Contents/Tree API 的完整树同样没有 `.github`（https://api.github.com/repos/vedang/pi-watcher/git/trees/main?recursive=1）。`Makefile:58-60, 66-79` 提供 `make check` / `make test`，但需要人手动跑。

**未被覆盖的风险点（[代码]）**：队列乱序（2.6 探针）、agent 失败/超时、`inFlight` 覆盖、配置热更新、`.gitignore` 子目录、`include` 配置、watcher error 自愈、清理写失败、`maxPromptBytes` 极端值/多字节边界（部分有）、ledger 500 淘汰。

---

### 2.11 设计取舍与已知问题

**README 声称 vs 代码核对（[代码]）**

| README 声称 | 代码事实 | 判定 |
|---|---|---|
| 监听 `AI!`/`AI?`/`AI.`，保存后发 prompt（README:3-5, 19-32） | 成立 | ✅ |
| 标记大小写不敏感、注释开头/结尾、忽略 `OpenAI`/`-ai!`/`some-ai?`/裸 `AI`（README:44） | 成立 | ✅ |
| `AI!` 改代码、`AI?` 提问、`AI.` 只作上下文锚点（README:46-50） | 成立（anchor 随下一批发送，`src/watcher.ts:325-335`、`src/prompt.ts:79-134`） | ✅ |
| debounce（README 配置 `debounceMs` 300） | 两级各 300ms，实际延迟更长 | ⚠️ 数值对，延迟有出入 |
|“忽略目录”（默认 8 条） | 实际 13 条内置 + 配置 + 根 `.gitignore`；README 未提 `.gitignore` | ⚠️ 不完整 |
|“防回环”（README:119-123） | 有账本+删除，但无 mtime，账本内存态、块级哈希、重启丢失 | ⚠️ 部分成立 |
|“处理完删除触发注释”（README:32, 48-50, 121） | 成立；但会删同块相邻独立注释，且不判断 agent 成功 | ⚠️ 有副作用 |
| 无 UI 打印模式不自动实时监听（README:17） | 成立（`src/index.ts:540`） | ✅ |
| 配置优先级 defaults < global < project（README:70-78） | 成立（`src/index.ts:214-223`） | ✅ |
| 大文件/二进制/忽略目录/roots 外跳过（README:128） | 成立 | ✅ |
| prompt 受 `maxPromptBytes` 限制（README:130） | 成立，超限降级摘要 | ✅ |

**已知问题/上游状态（[代码+API]）**

- **issue #1（open，2026-08-13）**：Lima/virtiofs 虚拟机里，宿主机编辑不会转发 FS 事件，扩展不响应；建议加轮询。这从用户侧证实架构是纯 FS 事件监听。URL: https://github.com/vedang/pi-watcher/issues/1
- **PR #2（open，draft）**：`allow semicolon inline comments when preceded by whitespace`，试图支持 `(foo) ; explain AI?`。当前代码确实只在“分号前只有空白”时才认分号（`src/parser.ts:109-112`），所以 `foo ; AI?` 不触发。注意该 PR 把模板字符串里的 `\\.` 改成 `\.`（`src/parser.ts:38`），在 JS 模板字符串里 `\.` 的 cooked 值是 `.`，会把“`AI` + 任意字符”都匹配上（本机验证 `AIx` 也会命中），合并前需要修正。URL: https://github.com/vedang/pi-watcher/pull/2
- **两个实现并存**：pi 扩展（chokidar、大小写不敏感、只认注释首尾、会删标记）与 Claude Code 插件（`monitors/watch-markers.mjs`：Node `fs.watch`、只认大写、任意位置、750ms debounce、只报告不删除），README 只描述前者。
- **仓库规模/活跃度**：2 stars、1 fork、27 commits、无 tag/release（GitHub API，2026-10-18 快照）。
- **许可**：`LICENSE.txt` 为 WTFPL v2；GitHub API 的 license 显示 “Other/NOASSERTION”。
- **`busyPolicy` 是死配置**（只有一个合法值且无行为分支，见 2.6）。
- **`createMarkerContextId` / `markerContextInput` 运行时未使用**（只测试/导出）。

---

## ③ 机制表

| # | 机制 | 实现要点 | 位置 | 可配置 |
|---|---|---|---|---|
| 1 | 文件事件源 | `chokidar` v4 `watch(roots)`，`add/change/unlink` | `src/watcher.ts:79-98` | roots 可配 |
| 2 | 初始不触发 | `ignoreInitial: true`，启动时改用 `scan("startup_scan")` | `src/watcher.ts:85`、`src/index.ts:547-551` | `scanOnStart` |
| 3 | 写稳定（一级 debounce） | `awaitWriteFinish.stabilityThreshold = debounceMs` | `src/watcher.ts:80-83` | `debounceMs`（默认 300） |
| 4 | 事件聚合（二级 debounce） | `pendingPaths` + `setTimeout(debounceMs)` | `src/watcher.ts:192-228` | 同上 |
| 5 | 扫描线程模型 | 同步 `readdirSync/readFileSync`（事件循环内） | `src/watcher.ts:236-315`、`src/ignore.ts:180-231` | 否 |
| 6 | 忽略 | 13 条内置 glob + 配置 + cwd 根 `.gitignore` | `src/ignore.ts:6-20,115-169` | 内置不可关；`ignore` 可配 |
| 7 | 包含 | `include` 与相对路径 minimatch（dot:true） | `src/ignore.ts:171-178` | `include`（默认 `**/*`） |
| 8 | roots 边界 | 解析为绝对路径后必须是 cwd 子路径 | `src/ignore.ts:57-79` | `roots` |
| 9 | 文件级过滤 | 符号链接 / 非普通文件 / 大小 / 非 UTF-8 / 不可读 | `src/ignore.ts:180-231` | `maxFileBytes`（1 MiB） |
| 10 | 标记解析 | 引号感知的行注释扫描 + 注释首/尾规则 + `gi` | `src/parser.ts:36-157` | `marker`（默认 `AI`） |
| 11 | 注释块 | 连续同 prefixKind 行合并；`;`/`;;;` 归一 | `src/parser.ts:41-43,183-197` | 否 |
| 12 | 意图 ID | `sha256(相对路径+"\n"+小写整块注释)`，与行号无关 | `src/state.ts:33-37`、`src/parser.ts:207-217` | 否 |
| 13 | 已处理账本 | `Set` + 插入顺序，上限 500，进程内 | `src/state.ts:7,55-71` | 否（硬编码） |
| 14 | in-flight 抑制 | 单槽 `state.inFlight`，派发时写入、settle 时转正 | `src/state.ts:132-165` | 否 |
| 15 | 批构造 | 变更文件 + `knownFiles` 中仅 context 的文件，按路径排序 | `src/watcher.ts:35-46,317-341` | 否 |
| 16 | 派发 | `sendUserMessage(prompt)`（触发新 turn） | `src/router.ts:169-187` | 否 |
| 17 | 排队 | 忙时 `queue.push`，settle 时一次派发一个 | `src/router.ts:98-137,189-216` | 否（`busyPolicy` 无实际作用） |
| 18 | 上下文窗口 | 标记块 ±`contextLines`，重叠合并，带行号/`█` | `src/prompt.ts:110-205` | `contextLines`（默认 12） |
| 19 | 字节上限 | 整 prompt ≤`maxPromptBytes`，超限换摘要并 UTF-8 安全截断 | `src/prompt.ts:215-264,307-326` | `maxPromptBytes`（默认 60000） |
| 20 | 清理 | `agent_settled` 后删块内独立注释行 / 剥行内注释；realpath 防符号链接 | `src/cleanup.ts:14-31,53-75,161-199` | `removeHandledMarkerComments`（默认 true） |
| 21 | 状态持久化 | `appendEntry("pi-watcher-state", snapshot)`，**只写不读** | `src/router.ts:222-224`、`src/state.ts:6` | 否 |
| 22 | UI | footer `setStatus` + widget（queued/queued markers/last trigger） | `src/index.ts:308-325,469-471` | 否 |
| 23 | 命令 | `/watcher status/config/start/stop/scan/clear/retry/global|project start|stop` | `src/index.ts:128-138,598-712` | 否 |
| 24 | 失败处理 | watcher 错误仅通知；无超时/重试；清理异常静默 | `src/watcher.ts:96-98`、`src/cleanup.ts:102-116` | 否 |
| 25 | Claude 端口 | `fs.watch(recursive)` + 750ms debounce + stdout 通知，只报告不执行 | `monitors/watch-markers.mjs:18-125` | 环境变量 |
| 26 | 状态恢复 | 无（重启即丢账本） | `grep sessionManager/getEntries` 无匹配 | — |

---

## ④ 局限与坑

按影响从高到低排列（全部有 `file:line` 或运行证据）：

1. **[高] 防回环依赖“整块注释文本”哈希，agent 改动同块任一注释行即重新触发。** `normalizedBlock` 覆盖连续注释块（`src/parser.ts:199-217`）；探针实测在标记旁新增一行注释后不再被抑制。用户若开启 `removeHandledMarkerComments:false`，一个爱补注释的 agent 可能形成多轮循环。
2. **[高] 清理会误删同一注释块里的普通独立注释。** `collectLinesToClean` 把块内所有“独占一行的注释”加入删除集（`src/cleanup.ts:66-71`）；实测 `// important note` 随 `// add guard AI!` 一起消失。仓库测试还把这一行为固化为期望（`__tests__/index.test.ts:347-380`）。
3. **[高] 语言无关的行注释识别会误判代码并造成清理破坏。** 实测 `x--; // AI!` 清理后变成 `x`；`#[cfg(test)] // AI!` 整行被删。原因：`findLineComment` 把任意位置（引号外）的 `--`、`#` 当注释（`src/parser.ts:90-100`）。对 JS/Go/Rust/Java 的 `--`、`#` 语义都不安全。
4. **[高] 清理不判断 agent 成功。** `agent_settled` 一到就删（`src/index.ts:588-594`），没有检查工具调用结果/错误；agent 失败时用户请求会被无声丢弃（标记没了，账本也记为已处理）。
5. **[中] 账本只在内存，重启丢失；上限 500。** `appendEntry` 只写不读（2.2 证据 7），`DEFAULT_LEDGER_LIMIT = 500`（`src/state.ts:7`）；关闭清理时，重启 pi 或处理超过 500 个标记后，旧标记可能再次触发。
6. **[中] 队列乱序竞态。** `enqueueBatch` 空闲即派发、不检查已有队列（`src/router.ts:109-111`）；探针实测后保存的 B 先于先排队的 A 派发。另外 `inFlight` 是单槽，两次派发会覆盖。
7. **[中] 无超时/无取消/无重试。** agent 卡住则 `inFlight` 与队列永久停滞；`sendUserMessage` 没有回调（`src/router.ts:182`），失败不可见。
8. **[中] “保存”是笼统的 FS 变更。** 格式化、git 操作、agent 自己写文件都会触发；没有“编辑器保存”语义，也没有区分 agent 写入的来源。
9. **[中] `;` 行内注释不支持。** `foo ; AI?` 不触发（`src/parser.ts:109-112`），open PR #2 尚未合并且其 diff 存在正则转义 bug（2.11）。
10. **[中] 标记在注释中间不生效。** `// fix AI! now` 不触发（`src/parser.ts:140-149`），README 虽有说明，但这是最常见的写法之一。
11. **[中] 不识别块注释。** `/* AI! */` 完全不会触发（行注释限定，`src/parser.ts:90-115`）；C/C++/Java/CSS 用户需要改用行注释。
12. **[中] `.gitignore` 只读 cwd 根一份。** 子目录 `.gitignore`（Java/Maven 多模块、monorepo 常见）不生效（`src/ignore.ts:126`）。
13. **[中] 非 UTF-8 文本被当二进制跳过。** `isUtf8Text` 严格解码失败即 skip（`src/ignore.ts:102-113`），GBK/Latin-1 源码不会被监听。
14. **[低] 无内置 `vendor/target/.venv/.idea/__pycache__` 忽略。** 需靠 `.gitignore` 或用户配置；Claude 端口反而内置了这些（`monitors/watch-markers.mjs:27-31`）。
15. **[低] 清理写回非原子且吞异常。** `writeFileSync` 全量覆盖（`src/cleanup.ts:102-116`），与编辑器/agent 的并发保存在读→写窗口内可能互相覆盖；失败无提示。
16. **[低] 混合行尾会被统一成首个行尾**（`src/cleanup.ts:131-132`）。
17. **[低] `busyPolicy` 死配置、`createMarkerContextId`/`markerContextInput` 未使用**，配置面有噪音。
18. **[低] watcher error 不重启；扫描用同步 FS 调用**（`src/watcher.ts:212-315`），超大仓库/事件风暴时会阻塞事件循环。
19. **[低] 配置不热更新**：只在 `session_start`、`/watcher start|stop`、命令执行时重读（`src/index.ts:368-373,578-580`），改 JSON 后需重启或触发命令。
20. **[低] 测试/工程化缺口**：无 CI、无 integration/LLM 测试、无 agent 失败/队列/超时测试；issue #1 的 VM 事件不转发场景没有轮询兜底。

---

## ⑤ 对 GoLand「注释驱动编码伴侣」插件：值得借用 / 必须重写清单

> 判定标签：**可直接移植思路**（算法/协议/配置语义可以照搬，实现语言换成 Kotlin/Java）；**需 PSI+Inlay 重写**（思路保留，实现必须换成 IntelliJ 平台机制）；**不可行**（在 IDE 插件里不能或不应这样实现）。

### 5.1 可直接移植思路

| 项 | pi-watcher 证据 | 对 GoLand 的落地建议 |
|---|---|---|
| 标记语法语义（action 三态 + 忽略大小写 + 边界规则 + “注释首/尾”位置） | `src/parser.ts:34-157` | 保留判定规则，但**解析实现**换 PSI（见 5.2）；建议增加“注释中间标记”支持（比 pi-watcher 更符合直觉），并把边界规则写成单元测试矩阵 |
| 意图哈希账本（路径+内容归一化，行号无关）防重复派发 | `src/state.ts:33-71,106-130` | 算法可照搬；但哈希范围应改为“标记 token 文本 + 文件 + 锚点”，**不要**照搬“整块注释”，否则 agent 改相邻注释就重复触发（见 2.5 探针）。账本要持久化到 project service（workspace state），不能只放内存 |
| 三态筛批与 in-flight 抑制 | `src/state.ts:106-165` | 保留“已处理 + 处理中”双层抑制；把单槽 `inFlight` 改成 Set/Map，支持多个批次（5.2 修） |
| prompt 上下文窗口构造（窗口合并、行号+标记符号、字节上限、UTF-8 安全截断） | `src/prompt.ts:110-264,266-326` | 可直接移植到 Kotlin；读取来源改为 `Document`/PSI，窗口边界用“标记所在 PSI 元素上下文”增强 |
| 配置分层（default < global < project）与字段归一化/校验 | `src/index.ts:99-112,140-151,214-223` | 可直接移植；建议改用 IDE 的 `PersistentStateComponent` + Settings UI，而不是裸 JSON（但保留 project `.idea/pi-watcher.json` 或 `.ai-comments.json` 作为可提交配置） |
| 忽略列表 + include + roots + `.gitignore` | `src/ignore.ts:6-20,115-178` | 内置列表可借用；**实现改用** `ProjectFileIndex`/`GlobalSearchScope`，`.gitignore` 用 Git4Idea 的 `GitIgnoreManager`（自动支持子目录），不要自己解析 |
| 手动命令集（status/config/start/stop/scan/clear/retry） | `src/index.ts:128-138,598-712` | 命令语义可借鉴；IDE 里映射为 Action/ToolWindow 按钮 + 状态栏菜单，`retry last batch` 特别值得保留 |
| 测试矩阵（parser 误报、watcher 忽略、router 队列、cleanup 边界） | `__tests__/*.test.ts` | 直接照搬用例设计，另补：agent 失败、超时、队列乱序、并发保存、IDE 重启后账本恢复 |

### 5.2 需 PSI + Inlay 重写（思路保留，实现替换）

| 项 | pi-watcher 的做法 | GoLand 必须怎么重写 |
|---|---|---|
| “文件已保存”事件 | chokidar 监听 FS（`src/watcher.ts:79-98`） | 用 `FileDocumentManagerListener`/`DocumentListener` + `VfsEvents`，只在 `Document.save`/显式触发时动作；不要依赖 OS watcher，也不要在 EDT 上做 I/O |
| 注释识别 | 手写引号状态机 + 前缀扫描（`src/parser.ts:60-119`），对语言不敏感 | 必须用 PSI：`PsiComment`/`PsiElement` 遍历、语言特定注释 token；天然处理块注释、字符串、Rust `#[]`、Go 递减 `--` 等；`--` 在 Go 代码里不是注释，PSI 能区分 |
| 清理触发注释 | `readFileSync`+`writeFileSync` 全量覆写（`src/cleanup.ts:102-149`） | 必须用 `WriteCommandAction` + PSI 删除/替换（可撤销、尊重格式化、不破坏 CRLF/BOM）；**只删标记 token/所在注释**，绝不删相邻用户注释；要能处理读→写之间的并发修改（`Document` 版本号/`PsiDocumentManager` 校验） |
| 标记显示与交互 | 无（只发 prompt） | `InlayHintsProvider`（在注释行尾显示 `AI!`/`AI?` 状态 chip）、`GutterIconProvider`、`EditorNotificationProvider`、状态栏 `StatusBarWidget`；点击可触发/取消/查看队列 |
| 上下文构造 | 按行号 ±12 行切窗口（`src/prompt.ts:110-145`） | 用 PSI 定位“标记所在的语句/函数/字段”作为语义上下文，比固定行数更稳；仍可保留“±N 行”作为 fallback，并允许配置 |
| debounce/排队 | 两级 `setTimeout` + FIFO 数组（`src/watcher.ts:201-210`、`src/router.ts:98-137`） | 用 `Alarm`/`CoroutineScope` + project service；按文件合并、按“最早等待时间”排序（修掉 2.6 的乱序）；I/O 用 `ReadAction`（BGT），写用 `WriteCommandAction`（EDT），禁止同步全树扫描 |
| 状态持久化 | `appendEntry` 只写不读，重启丢（`src/router.ts:222-224`） | 用 `PersistentStateComponent`/`@State` 存账本、队列、已处理哈希；IDE 重启后恢复，避免重复派发或漏派发 |
| 忽略/包含 | 自定义 minimatch + 根 `.gitignore`（`src/ignore.ts`） | 用 `ProjectFileIndex.isExcluded`、`GlobalSearchScope`、Git4Idea ignore API；多模块项目按 module/root 处理 |
| “AI. 锚点” | 把锚点文件整块拼进下一批 prompt（`src/watcher.ts:325-335`） | 用 PSI 解析锚点所在符号（函数/类/变量），以“引用 + 代码片段”形式附上；避免整文件/整块噪音 |
| 队列/UI 状态 | footer 文本 + 3 行 widget（`src/index.ts:308-325`） | ToolWindow（队列列表、每条标记的 状态/文件/动作）+ 状态栏；Inlay 直接展示每条标记的处理状态 |

### 5.3 不可行 / 不应照搬

| 项 | 原因 |
|---|---|
| `chokidar` + `awaitWriteFinish` 的双 debounce 模型 | IDE 已有 VFS/Document 事件与保存语义；再挂 OS watcher 会重复触发、跨平台行为不一致、无法区分 agent 写入与用户保存。**不可行/不应移植** |
| `pi.sendUserMessage` / `appendEntry` / `agent_settled` 接口 | 这是 pi-coding-agent 进程内 ExtensionAPI（`@earendil-works/pi-coding-agent` 类型），GoLand 没有等价物；必须自建 agent 桥（本地 CLI/HTTP/socket/LSP 任一），并**显式定义完成/失败/超时事件**，不能假设“发出去就一定会 settle”。**不可行** |
| 依赖 agent 自己删除注释（`src/prompt.ts:24-28` 的软约束） | 无法验证、不可靠；IDE 插件必须由自己执行清理，并且只在明确成功/用户确认后执行。**不可行** |
| 全量同步扫描 + `readFileSync`（`src/watcher.ts:236-315`） | 在 IDE 里会卡 EDT/阻塞索引；大项目不可接受。必须换成 `ProjectFileIndex` + 后台协程 + 增量 PSI。**不可行** |
| 用语言无关的“行注释正则”做跨语言标记识别 | 已被 `x--;`、`#[]` 探针证伪；GoLand 插件必须 PSI/语言感知。**不可行** |
| Claude Code 端口那种“stdout 每行一条通知”的宿主模型（`monitors/watch-markers.mjs`） | 适合 CLI 宿主，不适合 IDE 插件；但其忽略目录列表和“每文件已报告 (line,text) 集合”的去重思路可借鉴。**不可行（模式），思路可借用** |
| 无成功判定就清理 | pi-watcher 的行为是反例；GoLand 插件必须把“agent 成功返回”作为清理前提，失败保留标记并提供重试。**不可行** |

### 5.4 三条硬性建议（针对我们的插件）

1. **事件与线程**：只用 `DocumentListener`/`FileDocumentManagerListener` + VFS 事件；所有解析在 `ReadAction`/后台协程，所有写回在 `WriteCommandAction`；绝不复用 pi-watcher 的同步 FS 扫描。
2. **清理策略**：删除/改写**只针对标记 token 所在注释**，用 PSI 做最小 diff；保留用户相邻注释；支持 Undo；清理前校验 `Document` 版本，避免覆盖并发保存。
3. **可靠性**：持久化“已处理账本 + 队列”；单一批次带超时和取消；agent 桥必须给出明确的 success/failure/timeout 信号；失败保留标记、暴露重试入口；为队列顺序、重启恢复、并发保存建集成测试。

---

## ⑥ 核查过的文件清单与未核实项

### 6.1 核查文件（全部逐行读过）

| 文件 | 行数 | 作用 | 关键内容 |
|---|---|---|---|
| `src/parser.ts` | 243 | 标记解析 | 正则、行注释识别、块合并、意图输入 |
| `src/watcher.ts` | 342 | chokidar 监听 | 两级 debounce、扫描、批构造 |
| `src/ignore.ts` | 240 | 忽略/分类 | 内置 glob、`.gitignore`、大小/二进制/符号链接 |
| `src/state.ts` | 165 | 状态 | 意图 ID、账本、in-flight、过滤 |
| `src/router.ts` | 234 | 派发/队列 | sendUserMessage、排队、settle、retry |
| `src/prompt.ts` | 327 | payload | 指令头、窗口、围栏、截断 |
| `src/cleanup.ts` | 200 | 清理 | 块/行内删除、realpath 防护、失败吞掉 |
| `src/index.ts` | 713 | 扩展入口 | 配置、事件、命令、UI |
| `__tests__/parser.test.ts` | 130 | 测试 | 5 例 |
| `__tests__/watcher.test.ts` | 163 | 测试 | 6 例 |
| `__tests__/router.test.ts` | 115 | 测试 | 4 例 |
| `__tests__/state.test.ts` | 72 | 测试 | 2 例 |
| `__tests__/prompt.test.ts` | 166 | 测试 | 5 例 |
| `__tests__/index.test.ts` | 634 | 测试 | 22 例（含清理/符号链接） |
| `monitors/watch-markers.mjs` | 125 | Claude 端口 | `fs.watch`、750ms、stdout 通知 |
| `monitors/monitors.json` | 7 | Claude 插件配置 | 命令/描述 |
| `package.json` | 37 | 包定义 | `pi.extensions`、依赖版本、脚本 |
| `README.md` | 147 | 文档 | 与代码逐条核对（见 2.11） |
| `Makefile` | 94 | 工程命令 | `make check/test`，无 CI 调用 |
| `vitest.config.unit.ts` / `.integration.ts` / `.llm.ts` | 17/9/9 | 测试配置 | integration/llm 目录不存在 |
| `tsconfig.json` / `biome.json` / `knip.json` / `.jscpd.json` | 16/16/10/12 | 工程配置 | 严格 TS、Biome、重复代码阈值 |
| `.pi/extensions/pi-watcher.json`、`.pi/extensions/simplify-code.json`、`examples/sample-project/example.ts`、`.gitignore`、`.claude-plugin/*` | 3/2/3/6/29 | 项目自身配置/示例 | 仓库内 `enabled:false`（避免自触发） |
| `LICENSE.txt` | 19 | 许可 | WTFPL v2 |
| `bun.lock` / `package.json` | 984/37 | 依赖锁 | chokidar 4.0.3、ignore 7.0.5、minimatch 10.2.5、pi 0.80.6、vitest 3.2.4 |

**外部核查**：

- GitHub API `repos/vedang/pi-watcher`：default_branch `main`、language TypeScript、stars 2、forks 1、created 2026-05-10、pushed 2026-07-19、license "Other"。
- GitHub API `git/trees/main?recursive=1`：完整树无 `.github/`、无 `__tests__/integration` 或 `__tests__/llm`。
- GitHub API issues：只有 #1（VM FS 事件）、#2（open draft PR，分号行内注释）。
- npm `@earendil-works/pi-coding-agent@0.80.6` 类型声明：`ExtensionAPI.on("agent_settled")`、`sendUserMessage`（always triggers a turn）、`appendEntry`（not sent to LLM）、`CONFIG_DIR_NAME = ".pi"`。

**复现命令（[运行] 已执行）**

```bash
git clone --depth 1 https://github.com/vedang/pi-watcher /tmp/piwatcher-evidence/src
cd /tmp/piwatcher-evidence/src
npm install --no-audit --no-fund --ignore-scripts     # 本机无 bun，用 npm 替代
npx vitest run --config vitest.config.unit.ts         # 6 files / 44 tests passed
npx tsc --noEmit                                      # exit 0
npx @biomejs/biome check src __tests__ package.json tsconfig.json biome.json knip.json vitest.config.unit.ts
# Checked 19 files. No fixes applied.
```

**临时探针（未写入仓库，仅用于报告）**：`parser` 边界 23 例、cleanup 误删 4 例、router 队列乱序 2 例、prompt 实际 payload 1 例；运行结果已分别引用在 2.1/2.5/2.6/2.7/2.8。

### 6.2 未核实项（诚实边界）

1. **未运行真实的 pi agent 会话**（无 API key/交互环境）：`sendUserMessage` 之后 `ctx.isIdle()` 是否同步翻转、`agent_settled` 在 agent 报错时是否仍触发、真实 turn 的并发行为，均属 **[推断]**。
2. **未在 Windows/Linux 实测**（本机 macOS）：chokidar 事件行为、`monitors/watch-markers.mjs` 的 `fs.watch({recursive:true})` 在 Linux 的支持情况未验证。
3. **未做性能压测**：大仓库初次 `scan`（同步递归读文件）、事件风暴下的内存/延迟未量化。
4. **未执行 Claude Code 插件路径**（无 Claude Code 环境），其行为仅来自源码阅读。
5. **未核对 npm 包发布产物**：仓库 `package.json` 声明 `main: ./dist/index.js`，但 `pi` 扩展字段直接指向 `./src/index.ts`；`pi install git:...` 的实际加载路径未实测。
6. **未验证配置热更新/多会话并发**：两个 pi 进程同时监听同一项目、账本互不可见时的行为未测试。
7. **未验证 `AI.` 锚点在真实 LLM 会话中的效果**（只验证了 prompt 文本包含锚点）。
8. **未逐条验证 GitHub 之外的社区信息**（仓库只有 2 个 open items，无 tag/release，无 discussions 数据）。
9. **上游可能已变化**：本次快照为 2026-10-18 的 `main@501fa80`（2026-07-13 提交）；后续提交可能改变上述结论，引用时请以 commit 为准。
