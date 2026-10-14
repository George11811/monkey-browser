# Monkey Browser — 开发方案

> 文档版本：1.0  
> 日期：2026-10-12  
> 目标：为 JetBrains Rider 开发支持 Userscript 的 JCEF 内嵌浏览器插件。  
> 读者：AI Coding Agent、插件开发者。

## 1. 项目目标

在 Rider 内实现浏览器，可在右侧 **ToolWindow** 或中央 **Editor Tab** 使用，支持多个网页标签页，并可加载 Tampermonkey / Violentmonkey 格式的 `.user.js` 用户脚本。

### 1.1 MVP 功能（P0）

- ToolWindow 内嵌浏览器，地址栏、导航、前进/后退、刷新。
- 中央 Editor Tab 浏览器，以及从 ToolWindow 切换至 Editor Tab。
- 多标签页管理，避免浏览器会话在切换 UI 容器时意外丢失。
- 用户脚本导入、启用/禁用、URL 匹配、页面加载后的 JS/CSS 注入。
- 基本错误处理与资源释放。

### 1.2 后续功能

- P1：GM 存储 API、脚本管理界面、脚本编辑、会话恢复、脚本权限。
- P2：GM 网络 API、资源加载、自动更新、DevTools、下载支持、iframe/SPA 兼容。

**非目标（初始版本）**：完整 Tampermonkey 兼容、安装 Chrome 扩展、与外部 Chrome/Edge 自动共享用户配置或 Cookie。

## 2. 技术选型

| 模块 | 技术 | 职责 |
| --- | --- | --- |
| IDE 插件 | IntelliJ Platform SDK | Rider 前端扩展 |
| 语言 | Kotlin | 插件逻辑 |
| 构建 | Gradle + IntelliJ Platform Gradle Plugin 2.x | 构建和测试 |
| 界面 | Swing、JetBrains UI Components | ToolWindow、工具栏、脚本管理 |
| 浏览器 | JCEF / `JBCefBrowser` | Chromium 网页渲染 |
| JS 桥接 | `JBCefJSQuery` | JavaScript 与 Kotlin 交互 |
| 用户脚本 | 自定义 Parser / Matcher / Injector | 元数据解析与注入 |
| 配置 | `PersistentStateComponent` | IDE 插件设置 |
| 脚本数据 | JSON / 文件系统 | 脚本正文、GM 存储 |

建议以 Rider 2026.2 作为首个目标版本，并对 JDK、IDE SDK、插件模块依赖进行独立验证；若需要兼容 Rider 2025.x，应建立不同 IDE 版本的测试矩阵。2026.2 目标需按 JetBrains SDK 要求显式声明 JCEF 模块，并检查 Java 25 的构建要求。

此插件的浏览、脚本与 UI 功能都可优先在 IntelliJ 前端实现，**不需要**为最初的目标编写 ReSharper 后端逻辑。

## 3. 总体架构

```text
Rider
├── ToolWindow UI ───────────────┐
├── Editor Tab UI ───────────────┤
└── Settings / Script Manager ───┤
                                  ▼
                          BrowserManager
                          ├── BrowserSession
                          ├── NavigationController
                          ├── ScriptManager
                          └── PermissionManager
                                  ▼
                           JCEF Adapter
                          ├── JBCefBrowser
                          ├── ScriptInjector
                          └── GM Bridge
                                  ▼
                           Chromium Page
```

原则：**浏览器会话与 UI 容器解耦**。ToolWindow 和 Editor Tab 使用同一套 BrowserManager，但同一 Swing `JComponent` 不能同时挂载两个父容器；切换容器时在 EDT 安全移除和重新挂载，或者在确有必要时采用独立浏览器实例。

## 4. 模块设计

### 4.1 BrowserManager

职责：创建、导航、关闭、切换、恢复浏览器会话；维护标签列表与活动会话；统一浏览器生命周期。

```kotlin
interface BrowserManager {
    fun createTab(url: String): BrowserSession
    fun closeTab(id: String)
    fun navigate(id: String, url: String)
    fun moveToEditor(id: String)
    fun moveToToolWindow(id: String)
    fun getActiveSession(): BrowserSession?
}

data class BrowserSessionState(
    val id: String,
    val url: String,
    val title: String,
    val placement: BrowserPlacement,
    val isPinned: Boolean
)

enum class BrowserPlacement { TOOL_WINDOW, EDITOR_TAB }
```

`BrowserSessionState` 是持久化模型；不要序列化 `JBCefBrowser`、`JBCefClient` 等本地资源。建议 ToolWindow 使用 `ToolWindowFactory` / `ContentManager`，Editor Tab 使用 `FileEditorProvider` / 自定义 `VirtualFile` / `FileEditorManager`。在 UI EDT 上进行 Swing 结构变化，在生命周期末尾释放 JCEF 资源。

### 4.2 Userscript 解析与匹配

示例脚本：

```javascript
// ==UserScript==
// @name         Example Script
// @namespace    rider.monkey
// @version      1.0
// @match        https://example.com/*
// @run-at       document-end
// @grant        GM_getValue
// @grant        GM_setValue
// ==/UserScript==

console.log("Hello from Rider!");
```

建议分离：

1. `UserscriptParser`：读取元数据块及脚本正文。
2. `UserscriptMatcher`：根据 URL 解析 `@match`、`@include`、`@exclude`；处理优先级及边界情况。
3. `ScriptManager`：存储、启停、版本、授权信息。
4. `ScriptInjector`：按每个页面与 frame 的生命周期选择执行时机。

首版至少解析 `@name`、`@namespace`、`@version`、`@match`、`@include`、`@exclude`、`@grant`、`@run-at`、`@noframes`。

执行流程：页面导航 → 识别文档与 frame → URL 匹配 → 检查启用和权限 → 判定运行阶段 → 注入 → 日志与错误回收。

**重要限制**：页面加载后的 `executeJavaScript()` 并不能严格提供 `document-start` 语义。首版仅承诺经过测试的 `document-end` / `document-idle`；`document-start` 应先验证目标 JCEF 暴露的早期注入能力，包括 iframe、SPA 导航及执行上下文。

### 4.3 GM API Bridge

通过 JBCefJSQuery（或经验证的桥接技术）在 JavaScript 与 Kotlin 间传递 RPC 消息：

```text
Userscript → JS Bridge → 权限/身份验证 → Kotlin Service → 响应
```

推荐 API 分批实现：

- MVP：`GM_addStyle`。
- V0.2：`GM_getValue`、`GM_setValue`、`GM_deleteValue`、`GM_listValues` 及相应现代 Promise 风格接口。
- V0.3：`GM_xmlhttpRequest`、`GM_registerMenuCommand`、`GM_getResourceText`。
- V0.4：`GM_download` 等增强能力。

**同步语义风险**：旧式 `GM_getValue` 等接口通常是同步调用，而 JBCefJSQuery 是异步桥接；不能直接返回 Promise 却声称兼容同步 API。可研究注入前预加载存储快照，并以消息桥同步后续写入。语义需要通过专门的兼容测试。

**安全要求**：RPC 每次校验脚本 ID、操作类型、权限、当前文档/导航 token；按元数据授权控制跨域请求；禁止普通网页借助 JS Bridge 调用任意 Kotlin 后端能力。

### 4.4 资源与持久化

- Rider 工程设置与插件全局设置分离。
- 用户脚本文件、启用状态、来源与授权持久化。
- 会话仅保存必要状态（URL、标签排序、容器位置），运行时浏览器对象不得序列化。
- 明确 dispose 责任：关闭单个标签、关闭项目、卸载插件均需释放对应资源。
- 对敏感数据（如 token / Cookie）不应在日志中明文输出。

## 5. 建议目录

```text
monkey-browser/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── src/main/
│   ├── kotlin/com/ridermonkey/
│   │   ├── browser/
│   │   │   ├── BrowserManager.kt
│   │   │   ├── BrowserSession.kt
│   │   │   ├── JcefBrowserAdapter.kt
│   │   │   └── NavigationController.kt
│   │   ├── ui/
│   │   │   ├── BrowserToolWindowFactory.kt
│   │   │   ├── BrowserFileEditorProvider.kt
│   │   │   ├── BrowserPanel.kt
│   │   │   └── ScriptManagerPanel.kt
│   │   ├── userscript/
│   │   │   ├── UserscriptParser.kt
│   │   │   ├── UserscriptMatcher.kt
│   │   │   ├── UserscriptEngine.kt
│   │   │   └── ScriptInjector.kt
│   │   ├── bridge/
│   │   │   ├── GmBridge.kt
│   │   │   ├── GmStorage.kt
│   │   │   └── PermissionManager.kt
│   │   └── storage/
│   │       ├── PluginSettings.kt
│   │       └── ScriptRepository.kt
│   └── resources/
│       ├── META-INF/plugin.xml
│       └── js/
│           ├── userscript-runtime.js
│           └── gm-api.js
└── src/test/
    ├── UserscriptParserTest.kt
    ├── UserscriptMatcherTest.kt
    └── ScriptPermissionTest.kt
```

`plugin.xml` 扩展点示意（完整构建前需按目标 SDK 核对）：

```xml
<idea-plugin>
    <id>com.ridermonkey.browser</id>
    <name>Monkey Browser</name>
    <vendor>Monkey Browser</vendor>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.modules.jcef</depends>

    <extensions defaultExtensionNs="com.intellij">
        <toolWindow id="Monkey Browser" anchor="right"
            factoryClass="com.ridermonkey.ui.BrowserToolWindowFactory"/>
        <fileEditorProvider
            implementation="com.ridermonkey.ui.BrowserFileEditorProvider"/>
    </extensions>
</idea-plugin>
```

## 6. 开发里程碑与验收

> 工作量为粗估，前提是开发者熟悉 Kotlin 和 IntelliJ 插件 SDK；JCEF 行为及同步 GM API 的不确定性可能带来显著返工。

| 版本 | 预估 | 范围 | 验收标准 |
| --- | --- | --- | --- |
| V0.1 | 2–4 天 | ToolWindow / Editor Tab、导航、JCEF 生命周期 | 常见 HTTPS 网页可打开；切换容器不丢页面状态 |
| V0.2 | 3–5 天 | Userscript Parser/Matcher、注入、存储 GM API | `.user.js` 能导入，匹配网址执行，刷新后仍生效 |
| V0.3 | 4–7 天 | 网络 GM API、权限、安全、iframe、异常追踪 | 权限与脚本身份可验证，能诊断执行失败 |
| V1.0 | 5–10 天 | 持久化、性能、资源管理、兼容性、发布 | 重启恢复会话，无明显资源泄露，通过目标 SDK 兼容性校验 |

总计约 **14–26 个工作日**，不包含完整 Tampermonkey 兼容。

## 7. 优先技术验证（必须先做）

- [ ] **JCEF 注入时机 PoC**：确定可实现的运行阶段，尤其 `document-start`、iframe 与 SPA。
- [ ] **跨容器组件迁移 PoC**：测试 ToolWindow ↔ Editor Tab 时页面状态、输入焦点、生命周期。
- [ ] **GM Bridge 安全 PoC**：阻止普通页面越权调用；评估同步 GM API 兼容。
- [ ] 验证目标 Rider 版本、JDK、JCEF 模块依赖和 `JBCefApp.isSupported()`。

以上验证结果应写入 ADR（Architecture Decision Record），再确定最终实现。

## 8. 风险与对策

| 风险 | 对策 |
| --- | --- |
| JCEF 内存和 CPU 占用 | 标签按需加载、明确 dispose、性能基准 |
| 登录态与 Cookie | 依赖 JCEF 自身会话，不承诺与 Edge 共享 |
| JS Bridge 越权 | 最小权限、脚本身份及 document token 校验 |
| 跨域请求滥用 | 按 `@connect` 等元数据授权，敏感操作显式确认 |
| 脚本破坏网页 | 单脚本启停、异常捕获、可观察性 |
| CSP、iframe、SPA | 按能力矩阵分批验证，不做超前兼容承诺 |
| 生命周期泄漏 | 资源 ownership / `Disposable` 管理及压力测试 |
| Rider 版本升级破坏 | 多目标 IDE 兼容性检查、自动化回归 |

## 9. AI 开发执行建议

开发 Agent 应严格按以下顺序推进：

1. 创建最小 IntelliJ Platform 插件工程，确保在目标 Rider 中启动。
2. 完成 JCEF ToolWindow 并验证基本导航。
3. 实现 BrowserSession 生命周期及 Editor Tab。
4. 完成三个高风险 PoC，并记录验证结果。
5. 编写 Userscript Parser 和 Matcher 的单元测试。
6. 实现可用的 `document-end` 注入和脚本管理。
7. 添加 GM 数据存储与权限模型。
8. 逐步增加复杂 GM API、持久化与兼容性测试。

约束：

- 不得假定 JCEF 可直接安装 Chrome 扩展或 Tampermonkey CRX。
- 不得把网页加载后执行 JS 等同于 `document-start`。
- 不得把异步 Promise API 冒充同步 `GM_getValue`。
- 不得把页面 JS Bridge 暴露成无认证的特权 Kotlin API。
- 对 JetBrains SDK 版本相关接口，以目标版本官方文档和可运行 PoC 为准。

## 10. 官方参考链接

- JCEF：https://plugins.jetbrains.com/docs/intellij/embedded-browser-jcef.html
- ToolWindow：https://plugins.jetbrains.com/docs/intellij/tool-windows.html
- Rider 插件开发：https://plugins.jetbrains.com/docs/intellij/rider.html
- IntelliJ Platform Gradle Plugin：https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html

---

**推荐首个可用交付目标：V0.2**。先让 Rider 内侧栏/Editor Tab 网页浏览与基础 Userscript 执行稳定工作，再提高 Tampermonkey 兼容度。
