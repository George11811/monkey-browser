# ADR-0002：JCEF 注入时机实测（结论）

- 日期：2026-10-12
- 状态：**已定论**（以第三轮 v3 实测为准；v1/v2 结论作废，原因见下）
- 相关文档：Monkey-Browser-Development-Plan.md 第 4.2、7、9 节
- 实测环境：JetBrains Rider 2026.2（RD-262.8665.328）、JCEF 144.0.15-262-b37

## 修订（2026-10-13）：C1 的「上限是 interactive」被推翻

0.7.0 把注入点前移到「主 frame `onLoadStart`」后，真实站点上**多次实测到
`document.readyState = loading`（仍在解析期）**：

```text
注入「NGA · Codex 外观」-> https://bbs.nga.cn/thread.php?fid=-7
脚本执行完毕 nga-codex.user.js ready=loading href=https://bbs.nga.cn/thread.php?fid=-7
注入「Linux DO · Codex 外观（伪装版）」-> https://linux.do/
脚本执行完毕 linuxdo-codex.user.js ready=loading href=https://linux.do/
```

**为什么 v3 只测到 interactive**：v3 的探针是「每个阶段只注入一次」且只在特定回调序列下触发，
样本极少；而 0.7.0 是在每次主 frame 导航都注入，样本量大得多。真实网络导航下，
CEF 会把 `executeJavaScript` 排进新文档的 JS 上下文，**上下文一建立就跑**——那时通常还在解析期。

**但同一批日志也暴露了该档位的不稳定性**：v2ex 上出现过「为帖子页而发」的注入**实际落进旧文档**
（完成标记的 `href` 是首页），并被按新 URL 记账，导致目标页面永远拿不到脚本（表现为闪原样式）。

**结论修订**：
- C1 应改为「**可达解析期（loading），但不保证**」；
- C2（document-start 不可行）**仍然成立**：本档位再早也只是解析期，无法保证早于页面自身脚本；
- 落点不稳定必须由实现层兜住（见 ADR-0003 的自愈机制）。

## 方法

临时探针（com.ridermonkey.poc.InjectionTimingPoc）在 CEF 各生命周期回调里用
`CefFrame.executeJavaScript` 注入探针脚本；探针用 `console.log` 回传
`document.readyState` 与实际 `location.href`，由 `CefDisplayHandler.onConsoleMessage` 收回，
按到达顺序组成时间线。用 console.log 而非 JBCefJSQuery，是为了区分「注入没生效」与「回传没生效」。

三轮迭代，只有第三轮有效：

| 轮次 | 夹具 | 结论 | 有效性 |
| --- | --- | --- | --- |
| v1 | `loadHTML`（file:///jbcefbrowser 伪 origin） | 「注入可落在解析期」 | ❌ 作废：伪 origin + 跨 about:blank 过渡导航的假象 |
| v2 | 直接 `loadURL` 真实 HTTPS | 「注入全落在旧文档」 | ❌ 作废：工具窗初始 about:blank 仍在加载，钩子被它消耗光，目标导航未被测到 |
| v3 | 先热身到 about:blank 空闲，再发起目标导航，**每个钩子都注入**并标注「为谁而发」 | 见下 | ✅ 有效 |

v3 对每次注入记录「注入时的目标 URL（for=）」与「实际执行时的 href」，因此能判断注入到底落在哪个文档。

## 实测时间线（v3 节选，完整 46 条见 idea.log 的 [RM-POC] 行）

```text
 6. OBS onLoadEnd frame=MAIN status=0 url=about:blank          ← 热身结束
 7. OBS 阶段 2：发起目标导航 https://www.baidu.com/
10. OBS onLoadingStateChange isLoading=true
12. OBS onLoadStart frame=MAIN url=about:blank                ← 已发起导航，URL 仍是旧值
16. OBS onLoadEnd  frame=MAIN status=0 url=about:blank
20. OBS onLoadingStateChange isLoading=true
26. OBS onLoadStart frame=MAIN url=https://www.baidu.com/     ← 新文档提交后 URL 才更新
27. OBS dispatch label=onLoadStart#5 for=https://www.baidu.com/
31. JS inject@onLoadStart#5      ready="interactive" href="https://www.baidu.com/"
32. JS inject@onAddressChange#6  ready="interactive" href="https://www.baidu.com/"
33. OBS onLoadEnd frame=MAIN status=200 url=https://www.baidu.com/
37. JS inject@onLoadEnd#7        ready="complete"    href="https://www.baidu.com/"
38. OBS onLoadingStateChange isLoading=true                    ← pushState 也触发它
40. OBS onAddressChange frame=MAIN url=https://www.baidu.com/rm-poc-spa   ← pushState 被感知
44. JS spa-pushState-done before=https://www.baidu.com/ after=.../rm-poc-spa
```

## 结论

### C1：注入时机的上限是 `interactive`，达不到 `loading`（解析期）

所有命中新文档的注入都拿到 `readyState="interactive"`（DOMContentLoaded 之后），
`onLoadEnd` 注入拿到 `complete`。**没有任何一次落在解析期。**

**设计含义**：首版只承诺 `document-end` / `document-idle`——方案第 4.2 节的保守假设成立。
「解析期注入」这一档**不可得**，不要为它设计接口。

### C2：`document-start` 确定不可行

页面自身脚本的执行早于任何可用注入点。方案第 9 节「不得把网页加载后执行 JS 等同于
document-start」**由实测坐实**；`@run-at document-start` 只能降级实现（并在契约里写明），
不能声称兼容。

### C3：iframe 注入可行

v1 实测：`onLoadStart`/`onLoadEnd` 对子 frame 均触发（`frame=SUB`），
且 `CefFrame.executeJavaScript` 的探针成功回传。逐 frame 注入与 `@noframes` 在技术上可行。

### C4：SPA（history.pushState）可被感知

`onAddressChange` 会为 pushState 后的 URL 触发（含 `/rm-poc-spa` 的 onAddressChange 共 3 次）。
**并且 pushState 还会额外触发一次 `onLoadingStateChange(true)`**——可作为「SPA 路由变化」的
触发信号，用于重新匹配并注入用户脚本。

### C5：导航早期的 `frame.url` 是旧值（重要陷阱）

发起新导航后，早期回调（`onLoadStart`/`onAddressChange`/`onLoadEnd`）报出的 `frame.url`
仍是**上一个文档**的 URL，直到新文档提交才更新。

**设计含义**：**不得用 `onLoadStart` 的 `frame.url` 判断本次导航要去哪**。基于它做
`@match` 匹配会把脚本注入到错误的页面。必须改用「文档提交后」的 URL
（`onLoadEnd` 的 `frame.url`、或 `onAddressChange` 中 URL 发生变化的那一次）。

### C6：`onLoadingStateChange` 不能单独作为「新导航开始」的信号

旧文档加载收尾时也会触发 `true/false` 循环（v3 时间线 8–19 行即为该情况）。
必须结合 URL 变化或其他判据使用。

## 对后续设计的影响

1. **注入架构**：以 `onLoadEnd`（`complete`）为主注入点；`onAddressChange`（`interactive`）
   可作为更早一档，但两者都在 DOMContentLoaded 之后，不承诺更早。
2. **匹配时机**：只在拿到**新文档 URL** 之后做 `@match` 匹配（见 C5）。
3. **SPA**：监听 `onAddressChange` + `onLoadingStateChange(true)`，在 URL 变化时重新匹配注入（见 C4）。
4. **iframe**：按 frame 逐个注入，`@noframes` 用 `frame.isMain` 判定（见 C3）。
5. **`@run-at` 语义**：`document-start` 只能降级到 `document-end` 并明确告知，不得伪装。

## 待办

- ✅ 上述第 1–5 条已落地为 ADR-0003（注入架构），并由 ADR-0003 的修订补充了早档与自愈。
- ✅ com.ridermonkey.poc 探针代码已移除（已移入回收站）。