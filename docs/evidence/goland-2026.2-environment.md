# GoLand 2026.2.3 环境与插件开发可行性 · 证据报告

> 检查日期：2026-10-18
> 检查对象：本机安装的 GoLand（`/Users/kaughara/Applications/GoLand.app`）
> 目的：为「注释驱动编码伴侣」GoLand 插件第一版确认：平台版本、Go PSI/编辑器 API 是否可用、构建工具链怎么走、插件怎么加载。
> 证据等级：**[文件]** = 直接读取安装包内文件；**[编译]** = 实际用 javac 编译探针；**[文档]** = JetBrains 官方文档；**[推断]** = 由上述推导。

---

## ① 结论摘要（先看这 10 条）

1. GoLand 已安装且版本明确：**2026.2.3 / build GO-262.10968.67**，产品码 `GO`，`minRequiredJavaVersion = 25`，自带 **JBR 25.0.4**（含 `javac`）。**[文件]**
2. Go 插件是**内置**的（`org.jetbrains.plugins.go`，版本与 IDE 同号，`<idea-version since-build="262.10968.67" until-build="262.10968.67"/>`）。Go PSI 类就在安装包内：`plugins/go-plugin/lib/intellij.go.impl.jar`。**[文件]**
3. 关键 Go PSI API 已逐一验证存在（用解析 class 文件的方式，不是猜）：
   - `GoFile.getFunctions()/getMethods()/getTypes()/getImports()/addImport(String,String)/getPackageName()`；
   - `GoFunctionDeclaration.getBlock()/getSignature()/getIdentifier()`（`getBlock()` 直接给出函数体 AST 节点，`null` = 声明无体）；
   - `GoBlock.getStatementList()`；
   - `GoSignature.getParameters()/getResult()/getResultType()`；
   - `GoMethodDeclaration.getReceiver()/getReceiverType()`。**[文件]**
4. 编辑器 Inlay API 完整可用（`intellij.platform.editor.ui.jar`）：`InlayModel.addInlineElement / addBlockElement / addAfterLineEndElement`、`Inlay.update()/repaint()/getBounds()`、`InlayModel.Listener`；`EditorCustomElementRenderer` 的签名是**裸类型** `Inlay`（不是泛型），写代码时不要写 `Inlay<?>`。**[编译]**
5. **javac 直编这条路已验证可行**：用 GoLand 自带 JBR 25 的 `javac --release 21`，classpath = 安装包 `Contents/lib/*.jar`(419 个) + `plugins/go-plugin/lib/*.jar`，编译一个同时用到 Inlay + Go PSI + `WriteCommandAction` + `Document` 的探针类，**1.7 秒通过，产物 class major=65**。**[编译]**
6. **Gradle 这条路本机暂时走不通（网络原因，不是配置问题）**：`services.gradle.org` 的 distribution 请求 307 后长时间 0 字节、`downloads.gradle.org` 超时、`github.com` 不可达；`plugins.gradle.org` / Maven Central 虽可达，但拿不到 Gradle 本体。→ 第一版**不要**把构建绑定在 Gradle 上。**[实测]**
7. 插件描述符可以照官方推荐写：`<depends>com.intellij.modules.platform</depends>` + `<depends>org.jetbrains.plugins.go</depends>` + `<depends>com.intellij.modules.goland</depends>`。后两者的模块 id 在 `product-info.json` 的 60 个 product modules 里**都能找到**（`com.intellij.modules.goland` / `com.intellij.modules.go` / `com.intellij.modules.go-capable`）。**[文件+文档]**
8. 本机没有系统 JDK，也没有 Gradle/Maven；**唯一可用的 JDK 是 GoLand 自带的 JBR 25**，Go 工具链是 go1.27.1（`gofmt` 在 `~/.local/go/bin`），没有 `goimports`、没有 `gopls`。**[文件]**
9. GoLand 还自带两个无 GUI CLI：`Contents/bin/format.sh`（`goland format`）和 `Contents/bin/inspect.sh`（`goland inspect`），未来做「离线格式化/静态检查」验证时可用。**[文件]**
10. **最重要的行为差异（会改变设计）**：IntelliJ 系 IDE 没有「显式保存」作为唯一入口（自动保存/内存 Document），所以触发**不能照搬 Aider / pi-watcher 的“文件保存事件”**，只能做 **Document 级空闲 debounce**（用户停手 N 秒）+ 可选的手动触发 action。**[推断，基于平台机制]**

---

## ② 环境事实（可复现）

### 2.1 版本与运行时

```bash
# /Users/kaughara/Applications/GoLand.app/Contents/Resources/build.txt
GO-262.10968.67

# product-info.json 关键字段
name=GoLand  version=2026.2.3  buildNumber=262.10968.67  productCode=GO
minRequiredJavaVersion=25   modules=60 个   bundledPlugins=87 个

# Contents/jbr/Contents/Home/bin/java -version
openjdk version "25.0.4" 2026-07-21
OpenJDK Runtime Environment JBR-25.0.4+1-508.27-nomod
```

- `Contents/lib` 下 **419 个 jar**；`Contents/plugins` 下 **87 个内置插件**。
- Go 工具链：`go1.27.1 darwin/arm64`，`GOROOT=/Users/kaughara/.local/go`，`GOPATH=/Users/kaughara/go`；`gofmt` 可用。
- 用户配置目录：`~/Library/Application Support/JetBrains/GoLand2026.2/`（已有 `plugins/`、`options/`、`goland.vmoptions`）。

### 2.2 Go 插件

```text
Contents/plugins/go-plugin/lib/
  go-plugin.jar               # 插件主 jar，META-INF/plugin.xml：<id>org.jetbrains.plugins.go</id>
  intellij.go.frontback.jar   # 只含 token types（GoCompositeElementType 等）
  intellij.go.impl.jar        # 含 com.goide.psi.*（GoFile / GoFunctionDeclaration / GoBlock / GoSignature ...）
  intellij.go.impl.jar 同目录还有 modules/（3 个前端模块）、src/（go-openapi-src.jar）
```

`plugin.xml` 关键行（[文件]）：

```xml
<id>org.jetbrains.plugins.go</id>
<version>262.10968.67</version>
<idea-version since-build="262.10968.67" until-build="262.10968.67" />
```

> 注意：`until-build` 与 IDE build **完全相等**，说明 Go 插件是跟 IDE 绑死的。我们的插件声明 `sinceBuild="262"`、`untilBuild="262.*"` 即可，不要抄这个精确值。

### 2.3 Go PSI API 清单（class 文件解析所得，[文件]）

用 Python 解析 `intellij.go.impl.jar` 中 class 常量池得到（完整命令见 §5）：

| 类型 | 已验证的 public 方法 |
|---|---|
| `com.goide.psi.GoFile` | `getFunctions()`、`getFunctions(String)`、`getMethods()`、`getMethods(String)`、`getTypes()`、`getImports()`、`addImport(String, String)`、`getImportList()`、`getPackageName()`、`hasMainFunction()`、`getVars()/getConstants()` |
| `com.goide.psi.GoFunctionDeclaration` | `getBlock(): GoBlock`、`getSignature(): GoSignature`、`getIdentifier()`、`getFunc()`、`getName()`（继承 `PsiNamedElement`） |
| `com.goide.psi.GoFunctionOrMethodDeclaration` | `getBlock()`、`getSignature()`、`getIdentifier()`、`getFunc()` |
| `com.goide.psi.GoMethodDeclaration` | `getReceiver(): PsiElement`、`getReceiverType()`、`getBlock()`、`getSignature()` |
| `com.goide.psi.GoBlock` | `getStatementList(): List` |
| `com.goide.psi.GoSignature` | `getParameters(): GoParameters`、`getResult(): GoResult`、`getResultType(): GoType`、`getTypeParameters()` |
| `com.goide.psi.GoTypeSpec` | `getMethods()`、`getAllMethods()`、`isTypeAlias()`、`getIdentifier()` |

对插件最关键的三点是：
- **定位函数体**：`fn.getBlock()` 直接拿 `GoBlock`（`null` 表示无函数体/接口方法），比正则扫 `{` 稳。
- **取函数边界**：`fn.getTextRange()`；**函数头/签名区**用 `fn.getSignature().getTextRange()`。
- **改函数体**：对 `Document` 做最小 diff 编辑，或用 `PsiElement.replace()`；**不要重写整文件**（会破坏 Local History / 相邻未保存编辑）。

### 2.4 编辑器 / Inlay / 编辑 API（[编译] 已验证）

```java
// 全部来自 intellij.platform.editor.ui.jar / core.jar / ide.jar
InlayModel.addInlineElement(int offset, boolean relatesToPrecedingText, EditorCustomElementRenderer r)
InlayModel.addBlockElement(int offset, boolean above, boolean showAbove, int priority, EditorCustomElementRenderer r)
InlayModel.addAfterLineEndElement(int offset, boolean relatesToPrecedingText, EditorCustomElementRenderer r)
InlayModel.addListener(InlayModel.Listener, Disposable)
Inlay.update() / repaint() / getBounds() / getWidthInPixels() / isValid()

interface EditorCustomElementRenderer {
  int  calcWidthInPixels(Inlay inlay);                       // 裸类型，不是 Inlay<?>
  default void paint(Inlay inlay, Graphics g, Rectangle r, TextAttributes a);
}
```

其余确认存在（用于状态栏 / 侧边栏 / 状态持久化 / 事件）：
`StatusBarWidgetFactory`、`StatusBarWidget`、`ToolWindowFactory`、`PersistentStateComponent`、`@State`、`FileEditorManagerListener`、`EditorFactoryListener`、`DocumentListener`、`StartupActivity`、`DumbAware`、`DumbAwareAction`、`WriteCommandAction`、`CommandProcessor`、`UndoManager`、`Alarm`、`DaemonCodeAnalyzer`、`NotificationGroupManager`、`Messages`、`Gson`（平台自带 `intellij.libraries.gson.jar`）。

> `com.intellij.openapi.startup.PostStartupActivity` 在这个版本**不存在**（已并入 `StartupActivity` / `StartupActivity.DumbAware`）。写代码时以 §2.3/§2.4 表里的类为准，不要照旧教程抄类名。

### 2.5 构建工具链实测

| 方案 | 结果 | 证据 |
|---|---|---|
| **javac 直编**（推荐 v1） | ✅ 通过 | `javac --release 21 -cp <424 jars> ...` → exit 0，1.7s，class major 65；打包用 `/usr/bin/zip` 生成 jar（JBR 无 `jar` 工具，但 `/usr/bin/jar` 存在且可用） |
| Gradle（IPGP 2.x） | ❌ 本机不可行（网络） | `gradle-9.8.1-bin.zip`：`services.gradle.org` 307 后 40s 无字节；`downloads.gradle.org` 超时；`github.com` HTTP 000。IPGP 2.19.0 的 pom 在 `plugins.gradle.org` 可达（200），但 Gradle 本体拿不到 |
| 系统 JDK | ❌ 无 | `/usr/libexec/java_home -V` → "Unable to locate a Java Runtime" |
| Maven | ❌ 无 | `which mvn` 空 |

**结论**：v1 用「JBR 25 的 javac + zip」写一个 30 行 build 脚本即可；Gradle 留作后续（等网络/装好 Gradle 再迁移，工程结构按 Gradle 习惯放 `src/main/java`、`src/main/resources/META-INF/plugin.xml`，迁移成本≈改一个 build 文件）。

### 2.6 插件加载 / 调试方式（无需 Gradle）

- **安装**：把插件 jar（`META-INF/plugin.xml` 在根部）放进
  `~/Library/Application Support/JetBrains/GoLand2026.2/plugins/comment-companion/lib/comment-companion.jar`，重启 GoLand；或用 GUI「Install Plugin from Disk」。
- **沙盒调试**（不污染日常配置）：
  `Contents/MacOS/goland -Didea.config.path=/tmp/goland-sandbox/config -Didea.system.path=/tmp/goland-sandbox/system -Didea.plugins.path=/tmp/goland-sandbox/plugins`
- **断点调试**：给沙盒进程加 `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005`，用任意 Java 调试器 attach。
- `Contents/bin/format.sh`、`Contents/bin/inspect.sh` 可做离线格式化/检查（后续做回归验证时可当“第二双眼睛”）。

> 首次真实加载（plugin.xml 被平台解析、Go PSI 在运行时可用）**尚未实测**，列为 M0 第一件事。

---

## ③ 对第一版设计的具体影响

1. **触发**：不能等“保存”。用 `DocumentListener`（或 `FileDocumentManagerListener` + 文档改动）拿到改动 → `Alarm` 做 1–1.5s 空闲 debounce → 且只在 `ai：` 注释出现/变化时排队。自动保存导致的“文档未变但磁盘变了”不应触发。
2. **反馈**：`InlayModel.addAfterLineEndElement` 适合“写完了 · 第 N 轮”这种**不占文本、不移动光标**的短提示；`addBlockElement(above=true)` 适合“正在写…”占位块；两者都只影响渲染、不进文件内容，天然满足“几秒后自动消失”。问题提示 `ai？` 用**真实注释**（进文件、可持久），inlay 只做“光标附近”的即时提示。
3. **最小 diff**：用 `GoBlock.getTextRange()` 定位函数体区间，在 `Document` 上做区间替换，`WriteCommandAction` 包一层保证可撤销；撤销分组用“一次命令 = 函数体 + 注释规范化 + 同文件 import”实现。
4. **编译基线/回滚**：插件内不能直接跑 `go build` 阻塞 EDT；做法是后台进程 + `ReadAction` 读 `GoFile`，或用 `DaemonCodeAnalyzer` 的结果判断语法/解析错误；真正的编译检查放后台 Task + `Alarm`，结果回到 inlay。
5. **状态**：`PersistentStateComponent`（project 级，`.idea`）+ 内存队列；账本按「文件路径 + 函数签名」为 key，存 round / 指令文本哈希 / 结果哈希 / status。
6. **构建**：`build.sh` = javac + zip；目录按 Gradle 惯例组织，方便以后迁移。

---

## ④ 风险与待 M0 验证项

| # | 风险/待验证 | 说明 | 验证方式 |
|---|---|---|---|
| 1 | 插件真实加载 | plugin.xml 依赖声明是否被 2026.2 接受 | 装进沙盒 GoLand，看 `idea.log` |
| 2 | Go PSI 运行时可见性 | Go 插件做了 frontend/backfront 拆分，第三方插件能否直接 `instanceof GoFile` | 沙盒里写个 action 打印 `GoFile.getFunctions().size()` |
| 3 | Inlay 添加线程/时机 | 平台要求在 EDT 添加，文档/命令上下文是否必须 | 沙盒实测；必要时 `ApplicationManager.getApplication().invokeLater` |
| 4 | Go 插件 API 稳定性 | 非公开 API（`com.goide.*`）随版本变动；`until-build` 精确绑定 | 只用 2026.2.x；升级 IDE 时重编译验证 |
| 5 | 自动保存语义 | 用户手打、格式化、AI 写入都会触发 Document 事件 → 回环风险 | 加“AI 自写忽略”标记 + 文档版本比对 |
| 6 | `--release` 目标版本 | IDE 要求 Java 25 运行时，插件 bytecode 用 21 更保险（若以后兼容 2025.x） | 沙盒里跑一次 |
| 7 | gofmt | 本机有 gofmt；插件里应该用平台 `CodeStyleManager.reformat` 而非起子进程 | 沙盒实测格式化结果 |

---

## ⑤ 复现命令

```bash
APP=/Users/kaughara/Applications/GoLand.app

# 版本
cat "$APP/Contents/Resources/build.txt"
"$APP/Contents/jbr/Contents/Home/bin/java" -version

# 编译探针（classpath = 安装包内全部 jar）
CP=$(python3 - "$APP" <<'PY'
import sys,glob
app=sys.argv[1]
print(":".join(sorted(glob.glob(app+"/Contents/lib/*.jar"))+sorted(glob.glob(app+"/Contents/plugins/go-plugin/lib/*.jar"))))
PY
)
"$APP/Contents/jbr/Contents/Home/bin/javac" --release 21 -nowarn -cp "$CP" -d /tmp/out src/.../Probe.java

# 打包（JBR 无 jar 工具，用 zip）
(cd build/classes && cp -R ../resources/META-INF . && zip -q -r ../comment-companion.jar .)
```

探针源码（已验证可编译的最小骨架，同时覆盖 Inlay + Go PSI + 写文档）：

```java
package probe;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.goide.psi.*;
import java.awt.*;

public class Probe {
  @SuppressWarnings("rawtypes")
  static void feedback(Editor editor) {
    InlayModel model = editor.getInlayModel();
    EditorCustomElementRenderer r = new EditorCustomElementRenderer() {
      @Override public int calcWidthInPixels(Inlay inlay) { return 80; }
      @Override public void paint(Inlay inlay, Graphics g, Rectangle rect, TextAttributes attrs) {}
    };
    model.addAfterLineEndElement(editor.getCaretModel().getOffset(), false, r);
  }

  static void scan(PsiFile file) {
    if (!(file instanceof GoFile goFile)) return;
    for (GoFunctionDeclaration fn : goFile.getFunctions()) {
      GoBlock block = fn.getBlock();                       // null => 无函数体
      int bodyStart = block == null ? -1 : block.getTextRange().getStartOffset();
      int bodyEnd   = block == null ? -1 : block.getTextRange().getEndOffset();
      PsiComment doc = PsiTreeUtil.getPrevSiblingOfType(fn, PsiComment.class);
    }
  }

  static void write(Project project, PsiFile file, String text) {
    Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
    WriteCommandAction.runWriteCommandAction(project, () -> { if (doc != null) doc.insertString(doc.getTextLength(), text); });
  }
}
```

---

## ⑥ 核查清单

- [文件] `Contents/Resources/build.txt`、`product-info.json`、`Contents/plugins/go-plugin/lib/go-plugin.jar!META-INF/plugin.xml`
- [文件] `intellij.go.impl.jar`：`GoFile`、`GoFunctionDeclaration`、`GoFunctionOrMethodDeclaration`、`GoMethodDeclaration`、`GoBlock`、`GoSignature`、`GoTypeSpec` class 常量池
- [文件] `intellij.platform.editor.ui.jar`：`InlayModel`、`Inlay`、`EditorCustomElementRenderer`、`InlayProperties`
- [文件] 平台类存在性：StatusBarWidgetFactory、ToolWindowFactory、PersistentStateComponent、FileEditorManagerListener、EditorFactoryListener、DocumentListener、StartupActivity、WriteCommandAction、UndoManager、Alarm、DaemonCodeAnalyzer、NotificationGroupManager、Gson
- [编译] javac 探针（javac exit=0，class major 65，1.7s）
- [实测] Gradle 下载失败路径（services.gradle.org / downloads.gradle.org / github.com）
- [文档] plugins.jetbrains.com/intellij/goland.html（GoLand 插件开发页，2026-04-07）
- [文档] plugins.jetbrains.com/intellij/tools-intellij-platform-gradle-plugin.html（IPGP 2.x，要求 Gradle 9.0+/Java 17+，`local(...)` 用法）
- [未验证] 插件真实加载、Go PSI 运行时可见性、Inlay 线程约束、沙盒调试流程
