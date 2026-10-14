# ADR-0001：构建工具链与 JCEF 接入方式

- 日期：2026-10-12
- 状态：已采纳（部分条目待后续 PoC 补充）
- 相关文档：Monkey-Browser-Development-Plan.md 第 2、4、7、9 节

本 ADR 只记录**在本机实测得到的事实**，不记录推测。每条结论都附证据来源。

## 背景

方案要求先完成技术验证再确定实现，并把验证结果写入 ADR。本次为 V0.1 骨架搭建过程中实际
踩到并确认的构建/接入问题。

## 决策与依据

### D1：编译目标直接对准本机安装的 Rider 2026.2

- **目标 IDE 已在本机，但目录名是误导性的**：`D:\JetBrains Rider 2025.3.3` 目录里的
  `product-info.json` 写的是 `name=JetBrains Rider`、`version=2026.2`、
  `buildNumber=262.8665.328`、`dataDirectoryName=Rider2026.2`；`build.txt` 为
  `RD-262.8665.328`。正在运行的进程同样是 `D:\JetBrains Rider 2025.3.3\bin\rider64.exe`。
  即：**装的是 Rider 2026.2，目录名停留在 2025.3.3**。
- 该项目标的 `minRequiredJavaVersion=25`，与 D2 一致；自带 JBR 25.0.3；
  `plugins\jcef-plugin\lib\modules\` 下 JCEF 模块 jar 齐全。
- 因此 `riderMonkey.idePath=D:/JetBrains Rider 2025.3.3`，编译平台就是真正的目标平台，
  既不需要下载 IDE 分发包，也不需要拿 IDEA 代打。

**更正记录（重要）**：本 ADR 早期版本判断「本机未安装 Rider 2026.2」，依据是
`C:\Program Files\JetBrains\Rider` 下只有一个 `r2r` 目录、注册表卸载项只有
`JetBrains Rider 2025.3.3 (253.31033.136)`、Toolbox 目录不存在。该判断**错误**，原因：
(1) 当时 DSH 沙箱对工作区外的读取是部分受限的，目录枚举结果并不完整；
(2) 该安装位于 D 盘，且不是安装器方式（注册表无对应卸载项），不在当时的检索范围内。
**教训**：判断「某软件没装」不能只依赖单一枚举路径 + 注册表，应交叉使用运行中进程的
`Path`、全盘 `product-info.json` / `build.txt` 检索等手段。

### D2：编译 JDK 必须是 25

- 实测 262 平台 class 文件 **major version = 69**，即 Java 25 字节码。
- 编译用的 JDK 25 直接用目标 IDE 自带的 JBR：`D:\JetBrains Rider 2025.3.3\jbr`（25.0.3）。
  已确认它是**完整 JDK**：含 `bin/javac.exe` 与 `jdk.compiler` 模块（但缺 `jdk.jdeps`，
  所以没有 `javap`）。
- 本机另一处 JDK 25 是 IDEA 自带的 JBR（25.0.4），亦可使用；本机唯一的 JDK 17
  无法编译 262 平台。

### D3：Gradle 必须 ≥ 9.0.0，且必须由 wrapper 固定

- IntelliJ Platform Gradle Plugin 2.19.0 在配置阶段直接抛：
  `IntelliJ Platform Gradle Plugin requires Gradle Gradle 9.0.0 and higher`
  （`org.jetbrains.intellij.platform.gradle.plugins.PluginsKt.checkGradleVersion`）。
- 本机原有 Gradle 8.14.4 **无法在 JDK 25 上运行 Kotlin DSL**，实测异常：
  `java.lang.IllegalArgumentException: 25.0.4`
  `at org.jetbrains.kotlin.com.intellij.util.lang.JavaVersion.parse`
  —— Gradle 8.14.4 内嵌的 Kotlin（2.0.21）不认识 Java 25 的版本串。
- 因此：**用 Gradle 9.8.1（wrapper 固定）+ JBR 25 运行**，一次绕开两个限制。
- wrapper 引导方式：用 8.14.4 在临时空工程执行 `wrapper --gradle-version 9.8.1`，再把
  `gradlew`/`gradlew.bat`/`gradle/wrapper/*` 复制进项目（必须带代理，否则 wrapper 任务的
  分发地址校验会失败）。

### D4：JCEF 的模块 ID 是 `com.intellij.modules.jcef`

- 证据：`<IDEA>\plugins\jcef-plugin\lib\jcef-plugin.jar!META-INF/plugin.xml` 中
  `<id>com.intellij.modules.jcef</id>`，版本 `262.10968.63-windows-x86_64`，
  `<idea-version since-build="262.10968" until-build="262.*"/>`。
- 相关类位于该插件的模块 jar：`intellij.platform.ui.jcef.jar`（`com.intellij.ui.jcef.*`）
  与 `intellij.libraries.jcef.jar`（`org.cef.*`）。
- Rider 2026.2 侧同一路径同样存在该插件，模块 jar 齐全（`intellij.platform.ui.jcef.jar`、
  `intellij.libraries.jcef.jar` 等），因此对准 Rider 构建时 `bundledPlugin` 可正常解析。

### D5：`local()` 不会自动带入内置插件模块，必须显式声明

- 只写 `local(idePath)` 时，`com.intellij.ui.jcef.*` 与 `org.cef.*` **全部无法解析**，
  `compileKotlin` 报 `Unresolved reference 'JBCefApp'` 等。
- 修复：额外声明 `bundledPlugin("com.intellij.modules.jcef")`。

### D6：`JBCefClient` 的 Kotlin 属性名不是 `jcefClient`

- `getJBCefClient()` 在 Kotlin 中推导出的属性名是 `JBCefClient`（前两个字母均为大写时不
  做首字母小写化）。写 `browser.jcefClient` 会报
  `Unresolved reference 'jcefClient' on receiver of type 'JBCefBrowser'`。
- 本项目统一**显式调用 getter**，避免依赖隐式属性名推导。

### D7：`until-build` 不写

- `verifyPluginProjectConfiguration` 提示：IntelliJ Platform 2024.3+（build 243+）建议移除
  `until-build`，否则会挡住用户升级后的新 IDE 版本。
- 只保留 `sinceBuild = "262"`。

### D8：禁用 Gradle Kotlin 插件自动添加的 stdlib

- `verifyPluginProjectConfiguration` 提示 stdlib 版本冲突可能导致运行时
  `ClassNotFoundException`。
- 采用官方建议：`kotlin.stdlib.default.dependency=false`（平台自带 stdlib）。

## 已确认可用的 JCEF API 签名（262 实测）

从本地 jar 解析出的真实签名，用于替代猜测：

| 调用 | 签名 |
| --- | --- |
| 创建浏览器 | `JBCefBrowser.createBuilder().setCreateImmediately(true).setUrl(url).build()` |
| 取视图 | `JBCefBrowserBase.getComponent(): JComponent` |
| 导航 | `JBCefBrowserBase.loadURL(String)`、`CefBrowser.goBack()/goForward()/reload()/stopLoad()` |
| 取 CEF 对象 | `getCefBrowser(): CefBrowser`（未标注可空，但底层延迟创建） |
| 加载回调 | `JBCefClient.addLoadHandler(CefLoadHandler, CefBrowser)` |
| JS 查询桥 | `JBCefJSQuery.create(JBCefBrowser)`、`inject(String)`、`addHandler(Function)` |
| 支持性检测 | `JBCefApp.isSupported()` |
| 资源归属 | `JBCefDisposable extends com.intellij.openapi.Disposable`，故 `JBCefBrowser` 可交给 `Disposer` |

注：`Content` **不是** `Disposable`（接口为 `UserDataHolder`+`ComponentContainer`），
不能作为 `Disposer` 的父节点；`ToolWindow.getDisposable()` 存在，用它。

## 本机环境约束

- DSH 沙箱在当前模式下**禁止一切外部进程创建**（`java.exe`/`cmd.exe`/`whoami.exe` 均报
  `Access is denied`，而文件 ACL 正常）。因此所有 Gradle 构建都必须单次提权执行。
- 本机 DNS 为 fake-ip 模式（解析到 `198.18.x.x`），`web_fetch` 会以「非公网 IP」为由拒绝；
  联网一律走 `127.0.0.1:7897` 代理，通过代理参数用 `JAVA_TOOL_OPTIONS` 传给 Gradle。

## 附：V0.1 Review 后的补充实测事实

- 262 的 `Application` **没有** `invokeLater(Runnable, ModalityState, Disposable)` 重载。实测签名只有：
  `invokeLater(Runnable)`、`invokeLater(Runnable, Condition<?>)`、
  `invokeLater(Runnable, ModalityState)`、`invokeLater(Runnable, ModalityState, Condition<?>)`。
  因此「释放后不再执行已排队回调」必须靠自己的 disposed 标志位保证，不能交给父 Disposable。
- `ModalityState.any()` 存在，可用于避免回调被模态对话框阻塞。
- `ContentFactory.getInstance()`、`ToolWindow.getDisposable()` 均存在（实测）。
- V0.1 首轮只读 code review 的结论：无阻断问题，2 条重要、9 条次要、8 条建议；
  其中已确认的缺陷（状态文本互相覆盖、IPv6 被拒、localhost 大小写、空主机 scheme、
  尾点 FQDN、地址栏输入被覆写）已修复并补了回归测试。
- 第二轮复审又发现 3 条重要问题，均已修复：
  1. 方括号 IPv6 分支提前 return，绕过端口校验，`[::1]:abc` / `[::1]:` 被误接受；
  2. `onLoadError` 无差别报错，用户点「停止加载」产生的 `ERR_ABORTED` 被显示成「加载失败」；
  3. 注释声称拒绝 `javascript:`，但 `javascript://%0aalert(1)` 会命中分层 scheme 分支被放行
     —— 安全意图与实现不符，已把危险 scheme 检查提到最前。
- 其余修复：JCEF 不支持分支不再重复显示；`cef == null` 分支不再让状态栏永久停在「加载中…」；
  地址栏改用 DocumentListener + focusLost 跟踪用户编辑而不是焦点启发式；端口只接受
  1..65535 的 ASCII 数字；主机标签做字符集校验；data: 不放行（Chromium 本就拦截顶层 data:
  导航，放行只会静默失败）；空白字符检查提到最前（Kotlin 的 Char.isWhitespace 已覆盖
  NBSP 与全角空格）。

### 修复后的验证结果（实测）

```text
gradlew clean buildPlugin test verifyPluginProjectConfiguration
BUILD SUCCESSFUL
UrlUtilsTest: tests=20, failures=0, errors=0
编译告警：0
verifyPluginProjectConfiguration：无问题
产物：build/distributions/rider-monkey-browser-0.1.0.zip

gradlew verifyPlugin（目标：本机 Rider，RD-262.8665.328）
Plugin com.ridermonkey.browser:0.1.0 against RD-262.8665.328: Compatible
  4 usages of deprecated API, 6 usages of experimental API
```

那 4 + 6 条告警全部指向 `ToolWindowFactory` 的接口方法（`isApplicable`、
`isDoNotActivateOnStart`、`getAnchor`、`getIcon`、`manage`、`shouldBeAvailable`）。
本工程源码只 override 了 `createToolWindowContent`；已解析构建产物中
`BrowserToolWindowFactory.class` 的常量池确认，其余是 Kotlin 编译器为接口默认方法生成的
桥接方法，不是可修的源码问题。

### 用 Plugin Verifier 校验本机 IDE 的写法

```kotlin
intellijPlatform {
    pluginVerification {
        ides { local(riderMonkeyIdePath) }   // 实测可用，校验器会直接读取该 IDE 目录
        // 插件名含 "Rider"，Marketplace 策略检查 TemplateWordInPluginName 会拦截；
        // 这是策略检查不是技术检查，上架前必须处理，暂用官方开关跳过。
        freeArgs = listOf("-mute", "TemplateWordInPluginName")
    }
}
```

同时 `<description>` 必须以拉丁字符开头且 ≥40 字符，否则校验器直接判定插件结构非法、
连兼容性校验都不会排期。

## 待办（尚未验证，不得当作已成立）

- ✅ JCEF 注入时机 PoC —— 已完成（ADR-0002，2026-10-13 修订）。
- ✅ 跨容器迁移 PoC：ToolWindow ↔ Editor Tab 的页面状态与焦点 —— 已完成并实测通过（ADR-0006）。
- ❌ GM Bridge 安全 PoC：越权防护 —— **仍未完成**。当前实现靠一次性 token 校验，
  不是沙箱，页面提前 hook `console.log` 可窃取 token（ADR-0005 D3 已如实记录）。
  同步 GM API 兼容性已由「注入时嵌入存储快照」解决（ADR-0005 D1）。
- 在真实 Rider 2026.2 中启动插件 —— 已完成**构建对准**（编译平台 RD-262.8665.328）与
  **Plugin Verifier 二进制兼容性校验（Compatible）**，但尚未用 `runIde` 实际拉起 IDE
  做界面级冒烟验证。