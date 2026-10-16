# Monkey Browser

在 JetBrains Rider 内提供内嵌浏览器（JCEF），并计划支持 Tampermonkey / Violentmonkey 格式的
用户脚本（`.user.js`）。

开发方案见 [docs/Monkey-Browser-Development-Plan.md](docs/Monkey-Browser-Development-Plan.md)，
关键取舍与实测结论见 [docs/adr/](docs/adr/)（16 篇 ADR）。

## 当前进度

当前版本 **0.17.7**。构建 + 181 个单测（0 失败）+ Plugin Verifier 校验通过（Compatible）。

| 里程碑 | 状态 |
| --- | --- |
| 最小 IntelliJ Platform 插件工程 | 已实现 |
| JCEF ToolWindow + 地址栏 / 前进 / 后退 / 刷新 / 停止 / 编辑区切换 | 已实现 |
| JCEF 注入时机 PoC（方案 §7 第 1 项） | 已完成，结论见 ADR-0002（**已于 2026-10-13 修订**：解析期 `loading` 可达，但 `document-start` 仍不可行） |
| 跨容器组件迁移 PoC（方案 §7 第 2 项） | 已完成并实测通过，见 ADR-0006 |
| Userscript 解析 / 匹配 | 已实现，语义见 ADR-0004 |
| Userscript 注入（两档时机 + 落点自愈） | 已实现，架构见 ADR-0003（曾尝试「早档探针」方案并**已回滚**，原因见该 ADR 的修订节）|
| 脚本库（文件夹即库）+ 脚本管理工具窗 + 全局暂停 | 已实现 |
| GM 存储 API 与 `GM_addStyle` | 已实现，语义与安全模型见 ADR-0005 |
| 中央 Editor Tab 浏览器、ToolWindow ↔ Editor Tab 互转 | 已实现 |
| 多标签页管理 | 已实现（每个标签页独立会话，可各自移到编辑区） |
| 会话恢复 | 已实现（只恢复 URL，位置一律回侧边栏），见 ADR-0008 |
| 书签（默认名取域名；收藏 / 打开 / 新标签页打开 / 重命名 / 删除） | 已实现，见 ADR-0014 |
| GM Bridge 安全 PoC（方案 §7 第 3 项） | 已完成：**isolated world 经核实不可用**（JCEF 平台限制），改用 console 消息 source 指纹，见 ADR-0007 |
| 脚本权限（按 `@grant` 裁剪能力） | 已实现（`GmCapabilities`） |
| 脚本编辑 | 已实现（双击 / 「编辑」按钮用 IDE 编辑器打开） |
| GM 网络 API（`GM_xmlhttpRequest` / `@connect` 授权） | 已实现，见 ADR-0009 |
| `@resource` + `GM_getResourceText/URL`、`GM_info`、`GM_setClipboard`、`GM_notification` | 已实现，见 ADR-0010 |
| DevTools 入口 | **评估后撤回**（0.13.5 引入、0.13.6 移除）：平台 API 确实存在且能打开窗口，但 JetBrains 的 JCEF 未打包 DevTools 前端（`resources.pak` 里搜不到），内嵌窗口恒为空白。可用替代＝调试端口 + 外部 `chrome://inspect`。详见 ADR-0011 |
| `GM_xmlhttpRequest` 的 `responseType` / `onprogress` / `abort()` | 已实现，见 ADR-0012 |
| `GM_download` / `GM.download`（落盘到系统下载目录，重名加序号） | 已实现，见 ADR-0012 |
| `GM_openInTab` / `GM.openInTab`（含 `close()` 句柄；`onclose` 仅在脚本主动关闭时触发） | 已实现，见 ADR-0012 |
| 自动更新（`@updateURL` / `@downloadURL`） | 已实现，见 ADR-0013。**刻意做成「按需检查 + 用户确认后应用」，不是静默自动替换**——理由见该 ADR 开头 |
| 在系统浏览器中打开当前页（JCEF 访问不了的站点走这条） | 已实现，动机见 ADR-0015 |
| 页面弹窗接管（`target="_blank"` / `window.open` → 插件新标签页，兜底系统浏览器） | 已实现，见 ADR-0017 |
| P2 其余（脚本菜单命令 `GM_registerMenuCommand`） | 未开始 |

> **命名说明**：本插件原名 `Rider Monkey Browser`（0.16.1 及以前）。插件名含 JetBrains 商标词
> `Rider` 会触发 Marketplace 的 `TemplateWordInPluginName` 校验，故改名为 **Monkey Browser**；
> 校验通过后，构建里原先 `-mute` 跳过它的临时手段也已移除。
>
> 插件 id `com.ridermonkey.browser` **保持不变**，所以升级不会出现重复插件、也不丢配置。
> Kotlin 包名 `com.ridermonkey.*`、数据目录 `rider-monkey`、日志 logger `RiderMonkeyBrowser`
> 同理保持原样 —— 这些是**程序认的身份**，改了会丢数据或丢会话状态。
>
> `docs/adr/` 是当时写下的决策记录，其中的旧名与旧路径**未回改**，属于历史证据。

## 环境要求

| 项 | 版本 | 说明 |
| --- | --- | --- |
| 目标 IDE | **JetBrains Rider 2026.2**（`RD-262.8665.328`） | `since-build=262` |
| 编译 JDK | **JDK 25** | 262 平台字节码为 class file major 69 |
| Gradle | 9.8.1（由 wrapper 提供） | IntelliJ Platform Gradle Plugin 2.19.0 要求 Gradle 9.0.0+ |
| Kotlin | 2.4.21 | |
| IntelliJ Platform Gradle Plugin | 2.19.0 | |

编译 JDK 可直接用你本机 IDE 自带的 JBR（路径含空格，注意加引号）：

```
<你的 IDE 安装目录>\jbr
```

## 构建

**前置步骤：设置编译目标 IDE。** 缺少 `monkeyBrowser.idePath` 时构建会在配置阶段直接失败
（这是有意的 fail-fast，详见下节「编译目标 IDE」）：

```powershell
# 一次性写法（推荐写进 ~/.gradle/gradle.properties，见下节）
.\gradlew.bat -PmonkeyBrowser.idePath="D:/Your/IDE" tasks
```

然后构建：

```powershell
$env:JAVA_HOME = '<你的 IDE 安装目录>\jbr'
.\gradlew.bat buildPlugin check verifyPluginProjectConfiguration

# 与真实 Rider 2026.2 做二进制兼容性校验（无 GUI，会联网下载校验依赖）
.\gradlew.bat verifyPlugin
```

产物位于 `build/distributions/`；兼容性报告位于 `build/reports/pluginVerifier/`。

`check` 里挂了三个校验：Kotlin 单测、`checkHandlerVersion`（版本常量与 `version` 一致）、
以及 `checkGmRuntime`（用 Node 沙箱跑 `gm-api.js` 的行为断言，见下）。

> `checkGmRuntime` **需要本机有 `node`**。找不到 node 时会明确失败，而不是静默跳过——
> 静默跳过等于没有校验。确实没有 node 的机器用 `-PskipGmRuntimeCheck=true` 显式跳过。

### GM 运行时校验（tools/check-gm-runtime.mjs）

`gm-api.js` 是注入到页面里的 GM 运行时，**Kotlin 单测覆盖不到它**。它改坏的症状是
「脚本在页面上静默失效」——从插件侧很难发现。因此单独拉出来，用 Node 的 `vm` 造一个
最小页面沙箱（`window` + `console` + 少量 Web API）跑行为断言：

```powershell
.\gradlew.bat checkGmRuntime
# 或直接跑（便于调试，可指定文件）
node tools\check-gm-runtime.mjs
```

覆盖范围：

| 分组 | 断言内容 |
| --- | --- |
| 授权裁剪 | 未 `@grant` 的 API 必须**不存在**（防止能力泄漏给所有脚本） |
| 回传协议 | 每条消息都必须带 `scriptId` / `frameId` / `token` / `op` |
| GM_xmlhttpRequest | method 大写、responseType / timeout / header 透传、进度事件形状、`text`/`json`/`arraybuffer`/`blob`、abort 带同一 id、`document` 明确抛错、Promise 版 abort |
| GM_download | name / header 透传、`path` / `name` 回传、Promise 版 |
| GM_openInTab | `active` 透传与默认值、句柄字段、`close()` 发 `closeTab`、**id 未到达时先记下、到达后补发**、`onclose` |

### 已实测的验证结果

```text
gradlew clean buildPlugin check verifyPluginProjectConfiguration verifyPlugin -> BUILD SUCCESSFUL
单测：14 个测试类，tests=181  failures=0  errors=0  skipped=0
gradlew checkHandlerVersion                                                 -> BUILD SUCCESSFUL
gradlew checkGmRuntime                                                      -> BUILD SUCCESSFUL（56 项断言，0 失败）
gradlew verifyPlugin
  -> Plugin com.ridermonkey.browser:0.17.7 against RD-262.8665.328: Compatible
     8 usages of deprecated API, 12 usages of experimental API
```

那 8 + 12 条告警全部指向 `ToolWindowFactory` 的接口方法（`isApplicable`、`isDoNotActivateOnStart`、
`getAnchor`、`getIcon`、`manage`、`shouldBeAvailable`）。本工程的源码只 override 了
`createToolWindowContent`，其余是 **Kotlin 编译器为接口默认方法生成的桥接方法**，不是可修的源码问题。

> 注意：**不要**用 JDK 25 去启动 Gradle 8.x。Gradle 8.14.4 内嵌的 Kotlin DSL 编译器无法解析
> Java 25 的版本串（`IllegalArgumentException: 25.0.4`）。本项目使用 Gradle 9.8.1 规避该问题。

### 本机代理

本机访问国外仓库需要走 7897 代理。Gradle 不读取 `HTTP_PROXY` 环境变量，用：

```powershell
$env:JAVA_TOOL_OPTIONS = '-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897'
```

代理配置故意不写进 `gradle.properties`，避免把机器相关配置带进仓库。

## 编译目标 IDE

构建需要 `monkeyBrowser.idePath` 指向**本机已安装**的 IDE 目录，`local(...)` 会直接拿它作为平台
依赖来源，省掉下载 1GB+ 的 IDE 分发包。

**该属性刻意不提交进仓库**（它是机器相关路径）。任选一种方式提供：

| 方式 | 写法 | 适用 |
| --- | --- | --- |
| 用户级配置（推荐） | 在 `~/.gradle/gradle.properties` 写 `monkeyBrowser.idePath=D:/Your/IDE` | 配一次全仓库生效，且不会误提交 |
| 命令行 | `.\gradlew.bat -PmonkeyBrowser.idePath="D:/Your/IDE" <task>` | 临时换目标 IDE |

> **未设置会在配置阶段直接失败**，报错信息会告诉你该怎么填。因为失败发生在配置阶段，
> 所以连 `gradlew tasks` 也会失败 —— 这不是工程坏了。

目标平台是 IntelliJ Platform **262**（`since-build=262`，已在 `RD-262.8665.328` 上验证）。
判断某个目录能不能当编译目标，**看目录里 `product-info.json` 的 `buildNumber`，不要看目录名** ——
目录名可能具有误导性。

## 在 IDE 中运行

```powershell
.\gradlew.bat runIde
```

## 日志与排查

插件日志统一走 `RiderMonkeyBrowser` 这个 logger，行内前缀区分来源：
`[RM-BROWSER]`（浏览器/内核/标签页）、`[RM-INJECT]`（脚本注入）、`[RM-GM]`（GM 桥）、
`[RM-XHR]`（GM 网络）、`[RM-RES]`（@resource 缓存）。

**默认级别是刻意压低的。** 逐帧加载（`onLoadStart` / `onLoadEnd`）和内核尺寸推送
（拖动窗口时每次重绘都会触发）位于高频路径，实测单个会话可产生上千行日志，
因此**降到 DEBUG**；INFO 只保留低频且关键的事件——导航、脚本注入与执行、内核创建/就绪/释放、
标签页新建/释放、容器创建/释放、以及所有失败与拒绝。

需要全量日志时：

```text
Help | Diagnostic Tools | Debug Log Settings...
加入：RiderMonkeyBrowser
```

日志文件位置：

```text
%LOCALAPPDATA%\JetBrains\Rider2026.2\log\idea.log
```

> 排查"是否重复创建内核"时，**不要数 `chrome://inspect` 的行数**——它不显示 target 类型、
> 也不随目标关闭实时刷新，同一页面的跨域 iframe 会被并列显示，容易误判。
> 权威来源是 `http://127.0.0.1:9222/json/list`，只数 `"type": "page"` 的条目；
> 插件日志里的 `新建标签页 … 来源=…` 与 `面板#… 已释放` 也可交叉核对。

## 决策记录（ADR）

| 编号 | 主题 |
| --- | --- |
| [ADR-0001](docs/adr/0001-build-and-environment-decisions.md) | 构建工具链与 JCEF 接入方式 |
| [ADR-0002](docs/adr/0002-jcef-injection-timing.md) | JCEF 注入时机实测（定论） |
| [ADR-0003](docs/adr/0003-userscript-injection-architecture.md) | 用户脚本注入架构 |
| [ADR-0004](docs/adr/0004-userscript-matching-semantics.md) | 用户脚本 URL 匹配语义 |
| [ADR-0005](docs/adr/0005-gm-api-semantics.md) | GM API 语义、桥接通道与安全模型 |
| [ADR-0006](docs/adr/0006-editor-tab-container.md) | 中央编辑区容器与跨容器迁移 |
| [ADR-0007](docs/adr/0007-gm-bridge-security.md) | GM Bridge 安全：isolated world 不可用与来源指纹 |
| [ADR-0008](docs/adr/0008-session-restore.md) | 浏览器会话恢复 |
| [ADR-0009](docs/adr/0009-gm-network-api.md) | GM 网络 API 与 @connect 授权 |
| [ADR-0010](docs/adr/0010-more-gm-apis-and-runtime-verification.md) | 补齐 GM API、资源缓存与 Node 运行时验证 |
| [ADR-0011](docs/adr/0011-devtools-entry.md) | DevTools 入口：平台 API 存在但 JCEF 未打包前端（已撤回） |
| [ADR-0012](docs/adr/0012-download-openintab-and-xhr-extras.md) | GM_download / GM_openInTab 与 xhr 的 responseType·onprogress·abort |
| [ADR-0013](docs/adr/0013-script-updates.md) | 脚本更新检查（@updateURL）与「不静默覆盖」的取舍 |
| [ADR-0014](docs/adr/0014-bookmarks.md) | 书签：文件存储格式与弹出菜单式 UI |
| [ADR-0015](docs/adr/0015-jcef-site-isolation-cloudflare-loop.md) | Cloudflare 挑战页死循环：JCEF 站点隔离（**非插件缺陷**） |
| [ADR-0016](docs/adr/0016-page-tooltip-needs-host.md) | 页面 tooltip 必须由插件自己接（JCEF 只转发不显示） |
| [ADR-0017](docs/adr/0017-page-popups.md) | 页面弹窗接管：新标签页 + 系统浏览器兜底（`about:blank` 刻意不拦截） |

## 用户脚本

脚本库是一个文件夹（丢文件即导入）：

```text
%APPDATA%\JetBrains\Rider2026.2\rider-monkey\scripts\*.user.js
```

GM 存储落在同级的 `storage/<脚本名>/<键>`，禁用状态在 `disabled.txt`。
界面用右侧的 **Monkey Scripts** 工具窗管理（启用/禁用、刷新、打开目录、移出脚本库）。

当前实现的 GM API：`GM_addStyle`、`GM_getValue`、`GM_setValue`、`GM_deleteValue`、
`GM_listValues`，以及 Promise 风格的 `GM.*` 别名。语义见 ADR-0005。

## 目录结构

```text
build.gradle.kts      构建脚本（含 checkHandlerVersion / checkGmRuntime 两个校验任务）
gradle.properties     Gradle 配置（不含机器相关路径，见「编译目标 IDE」）
src/main/kotlin/com/ridermonkey/
├── browser/    位置模型（ToolWindow / Editor Tab）、会话状态、虚拟文件与编辑器接入
├── storage/    脚本文件、GM 存储、@resource 缓存、书签
├── ui/         浏览器面板与容器、工具栏、脚本管理工具窗、文件编辑器 Provider
├── userscript/ 元数据解析、URL 匹配、脚本选择、注入、GM 桥与网络、能力裁剪、更新检查
└── util/       URL 规范化、JSON 转义
src/main/resources/
├── META-INF/plugin.xml   插件声明
└── js/gm-api.js          注入到页面的 GM 运行时（由 checkGmRuntime 校验）
src/test/kotlin/        14 个测试类 / 181 个单测（纯逻辑，不依赖 IDE）
tools/check-gm-runtime.mjs   gm-api.js 的 Node 沙箱行为断言（56 项）
samples/hello.user.js        示例用户脚本
docs/adr/                    16 篇决策记录
docs/Monkey-Browser-Development-Plan.md   开发方案
```

Swing / JCEF 相关逻辑需要真实 IDE 环境，**没有单元测试**，目前依赖手工验证。

## 已知限制

- 已对准真正的 Rider 2026.2 平台（RD-262.8665.328）构建通过，并通过 Plugin Verifier 校验为
  **Compatible**；也已在本机 Rider 2026.2 上以构建产物实际安装并手工验证过主要路径。
  但没有自动化 UI 测试，Swing / JCEF 逻辑的回归仍需手工验证。
- **受 Cloudflare 托管挑战保护的站点可能反复重试**（卡在 "Just a moment..."）：若所在 IDE 把
  `ide.browser.jcef.osr.siteIsolation.disable` 默认为 true（IDEA 2026.2.3 实测如此，Rider 不会），
  CEF 会带 `--disable-site-isolation-trials` 启动，跨域 iframe 与顶层文档同进程，Turnstile 无法完成验证。
  **解法见 ADR-0015；这不是插件缺陷。**
- 注入时机为「解析期 + 页面加载完成」两档；`document-start` 在 JCEF 上不可行（实测结论见 ADR-0002）。
- `JBCefApp.isSupported()` 为 false 时（headless 等），面板显示降级提示而不是崩溃。

## 许可证

[MIT](LICENSE)
