# ADR-0017：页面弹窗接管（新标签页打开，兜底系统浏览器）

- 日期：2026-10-15
- 状态：已实现（0.17.7）
- 相关文档：ADR-0002（JCEF 注入时机）、ADR-0012（GM_openInTab）、ADR-0015（系统浏览器逃生通道）

## 现象与病因

点页面里的 `target="_blank"` 链接、或页面调 `window.open()` 时，会冒出一个**不知名的空白窗口**。

全仓 grep：`onBeforePopup` / `CefLifeSpanHandler` **0 命中** —— 插件从未注册过弹窗生命周期处理器，
于是 CEF 走默认弹窗路径。那个窗口不在插件的标签页模型里（不是 `BrowserContainer` 的 tab），
也没有我们注册的 `CefLoadHandler` 与注入链路，因此既不受控、也没有内容。

## 决策

`CefLifeSpanHandler.onBeforePopup` 里 return `true`（取消 CEF 建弹窗），改由插件自己开标签页；
宿主未装配时退到系统浏览器（复用 ADR-0015 已有的 `BrowserUtil.browse` 通道）。

分流规则抽成纯函数 `PopupRouting.decide`，可单测：

| 目标地址 | 处理 |
| --- | --- |
| 空串 / 任何 `about:` | **不拦截**（没有真实地址，见 D1） |
| `http` / `https` | 插件新标签页 |
| `mailto:` / `tel:` | 系统浏览器 |
| 其余一切（`data:` / `blob:` / `file:` / `javascript:` / 自定义 scheme / 不认识的协议） | **不拦截**（见 D2） |

## D1：为什么「没有真实地址」不拦截

本构建（Rider 262 的 JCEF）里 `onBeforePopup` 只有四个参数：

```java
boolean onBeforePopup(CefBrowser browser, CefFrame frame, String targetUrl, String targetFrameName);
```

上游 JCEF 的十几个参数（`popupFeatures` / `client` / `windowInfo` / `userGesture`）在这个 fork 里**没有**，
所以**拿不到弹窗浏览器句柄**，无法把「新建的窗口」还给页面的 `window.open()` 返回值。

而空目标的弹窗典型用法恰恰是 `w = window.open(); w.document.write(...)`（OAuth 中转、在线编辑器）。
拦截它 = 把 `w` 变成 null = 弄坏对方的登录/编辑流程，而收益是零（我们开出来的空白页更没用）。
两害相权：**放行**。

判定写作「任何 `about:`」而不是「等于 `about:blank`」：`about:srcdoc` / `about:blank#x` 这类同样是
**没有真实地址**，甩给系统浏览器只会让用户点出一个「打开失败」（评审指出的缺口，已按此收口）。

## D2：为什么只把 `mailto:` / `tel:` 交给系统

`mailto:` / `tel:` 是公认由系统处理、且参数**不可执行**的协议（打开邮件客户端 / 拨号器），
交给 `BrowserUtil.browse` 安全。

其余协议刻意**不**交给系统，这是安全边界而不是遗漏：弹窗目标是**不可信页面可控**的字符串，
把它丢给操作系统等于让页面去调系统协议处理器 —— 最典型的坏例子是 `file:`，交给系统就是让
默认关联程序去打开页面指定的本地路径（`.exe` 之类甚至会直接执行）。浏览器自己遇到
自定义协议会先弹确认框，我们静默调用就等于替用户点了「允许」。
`data:` / `blob:` 交给系统浏览器也必定失败，同样不值得做。

## D3：必须切回 EDT，且复用既有 `onEdt`

`onBeforePopup` 在 **CEF 线程**回调，而开标签页动的是 Swing（`JBTabs` / `TabInfo` / `refreshContent`）。
两条分支都走 `BrowserPanel.onEdt`，而不是裸 `invokeLater`：它带 `disposed` 守卫与
`ModalityState.any()`（模态对话框不会把这次开标签页排到后面），与文件里其余 CEF→EDT 跳转保持一致。
CEF 线程上只做纯函数判定 + 一行日志。

`GM_openInTab` 存在同类问题（`GmBridge` 在 CEF 线程直接调 `GmHost.openTab`）。本批**刻意没有**一并修：
它会牵动 GmBridge 的同步契约与那 56 条 GM 断言，而当前脚本库全部是 `@grant none`，实际收益为零。
留作独立批次，避免把一次行为修复和一次时序重构搅在一起。

## D4：只覆盖 onBeforePopup

JCEF 对生命周期回调是聚合语义（同一个 `CefClient` 上可以挂多个 handler）。
`onAfterCreated` / `afterParentChanged` / `doClose` / `onBeforeClose` 一律保持适配器默认实现，
避免干扰 IntelliJ 自己的 JCEF 生命周期处理。

## D5：为什么不用 GmHost.openTab

`GmHost.openTab` 会分配 GM 句柄号，而脚本侧可以用任意 `tabId` 调 `closeTabById`。
给弹窗标签页发 GM 句柄，等于把它暴露到脚本的关闭路径上。因此新增了一个独立回调
`openUrlInNewTab`（由 `BrowserContainer` 实现为 `newTab(url, source = "popup")`），与 GM 通道分开。
弹窗标签页的 `gmId` 保持 0，脚本无法用句柄关掉它。

## 验证

- `PopupRoutingTest`（5 项）：空目标 / 任何 `about:` 变体 / http·https / `mailto:`·`tel:` /
  `file:`·`data:`·`blob:`·`javascript:`·自定义协议一律不交给系统 / `httpx:`、`https-ftp:` 防误判。
- 手工验证：点 `target="_blank"` 链接 → 在插件内新标签页打开；
  日志 `[RM-BROWSER] 弹窗请求 … 决策=OpenTab` 与 `[RM-BROWSER] 弹窗已开成标签页 -> …`。

## 已知限制

- `window.open()` 的返回值语义无法还原（见 D1）：依赖它的流程要么被放行（空目标），
  要么在拦截后拿不到句柄（有真实地址的 `window.open`）。需要完整弹窗语义的站点仍应走
  「在系统浏览器中打开」（ADR-0015）。
- **命名窗口**（`target="someName"`）也按新标签页打开，不再有「同一个命名窗口复用」的语义。
- **没有节流**：页面用 `for(;;) window.open(u)` 就能不停开标签页（每个标签页都带一次
  `refreshContent` + `persistState`）。改动前那种页面的表现是「不停冒 CEF 弹窗窗口」，
  所以这不是本批引入的退步，但要如实记录；真要防，应在 `PopupHandler` 里加面板级最小间隔。
- 自定义协议（`slack://` 之类）不会拉起对应客户端 —— 见 D2 的安全取舍。