# M0 真机验证：插件加载 / Go PSI / Inlay

> 日期：2026-10-18
> 环境：GoLand 2026.2.3（`GO-262.10968.67`）+ JBR 25.0.4，macOS
> 方式：`./scripts/dev-sandbox.sh`（隔离沙盒，不碰日常配置）

## ① 结论

**M0 目标全部达成**，插件第一次在真实 GoLand 里跑起来：

| 验证项 | 结果 | 证据 |
|---|---|---|
| plugin.xml 被平台接受、插件被加载 | ✅ | `idea.log`: `Loaded custom plugins: Comment Companion (0.1.0)` |
| 声明 `depends: com.intellij.modules.platform / org.jetbrains.plugins.go / com.intellij.modules.goland` 无冲突 | ✅ | 无插件加载错误；`app-internal-state.db` 正常 |
| 项目打开后第三方插件能拿到 Go 文件 | ✅ | `verify.json`: `goFile=.sandbox/project/verify.go` |
| Go PSI 在运行时可用（不是只编译期） | ✅ | `psiClass=com.goide.psi.GoFile`，`goFunctionNames=["Add","Mul"]`，`goFunctionsWithBody=2` |
| 注释协议在真实 PSI 文本上解析正确 | ✅ | `textDirectives=["实现加法，只处理 int。","实现乘法"]` |
| 编辑器可打开、`TextEditor.editor` 可获取 | ✅ | `editor=opened` |
| `InlayModel.addAfterLineEndElement` 运行时可用、可销毁 | ✅ | `inlay=added-and-disposed` |
| 沙盒启动流程可自动化 | ✅ | 全程无需人工点击（见 ②3） |

## ② 过程中的两个真坑

### 2.1 「Trust project?」模态框会挡住启动活动

第一次启动时项目一直没打开，`idea.log` 停在 `PlatformProjectOpenProcessor - Opening (async) ...` 之后没有下文，`jcmd Thread.print` 显示 EDT 卡在：

```
com.intellij.ide.trustedProjects.impl.TrustedProjectStartupDialog ... showAndGet
```

**结论**：新建沙盒配置（没有 trust 记录）会弹「Trust project?」模态框；项目打开流程没完成，`postStartupActivity` 就不会执行。

**修复**：`dev-sandbox.sh` 在启动前写 `config/options/trusted-paths.xml` 预授权验证项目：

```xml
<application>
  <component name="Trusted.Paths">
    <option name="TRUSTED_PROJECT_PATHS">
      <map>
        <entry key="$PROJECT" value="true" />
      </map>
    </option>
  </component>
</application>
```

### 2.2 沙盒里没有 Go SDK，但 PSI 仍然可用

`idea.log` 显示 `Automatic SDK initialization: no compatible SDKs found`。这是预期（沙盒是新配置，未配置 GOROOT），但**不影响本次验证**：

- 语法树解析、`GoFile.functions`、`GoFunctionDeclaration.getBlock()` 都正常；
- 需要类型推断/解析的能力（补全、跳转、`addImport` 后解析）才依赖 SDK。

→ 插件实现里要注意：**不要假设类型解析一定可用**；MVP 的注释协议解析与函数体定位只依赖 PSI 树，是稳妥的。

## ③ 复现步骤

```bash
cd ~/go/comment-companion
./scripts/dev-sandbox.sh          # 启动沙盒 GoLand（首次会播种 license + options）
# 等约 20 秒，验证结果写入：
cat .sandbox/verify.json
# 关闭沙盒实例（不要关掉日常 GoLand）
pkill -f "MacOS/goland .*/\.sandbox/project"
```

## ④ 验证 JSON（原始输出）

```json
{
  "plugin": "loaded",
  "project": "project",
  "projectPath": "/Users/kaughara/go/comment-companion/.sandbox/project",
  "goFile": "/Users/kaughara/go/comment-companion/.sandbox/project/verify.go",
  "psiClass": "com.goide.psi.GoFile",
  "textLength": 159,
  "textDirectives": ["实现加法，只处理 int。","实现乘法"],
  "goFunctionNames": ["Add","Mul"],
  "goFunctionsWithBody": 2,
  "goMethods": 0,
  "editor": "opened",
  "inlay": "added-and-disposed"
}
```

## ⑤ 仍未验证

- 通过 UI 触发 `ScanFileAction`（Tools 菜单 / `Ctrl+Alt+Shift+A`）——动作已注册，留给 M1 交互验证；
- 在「日常 GoLand」里安装插件后的行为（沙盒已证明加载路径一致，但用户配置下插件冲突面更大）；
- `WriteCommandAction` 真正改文档 + Undo 分组（M1 实现函数体写回时验证）；
- 沙盒实例的 JDWP attach（`DEBUG_PORT=5005` 已支持，未实测）。
