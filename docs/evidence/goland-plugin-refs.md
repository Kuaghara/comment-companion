# GoLand 插件参考仓库审查：工程模式与 Go PSI 手法（证据版）

> 审查对象：官方文档「Open Source Plugins for GoLand」列出的两个仓库
> —— <https://github.com/pkondratev/Intellij-go-method-generator>（下称 **R1**）
> 与 <https://github.com/OddCN/go-builder-generator-idea-plugin>（下称 **R2**）。
>
> 本地目标环境：GoLand **2026.2.3**，build **GO-262.10968.67**（`/Users/kaughara/Applications/GoLand.app`），
> 自带 JBR **25.0.4**、Go 插件 **262.10968.67**。
>
> 审查时间：2026-10-18。所有结论只写可复核的事实；推断处一律标注「推断」；未核实项集中在 §6。

---

## 0. 方法与证据等级

### 0.1 取证方式

| 途径 | 说明 |
| --- | --- |
| 仓库取证 | `git clone` 被网络阻断（`github.com:443` 连不上、`raw.githubusercontent.com` 000）。改用 **codeload tarball** 下载解包，并用 GitHub API 核对文件树/commit/branch。快照落在 `/tmp/golrefs/`。 |
| IDE 取证 | 直接读本地 GoLand 安装的 `product-info.json`、`build.txt`、各 `*.jar`。系统无 JDK（`javap` 不可用，JBR 已裁剪 `jdk.jdeps`），故自写最小 class 文件解析器 `/tmp/golrefs/ClassDump.py`、`/tmp/golrefs/RefDump.py`，从常量池读出**类/方法/字段签名与 `@Deprecated` 标记**。 |
| 官方文档取证 | `plugins.jetbrains.com/docs/intellij/*` 可访问（HTTP 200）；官方模板 `JetBrains/intellij-platform-plugin-template` 经 GitHub Contents API 取原文。 |

### 0.2 快照标识（可复现）

| 仓库 | 分支 | HEAD commit | 提交日期 | License | Stars/Forks | 最后 push |
| --- | --- | --- | --- | --- | --- | --- |
| R1 pkondratev/Intellij-go-method-generator | `master`（唯一分支） | `1897113721088a7c9c3c7e3f2b0ff96722837532` | 2018-03-10 | GPL-3.0 | 4 / 2 | 2018-03-10 |
| R2 OddCN/go-builder-generator-idea-plugin | `master`（唯一分支） | `6d1a48bf740d35cacb7594cd9c93491affc29cfe` | 2018-07-02 | MIT | 26 / 7 | 2018-07-02 |

### 0.3 证据等级标记

- **【仓】**：仓库内文件 + 行号，可直接打开核对。
- **【IDE】**：本地 GoLand 2026.2.3 的 jar 内 class 结构（类/方法/字段/注解），可复跑脚本核对。
- **【档】**：JetBrains 官方文档/官方模板原文（URL 见 §5 引用）。

---

## 1. 首要结论（必须先看）

**被官方文档列为 GoLand 插件参考的这两个仓库，都不是 Gradle 工程，也完全没有使用 Go PSI。**

| 核查项 | R1 | R2 |
| --- | --- | --- |
| `build.gradle(.kts)` / `settings.gradle(.kts)` / `gradle.properties` / `gradle/wrapper/**` | **0 个** | **0 个** |
| 是否有 `.github/`（CI） | 无 | 无 |
| 是否有 `src/test` | 无 | 无 |
| 构建方式 | IntelliJ DevKit 的 `*.iml`（`PLUGIN_MODULE`） | 无构建描述文件，仅手工提交 `PluginGoBuilderGenerator.jar` |
| `import com.goide.*` 次数 | **0** | **0** |
| `import com.intellij.psi.*` 次数 | **0** | **0** |
| 实际编辑手段 | `Document.replaceString`（按行正则替换文本） | `Document.insertString`（选中文本正则解析 → 字符串拼接） |
| `plugin.xml` 依赖 | `com.intellij.modules.lang` | `com.intellij.modules.lang` |
| 代码语言/年代 | Java，2018-03 | Java，2018-07 |

复现命令（在两个快照目录执行，均可重跑）：

```bash
# 1) 构建文件：无输出
$ find . -name 'build.gradle*' -o -name 'settings.gradle*' -o -name 'gradle-wrapper.properties' -o -name 'gradle.properties'

# 2) Go PSI / 平台 PSI：唯一命中是类名含子串 "GoMethod" 的 GenerateGoMethodsClass，
#    不是 PSI 引用（R1 src/GenerateGoMethodsClass.java:13/15/19 与 plugin.xml:45）
$ grep -rn -E 'com\.goide|GoFile|GoFunction|GoBlock|GoSignature|GoTypeSpec|GoMethod|GoPsiImplUtil|GoTypeDeclaration|PsiElement|PsiFile|com\.intellij\.psi' \
    --include=*.java --include=*.xml --include=*.md .
```

两仓库合计：`com.goide` **0**、`com.intellij.psi` **0**。

```bash
# 3) 写命令/格式化/inlay：0 条
$ grep -rn -E 'CommandProcessor|CodeStyleManager|reformat|adjustLineIndent|PostprocessReformatting|InlayModel|EditorCustomElementRenderer|Inlay' \
    --include=*.java .
```

**因此：**
1. 任务要求核实的「`goland(...)` / `create(IntelliJPlatformType.GoLand, ...)` / `bundledPlugin("org.jetbrains.plugins.go")` / Gradle wrapper 版本 / JDK-Kotlin toolchain / `pluginConfiguration` / `patchPluginXml`」在这两个仓库里**全部不存在**，无法从它们提炼 2.x 骨架（§2.1 给出核查表）。
2. 「枚举函数 / 取函数体 / 取签名 / 改函数体 / 加 import」的 Go PSI 手法在这两个仓库里**没有任何实例**（§3.1 给出 grep 证据）；它们用的是行级文本正则。
3. 可复制的 2.x 骨架只能来自官方文档 + 官方模板（§2.5，已明确标注来源，不属于这两个仓库）。
4. 两个仓库仍有价值的地方：`plugin.xml` 描述符的最小写法、Action 注册写法、`EditorWriteActionHandler`/`WriteCommandAction` 两种写操作历史写法（其中一种在 2026.2 已不能编译，见 §4）。

---

## 2. ① 可复制工程骨架

### 2.1 构建配置核查表（任务 A 项逐条）

| 任务要求核查项 | R1 结果 | R2 结果 | 证据 |
| --- | --- | --- | --- |
| intellij platform 版本 | 不存在 | 不存在 | 两仓库无任何 gradle 文件【仓】 |
| `goland("...")` | 不存在 | 不存在 | 同上 |
| `create(IntelliJPlatformType.GoLand, ...)` | 不存在 | 不存在 | 同上 |
| `bundledPlugin("org.jetbrains.plugins.go")` | 不存在 | 不存在 | 同上 |
| Gradle wrapper 版本 | 不存在（无 `gradle/`） | 不存在（无 `gradle/`） | 两仓库文件树无 `gradle/`【仓】 |
| JDK/Kotlin toolchain | 只有 `.iml` 的 `inheritedJdk` | 无 | R1 `GoMethodGenerator.iml:10`【仓】 |
| `intellijPlatform { }` 块（plugins / pluginConfiguration / ideaVersion） | 不存在 | 不存在 | 无 gradle 文件【仓】 |
| `patchPluginXml` | 不存在 | 不存在 | 无 gradle 文件【仓】 |

### 2.2 R1 的真实骨架（可直接照抄的只有描述符部分）

文件树（GitHub API `git/trees/HEAD?recursive=1`，共 8 项 = 5 个文件 + `resources/`、`resources/META-INF/`、`src/` 三个目录）：

```
GoMethodGenerator.iml              613 B
LICENSE                           35147 B   (GPL-3.0)
README.md                          2183 B
resources/META-INF/plugin.xml      1759 B
src/GenerateGoMethodsClass.java    2858 B   ← 默认包，无 package 声明
```

唯一的“构建描述”是 DevKit 模块文件 `GoMethodGenerator.iml`（全文 13 行）：

```xml
 1  <?xml version="1.0" encoding="UTF-8"?>
 2  <module type="PLUGIN_MODULE" version="4">
 3    <component name="DevKit.ModuleBuildProperties" url="file://$MODULE_DIR$/resources/META-INF/plugin.xml" />
 4    <component name="NewModuleRootManager" inherit-compiler-output="true">
 5      <exclude-output />
 6      <content url="file://$MODULE_DIR$">
 7        <sourceFolder url="file://$MODULE_DIR$/src" isTestSource="false" />
 8        <sourceFolder url="file://$MODULE_DIR$/resources" type="java-resource" />
 9      </content>
10      <orderEntry type="inheritedJdk" />
11      <orderEntry type="sourceFolder" forTests="false" />
12    </component>
13  </module>
```

`resources/META-INF/plugin.xml` 关键段（原文）：

```xml
 2    <id>com.kondratevpavel.plugins.goland.gomethodgenerator</id>
 3    <name>Go method generator</name>
 4    <version>1.0</version>
 5    <vendor email="pavelgeme2@gmail.com" url="https://bobbykitten.github.io">Kondratev Pavel</vendor>
...
29    <idea-version since-build="162.0"/>
...
36    <depends>com.intellij.modules.lang</depends>
37
39    <extensions defaultExtensionNs="com.intellij">
40      <!-- Add your extensions here -->
41    </extensions>
43    <actions>
44      <!-- Add your actions here -->
45      <action id="generate_go_method" class="GenerateGoMethodsClass" text="GenerateGoMethods"
46              description="Generate Go Methods">
47        <keyboard-shortcut keymap="$default" first-keystroke="shift ctrl alt G"/>
48      </action>
49    </actions>
```

可复制点：`resources/META-INF/plugin.xml` 位置 + `<actions>` 里「`class` 用全限定名（此处因默认包所以是裸类名）」+ `<keyboard-shortcut>` 写法。
不可复制点：`.iml` 构建方式（DevKit 已被 IntelliJ Platform Gradle Plugin 1.x/2.x 取代）、默认包类。

### 2.3 R2 的真实骨架

文件树（GitHub tree 共 20 项，含目录；文件 11 个，下表省略二进制 `pic/demo.gif` 3.1 MB）：

```
.gitignore                                65 B
LICENSE                                 1069 B   (MIT)
PluginGoBuilderGenerator.jar            8887 B   ← 手工构建产物，committed
README.md                               2275 B
resources/META-INF/plugin.xml           1872 B
src/com/cn/oddcn/GoBuilderGeneratorAction.java     1871 B
src/com/cn/oddcn/entity/StructEntity.java           330 B
src/com/cn/oddcn/entity/StructGenerateResult.java   358 B
src/com/cn/oddcn/util/BuilderUtil.java             3279 B
src/com/cn/oddcn/util/StructUtil.java              2574 B
```

`.gitignore` 反而证明了它是“本地 IDE 构建”而非 Gradle 工程（原文）：

```
 9  .gradle/*
10  build/*
11  out/*
```

`resources/META-INF/plugin.xml` 关键段（原文）：

```xml
 2    <id>com.cn.oddcn.plugin.gobuildergenerator</id>
 3    <name>Go Builder Generator</name>
 4    <version>1.1.1</version>
 5    <vendor email="oddcn.zheng@gmail.com" url="https://github.com/OddCN/go-builder-generator-idea-plugin">Junqiu Zheng
 6    </vendor>
...
36    <idea-version since-build="162"/>
37
38    <depends>com.intellij.modules.lang</depends>
39
40    <extensions defaultExtensionNs="com.intellij">
41        <!-- Add your extensions here -->
42    </extensions>
43
44    <actions>
45        <!-- Add your actions here -->
46        <action id="Go.BuilderGenerator" class="com.cn.oddcn.GoBuilderGeneratorAction"
47                text="Generate Go Builder Pattern Code"
48                description="Generate Builder pattern code for Go">
49            <add-to-group group-id="CodeMenu" anchor="after" relative-to-action="Generate"/>
50            <keyboard-shortcut keymap="$default" first-keystroke="alt B"/>
51        </action>
52    </actions>
```

`PluginGoBuilderGenerator.jar` 内的 `META-INF/plugin.xml` 与 `resources/` 内文件**逐行一致**（54 行，version 1.1.1），说明 jar 是手工把 `resources/` + 编译类打进去的。

可复制点：`<add-to-group group-id="CodeMenu" anchor="after" relative-to-action="Generate"/>` 的右键/Code 菜单挂载写法；`vendor` 内联文本写法。

### 2.4 小节判定

- 两个仓库能提供的“骨架”= **`resources/META-INF/plugin.xml` + 单个 Action 类**，仅此而已。
- 它们**不能**提供 2.x 构建骨架；把它们的工程结构搬过来等于回到 2018 年 DevKit 方式（无法用 `goland("2026.2.3")` 拉平台依赖，也无法跑 `runIde`/`verifyPlugin`）。

### 2.5 可用的 2.x 骨架（**【档】外部证据，非这两个仓库**）

> 来源：官方文档 IntelliJ Platform Gradle Plugin (2.x)（页面更新 2026-07-21）、Dependencies Extension、IntelliJ Platform Extension、Types、GoLand Plugin Development（2026-04-07），以及官方模板 `JetBrains/intellij-platform-plugin-template` @ `25126d86`（2026-10-09）。

**官方对 GoLand 的明确写法**（`goland.html` 原文摘录）：

```kotlin
repositories {
  mavenCentral()
  intellijPlatform {
    defaultRepositories()
  }
}

dependencies {
  intellijPlatform {
    goland("<versionNumber>")
    bundledPlugin("org.jetbrains.plugins.go")
  }
}
```

同页明确：`org.jetbrains.plugins.go` 是 **bundled plugin**，必须用 `bundledPlugin()` 添加；`bundledPlugin("com.intellij.java")` 这类写法是同一 API（Dependencies Extension 页示例里直接出现 `bundledPlugin("com.intellij.java")` 与 `intellijIdea("2026.2.3")`）。

**`create(IntelliJPlatformType.GoLand, ...)` 的官方依据**（Types 页 `IntelliJPlatformType` 表）：`GoLand` | code `GO` | coordinates `com.jetbrains.intellij.goland:goland` | binary release ✓；`create(type, version, configure)` 见 Dependencies Extension「Custom Target Platforms」。

**2.x 环境要求**（官方 2.x 首页原文）：IntelliJ Platform ≥ 2023.3、Gradle ≥ 9.0.0、Java Runtime ≥ 17；插件 ID 为 `org.jetbrains.intellij.platform`，示例版本 `2.19.0`。

**官方模板的四个文件（原文，可直接抄）**：

`settings.gradle.kts`（857 B）：

```kotlin
import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "IntelliJ Platform Plugin Template"

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.4.20"
        id("org.jetbrains.changelog") version "2.5.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.19.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    // Configure all projects' repositories
    repositories {
        mavenCentral()

        // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
        intellijPlatform {
            defaultRepositories()
        }
    }
}
```

`build.gradle.kts`（551 B，模板原文；把 `intellijIdea` 换成 `goland` + 补 `bundledPlugin` 即成本项目写法）：

```kotlin
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.6.1")
        testFramework(TestFrameworkType.Platform)
    }
}
```

`gradle.properties`（546 B）与 wrapper（281 B）原文：

```properties
group = org.jetbrains.plugins.template
version = 2.6.0

pluginRepositoryUrl = https://github.com/JetBrains/intellij-platform-plugin-template

# Opt-out flag for bundling Kotlin standard library -> https://jb.gg/intellij-platform-kotlin-stdlib
kotlin.stdlib.default.dependency = false

# Enable Gradle Configuration Cache -> https://docs.gradle.org/current/userguide/configuration_cache.html
org.gradle.configuration-cache = true

# Enable Gradle Build Cache -> https://docs.gradle.org/current/userguide/build_cache.html
org.gradle.caching = true
```

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.8.0-bin.zip
networkTimeout=10000
retries=0
retryBackOffMs=500
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

`pluginConfiguration` / `ideaVersion` 的官方写法（Extension 页原文）：

```kotlin
intellijPlatform {
  pluginConfiguration {
    id = "my-plugin-id"
    name = "My Awesome Plugin"
    version = "1.0.0"
    ideaVersion {
      sinceBuild = "241"
      untilBuild = "241.*"
    }
    vendor {
      name = "..."
      email = "..."
      url = "..."
    }
  }
}
```

官方说明：这些值由 `patchPluginXml` 任务写回 `plugin.xml`（2.x 中不再手写 `patchPluginXml { }` 任务块，而是 `intellijPlatform.pluginConfiguration { }`）。

官方模板的 CI 用 Java **21**（`.github/workflows/build.yml:49`，`distribution: zulu`, `java-version: 21`），`./gradlew buildPlugin` 构建，`check`/`verifyPlugin` 门禁。

**落地约束（与同批证据交叉核对，重要）**：本机**无法下载 Gradle distribution**（`services.gradle.org` / `downloads.gradle.org` 不可达、`github.com` 不可达），同批证据 `docs/evidence/goland-2026.2-environment.md` §①-6 已实测；该文同时验证了「JBR 25 `javac --release 21` 直编 + Go PSI/Inlay 探针」可行（§①-5）。因此：
- 上述 2.x 骨架适用于**有网环境/后续 CI/项目结构规范**（`src/main/java` + `src/main/resources/META-INF/plugin.xml` 的布局两种方式一致）；
- **本机第一版**应按 javac 直编路径推进，不要先把构建绑死在 Gradle 上；
- 本报告的 API 结论（§3.3、§4、§5.2）与构建方式无关，两种路径通用。

---

## 3. ② Go PSI 使用手法清单

### 3.1 仓库内的实际用量：**0**

对两仓库全量文本执行（含 `.java` / `.xml` / `.md` / `.iml`），检索 `com.goide`、`GoFile`、`GoFunctionDeclaration`、`GoFunctionOrMethodDeclaration`、`GoBlock`、`GoSignature`、`GoTypeSpec`、`GoMethodDeclaration`、`GoPsiImplUtil`、`GoTypeDeclaration`、`PsiElement`、`PsiFile`、`com.intellij.psi`：

- **命中 0 条**（唯一命中来自类名 `GenerateGoMethodsClass` 含子串 `GoMethod`，R1 `resources/META-INF/plugin.xml:45`、`src/GenerateGoMethodsClass.java:13/15/19`，与 PSI 无关）。
- 两个仓库的 import 全集只有 `com.intellij.openapi.*`（R1 见 `src/GenerateGoMethodsClass.java:1-11`；R2 见 `src/com/cn/oddcn/GoBuilderGeneratorAction.java:6-15`）与 `java.*`、`org.apache.commons.lang.StringUtils`。
- `plugin.xml` 也未声明 `org.jetbrains.plugins.go`（R1:36、R2:38），与「不用 Go PSI」自洽。

**结论：无法从这两个仓库给出「枚举函数/取函数体/取签名/改函数体/加 import」的 file:line 实例，因为它们没有这类代码。**

### 3.2 它们实际用的手法（文本级，非 PSI）

#### R1：把当前行当文本，正则校验后整行替换

`src/GenerateGoMethodsClass.java:27-76`（关键片段原文）：

```java
27    public void executeWriteAction(Editor editor, @Nullable Caret caret, DataContext dataContext) {
28        Document document = editor.getDocument();
29
30        String extension = Objects.requireNonNull(FileDocumentManager.getInstance().getFile(document)).getExtension();
31        if (!(extension != null && extension.toLowerCase().equals("go"))){
32            return;
33        }
...
43        int selectedLineNumber = document.getLineNumber(selectionModel.getSelectionEnd());
44
45        TextRange lineRange = new TextRange(
46                document.getLineStartOffset(selectedLineNumber),
47                document.getLineEndOffset(selectedLineNumber)
48        );
...
52        String[] params = selectedLine.split("\\.");
53        if (params.length != 2){
54            return;
55        }
56        if (!document.getText().contains(" " + params[0] + " ")){
57            return;
58        }
59
60        String pattern;
61        if (Pattern.matches("\\S+[(].*[)].*", params[1])) {
62            pattern = "func (this *%s) %s {\n\t\n}";
63        } else if (Pattern.matches("\\S+", params[1])) {
64            pattern = "func (this *%s) %s() {\n\t\n}";
65        } else {
66            return;
67        }
68
69        document.replaceString(
70                lineRange.getStartOffset(),
71                lineRange.getEndOffset(),
72                String.format(pattern, params[0], params[1])
73        );
74
75        caretModel.moveToOffset(caretModel.getOffset()-2);
76    }
```

特征：只看“当前行的文本形态”，`struct` 是否存在仅用 `document.getText().contains(" StructName ")` 判断（:56），函数体固定为空 `{\n\t\n}`。

#### R2：选区文本按行正则解析 → 字符串拼接生成代码 → 插到文件末尾

`src/com/cn/oddcn/GoBuilderGeneratorAction.java:28-47`（原文）：

```java
28        String selectedText = editor.getSelectionModel().getSelectedText();
29
30        StructGenerateResult structGenerateResult = StructUtil.generateStruct(selectedText);
...
36        String builderPatternCode = BuilderUtil.generateBuilderPatternCode(structGenerateResult.structEntityList);
37
38        int textLength = editor.getDocument().getTextLength();
39
40        new WriteCommandAction(project) {
41            @Override
42            protected void run(@NotNull Result result) {
43                editor.getDocument().insertString(textLength, builderPatternCode);
44                editor.getCaretModel().moveToOffset(textLength + 2);
45                editor.getScrollingModel().scrollToCaret(ScrollType.CENTER_UP);
46            }
47        }.execute();
```

解析与生成侧：`StructUtil.java:19`（按行切分）、`:24-37`（四条正则：注释行 / `type X struct {` / 字段 / `}`）、`:43-45`、`:56-58`（跳过注释行）；`BuilderUtil.java:10-78`（纯 `StringBuilder` 拼出 builder 模式代码）、`:80-86`（首字母小写化工具）。全程没有 AST，也没有 import 处理。

### 3.3 2026.2 实际可用的 Go PSI API（**【IDE】** 本地安装核实）

> 核实对象：`~/Applications/GoLand.app/Contents/plugins/go-plugin/lib/intellij.go.impl.jar`
> 方法签名由 class 文件常量池读出（脚本 `/tmp/golrefs/ClassDump.py`），均**存在且非 @Deprecated**（除注明外）。
> 该 jar 内的 Go 插件 `plugin.xml` 自报：`<id>org.jetbrains.plugins.go</id>`、`<version>262.10968.67</version>`、`<idea-version since-build="262.10968.67" until-build="262.10968.67"/>`（同一 `go-plugin/lib/` 下的 `go-plugin.jar`）。

#### (a) 拿到 `GoFile`

| 途径 | 证据 |
| --- | --- |
| `e.getData(CommonDataKeys.PSI_FILE)` 再 `instanceof GoFile` | `CommonDataKeys.PSI_FILE`/`PSI_ELEMENT` 字段【IDE `intellij.platform.editor.ui.jar`】；`GoFile extends com.intellij.extapi.psi.PsiFileBase`【IDE】 |
| `PsiDocumentManager.getPsiFile(Document)` + `commitDocument(Document)` | 【IDE `intellij.platform.core.jar`】`getPsiFile(Lcom/intellij/openapi/editor/Document;)Lcom/intellij/psi/PsiFile;`、`commitDocument(...)V` |
| `CommonDataKeys.EDITOR` / `.PROJECT` | 【IDE】字段存在 |

#### (b) 枚举函数 / 方法 / 类型 / import（全部来自 `GoFile`）

【IDE】`com.goide.psi.GoFile` 方法（原文签名）：

```
method getFunctions        ()Ljava/util/Collection;
method getFunctions        (Ljava/lang/String;)Ljava/util/Collection;
method getMethods          ()Ljava/util/List;
method getMethods          (Ljava/lang/String;)Ljava/util/Collection;
method getTypes            ()Ljava/util/Collection;
method getTypes            (Ljava/lang/String;)Ljava/util/Collection;
method getImports          ()Ljava/util/List;
method getImportList       ()Lcom/goide/psi/GoImportList;
method addImport           (Ljava/lang/String;Ljava/lang/String;)Lcom/goide/psi/GoImportSpec;
method deleteImport        (Lcom/goide/psi/GoImportSpec;)V
method getPackageName      ()Ljava/lang/String;
method hasMainFunction     ()Z
method getStub             ()Lcom/goide/stubs/GoFileStub;
```

配套：`com.goide.stubs.GoFileStub`、`GoFunctionDeclarationStub`、`GoMethodDeclarationStub` 均在 `com/goide/stubs/` 下【IDE】。

#### (c) 取函数体 / 取签名

【IDE】

```
### com.goide.psi.GoFunctionOrMethodDeclaration  (interface)
    getBlock      ()Lcom/goide/psi/GoBlock;
    getSignature  ()Lcom/goide/psi/GoSignature;
    getIdentifier ()Lcom/intellij/psi/PsiElement;

### com.goide.psi.GoFunctionDeclaration  (implements GoFunctionOrMethodDeclaration, StubBasedPsiElement)
    getBlock / getSignature / getIdentifier / getExampleCode / getExampleOutput / isNewLike

### com.goide.psi.GoMethodDeclaration  (implements GoFunctionOrMethodDeclaration, StubBasedPsiElement)
    getBlock / getSignature / getIdentifier
    getReceiver       ()Lcom/goide/psi/GoReceiver;
    getReceiverType   ()Lcom/goide/psi/GoType;
    resolveTypeSpec   ()Lcom/goide/psi/GoTypeSpec;
    isEmbeddedByPointer ()Z

### com.goide.psi.GoBlock  (implements GoLazyBlock)
    getStatementList ()Ljava/util/List;
    processDeclarations(...)Z
```

`GoSignature`【IDE】：

```
    getParameters     ()Lcom/goide/psi/GoParameters;
    getResult         ()Lcom/goide/psi/GoResult;
    getTypeParameters ()Lcom/goide/psi/GoTypeParameters;
    getResultType     ()Lcom/goide/psi/GoType;
```

`GoTypeSpec` / `GoTypeDeclaration`【IDE】：

```
### com.goide.psi.GoTypeSpec
    getSpecType     ()Lcom/goide/psi/GoSpecType;
    getGoTypeInner  (Lcom/intellij/psi/ResolveState;)Lcom/goide/psi/GoType;
    getMethods      ()Ljava/util/List;
    getAllMethods   ()Ljava/util/List;
    isTypeAlias     ()Z
### com.goide.psi.GoTypeDeclaration
    getTypeSpecList ()Ljava/util/List;
    deleteTypeSpec  (Lcom/goide/psi/GoTypeSpec;)V
```

#### (d) 改函数体（文本 → PSI 的最短路径）

【IDE】`com.goide.psi.impl.GoElementFactory`（关键静态工厂，原文签名）：

```
    createFileFromText   (Lcom/intellij/openapi/project/Project;Ljava/lang/String;)Lcom/goide/psi/GoFile;
    createBlock          (Lcom/intellij/openapi/project/Project;Ljava/lang/String;)Lcom/goide/psi/GoBlock;
    createBlock          (Lcom/intellij/openapi/project/Project;)Lcom/goide/psi/GoBlock;
    createElement        (Project; String; Class; PsiElement) -> PsiElement
    createReturnStatement(Project; String) -> GoReturnStatement
    createIfStatement / createIfElseIfStatement / createSwitchStatement / createSelectStatement
    createImportDeclaration(Project; String) -> GoImportDeclaration
    createImportDeclaration(PsiFile; String; String; boolean) -> GoImportDeclaration
    createImportSpec     (Lcom/intellij/psi/PsiFile;Ljava/lang/String;Ljava/lang/String;)Lcom/goide/psi/GoImportSpec;
    createFunctionSignatureFromText (Project; String; String) -> GoSignature
    createNewLine / createComma / createStringLiteral / createIdentifierFromText
```

配合 `com.intellij.psi.PsiElement`【IDE】的通用写方法：`replace(PsiElement)`、`addBefore/addAfter/delete/deleteChildRange`。

即最直接的做法：`GoElementFactory.createBlock(project, "{\n\t...\n}")` → `function.getBlock().replace(newBlock)`（或对 `getStatementList()` 目标位置 `addBefore`）。

#### (e) 加 import

三条已核实路径【IDE】：
1. `GoFile.addImport(String alias, String path) → GoImportSpec`（最省事，alias 传 `""`/`null` 视实现而定，签名已核实为两个 String）；
2. `GoFile.getImportList().addImport(String, String)` —— `com.goide.psi.GoImportList.addImport(Ljava/lang/String;Ljava/lang/String;)Lcom/goide/psi/GoImportSpec;`；
3. `GoElementFactory.createImportSpec(PsiFile, String, String)` 或 `createImportDeclaration(...)` 后插入 import 段。

#### (f) 格式化

【IDE】`com.intellij.psi.codeStyle.CodeStyleManager`（`intellij.platform.core.jar`）可用：

```
    reformat                (Lcom/intellij/psi/PsiElement;)Lcom/intellij/psi/PsiElement;
    reformatRange           (Lcom/intellij/psi/PsiElement;II)Lcom/intellij/psi/PsiElement;
    reformatText            (Lcom/intellij/psi/PsiFile;II)V
    adjustLineIndent        (Lcom/intellij/psi/PsiFile;Lcom/intellij/openapi/util/TextRange;)V
```

（`reformatTextWithContext` 两个重载已 **@Deprecated**，勿用。）

---

## 4. ③ 写操作与撤销/格式化写法

### 4.1 R2 的写法：`new WriteCommandAction(project) { run(Result) }` —— 2026.2 已不能编译

原文（`src/com/cn/oddcn/GoBuilderGeneratorAction.java:40-47`）：

```java
40        new WriteCommandAction(project) {
41            @Override
42            protected void run(@NotNull Result result) {
43                editor.getDocument().insertString(textLength, builderPatternCode);
44                editor.getCaretModel().moveToOffset(textLength + 2);
45                editor.getScrollingModel().scrollToCaret(ScrollType.CENTER_UP);
46            }
47        }.execute();
```

2026.2 实际类结构【IDE `intellij.platform.core.jar`】：

```
### com/intellij/openapi/command/WriteCommandAction   (major=52)
    field  DEFAULT_GROUP_ID               Ljava/lang/String;
    method <init>                         ()V                                                     private
    method writeCommandAction             (Project) -> WriteCommandAction$Builder                  public static
    method writeCommandAction             (PsiFile;[PsiFile) -> Builder                           public static
    method writeCommandAction             (Project;[PsiFile) -> Builder                           public static
    method writeCommandAction             (Project;Collection) -> Builder                         public static
    method runWriteCommandAction          (Project;Runnable)V                                    public static
    method runWriteCommandAction          (Project;String;String;Runnable;[PsiFile)V             public static
    method runWriteCommandAction          (Project;Computable) -> Object                          public static
    method runWriteCommandAction          (Project;ThrowableComputable) -> Object                 public static
```

- **没有** `WriteCommandAction(Project)` 构造子（只有 private 无参构造）；**没有** 抽象方法 `run(Result)`。→ 该匿名类写法在 2026.2 直接编译失败。
- `com.intellij.openapi.application.Result` 类仍存在，但整个类带 **@Deprecated**，且只剩 `<init>()V` 与 `setResult(Object)V`（无 `getResult`）→ 旧 `Result` 返回值取法也失效。
- 2026.2 的正确入口是 `WriteCommandAction.runWriteCommandAction(project, name, groupId, runnable, files...)` 或 `WriteCommandAction.writeCommandAction(...)` 返回的 `Builder`；Builder 提供【IDE】`withName`、`withGroupId`、`withUndoConfirmationPolicy`、`withGlobalUndo`、`shouldRecordActionForActiveDocument`、`run(ThrowableRunnable)`、`compute(ThrowableComputable)`。

### 4.2 R1 的写法：`EditorAction` + `EditorWriteActionHandler` + `Document.replaceString` —— 2026.2 仍可编译，撤销分组由框架负责

原文 `src/GenerateGoMethodsClass.java:13-28`：

```java
13  public class GenerateGoMethodsClass extends EditorAction {
14
15      public GenerateGoMethodsClass() {
16          this(new UpHandler());
17      }
...
23      private static class UpHandler extends EditorWriteActionHandler {
...
27          @Override
28          public void executeWriteAction(Editor editor, @Nullable Caret caret, DataContext dataContext) {
```

2026.2 核实【IDE `intellij.platform.ide.jar`】：

```
### com/intellij/openapi/editor/actionSystem/EditorWriteActionHandler
    method executeWriteAction (Editor;Caret;DataContext)V                         public
    method executeWriteAction (Editor;DataContext)V                               public @Deprecated
```

→ 该重载（3 参）**存在且未废弃**；2 参重载已 @Deprecated。R1 覆写的是 3 参版本，因此这段代码在现代平台仍能编译。

**撤销/命令包裹（关键，且可验证）**：读 `EditorWriteActionHandler` 的常量池引用，其内部只走 `Application.runWriteAction` 与 `EditorModificationUtil`，**不引用 `CommandProcessor`**；而 `EditorAction` 类的引用表里明确有【IDE】：

```
EditorActionHandler  executeInCommand (Editor;DataContext)Z
CommandProcessor     getInstance      ()Lcom/intellij/openapi/command/CommandProcessor;
EditorActionHandler  getCommandGroupId (Editor)LDocCommandGroupId;
CommandProcessor     executeCommand   (Project;Runnable;String;Object;UndoConfirmationPolicy;Document;)V
```

即（由上述常量池引用推得）：**通过 `EditorAction` 触发的写操作，平台会用 `executeInCommand` + `CommandProcessor.executeCommand(..., commandGroupId, ..., document)` 包一层命令**；撤销分组来自 `DocCommandGroupId`（同一编辑器/同一 groupId 的连续编辑会合并），R1 自身没有任何显式撤销分组代码。

`EditorWriteActionHandler` 常量池全部引用（26 条，摘相关性最强的 10 条）【IDE】：

```
IntentionPreviewUtils      getPreviewEditor
EditorModificationUtil     checkModificationAllowed (Editor)Z
ApplicationManager         getApplication ()Application
Application                isWriteAccessAllowed Z
EditorModificationUtil     requestWriting (Editor)Z
Application                runWriteAction (Runnable)V
EditorWriteActionHandler   executeWriteAction (Editor;Caret;DataContext)V
Document                   startGuardedBlockChecking / stopGuardedBlockChecking
EditorActionManager        getInstance / getReadonlyFragmentModificationHandler
```

### 4.3 两个仓库都没有做的事（明确否定）

- **没有** `CommandProcessor` 使用（两仓库 grep `CommandProcessor` = 0）。
- **没有** `CodeStyleManager` / `reformat` / `adjustLineIndent` 使用（两仓库 grep `CodeStyleManager|reformat|adjustLineIndent` = 0）。
- **没有** `PostprocessReformattingAwareAction`（两仓库 = 0）。该类在 2026.2 的 GoLand `Contents/lib/*.jar` 中**已整体不存在**（类定位脚本遍历 421 个 jar 为 `ABSENT`）。
- R1 生成的空函数体靠手写的 `\n\t` 维持缩进（`GenerateGoMethodsClass.java:62,64`），R2 生成代码靠 `StringBuilder` 里写死的 `\n\t`（`BuilderUtil.java:23-77`）。

### 4.4 任务 E：Action/Extension 注册与 inlay（两仓库的实际情况）

| 项 | R1 | R2 |
| --- | --- | --- |
| `<actions>` 注册 | 1 个：`id="generate_go_method" class="GenerateGoMethodsClass"`（默认包裸类名），`GenerateGoMethodsClass extends EditorAction`（`plugin.xml:45-48` ↔ `src/GenerateGoMethodsClass.java:13`） | 1 个：`id="Go.BuilderGenerator" class="com.cn.oddcn.GoBuilderGeneratorAction"`，`extends AnAction`（`plugin.xml:46-51` ↔ `GoBuilderGeneratorAction.java:17`） |
| 菜单挂载 | 无 `<add-to-group>`，仅快捷键 `shift ctrl alt G` | `<add-to-group group-id="CodeMenu" anchor="after" relative-to-action="Generate"/>` + 快捷键 `alt B` |
| `<extensions>` | 空（`plugin.xml:39-41`，含模板注释 `<!-- Add your extensions here -->`） | 空（`plugin.xml:40-42`，同样保留模板注释） |
| 自定义 EP / 服务 / listener | 无 | 无 |
| Inlay（`InlayModel` / `EditorCustomElementRenderer` / `Inlay`） | **没有**（grep = 0） | **没有**（grep = 0） |

结论：两仓库都只用了最基础的 `<actions>` 扩展点，**没有任何 inlay / editor 装饰用法**，也没有任何自定义 EP 注册示例。任务 E 中「inlay/editor 用法」在这两个仓库里的答案是**明确没有**。

### 4.5 任务 F：测试与 CI（两仓库的实际情况）

| 项 | R1 | R2 |
| --- | --- | --- |
| `src/test` 或任何测试源码 | 无 | 无 |
| 测试框架依赖（JUnit / test-framework） | 无 | 无 |
| `.github/workflows` 或任何 CI 配置 | 无（无 `.github/` 目录） | 无（无 `.github/` 目录） |
| 构建产物 | 无（需用 IDEA SDK 自行构建，README:37） | `PluginGoBuilderGenerator.jar`（1.1.1）手工提交到仓库根 |
| 发布情况 | Marketplace 插件 10517「Go method generator」，v1.0，2018-03-10，`since 162.0`/`until ""`，下载 25,793 | Marketplace 插件 10783「Go Builder Generator」，v1.1.1，2018-07-02，`since 162.0`/`until ""`，下载 12,231 |

结论：**两个仓库都没有任何可参考的插件测试与 CI 实践**（无 `src/test`、无 workflow、无 verifier 报告）。可参考的测试/CI 骨架只能来自官方模板（§2.5 的 `.github/workflows/build.yml`：`./gradlew buildPlugin` + `check` + `verifyPlugin`，Java 21）。

---

## 5. ④ 与 GoLand 2026.2 的兼容风险

### 5.1 版本/描述符层面

| 风险 | 事实与证据 | 影响 |
| --- | --- | --- |
| `until-build` 缺失 | R1 `plugin.xml:29` `since-build="162.0"`、R2 `:36` `since-build="162"`，两者**均无 `until-build`**；Marketplace 元数据也印证：插件 10517/10783 的 updates 记录 `since: "162.0"`, `until: ""`。 | 无上界 = IDE 认为与“所有未来版本”兼容，实际 API 早已破坏；目标为 262 时必须显式写 `until-build`。 |
| since-build 落后 100 个主版本 | `162` vs 本地 `262`（`GO-262.10968.67`）。 | 与“能装”不同，`162` 时代 API 在 262 大量移除（见 5.2）。 |
| 依赖声明不满足官方要求 | 官方 `goland.html` 明确：`com.intellij.modules.platform` 恒需、`org.jetbrains.plugins.go` 用 Go API 时必需、`com.intellij.modules.goland` 仅 GoLand 时必需。R1/R2 只有 `com.intellij.modules.lang`（R1:36 / R2:38）。 | 若照抄，插件在 2026.2 加载后拿不到 Go PSI 类；若加 Go PSI 必须补 `<depends>org.jetbrains.plugins.go</depends>`。 |
| Go 插件自身版本钉死 | 【IDE】`go-plugin/lib/go-plugin.jar!/META-INF/plugin.xml`：`<id>org.jetbrains.plugins.go</id>`、`<version>262.10968.67</version>`、`<idea-version since-build="262.10968.67" until-build="262.10968.67"/>`。 | Go PSI 只在**精确 build** 内被官方声明兼容；升级 GoLand 小版本后必须重新验证（这也是把插件 `until-build` 收紧的理由）。 |
| 依赖第三方旧库 | R2 用 `org.apache.commons.lang.StringUtils`（`GoBuilderGeneratorAction.java:14`、`StructUtil.java:5`）。2026.2 中该类只以 **shim** 形式存在：`intellij.platform.util.commonsLangV2Shim.jar` 内 `org/apache/commons/lang/StringUtils`，**整个类 @Deprecated**，继承 `org/apache/commons/lang3/StringUtils`，只保留了 `isBlank/isNotBlank` 等。 | 能跑但属废弃面；2.x 下应改为 JDK `String.isBlank()` 或自带工具方法，避免依赖平台内部 shim。 |
| 默认包类 | R1 的 `GenerateGoMethodsClass` 无 `package`，`plugin.xml:45` 用裸类名注册，源码直接放 `src/` 根。 | Kotlin 代码无法 import 默认包；2.x 标准布局下也不建议。新项目应放正式包名。 |

### 5.2 代码 API 层面（逐条已核实）

| 仓库用法 | 位置 | 2026.2 状态 | 结论 |
| --- | --- | --- | --- |
| `new WriteCommandAction(project) { run(Result) }` | R2 `GoBuilderGeneratorAction.java:40-47` | 【IDE】无 `(Project)` 构造子、无 `run(Result)`；仅 static `writeCommandAction/runWriteCommandAction` | **编译失败，必须改写** |
| `com.intellij.openapi.application.Result` | R2 `:9` | 【IDE】类存在但 `@Deprecated`，仅 `<init>()V`、`setResult(Object)V` | 已被 `WriteCommandAction` 移除其用法；不要使用 |
| `PlatformDataKeys.PROJECT` / `.EDITOR` | R2 `:21`、`:23` | 【IDE】2026.2 `PlatformDataKeys` 字段表中**没有** `PROJECT`/`EDITOR`（父类 `PlatformCoreDataKeys` 也只有 `PROJECT_FILE_DIRECTORY`/`FILE_EDITOR`/`PROJECT_CONTEXT`）；正确位置是 `CommonDataKeys.PROJECT`/`.EDITOR`（`intellij.platform.editor.ui.jar`） | **编译失败** |
| `Messages.showErrorDialog(Project,String,String)` | R2 `:32` | 【IDE】该重载存在 | 可用 |
| `EditorWriteActionHandler.executeWriteAction(Editor,Caret,DataContext)` | R1 `:27-28` | 【IDE】3 参重载存在且非废弃（2 参已 @Deprecated） | 可用 |
| `FileDocumentManager.getFile(Document)` | R1 `:31` | 【IDE】存在 | 可用 |
| `Document.getText/isWritable/getLineNumber/getLineStartOffset/getLineEndOffset/insertString/replaceString` | R1 `:29-73`、R2 `:38-43` | 【IDE】全部存在 | 可用 |
| `EditorAction` + `actionPerformed` | R1 `:13` | 【IDE】`EditorAction` 存在；`actionPerformed(AnActionEvent)` 为 `public final` | 可用 |
| `ScrollType.CENTER_UP`、`ScrollingModel.scrollToCaret` | R2 `:45` | 未逐一核实（见 §7.4） | 待验证 |

### 5.3 与任务 A/B 项直接对应的“缺失风险”

- 无 wrapper → 本地无法用 `./gradlew` 复现；如果照抄仓库，等于放弃 Gradle 依赖管理。
- 无 `intellijPlatform {}` / `pluginConfiguration` → `plugin.xml` 的 `idea-version` 必须手写，且没有 `patchPluginXml` 帮你注入版本。
- `plugin.xml` 无 `com.intellij.modules.platform`（官方“恒需”项）→ 插件会被视为 legacy 描述符；2.x 模板默认写 `<depends>com.intellij.modules.platform</depends>`（模板 `src/main/resources/META-INF/plugin.xml:11`）。

---

## 6. ⑤ 对本项目（GoLand 2026.2.3 / 单人自用 / 注释驱动生成函数体）的直接建议

> 以下建议中，每条 API 都已在上文【IDE】核实；代码只是**草图**，未编译验证（见 §7 未核实项）。

1. **构建**：走 2.x + 本地安装（本机已确认 `~/Applications/GoLand.app`，`GO-262.10968.67`）。两种取平台方式都官方支持，二选一：
   - `goland("2026.2.3")` + `bundledPlugin("org.jetbrains.plugins.go")`（官方 GoLand 页原样写法，适合可复现构建）；
   - 或 `local("/Users/kaughara/Applications/GoLand.app")` + `localPlatformArtifacts()`（官方 Local IDE 页，适合单人、离线、永远对齐本机）。
   同时把 `intellijPlatform.pluginConfiguration.ideaVersion` 设为 `sinceBuild = "262"`, `untilBuild = "262.*"`（与 Go 插件自钉 262.10968.67 的事实一致，避免升级后静默失效）。
   **注意**：本机 Gradle distribution 不可下载（§2.5「落地约束」），所以这一条用于**结构规范与后续 CI**；本机第一版按 javac 直编（同批证据 `docs/evidence/goland-2026.2-environment.md` §①-5/6）。

2. **`plugin.xml` 依赖**（官方 GoLand 页三条规则）：`com.intellij.modules.platform` + `org.jetbrains.plugins.go`（用 Go PSI 必须）；只用 GoLand 则再加 `com.intellij.modules.goland`。单人自用可直接加 `com.intellij.modules.goland` 省掉“装到 IDEA 上不工作”的排查。

3. **入口 Action 选 `AnAction`，不要照抄两种旧写法**：
   ```kotlin
   // 草图：基于 §3.3 已核实 API
   override fun actionPerformed(e: AnActionEvent) {
       val project = e.project ?: return
       val file = e.getData(CommonDataKeys.PSI_FILE) as? GoFile ?: return
       val targets = file.functions.filter { needsBody(it) }   // GoFile.getFunctions(): Collection
       WriteCommandAction.runWriteCommandAction(project, "Generate Body", null, {
           targets.forEach { fn -> fillBody(project, fn) }      // 一次命令 = 一条 undo
       }, file)
   }
   ```
   - 枚举：`GoFile.getFunctions()` / `getMethods()` / `getMethods(name)` / `getTypes()`。
   - 取签名/参数/返回：`GoFunctionOrMethodDeclaration.getSignature()` → `GoSignature.getParameters()` / `getResult()` / `getResultType()`。
   - 取函数体：`getBlock()`；空体/缺失体用 `getBlock() == null` 或 `getBlock()?.statementList.isNullOrEmpty()` 判空。
   - 改函数体：`GoElementFactory.createBlock(project, "{\n\t...\n}")` 后 `fn.block.replace(newBlock)`（`PsiElement.replace` 已核实存在）。
   - 加 import：`file.addImport(alias, path)` 或 `file.importList.addImport(alias, path)`。
   - 收尾格式化：`CodeStyleManager.getInstance(project).reformat(changedElement)`（或 `reformatRange`）。
   - 撤销：用 `runWriteCommandAction(project, name, groupId, runnable, file)` 一次性包住所有改动；**不要**学 R2 的旧匿名 `WriteCommandAction`。

4. **注释驱动解析可以先用“文本 + 定位”而不用纯 PSI**（这点是从两仓库学到的可用经验）：注释内容本身不是 Go AST 结构，最稳的是
   - 用 `PsiDocumentManager.getPsiFile(document) as GoFile` 拿 PSI，用 `fn.textRange` / `fn.signature.textRange` 定位注释所属函数；
   - 注释 → 指令的解析用正则（R1/R2 的文本正则在这里仍有参考价值），但**落笔（写文件）一定走 PSI 或 write command**，避免破坏 PSI 缓存。
   - 写前 `PsiDocumentManager.getInstance(project).commitDocument(document)`，否则 PSI 与 Document 不同步。

5. **测试**：官方 2.x 为 Go 提供了专用测试框架坐标 `TestFrameworkType.Plugin.Go` → `com.jetbrains.intellij.go:go-test-framework`（types 页原文）。单人自用可先跳过 `verifyPlugin` 全量门禁，但建议至少保留一个 `BasePlatformTestCase` 级别的「注释→函数体」用例，因为 Go PSI 行为随 Go 插件 build 变化（见 5.1）。

6. **不要照抄的地方**（明确清单）：`.iml` 构建、默认包类、`new WriteCommandAction(project){run(Result)}`、`PlatformDataKeys.PROJECT/EDITOR`、`org.apache.commons.lang.StringUtils`、`since-build="162"`、无 `until-build`。

---

## 7. ⑥ 已核查文件清单 + 未核实项

### 7.1 仓库文件（全部逐行读过）

**R1 `pkondratev/Intellij-go-method-generator` @ `1897113`（共 5 个文件，全查）**

| 文件 | 行数 | 核查要点 |
| --- | --- | --- |
| `GoMethodGenerator.iml` | 13 | DevKit `PLUGIN_MODULE`、`inheritedJdk` |
| `LICENSE` | — | GPL-3.0（API 查得） |
| `README.md` | 82 | 使用方式、快捷键、`JDK 8 + IDEA SDK` 构建说明（:37） |
| `resources/META-INF/plugin.xml` | 51 | id/vendor/`since-build=162.0`/`depends`/action |
| `src/GenerateGoMethodsClass.java` | 78 | `EditorAction` + `EditorWriteActionHandler` + `Document.replaceString` |

**R2 `OddCN/go-builder-generator-idea-plugin` @ `6d1a48b`（共 11 个文件，全查）**

| 文件 | 行数 | 核查要点 |
| --- | --- | --- |
| `.gitignore` | 12 | `.gradle/*`、`build/*`、`*.iml` → 非 Gradle 工程 |
| `LICENSE` | — | MIT |
| `PluginGoBuilderGenerator.jar` | 8887 B | 内含 14 个 entry；`META-INF/plugin.xml` 与 resources 版一致 |
| `README.md` | 105 | 用法/示例；Marketplace 链接 plugin/10783 |
| `resources/META-INF/plugin.xml` | 54 | id/`since-build=162`/`depends`/action+`add-to-group` |
| `src/com/cn/oddcn/GoBuilderGeneratorAction.java` | 49 | `AnAction` + 旧 `WriteCommandAction` + `insertString` |
| `src/com/cn/oddcn/entity/StructEntity.java` | 14 | `LinkedHashMap` 保序字段 |
| `src/com/cn/oddcn/entity/StructGenerateResult.java` | 15 | error + list |
| `src/com/cn/oddcn/util/StructUtil.java` | 77 | 4 条正则解析 struct |
| `src/com/cn/oddcn/util/BuilderUtil.java` | 87 | `StringBuilder` 生成代码 |
| `pic/demo.gif` | 3.1 MB | 未解析（二进制演示） |

### 7.2 本地 IDE 核查对象

- `~/Applications/GoLand.app/Contents/Resources/build.txt` → `GO-262.10968.67`
- `~/Applications/GoLand.app/Contents/Resources/product-info.json` → name/version/buildNumber/bundledPlugins（含 `org.jetbrains.plugins.go`、`org.jetbrains.plugins.go-template`、`org.jetbrains.plugins.go.sharedIndexes.bundled`）
- `Contents/jbr/Contents/Home/bin/java -version` → `25.0.4`（JBR-25.0.4+1-508.27-nomod）
- `Contents/plugins/go-plugin/lib/go-plugin.jar` + `intellij.go.impl.jar`
- `Contents/lib/` 421 个 jar 全量建索引（类定位扫描；`find Contents/lib -name '*.jar' | wc -l` 递归计数，仅顶层计数会略少，同批证据记为 419）

类/方法级核实清单（均来自上述 jar）：

| 类 | 结论 |
| --- | --- |
| `com.goide.psi.GoFile` / `GoFunctionDeclaration` / `GoMethodDeclaration` / `GoFunctionOrMethodDeclaration` / `GoBlock` / `GoSignature` / `GoTypeSpec` / `GoTypeDeclaration` / `GoStructType` / `GoImportSpec` / `GoImportList` / `GoParameters` / `GoResult` / `GoReceiver` / `GoType` / `GoSpecType` | 全部存在【intellij.go.impl.jar】 |
| `com.goide.psi.GoPsiImplUtil` | **不存在**于 `com.goide.psi`；实际在 `com.goide.psi.impl.GoPsiImplUtil`（175 KB）与 `com.goide.psi.impl.GoPsiUtil` |
| `com.goide.psi.impl.GoElementFactory` | 存在，createBlock/createFileFromText/createImportSpec 等已列 |
| `com.goide.stubs.GoFileStub` / `GoFunctionDeclarationStub` / `GoMethodDeclarationStub` | 存在（`com/goide/stubs/`） |
| `WriteCommandAction` + `$Builder` | 旧构造子/抽象方法已移除；新 API 已列 |
| `com.intellij.openapi.application.Result` | 存在但整类 @Deprecated |
| `PlatformDataKeys` | 无 `PROJECT`/`EDITOR` 字段 |
| `CommonDataKeys` | `PROJECT`/`EDITOR`/`PSI_FILE`/`PSI_ELEMENT` 存在 |
| `EditorWriteActionHandler` | 3 参 `executeWriteAction` 存在；2 参 @Deprecated |
| `EditorAction` | 引用 `executeInCommand` + `CommandProcessor.executeCommand` |
| `CommandProcessor` | 存在（`intellij.platform.core.jar`） |
| `Document` / `PsiDocumentManager` / `CodeStyleManager` / `FileDocumentManager` / `Messages` / `PsiElement` | 存在，关键方法已列 |
| `PostprocessReformattingAwareAction` | **不存在**（421 jar 全扫） |
| `com.intellij.psi.PsiElementFactory` / `ElementFactory` / `PsiElementFactoryImpl` | **未在 421 个 lib jar 中定位到**（仅有 `XmlElementFactory`、Go 自己的 `GoElementFactory`）；插入文本建议改走 `GoElementFactory`/`PsiFileFactory`（后者未逐个核实） |
| `org.apache.commons.lang.StringUtils` | 仅存在于 `intellij.platform.util.commonsLangV2Shim.jar`，整类 @Deprecated |

### 7.3 官方文档/模板（**【档】**）

- <https://plugins.jetbrains.com/docs/intellij/goland.html>（2026-04-07；含本任务两个仓库被列为参考实现）
- <https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html>（2026-07-21；2.x 环境要求、`2.19.0`、`bundledPlugin` 示例）
- <https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html>（`goland()`、`create()`、`bundledPlugin()`、`testFramework()`、`local()`）
- <https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html>（`pluginConfiguration`/`ideaVersion`/`vendor`、`patchPluginXml` 关系）
- <https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-types.html>（`IntelliJPlatformType.GoLand`=GO、`TestFrameworkType.Plugin.Go`）
- <https://github.com/JetBrains/intellij-platform-plugin-template> @ `25126d8669a87c8962bf79aaf482c7d933c355c5`（2026-10-09）：`build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`、`gradle/wrapper/gradle-wrapper.properties`、`src/main/resources/META-INF/plugin.xml`、`.github/workflows/build.yml`
- Marketplace API：`https://plugins.jetbrains.com/api/plugins/10517/updates`、`.../10783/updates`（两插件均 `since 162.0` / `until ""`）

### 7.4 未核实项（明确声明）

1. **未实际执行 `./gradlew` 构建**：本报告所有 2.x 骨架片段来自官方文档/模板原文，未在本机跑通（且本机无 Gradle、无完整 JDK）。`settings.gradle.kts` 中 `org.jetbrains.intellij.platform` 在 `build.gradle.kts` 里未写版本、由 settings 侧插件（`...platform.settings` 2.19.0）带入的具体解析机制，**未验证**。
2. **未编译验证任何草图代码**（§6 片段），仅保证所用 API 在 2026.2 的 class 层存在。
3. `ScrollType.CENTER_UP`、`ScrollingModel.scrollToCaret` 未逐一核实（R2 `:45`）。
4. `GoFile.addImport(alias, path)` 的 `alias` 允许的取值（空串/null/"."_" 等）未核实；`GoImportList.addImport` 同样。
5. `GoElementFactory.createBlock(project, text)` 对「只给 body 不给 func 上下文」的解析行为未核实（可能需带 `{...}` 完整块文本）。
6. `PsiFileFactory`（用于 `createFileFromText` 的通用替代）未定位核实。
7. 两仓库产出的 jar 在 2026.2 实际能否加载未做运行验证（仅做 API/描述符差异分析）。
8. `GoPsiImplUtil`（175 KB）只确认存在，未逐个 dump 方法（本报告未依赖它）。
9. 未访问 `raw.githubusercontent.com`（被拒）与 `github.com`（不可达）；仓库文件来自 codeload tarball，内容与 GitHub API 文件树一致（文件数与字节数吻合），但未用 git 协议校验对象哈希。
10. 本报告未重复做 Gradle/网络实测；「Gradle distribution 不可下载」引用同批证据 `docs/evidence/goland-2026.2-environment.md` §①-6（实测记录），本报告未独立复测。
11. 本报告未核实 Inlay 相关 API（不在两仓库范围内）；`InlayModel`/`EditorCustomElementRenderer` 的可用性由同批证据 `docs/evidence/goland-2026.2-environment.md` §①-4 验证。

---

## 8. 一页速查：该抄什么、不该抄什么

| 目的 | 来源 | 抄什么 |
| --- | --- | --- |
| 2.x 构建 | 【档】官方 2.x 页/模板 | `plugins { id("org.jetbrains.intellij.platform") version "2.19.0" }`、`settings` 的 `defaultRepositories()`、wrapper `gradle-9.8.0-bin.zip`、`pluginConfiguration.ideaVersion{sinceBuild="262"; untilBuild="262.*"}` |
| GoLand 目标平台 | 【档】GoLand 页 | `goland("2026.2.3")` + `bundledPlugin("org.jetbrains.plugins.go")`（或 `local(".../GoLand.app")`） |
| `plugin.xml` 依赖 | 【档】GoLand 页 + 【IDE】插件 id | `com.intellij.modules.platform`、`org.jetbrains.plugins.go`、（可选）`com.intellij.modules.goland` |
| Action 注册 | 【仓】R1:43-49 / R2:44-52 | `<actions><action id=... class=全限定名 ...><add-to-group group-id="CodeMenu" .../><keyboard-shortcut .../></action></actions>` |
| 写操作 + undo | 【IDE】2026.2 API | `WriteCommandAction.runWriteCommandAction(project, name, groupId, {...}, file)`（**勿**抄 R2 的匿名类）；`EditorAction + EditorWriteActionHandler` 仍可用（R1 写法） |
| Go PSI | 【IDE】`intellij.go.impl.jar` | `GoFile.getFunctions()/getMethods()/getTypes()`、`GoFunctionOrMethodDeclaration.getBlock()/getSignature()`、`GoBlock.getStatementList()`、`GoElementFactory.createBlock/createImportSpec`、`GoFile.addImport` |
| 格式化 | 【IDE】`CodeStyleManager` | `reformat(PsiElement)` / `reformatRange(...)` |
