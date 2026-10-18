# Aider `--watch-files` 源码级证据审查报告

**审查对象**：`Aider-AI/aider`（GitHub；任务中写的 `paul-gauthier/aider` 已重定向到该仓库，`api.github.com/repos/Aider-AI/aider` 返回 `full_name: Aider-AI/aider`）
**固定版本**：commit `5dc9490bb35f9729ef2c95d00a19ccd30c26339c`，提交时间 2026-05-22，`aider/__init__.py` 显示 `__version__ = "0.86.3.dev"`
**取证方式**：`codeload.github.com` tarball 解包（`/tmp/aider-evidence/aider-main`）+ GitHub Contents API 补取 `tests/basic/test_watch.py`、`aider/website/docs/usage/watch.md`、`tests/fixtures/watch*.{py,js,lisp}`、`pyproject.toml`、`requirements.txt`；依赖源码取自 `samuelcolvin/watchfiles` tag `v1.1.1` 与 `Aider-AI/grep-ast` main（版本 caveat 见第 ⑥ 节）。
**行号约定**：所有 `path:line` 均为上述 commit 内文件行号。未安装 aider 运行时，第 1 项的正则行为用同一份正则在本机 Python 3 复现验证（见 2.1 实测表），其余均为逐行读码证据。

---

## ① 结论摘要（10 条）

1. **标记识别只有一处实现**：`aider/watch.py` 内的 `FileWatcher.ai_comment_pattern`（`aider/watch.py:69-71`）与 `get_ai_comments()`（`aider/watch.py:257-281`）。`watch_prompts.py` 只存放 prompt 文本，不参与识别。
2. **精确匹配规则**：`(?:#|//|--|;+) *(ai\b.*|ai\b.*|.*\bai[?!]?) *$`，`re.IGNORECASE`，用 `.search()`（非 `fullmatch`，不锚定行首）。注释前缀只认 `#`、`//`、`--`、`;+`；`ai` 必须作为单词出现在注释内容**开头**（`ai\b`）或整行**结尾**（`\bai[?!]? *$`），中缀不匹配；`AI`/`ai`/`Ai` 均可，`aider` 因词边界被排除（`tests/fixtures/watch.py:10`）。
3. **触发语义**：只有 `AI!` → 改代码、`AI?` → 问答时才真正调用模型；纯 `AI` 注释只把文件加入 chat 并打印提示（`aider/watch.py:202-207`）。
4. **接线路径**：`--watch-files`（`aider/args.py:520-525`）→ `main.py:1028-1036` 实例化 `FileWatcher` 并挂到 `coder.file_watcher` → `io.get_input()` 在**每次显示输入提示符前** `start()`、在 `finally` 里 `stop()`（`aider/io.py:646-651, 688-692`）；文件变更会 `io.interrupt_input()`（`aider/watch.py:142`、`aider/io.py:516-521`），随后 `process_changes()` 返回的字符串被当作**用户输入**交给 `coder.run_one()`。**当前 HEAD 没有 `/watch` 斜杠命令**（全仓 grep 仅命中 HISTORY/blame 文档）。
5. **监听库**：`watchfiles==1.1.1`（`requirements.txt:458`），`watch()` 默认 `debounce=1600ms`、`step=50`、`recursive=True`（watchfiles `watchfiles/main.py:52-66`），Aider 未覆盖这些参数（`aider/watch.py:150-155`）。
6. **防回环不靠内容指纹，靠时间窗口**：watcher 只在"等待用户输入"期间存活（`io.py:646-692`），模型处理/写文件时 watcher 已停；`start()` 每次重置 `changed_files`（`aider/watch.py:167`）。代码里**没有** processed 集合、mtime 或"自己写的文件"判定（全文件 318 行读完确认）。
7. **标记清理完全交给 LLM**：`watch_code_prompt` 要求模型删除 AI 注释（`aider/watch_prompts.py:6`），但代码中**没有任何删除/改写标记的语句**；`watch_ask_prompt`（`AI?` 问答模式）**连删除指令都没有**（`aider/watch_prompts.py:9-12`）。因此 `AI?` 之后标记几乎必然残留，下次保存会再次触发。
8. **上下文构造**：prompt 后追加 `repo相对路径:` + `grep-ast` 的 `TreeContext` 片段——只给"标记行 + `loi_pad=3` 邻域 + 语法父作用域"，行首用 `█` 标出标记行、`⋮` 省略未显示行（`aider/watch.py:232-250`；`grep_ast/grep_ast.py:108-135, 208-240`，`█` 在 `:229`）。同时被改动文件会先被加入 `coder.abs_fnames`（`aider/watch.py:191-200`），所以模型还能在常规 chat 上下文中看到文件全文与 repo map。
9. **串行但顺序不确定**：单 watcher 线程、一批变更只处理一次；`changed_files` / `abs_fnames` 都是 `set`，字符串哈希随机化使**文件遍历顺序跨进程可变**；`has_action` 采用"最后一次赋值生效"（`aider/watch.py:186-189, 266-278`），因此同时存在 `AI!` 与 `AI?` 时，改代码还是问答**取决于 set 迭代顺序**。
10. **测试与文档不覆盖机制本身**：`tests/basic/test_watch.py` 只有 4 个单测（gitignore 规则、watch roots、handle_changes、fixture 计数），没有 `process_changes` / 触发链路 / 防回环 / 清理的集成测试。文档 `aider/website/docs/usage/watch.md:290-294` 明确写了 "inspired by the way [Override] watches for file changes to find prompts embedded within `//> a specific set of delimiters <//`"。

---

## ② 逐项证据

### 2.1 触发标记：`AI` / `AI!` / `AI?` 的精确匹配规则

**结论**：正则 + IGNORECASE + `.search()`；注释前缀限定为 `#`、`//`、`--`、`;+`；标记必须位于注释内容开头或整行结尾；`has_action` 由逐行覆盖式赋值决定，最后匹配到的一行说了算。

**实现文件**：仅 `aider/watch.py`（`ai_comment_pattern` 与 `get_ai_comments`），`watch_prompts.py` 只有 prompt 文本。

`aider/watch.py:69-71`
```python
    # Compiled regex pattern for AI comments
    ai_comment_pattern = re.compile(
        r"(?:#|//|--|;+) *(ai\b.*|ai\b.*|.*\bai[?!]?) *$", re.IGNORECASE
    )
```

`aider/watch.py:266-281`
```python
        for i, line in enumerate(content.splitlines(), 1):
            if match := self.ai_comment_pattern.search(line):
                comment = match.group(0).strip()
                if comment:
                    line_nums.append(i)
                    comments.append(comment)
                    comment = comment.lower()
                    comment = comment.lstrip("/#-;")  # Added semicolon for Lisp comments
                    comment = comment.strip()
                    if comment.startswith("ai!") or comment.endswith("ai!"):
                        has_action = "!"
                    elif comment.startswith("ai?") or comment.endswith("ai?"):
                        has_action = "?"
```

**我们的解读**：
- 前缀后的空格是 `*` 不是 `+`，所以 `#AI!`、`#ai!`（无空格）都合法；fixture 也刻意覆盖（`tests/fixtures/watch.py:7-8`）。
- 分支 1/2 完全相同（`ai\b.*` 重复写了两遍），属冗余代码，行为等价于一个分支：`前缀 + 空格* + ai + 词边界 + 行尾任意内容`。
- 分支 3 `.*\bai[?!]? *$` 只要求"行尾是 ai / ai! / ai?"，不要求位置在注释开头——所以 `# helper ai` 也算 AI 注释（`x = 1  # ai!` 这类行尾注释同样命中）。这与文档一致：`watch.md:40` "one-liner comments … start or end with `AI`, `AI!` or `AI?`"。
- `\b` 同时排除了 `aider`（fixture：`tests/fixtures/watch.py:10` `# aider is not an ai comment`，实测不匹配）。
- `has_action` 是**逐行覆盖**而非优先级聚合：同一文件里 `AI!` 在前、`AI?` 在后，最终是 `"?"`；反之是 `"!"`（实测见下表）。`AI?` 与 `AI!` 同一行时，`startswith("ai!")` 不成立、`endswith("ai!")` 也不成立（行尾是 `line`），于是按 `ai?` 判为 `"?"`。

**实测表**（用 `aider/watch.py:70` 的同一正则 + `:266-278` 的判断逻辑在本机 Python 3 复现；`None` 表示不匹配）：

| 输入行 | 是否匹配（group 0） |
|---|---|
| `# Make a snake game. AI!` | 匹配，`# Make a snake game. AI!` |
| `# What is the purpose of this method AI?` | 匹配 |
| `// Write a protein folding engine. AI!` | 匹配 |
| `#ai 1 do something` | 匹配 |
| `x = 1  # ai!` | 匹配，仅 `# ai!` |
| `;; ai lisp` | 匹配 |
| `-- sql ai` | 匹配 |
| `# helper ai` | 匹配（行尾 ai） |
| `# AI! extra words here` | 匹配（注释开头 ai） |
| `# comment mentioning ai in middle` | **不匹配**（中缀不算） |
| `# aiders thing` / `# aider is not an ai! comment` | **不匹配**（词边界） |
| `/* ai */` | **不匹配**（无 `/*` 前缀） |
| `<!-- ai -->` | **误匹配**为 `-- ai -->`（`--` 前缀碰巧命中，HTML 注释的假阳性） |
| `//> prompt <//` | **不匹配**（Override 的 `//> … <//` 定界符语法并未实现） |
| `# finale` | 不匹配 |
| `print('ai')` | 不匹配（无注释前缀） |

**触发类型覆盖顺序实测**：

| 文件内容（行序） | `has_action` |
|---|---|
| `# fix this ai!` → `# why? ai?` | `?`（后者覆盖前者） |
| `# why? ai?` → `# fix this ai!` | `!` |
| `# why ai? and fix ai!`（同一行，`ai!` 在行尾） | `!`（`endswith("ai!")` 成立） |
| 仅 `# note ai` | `None`（不触发模型，只加文件） |

> 注：同一行同时出现 `AI?` 与 `AI!` 时，判定顺序是「先去前缀后 `startswith("ai!")` → `endswith("ai!")` → 否则 `startswith("ai?")` → `endswith("ai?")`」。因此只有 `ai!` 恰好位于注释开头或行尾才会判 `!`；否则落到 `?`（例如 `# why ai? then fix ai! now` 连 `!` 都不算行尾、`ai?` 也不在开头/行尾，则该行不贡献 action）。代码没有"`!` 优先于 `?`"的规则。

---

### 2.2 `--watch-files` 如何进入主循环；`/watch` 命令是否存在

**结论**：`--watch-files` 只是一个 CLI 布尔参数，真正接线在 `io.get_input()` 的输入提示符生命周期里；没有 `/watch` 斜杠命令。

`aider/args.py:520-525`
```python
    group.add_argument(
        "--watch-files",
        action=argparse.BooleanOptionalAction,
        default=False,
        help="Enable/disable watching files for ai coding comments (default: False)",
    )
```

`aider/main.py:1022-1036`
```python
    ignores = []
    if git_root:
        ignores.append(str(Path(git_root) / ".gitignore"))
    if args.aiderignore:
        ignores.append(args.aiderignore)

    if args.watch_files:
        file_watcher = FileWatcher(
            coder,
            gitignores=ignores,
            verbose=args.verbose,
            analytics=analytics,
            root=str(Path.cwd()) if args.subtree_only else None,
        )
        coder.file_watcher = file_watcher
```

`aider/io.py:646-651`（提示符前启动）与 `aider/io.py:670-675`、`688-692`（被打断后取命令、finally 停止）
```python
                    self.interrupted = False
                    if not multiline_input:
                        if self.file_watcher:
                            self.file_watcher.start()
                        if self.clipboard_watcher:
                            self.clipboard_watcher.start()
```
```python
                # Check if we were interrupted by a file change
                if self.interrupted:
                    line = line or ""
                    if self.file_watcher:
                        cmd = self.file_watcher.process_changes()
                        return cmd
...
            finally:
                if self.file_watcher:
                    self.file_watcher.stop()
                if self.clipboard_watcher:
                    self.clipboard_watcher.stop()
```

`aider/coders/base_coder.py:876-892`
```python
    def run(self, with_message=None, preproc=True):
        try:
            if with_message:
                self.io.user_input(with_message)
                self.run_one(with_message, preproc)
                return self.partial_response_content
            while True:
                try:
                    if not self.io.placeholder:
                        self.copy_context()
                    user_message = self.get_input()
                    self.run_one(user_message, preproc)
                    self.show_undo_hint()
                except KeyboardInterrupt:
                    self.keyboard_interrupt()
```

**`/watch` 命令的核实结果**：`aider/commands.py` 中所有斜杠命令由 `cmd_xxx` 方法自动注册（`aider/commands.py:334-335` 注释 "any method called cmd_xxx becomes a command automatically"），全仓 grep `"/watch"` / `cmd_watch` 只命中 `aider/website/HISTORY.md` 与 `aider/website/_data/blame.yml`，**没有任何 `/watch` 命令实现**。因此在本 commit 中：
- `--watch-files`：启动期开关；置位后 watcher 在每次等待输入时激活。
- `/watch`：不存在。若需要"聊天中途开关 watcher"，当前源码不支持。

**两种触发结果的不同投递路径**（这是 `/watch` 问题之外最关键的接线细节）：

`aider/watch.py:209-216`
```python
        if self.analytics:
            self.analytics.event("ai-comments execute")
        self.io.tool_output("Processing your request...")

        if has_action == "!":
            res = watch_code_prompt
        elif has_action == "?":
            res = watch_ask_prompt
```

- `AI!` → `watch_code_prompt` 是**纯文本**（不以 `/` 开头）→ 作为普通用户消息进入当前 coder（保留当前 edit format）。
- `AI?` → `watch_ask_prompt` 首行是 `/ask`（`aider/watch_prompts.py:9`）→ `base_coder.preproc_user_input()` 判定为命令（`base_coder.py:912-917`）→ `commands.run()`（`commands.py:300-333`）→ `cmd_ask()`（`commands.py:1182-1184`）→ `_generic_chat_command(args, "ask")`（`commands.py:1206-1222`），即另建一个 ask 模式子 coder 跑问答，不改文件。

`aider/commands.py:300-309`
```python
    def matching_commands(self, inp):
        words = inp.strip().split()
        if not words:
            return

        first_word = words[0]
        rest_inp = inp[len(words[0]) :].strip()
```

---

### 2.3 文件监听机制

**结论**：`watchfiles`（Rust notify 后端），递归监听 repository root（或 `--subtree-only` 时的 cwd），只监听"顶层未被 ignore 的条目"作为根、但递归覆盖子树；`filter_func` 再逐事件过滤：目录外、gitignore/内置 ignore、>1MB、读不出、无 AI 注释的文件全部丢弃。删除/重命名没有专门处理——过滤器完全不看 `change_type`。

`requirements.txt:458`
```text
watchfiles==1.1.1
```

`aider/watch.py:145-162`
```python
    def watch_files(self):
        """Watch for file changes and process them"""
        try:
            roots_to_watch = self.get_roots_to_watch()

            for changes in watch(
                *roots_to_watch,
                watch_filter=self.filter_func,
                stop_event=self.stop_event,
                ignore_permission_denied=True,
            ):
                if self.handle_changes(changes):
                    return
```

watchfiles 默认参数（`watchfiles/main.py:52-66`，tag `v1.1.1`）：
```python
def watch(
    *paths: Union[Path, str],
    watch_filter: Optional[Callable[['Change', str], bool]] = DefaultFilter(),
    debounce: int = 1_600,
    step: int = 50,
    ...
    recursive: bool = True,
    ignore_permission_denied: Optional[bool] = None,
) -> Generator[Set[FileChange], None, None]:
```

`aider/watch.py:90-119`（过滤器）
```python
    def filter_func(self, change_type, path):
        """Filter function for the file watcher"""
        path_obj = Path(path)
        path_abs = path_obj.absolute()

        if not path_abs.is_relative_to(self.root.absolute()):
            return False

        rel_path = path_abs.relative_to(self.root)
        ...
        if self.gitignore_spec and self.gitignore_spec.match_file(
            rel_path.as_posix() + ("/" if path_abs.is_dir() else "")
        ):
            return False

        # Check file size before reading content
        if path_abs.is_file() and path_abs.stat().st_size > 1 * 1024 * 1024:  # 1MB limit
            return False
        ...
        try:
            comments, _, _ = self.get_ai_comments(str(path_abs))
            return bool(comments)
        except Exception:
            return
```

`.git` 等排除靠 pathspec 内置模式（`aider/watch.py:15-62`）：
```python
    patterns = [
        ".aider*",
        ".git",
        # Common editor backup/temp files
        "*~",  # Emacs/vim backup
        "*.bak",  # Generic backup
        "*.swp",  # Vim swap
        ...
        ".idea/",  # JetBrains IDEs
        ".vscode/",  # VS Code
        ...
        "node_modules/",  # Node.js dependencies
        "vendor/",  # Various dependencies
```

`aider/watch.py:121-133`（监听根）
```python
    def get_roots_to_watch(self):
        """Determine which root paths to watch based on gitignore rules"""
        if self.gitignore_spec:
            roots = [
                str(path)
                for path in self.root.iterdir()
                if not self.gitignore_spec.match_file(
                    path.relative_to(self.root).as_posix() + ("/" if path.is_dir() else "")
                )
            ]
            # Fallback to watching root if all top-level items are filtered out
            return roots if roots else [str(self.root)]
        return [str(self.root)]
```

**我们的解读**：
- 监听是"递归 + 事件级过滤"：顶层被 ignore 的目录不会进入 `roots_to_watch`，但**嵌套**的 `node_modules/`、`.idea/` 仍会被 OS 层递归监听，只是事件在 `filter_func` 里被丢弃（有性能成本，但不影响正确性）。
- `filter_func` 每次事件都真正读文件、跑正则（`get_ai_comments`），大仓库高频保存时是 O(每次保存×文件大小) 的同步 IO。
- **`change_type` 参数完全未使用**：删除事件 → `read_text` 返回 `None`（`aider/io.py:453-476` 的 `FileNotFoundError` 分支在 `silent=True` 下返回 `None`）→ `get_ai_comments` 返回 `(None,None,None)` → 过滤掉；重命名通常以"新路径的 add/modified 事件"到达（notify 后端语义），新路径若仍含注释就会被当作普通修改处理。没有 rename 配对逻辑，也没有"删除后从 chat 移除"的逻辑。
- 大小上限 1MB、编码错误/不可读 → 一律静默丢弃（`except Exception: return`，返回 `None` 即 falsy）。
- 内置 ignore 列表**只在 `gitignore_spec` 非空时生效**：`load_gitignores([])` 直接返回 `None`（`aider/watch.py:16-18`）。在 git 仓库中 `main.py` 总会把 `.gitignore` 路径塞进列表，故内置模式生效；在非 git 目录（`git_root` 为空且无 `--aiderignore`）则 `gitignore_spec is None`，`.idea/`、`node_modules/` 等内置排除**不生效**（只是仍然要求文件含 AI 注释才会被处理）。

---

### 2.4 防抖/节流与"等待用户停止输入"策略

**结论**：Aider 自身**没有**写任何 debounce/idle 阈值代码；防抖来自 watchfiles 的默认 `debounce=1600ms`（+ `step=50ms`）。"等待用户停止输入"由**生命周期**保证：watcher 只在 `prompt_session.prompt()` 阻塞等待输入时运行，一旦检测到合格的变更就打断输入、消费这一批变更，然后**整个 watcher 线程退出**（`handle_changes` 返回 `True` → `watch_files` return）。处理期间发生的修改**不会被记录**，也没有队列。

`aider/watch.py:135-143`
```python
    def handle_changes(self, changes):
        """Process the detected changes and update state"""
        if not changes:
            return False

        changed_files = {str(Path(change[1])) for change in changes}
        self.changed_files.update(changed_files)
        self.io.interrupt_input()
        return True
```

`aider/watch.py:150-157`
```python
            for changes in watch(
                *roots_to_watch,
                watch_filter=self.filter_func,
                stop_event=self.stop_event,
                ignore_permission_denied=True,
            ):
                if self.handle_changes(changes):
                    return
```

`aider/watch.py:164-179`
```python
    def start(self):
        """Start watching for file changes"""
        self.stop_event = threading.Event()
        self.changed_files = set()

        self.watcher_thread = threading.Thread(target=self.watch_files, daemon=True)
        self.watcher_thread.start()

    def stop(self):
        """Stop watching for file changes"""
        if self.stop_event:
            self.stop_event.set()
        if self.watcher_thread:
            self.watcher_thread.join()
            self.watcher_thread = None
            self.stop_event = None
```

`aider/io.py:516-521`
```python
    def interrupt_input(self):
        if self.prompt_session and self.prompt_session.app:
            # Store any partial input before interrupting
            self.placeholder = self.prompt_session.app.current_buffer.text
            self.interrupted = True
            self.prompt_session.app.exit()
```

**我们的解读**：
- **没有独立的 idle 阈值**："用户停止输入"指用户在终端里停在提示符（watcher 只在此时存在）；"文件停止变动"由 watchfiles 的 1600ms quiet window 决定。两者叠加，Aider 侧没有额外逻辑。
- 多文件连续保存（间隔 <1600ms）会被合并进同一批 `changes` 一次性处理；不同文件在同一批里只处理一个 prompt（见 2.8）。
- **处理期间再次修改 = 永久丢弃**：`get_input` 的 `finally` 在 `process_changes()` 返回后立即 `stop()`，模型整个 `run_one()` 期间 watcher 不运行；`start()` 会重置 `changed_files`（`:167`），watchfiles 重新起步时不会补发旧事件。所以"模型思考时用户又保存了一次文件"在这次处理结束后不会自动重放，除非用户再存一次。
- 用户已输入一半的文字不会丢：`interrupt_input` 把它存进 `io.placeholder`（`:519`），下一次提示符作为 default 显示（`io.py:641-644`）。
- 一个易忽略的细节：`io.py:647` 的 `if not multiline_input:` 意味着**多行输入模式**下 watcher 根本不启动。

---

### 2.5 防回环：Aider 自己写文件会不会再次触发 watcher？

**结论**：**不会因为"自己写"而触发**，但机制是"时序 + 内容门槛"，不是"写者身份识别"。代码中没有任何 processed 集合、mtime 记录、inode 比对或 "aider 写了这个文件" 的标记；防回环完全依赖三件事：(a) 写文件发生在 watcher 停止期间；(b) 重启 watcher 后 watchfiles 不会补发旧事件；(c) 过滤器要求"文件当前仍含 AI 注释"才会进入处理。

**证据 (a)：watcher 只在等待输入时存活** — `aider/io.py:646-692`（已在上文引用：`start()` 在 prompt 前，`stop()` 在 finally）。

**证据 (b)：一批变更处理完立即退出线程** — `aider/watch.py:150-157`（`handle_changes` 返回 True → return）。

**证据 (c)：内容门槛** — `aider/watch.py:114-117`
```python
        # Check if file contains AI markers
        try:
            comments, _, _ = self.get_ai_comments(str(path_abs))
            return bool(comments)
```

**证据 (d)：不存在 processed/mtime 记录** — 通读 `aider/watch.py` 全部 318 行，仅有的状态是 `stop_event`、`watcher_thread`、`changed_files`、`gitignores`、`gitignore_spec`（`aider/watch.py:73-88` 构造函数）；全文件无 `mtime`、无 `processed`、无 `hash`。可用 `grep -n "mtime\|processed" aider/watch.py` 复现为空。

`aider/watch.py:73-82`
```python
    def __init__(self, coder, gitignores=None, verbose=False, analytics=None, root=None):
        self.coder = coder
        self.io = coder.io
        self.root = Path(root) if root else Path(coder.root)
        self.verbose = verbose
        self.analytics = analytics
        self.stop_event = None
        self.watcher_thread = None
        self.changed_files = set()
        self.gitignores = gitignores
```

**我们的解读（回环成立/不成立的条件）**：
- 正常路径不回环：模型改写文件时 watcher 已停；下次启动 watcher 不会收到这段时间内的变更。
- **残留回环**：如果模型没有删掉 AI 注释（prompt 只是"要求"删除，不是"保证"），文件里标记仍在；**用户下一次保存任意其它文件时**，watch 重启后的新事件只覆盖"下一次保存的文件"，本轮模型写过的文件不会被再次通知——除非用户再次保存它。也就是说 Aider 的防回环是"事件驱动 + 时序"，而不是"内容处理后打标"。
- **真正的高危组合**：同一轮里模型重写了带标记的文件，而该标记所在的文件恰好也被用户/编辑器自动保存（例如 IDE format-on-save、外部 linter `--fix` 回写）→ 新事件重新命中，标记未删就会二次触发。源码没有任何"本轮已处理"的去重屏障。
- `AI?` 模式因为 prompt 完全没要求删除（见 2.6），残留标记几率接近 100%，属于事实上的"重复触发源"。

---

### 2.6 处理完的标记清理：删除、改写还是保留？

**结论**：**源码不清理**。唯一的清理手段是 prompt 里对 LLM 的自然语言要求（仅 `AI!`/code 模式有）；`AI?`/ask 模式的 prompt 不含删除指令。代码里没有任何"删除注释行/改写标记"的逻辑，所以标记是否消失取决于模型行为与 edit format。

`aider/watch_prompts.py:1-12`（全文）
```python
watch_code_prompt = """
I've written your instructions in comments in the code and marked them with "ai"
You can see the "AI" comments shown below (marked with █).
Find them in the code files I've shared with you, and follow their instructions.

After completing those instructions, also be sure to remove all the "AI" comments from the code too.
"""

watch_ask_prompt = """/ask
Find the "AI" comments below (marked with █) in the code files I've shared with you.
They contain my questions that I need you to answer and other instructions for you.
"""
```

`aider/watch.py:257-281`（`get_ai_comments` 只读，不写回；全文见 2.1）

**我们的解读**：
- **`AI!`（code 模式）**：`watch_prompts.py:6` 明确要求 "remove all the "AI" comments from the code too"，但这是给模型的指令，不是代码保证：模型可能只删部分、改写措辞、或因 diff 失败而保留。源码没有校验，也没有"标记仍在 → 自动清理/告警"的逻辑。
- **`AI?`（ask 模式）**：`watch_prompts.py:9-12` 只要求"回答问题"，**完全没有删除标记的要求**；ask coder 本身也不写文件（`cmd_ask` → `_generic_chat_command` → `Coder.create(edit_format="ask")`，`commands.py:1182-1222`）。因此 `AI?` 标记处理完仍在文件里，这正是 2.5 所说的高危残留。
- **用户想重跑怎么办**：由于没有持久化记录，重跑的语义就是"重新制造变更事件"。docs 提供两个官方姿势：手动再保存一次文件（触发 watcher），或在聊天里 `/undo` 回滚（`watch.md:180-182`）；标记被删后要重跑只能重新写一条标记。文档还说明：仅想"把文件加入 chat"时，可以写 `#AI` 注释、保存后立刻撤销该注释，文件仍留在 chat（`watch.md:161-166`），这依赖 `process_changes` 在返回空串前已完成 `abs_fnames.add`（`aider/watch.py:191-200`）。
- 代码层面没有"已处理标记"的登记，因此同一标记若未被删，**下次任意保存触发时会被当成新指令再次执行**——这是"重跑"在当前实现里唯一的内置途径，也是重复执行的隐患。

---

### 2.7 上下文构造：触发时发给模型什么？`AI` 的"锚点"语义如何体现？

**结论**：发给模型的是 **(1) 触发 prompt**（`watch_prompts.py`）**(2) 每个已跟踪文件里所有 AI 注释行 + 语法上下文片段**（`grep-ast.TreeContext`，标记行用 `█`，邻域 ±3 行，父作用域）**(3) 常规 chat 上下文**（被改动文件先加入 `abs_fnames`，所以文件全文、repo map、其它已加文件随正常请求发送）。**不是全文重复塞进 watch 段**；watch 段只给"锚点 + 局部上下文"。

`aider/watch.py:218-255`
```python
        # Refresh all AI comments from tracked files
        for fname in self.coder.abs_fnames:
            line_nums, comments, _action = self.get_ai_comments(fname)
            if not line_nums:
                continue

            code = self.io.read_text(fname)
            if not code:
                continue

            rel_fname = self.coder.get_rel_fname(fname)
            res += f"\n{rel_fname}:\n"

            # Convert comment line numbers to line indices (0-based)
            lois = [ln - 1 for ln, _ in zip(line_nums, comments) if ln > 0]

            try:
                context = TreeContext(
                    rel_fname,
                    code,
                    color=False,
                    line_number=False,
                    child_context=False,
                    last_line=False,
                    margin=0,
                    mark_lois=True,
                    loi_pad=3,
                    show_top_of_file_parent_scope=False,
                )
                context.lines_of_interest = set()
                context.add_lines_of_interest(lois)
                context.add_context()
                res += context.format()
            except ValueError:
                for ln, comment in zip(line_nums, comments):
                    res += f"  Line {ln}: {comment}\n"
```

`grep_ast/grep_ast.py:108-135`（`Aider-AI/grep-ast` main；`add_context` 先加 `lines_of_interest`，再按 `loi_pad` 扩邻域；`parent_context` 在 `TreeContext.__init__` 中默认 `True`（`grep_ast.py:18`），watch 调用未覆盖它，因此还会 `add_parent_scopes()` 补出标记行所在的语法父作用域；`last_line=False` 则不补文件末尾行）
```python
    def add_lines_of_interest(self, line_nums):
        self.lines_of_interest.update(line_nums)

    def add_context(self):
        if not self.lines_of_interest:
            return

        self.done_parent_scopes = set()

        self.show_lines = set(self.lines_of_interest)

        if self.loi_pad:
            for line in list(self.show_lines):
                for new_line in range(line - self.loi_pad, line + self.loi_pad + 1):
                    if new_line >= self.num_lines:
                        continue
                    if new_line < 0:
                        continue
                    self.show_lines.add(new_line)

        if self.parent_context:
            for i in set(self.lines_of_interest):
                self.add_parent_scopes(i)
```

`grep_ast/grep_ast.py:225-233`（`█` 标记与 `⋮` 省略号）
```python
            if i in self.lines_of_interest and self.mark_lois:
                spacer = "█"
                if self.color:
                    spacer = f"\033[31m{spacer}\033[0m"
            else:
                spacer = "│"

            line_output = f"{spacer}{self.output_lines.get(i, line)}"
```

`aider/watch.py:191-200`（先入 chat，再看动作）
```python
            if fname in self.coder.abs_fnames:
                continue
            if self.analytics:
                self.analytics.event("ai-comments file-add")
            self.coder.abs_fnames.add(fname)
            rel_fname = self.coder.get_rel_fname(fname)
            if not added:
                self.io.tool_output()
                added = True
            self.io.tool_output(f"Added {rel_fname} to the chat")
```

**我们的解读**：
- **"上下文锚点"的语义**：AI 注释写在代码里的**具体位置**（哪个函数、哪一行）就是锚点。`TreeContext` 把标记行前后 3 行、以及语法父作用域渲染成带行号省略（`⋮`）的片段，用 `█` 高亮"指令所在行"，让模型看到"指令在什么作用域、上下文是什么"。这正是 docs "Behind the scenes" 描述的行为（`watch.md:255-286`），也是该功能区别于"在聊天框里描述需求"的核心。
- **两个层次的上下文**：watch 段是"定位+指令"；完整文件内容通过 `abs_fnames` 进入常规 prompt（`aider/io.py` 的 file context 逻辑），docs 明确写 "Aider sends your AI comments to the LLM with the repo map and all the other code context you've added to the chat"（`watch.md:255-259`）。因此一个改动文件会同时出现"全文"和"锚点片段"。
- **非 tree-sitter 文件**：`TreeContext` 构造/解析抛 `ValueError` 时降级为 `Line N: <comment>` 纯文本（`aider/watch.py:251-253`），HISTORY v0.68.0 的 "Bugfix so watch files works with files that don't have tree-sitter support" 对应此处。
- **prompt 文本与文档有漂移**：`watch_prompts.py:1-7` 的当前措辞是 "I've written your instructions in comments in the code and marked them with "ai""，而 `watch.md:264-268` 引用的示例 prompt 是旧版本 "The "AI" comments below marked with █ can be found in the code files I've shared with you. They contain your instructions. Make the requested changes…"。文档示例与实际代码不一致（不影响行为，但说明 docs 未随 prompt 更新）。

---

### 2.8 串行/并发：多个标记同时存在时怎么排序处理

**结论**：**单 watcher 线程 + 单次 LLM 调用**，没有并发。一批变更 → 一个 prompt → 一次 `run_one()`；该 prompt 汇总**所有** `abs_fnames` 文件的 AI 注释。排序上：文件级由 `set` 迭代顺序决定（**跨进程不稳定**，且 `AI!`/`AI?` 模式判定会因此翻转）；文件内按行号升序；多文件在 prompt 里的呈现顺序同样来自 `set`。没有"每次处理一个标记"的队列。

`aider/watch.py:181-220`
```python
    def process_changes(self):
        """Get any detected file changes"""

        has_action = None
        added = False
        for fname in self.changed_files:
            _, _, action = self.get_ai_comments(fname)
            if action in ("!", "?"):
                has_action = action

            if fname in self.coder.abs_fnames:
                continue
            ...
        if not has_action:
            ...
            return ""

        if self.analytics:
            self.analytics.event("ai-comments execute")
        self.io.tool_output("Processing your request...")

        if has_action == "!":
            res = watch_code_prompt
        elif has_action == "?":
            res = watch_ask_prompt

        # Refresh all AI comments from tracked files
        for fname in self.coder.abs_fnames:
            line_nums, comments, _action = self.get_ai_comments(fname)
```

**我们的解读**：
- `self.changed_files` 是 `set`（`aider/watch.py:81, 141, 167`），`abs_fnames` 也是 `set`；Python 对 `str` 的哈希默认随机化（`PYTHONHASHSEED` 未固定），同一仓库两次运行可能得到不同遍历顺序。
- `has_action` 在遍历 `changed_files` 时被**覆盖**（`:187-189`）：若文件 A 只有 `AI!`、文件 B 只有 `AI?`，最终模式取决于 A/B 谁最后被遍历 → **改代码 vs 只问答可能随机**。这是当前实现最值得引以为戒的设计缺陷之一。
- 文件内顺序：`get_ai_comments` 用 `enumerate(...splitlines(), 1)` 升序（`:266`），呈现也是行号升序。
- 一次触发把所有已跟踪文件的注释**全部**发给模型（`:219`），模型在一次回复里可能要改多个文件；watch 模式没有"只处理当前文件"的隔离。
- 单线程：watcher 线程只负责"收事件 → 打断提示符"；真正的 LLM 调用跑在主线程的 `coder.run_one()` 里，天然串行。处理期间新的文件变更无人监听（见 2.4）。

---

### 2.9 错误处理：失败时标记保留还是删除？有无用户确认/重试？

**结论**：**没有用户确认、没有自动重试、没有标记状态管理**。失败时标记按"文件里实际内容"保留（因为根本没有清理代码）；部分异常会被静默吞掉，另一部分会把 watch 请求变成空输入。

`aider/watch.py:145-162`（watcher 线程异常：记录日志后**重新抛出**，线程死亡）
```python
    def watch_files(self):
        """Watch for file changes and process them"""
        try:
            roots_to_watch = self.get_roots_to_watch()

            for changes in watch(
                *roots_to_watch,
                watch_filter=self.filter_func,
                stop_event=self.stop_event,
                ignore_permission_denied=True,
            ):
                if self.handle_changes(changes):
                    return

        except Exception as e:
            if self.verbose:
                dump(f"File watcher error: {e}")
            raise e
```

`aider/watch.py:114-119`（过滤阶段异常静默丢弃）
```python
        # Check if file contains AI markers
        try:
            comments, _, _ = self.get_ai_comments(str(path_abs))
            return bool(comments)
        except Exception:
            return
```

`aider/io.py:677-687`（`get_input` 兜底异常：把错误当输入错误打印并返回空串）
```python
            except EOFError:
                raise
            except Exception as err:
                import traceback

                self.tool_error(str(err))
                self.tool_error(traceback.format_exc())
                return ""
```

**我们的解读**：
- **watcher 线程内异常**：daemon 线程里 `raise` 只会把 traceback 打到 stderr，**主 REPL 继续运行**；该次 watch 轮次的变更丢失，下一次提示符会新建线程（`start()` 重建 `stop_event`/`thread`）。
- **过滤阶段**：文件不可读、编码错误、stat 失败等一律 `return None`（falsy），静默跳过，用户无感知。
- **`process_changes` 阶段异常**：没有自己的 try/except；异常冒泡到 `io.get_input()` 的 `except Exception`，被当作"输入出错"，返回 `""`，于是这一轮 watch 指令**被丢弃**（模型不会看到），标记原样留在文件里，等待下一次保存再次触发。
- **模型执行失败**：`run_one()` 的失败处理属于通用 chat 逻辑（LLM 错误、diff 失败等），与 watch 无特殊约定；标记是否残留取决于模型是否已部分写回。没有任何"watch 任务失败 → 保留/回滚标记"的状态机。
- **无确认**：从检测到触发不询问用户（`--yes` 等无关），`watch.py` 中不存在 `confirm_ask` 调用；唯一的人工介入点是用户看到 `Processing your request...` 后用 Ctrl-C 中断（走通用 `keyboard_interrupt()`）。
- 因此对插件设计的启示是：Aider 的错误语义是"**幂等重试靠用户再存一次**"，插件必须自行补上可观测状态。

---

### 2.10 测试与文档；"受 Override 启发"的原文

**结论**：测试只覆盖工具函数与正则/fixture 计数，**没有覆盖 watch 主链路（触发、上下文、防回环、清理）**；文档齐全但 prompt 示例已过时；文档明确致谢 Override，但 Override 的 `//> … <//` 语法在代码中**没有实现**（实测不匹配）。

**测试文件**：`tests/basic/test_watch.py`（共 166 行，4 个测试：`test_gitignore_patterns`、`test_get_roots_to_watch`、`test_handle_changes`、`test_ai_comment_pattern`）。

`tests/basic/test_watch.py:115-134`（fixture 计数断言）
```python
def test_ai_comment_pattern():
    # Create minimal IO and Coder instances for testing
    io = InputOutput(pretty=False, fancy_input=False, yes=False)
    coder = MinimalCoder(io)
    watcher = FileWatcher(coder)
    fixtures_dir = Path(__file__).parent.parent / "fixtures"

    # Test Python fixture
    py_path = fixtures_dir / "watch.py"
    py_lines, py_comments, py_has_bang = watcher.get_ai_comments(str(py_path))
    ...
    py_expected = 10
    assert len(unique_py_comments) == 10, (
```

`tests/basic/test_watch.py:99-112`（`handle_changes` 单测；只验证采集，不验证后续处理）
```python
def test_handle_changes():
    io = InputOutput(pretty=False, fancy_input=False, yes=False)
    coder = MinimalCoder(io)
    watcher = FileWatcher(coder)

    # Test no changes
    assert not watcher.handle_changes([])
    assert len(watcher.changed_files) == 0

    # Test with changes
    changes = [("modified", "/path/to/file.py")]
    assert watcher.handle_changes(changes)
    assert len(watcher.changed_files) == 1
    assert str(Path("/path/to/file.py")) in watcher.changed_files
```

**关键缺口**（读完全部 4 个测试可确认）：没有 `process_changes()` 的测试；没有"混用 `AI!`/`AI?` 时哪种模式生效"的测试；没有"模型写回后标记是否残留"的测试；没有 watcher 生命周期（`io.get_input` 中 start/stop）的测试；没有错误路径测试。

**fixture**（`tests/fixtures/watch.py`，覆盖大小写/无空格/行尾/词边界/纯 `AI`）：
```python
# Regular not AI comment
#ai 1 do something
# AI 2 make this better
# ai! 3 urgent change needed
#AI! 4 another urgent one
# this is not an ai comment
# aider is not an ai comment
# not an ai! comment
```

```python
    # final 6 ai!
    # final 7 ai
```
（`tests/fixtures/watch.py:4-11, 16-17`；同名 fixture 还有 `watch.js`、`watch_question.js`、`watch.lisp`，`watch.lisp` 专门覆盖 `;`、`;;`、`;;;`、`;;;;` 四种前缀。）

**文档文件**：`aider/website/docs/usage/watch.md`（294 行）。
- 触发语法与语义：`:37-62`（"looks for one-liner comments … start or end with `AI`, `AI!` or `AI?`"；`AI!` 改代码、`AI?` 问答）。
- 注释风格：`:85-97`（只列 `#`、`//`、`--` 三种；**未提 `;+`**，尽管代码与 Lisp fixture 支持；HISTORY v0.69.0 也仅提 `#`/`//`/`--`）。
- 多标记/长指令/加文件：`:119-142`、`:144-159`、`:161-166`。
- "Behind the scenes"：`:255-286`（说明会带 repo map、highlight 注释上下文；示例 prompt 为旧版措辞，见 2.7）。
- **Override 致谢原文**（`aider/website/docs/usage/watch.md:288-294`）：
```markdown
--------

#### Credits

*This feature was inspired by
the way [Override](https://github.com/oi-overide) watches for file changes
to find prompts embedded within `//> a specific set of delimiters <//`.*
```

**HISTORY 中的演进证据**：v0.67.0 引入（`aider/website/HISTORY.md:559-565`："One-liner `# ...` or `// ...` comments that start or end with "AI" are instructions"）；v0.69.0 增加 `AI?`、改为监听所有文件、支持 `--`（`:522-527`）；v0.69.1 增加提示（`:515-518`）；v0.70.0 支持 `--subtree-only` 并改进 prompt（`:501-506`）。

**我们的解读**：文档与实现存在三处漂移（已在上文标注）：(1) 示例 prompt 是旧文本；(2) 注释风格未写 `;+`；(3) Override 的定界符语法仅作灵感，未落地为兼容语法。测试覆盖意味着"标记识别"这一层有回归保护，而"触发→上下文→写回→清理"这条最有风险的链路几乎没有自动化保障。

---

## ③ 机制表

| 环节 | 实现方式 | 关键位置 | 备注 |
|---|---|---|---|
| CLI 开关 | `argparse` 布尔参数，`--watch-files` / `--no-watch-files` | `aider/args.py:520-525` | 默认 `False` |
| 实例化 | `FileWatcher(coder, gitignores=[.gitignore, --aiderignore], root=cwd if --subtree-only else None)` | `aider/main.py:1022-1036` | 同时挂到 `coder.file_watcher`（`:1036`）和 `io.file_watcher`（`watch.py:88`） |
| 生命周期 | 每次显示输入提示符前 `start()`；`finally` 中 `stop()`；检测到变更 `interrupt_input()` | `aider/io.py:646-651, 688-692`；`aider/io.py:516-521` | 多行输入模式不启动（`:647`） |
| 监听库/参数 | `watchfiles==1.1.1`：`debounce=1600ms`、`step=50ms`、`recursive=True`（默认值，Aider 未覆盖） | `requirements.txt:458`；watchfiles `watchfiles/main.py:52-66`；`aider/watch.py:150-155` | Rust notify 后端 |
| 监听路径 | 仓库根（或 `--subtree-only` 的 cwd）的顶层非 ignore 条目；递归覆盖子树 | `aider/watch.py:121-133` | 嵌套 ignore 目录仍被 OS 监听，靠过滤器丢弃 |
| 排除规则 | 内置 ignore（`.aider*`、`.git`、备份、`.idea/`、`.vscode/`、`node_modules/`、`vendor/` 等）+ `.gitignore` + `--aiderignore` | `aider/watch.py:15-62` | 内置模式仅当 `gitignore_spec` 非空时生效（非 git 目录退化） |
| 事件过滤 | 不在 root 下、命中 ignore、>1MB、读失败、无 AI 注释 → 丢弃；**不看 change_type** | `aider/watch.py:90-119` | 删除/重命名无专门处理 |
| 防抖 | 无自有逻辑；依赖 watchfiles 1600ms quiet window | `aider/watch.py:150-155` | 连续保存合并为一批 |
| 变更采集 | 一批变更路径存入 `set`；`interrupt_input()` 打断提示符；`watch_files` 立即 return（线程结束） | `aider/watch.py:135-143, 150-157` | 处理期间的变更不记录、不重放 |
| 标记识别 | 单正则 + IGNORECASE + `search`；前缀 `#`/`//`/`--`/`;+`；开头或行尾 `ai`；`aider` 被词边界排除 | `aider/watch.py:69-71, 257-281` | 全仓唯一实现 |
| 动作判定 | `comment.lower().lstrip("/#-;")` 后 `startswith/endswith("ai!")` → `!`，`("ai?")` → `?`；**逐行覆盖，最后者生效** | `aider/watch.py:272-278` | 无 `!` 优先级 |
| 文件入 chat | 变更文件先 `abs_fnames.add`；无动作时打印提示并返回 `""` | `aider/watch.py:191-207` | 纯 `AI` 只加文件 |
| 模式分派 | `AI!` → code prompt（普通消息）；`AI?` → prompt 以 `/ask` 开头 → 走命令系统建 ask 子 coder | `aider/watch.py:209-216`；`aider/watch_prompts.py:1-12`；`commands.py:300-333, 1182-1222` | 两条完全不同的执行路径 |
| 上下文 | prompt + 每个 `abs_fnames` 文件的 `TreeContext`（标记行 `█`、`loi_pad=3`、`⋮` 省略）；解析失败降级 `Line N: comment` | `aider/watch.py:218-255`；grep-ast `grep_ast/grep_ast.py:108-135, 208-240` | 文件全文另经 chat 上下文发送 |
| 串行/排序 | 单 watcher 线程；一批一次 LLM 调用；文件顺序由 `set` 迭代决定（不稳定）；文件内行号升序 | `aider/watch.py:181-220` | `!`/`?` 混用可能随机 |
| 防回环 | 时间窗口（watcher 仅在等待输入时存活）+ 内容门槛（必须仍含标记）；**无 processed/mtime 记录** | `aider/io.py:646-692`；`aider/watch.py:114-117, 167` | 模型写文件时 watcher 已停 |
| 标记清理 | 仅 `AI!` prompt 里要求模型删除；`AI?` 不要求；**代码无删除逻辑** | `aider/watch_prompts.py:6, 9-12` | 是否残留取决于 LLM |
| 错误处理 | watcher 线程异常 raise（线程死、主循环继续）；过滤异常静默；`process_changes` 异常被 `get_input` 兜底成空输入 | `aider/watch.py:159-162, 118-119`；`aider/io.py:679-684` | 无确认/重试/状态机 |

---

## ④ 局限与坑（源码可证）

1. **`AI!` 与 `AI?` 混用时模式不确定**：`has_action` 逐次覆盖 + `changed_files` 是 `set`（`watch.py:186-189`）→ 谁最后遍历到谁决定"改代码还是问答"。同一仓库不同进程（`PYTHONHASHSEED` 随机）都可能不同。
2. **`AI?` 不清理标记**：`watch_ask_prompt`（`watch_prompts.py:9-12`）没有删除指令，ask coder 也不写文件 → 标记永久残留，下次保存重复提问。
3. **`AI!` 清理不可靠**：删除只靠模型遵守 prompt（`watch_prompts.py:6`），源码零校验、零兜底；模型改写措辞（如把 `AI!` 改成 `TODO`）也不会被检测。
4. **处理期间的编辑会丢**：watcher 在模型运行期间停止（`io.py:688-692`），`start()` 重置 `changed_files`（`watch.py:167`），watchfiles 不补发旧事件 → 模型思考时保存的文件改动不会被自动处理。
5. **非 git 目录忽略规则失效**：`load_gitignores([])` 返回 `None`（`watch.py:16-18`），内置的 `.idea/`、`node_modules/` 等排除不生效。
6. **删除/重命名无显式语义**：`filter_func` 忽略 `change_type`（`watch.py:90`）；删除文件只会静默丢弃事件，chat 里的文件条目与 repo map 不会同步清理（需手动 `/drop`）。
7. **HTML 注释假阳性**：`<!-- ai -->` 会因 `--` 前缀被匹配为 AI 注释（实测），在含 HTML/XML 的仓库里属于误报。
8. **`\b` 词边界的行为边界**：`aider` 被排除（好），但 `# helper ai`、`# see ai` 这类"行尾恰好是 ai"的普通注释也会被算作 AI 注释并进入 prompt（`watch.py:70` 分支 3），可能误导模型。
9. **每次事件同步读全文件**：`filter_func` → `get_ai_comments` → `io.read_text`（`watch.py:116, 262`），大文件/频繁保存时有明显 IO 与正则开销；1MB 上限只挡超大文件。
10. **`process_changes` 异常会吞掉本轮指令**：`io.get_input` 的 `except Exception` 返回 `""`（`io.py:679-684`），用户只看到 traceback，标记残留但这一轮不会执行。
11. **没有 `/watch` 命令**：无法在聊天中途开启/关闭 watcher；只能启动时用参数，或退出重启。
12. **文档与代码漂移**：docs 示例 prompt 是旧版（`watch.md:264-268` vs `watch_prompts.py:1-7`）；docs 未记录 `;+` 前缀（`watch.md:85-97`）。

---

## ⑤ 对 GoLand「注释驱动编码伴侣」插件的借鉴清单

> 判定含义：**可直接移植思路** = 概念/算法可照搬，实现用 Kotlin/Platform API 重写；**需要 PSI+Inlay 重写** = 想法可用，但必须基于 IntelliJ 平台能力（PSI、Inlay、DocumentListener、VFS）重新实现；**不可行** = 在当前平台上不应/无法照搬。

### A. 可直接移植思路（实现层用平台 API）

| # | 借鉴点 | 理由 | 判定 |
|---|---|---|---|
| A1 | 标记语法：前缀白名单 + 大小写不敏感 + "注释开头或行尾" | 简单、可预期的语法是低学习成本的关键；正则/分支结构可直接改写为 Kotlin 正则或 token 判断 | 可直接移植思路 |
| A2 | `AI` / `AI!` / `AI?` 三级语义（`AI` 只挂载上下文，`AI!` 改、`AI?` 答） | 区分"收集上下文"与"执行动作"能降低误触发；语义分层值得保留 | 可直接移植思路 |
| A3 | 防抖窗口（quiet window，Aider 用 1600ms） | 保存/格式化/linter 回写会连续产生事件，必须合并；在 IDE 里用 `Alarm` + 重置更自然 | 可直接移植思路 |
| A4 | 触发后"打断用户输入、把生成请求作为一次普通对话输入" | 复用同一套会话/历史/撤销，交互一致 | 可直接移植思路 |
| A5 | 失败时保留标记，让用户"再保存一次"即重试 | 无状态、实现简单、符合用户直觉 | 可直接移植思路 |
| A6 | 上下文锚点：只给"标记行 ±3 行 + 所在作用域"，而不是整文件重复注入 | 省 token、定位准；IDE 里可用 PSI 元素范围替代 `TreeContext` | 可直接移植思路（数据结构换成 PSI） |
| A7 | 一批变更一次处理、串行执行 | 避免并发写冲突；IDE 的 write action 也必须串行 | 可直接移植思路 |
| A8 | 测试用 fixtures 覆盖标记变体与计数 | Aider 的 `tests/fixtures/watch.*` 模式可直接变成 Kotlin `testData` + 参数化测试 | 可直接移植思路 |
| A9 | 用 Inlay 在标记旁显示"运行/提问"提示（Aider 用 `█` 高亮注释行） | 把不可见的文件内容变成可点 UI；这是 GoLand 的强项 | 需要 PSI+Inlay 重写 |

### B. 需要 PSI+Inlay 重写

| # | 借鉴点 | 理由 | 判定 |
|---|---|---|---|
| B1 | 文件变更监听 | 不应用 `watchfiles`/文件系统轮询：IDE 已有 `DocumentListener`、`FileDocumentManagerListener`、VFS 事件；需处理未保存文档（Aider 只处理已存盘文件，IDE 可处理内存文档） | 需要 PSI+Inlay 重写 |
| B2 | 标记识别 | `#`/`//`/`--`/`;+` 的正则扫描可换成 PSI `PsiComment` + 语言映射；但 `.md`/`.txt` 等无 PSI 文件仍要退回文本扫描；`<!-- -->` 假阳性可在 PSI 层避免 | 需要 PSI+Inlay 重写 |
| B3 | 作用域上下文 | Aider 依赖 tree-sitter 的 `TreeContext`（Python 库，含 `█`/`⋮` 渲染）；GoLand 应用 PSI：取标记所在的 `PsiElement` 作用域、渲染带省略的片段，并用 Inlay 显示 | 需要 PSI+Inlay 重写（`grep-ast` 直接移植不可行） |
| B4 | 触发模式选择（`!` vs `?`） | 不要复刻 Aider 的"set 迭代最后者生效"：应显式排序（路径 → 行号），并让 `AI!` 优先于 `AI?`，同时在 UI 上显示"本次将改代码/只问答" | 需要 PSI+Inlay 重写（必须重新设计） |
| B5 | 防回环 | Aider 靠"watcher 停止的时间窗口"，IDE 里模型写文件会触发 `DocumentListener`；必须用写标记/`modificationStamp`/批量写抑制（`ReadWriteAccess` 或显式 suppress 标志）区分"自己写"与"用户写" | 需要 PSI+Inlay 重写（必须重新设计） |
| B6 | 标记清理 | 不能只靠 LLM 删除：应在写入完成后用 PSI 校验标记是否还在，残留则提示/提供"一键清理"，或把标记转为不可见状态；否则 `AI?` 必然重复触发 | 需要 PSI+Inlay 重写（必须重新设计） |
| B7 | 多文件顺序与批处理 | Aider 一批只发一个 prompt；IDE 中应支持"按文件/按标记逐个执行 + 可取消"，并显示队列（Inlay/工具窗口） | 需要 PSI+Inlay 重写 |
| B8 | 错误可观测性 | Aider 的失败是静默/丢请求；IDE 应用 `Problems` 面板/通知中心显示"哪一行标记失败、可重试"，并与 `Undo` 集成 | 需要 PSI+Inlay 重写 |
| B9 | 文件加入上下文 | Aider 用 `abs_fnames` 集合隐式加入；IDE 里应显式展示上下文范围（Inlay/状态栏），支持只读/可编辑区分 | 需要 PSI+Inlay 重写 |

### C. 不可行（不要照搬）

| # | 借鉴点 | 理由 | 判定 |
|---|---|---|---|
| C1 | 直接复用 `watchfiles` + Python `FileWatcher` 线程模型 | 跨语言依赖、与 IDE 事件模型冲突；平台事件已覆盖 | 不可行 |
| C2 | 依赖 LLM prompt 作为标记清理的唯一机制 | 不可验证、不可回滚；`AI?` 场景会无限重复触发 | 不可行（作为唯一机制） |
| C3 | 把 `grep-ast`/`TreeContext` 代码搬进插件 | 是 Python 库、依赖 tree-sitter 语法包与 Python 运行时；Kotlin 侧无对应物，需按 PSI 重写 | 不可行 |
| C4 | Override 的 `//> … <//` 定界符语法 | Aider 也只是"受启发"未实现（实测不匹配）；自定义定界符与注释语法冲突、易误报 | 不可行（无必要） |
| C5 | "任意文件、任意注释前缀"不受文件类型限制 | IDE 需要对二进制/生成文件/大文件设限（Aider 已有 1MB 上限与编码失败丢弃的先例）；完全放开会拖慢索引与 daemon | 不可行（需限制） |
| C6 | 无 `/watch` 开关、只能启动时开启 | IDE 插件应提供 action/toggle（工具栏、Settings、Gutter 图标），照搬"只能启动时开"不符合 IDE 交互 | 不可行 |

**建议的最小可行架构（据此报告推导）**：`PsiComment` 识别标记 → `InlayModel` 在标记行渲染 `▶ 执行` / `? 提问` 芯片 → 点击后显式确认范围（本次涉及哪些标记）→ 单次串行调用模型 → `WriteCommandAction` 写回并记录 `modificationStamp` 抑制自身事件 → PSI 复核标记是否清理，残留则提示并提供一键删除 → 全程在 Tool Window 显示状态与失败重试。这样既保留 Aider 的"注释即指令 + 上下文锚点"体验，又补上 Aider 缺失的确定性、防回环与可观测性。

---

## ⑥ 核查过的文件清单与未核实项

### 已核查文件（逐行阅读）

| 文件 | 方式 | 覆盖内容 |
|---|---|---|
| `aider/watch.py`（318 行，全文） | tarball | 正则、过滤、监听、生命周期、process_changes、上下文、错误路径 |
| `aider/watch_prompts.py`（12 行，全文） | tarball | code/ask 两套 prompt；删除指令差异 |
| `aider/io.py`（相关段 453-521、523-760） | tarball | `read_text`、`interrupt_input`、`get_input` 的 watch 生命周期（start/return/stop） |
| `aider/main.py`（38、1022-1036、1159-1164） | tarball | `FileWatcher` 实例化与 REPL 主循环 |
| `aider/args.py`（520-525） | tarball | `--watch-files` 参数定义 |
| `aider/commands.py`（300-333、1182-1222、`cmd_` 列表） | tarball | 命令分发、`/ask` 路径、确认无 `/watch` |
| `aider/coders/base_coder.py`（122、182、338-362、876-922） | tarball | `file_watcher` 传递、主循环、preproc 命令分派 |
| `requirements.txt`（120、243、458） | API | `watchfiles==1.1.1`、`grep-ast==0.9.0`、`pathspec==1.0.4` |
| `pyproject.toml` | API | 依赖来自 `requirements.txt`（dynamic） |
| `tests/basic/test_watch.py`（166 行，全文） | API | 4 个单测；确认无集成测试 |
| `tests/fixtures/watch.py`、`watch.js`、`watch_question.js`、`watch.lisp` | API | 标记变体与计数断言输入 |
| `aider/website/docs/usage/watch.md`（294 行，全文） | API | 语法说明、示例、Behind the scenes、Override 致谢 |
| `aider/website/HISTORY.md`（501-565 相关段） | tarball | 功能引入与演进（v0.67.0 → v0.70.0） |
| `watchfiles` tag `v1.1.1` `watchfiles/main.py:52-66` | API | `debounce=1600`、`step=50`、`recursive=True` 默认值 |
| `Aider-AI/grep-ast` main `grep_ast/grep_ast.py:108-135, 208-240` | API | `add_context` 与 `█` 标记渲染；**版本 caveat 见下** |

### 未核实项 / 证据边界

1. **未运行 aider 本体**：本机未安装 aider 运行时依赖，所有结论来自读码 + 用同一正则做的独立复现（2.1 实测表）。命令行真实行为（`Processing your request...` 的时序、中断提示符的终端表现）未实际执行验证。
2. **`grep-ast` 版本未严格对齐**：`requirements.txt:120` 锁定 `grep-ast==0.9.0`，但该仓库（`Aider-AI/grep-ast`，原 `paul-gauthier/grep-ast` 已重定向）没有 `0.9.0` tag，我读取的是 `main` 分支的 `grep_ast/grep_ast.py`。其中 `█`、`add_context`、`loi_pad` 的行号可能与安装的 0.9.0 有偏移；报告引用这些行号时已标注来源为 upstream main，`watch.py` 侧的调用参数（`mark_lois=True`、`loi_pad=3` 等）不依赖该偏移。
3. **watchfiles 的 Rust notify 后端语义未实验**：`debounce` 合并、rename 事件形态（add/delete 配对）来自库文档与 notify 一般行为，未在 macOS/Windows 上实测；Aider 侧"忽略 `change_type`"这一事实本身已由源码确认。
4. **未审计 git 历史**：没有逐 commit blame 复核 `--watch-files` 的引入/修改过程，仅用 `HISTORY.md` 作为版本演进证据（v0.67.0 引入等）。
5. **Override 项目本身未审计**：仅凭 Aider 文档的致谢文字引用，未阅读 Override 源码验证其"文件监听 + 定界符"实现细节；`//> … <//` 在 Aider 中不匹配是实测结论，不代表 Override 的行为。
6. **未验证编辑器自动保存/format-on-save 与 watcher 的实际叠加**：2.5 的"高危组合"是基于事件模型的推演，未在真实 IDE 中复现。
