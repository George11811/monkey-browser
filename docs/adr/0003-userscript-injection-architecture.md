# ADR-0003：用户脚本注入架构

- 日期：2026-10-12
- 状态：已采纳并落地（含 0.13.3 修订；0.17.x 期间的「早档探针」方案已回滚）
- 依据：ADR-0002（JCEF 注入时机实测结论）
- 相关文档：Monkey-Browser-Development-Plan.md 第 4.2、4.3 节

## 背景

ADR-0002 用三轮实测定论：真实导航下注入时机的上限是 `document.readyState = "interactive"`，
达不到 `loading`（解析期），更达不到 `document-start`；且导航早期的 `frame.url` 是旧值。
本 ADR 据此确定注入架构。

## 决策

### D1：注入点分两档，不承诺更早

| 档位 | 触发钩子 | 实测 readyState | 适用 |
| --- | --- | --- | --- |
| 早档 | `CefDisplayHandler.onAddressChange`（URL 变化那一次） | `interactive` | 需要尽早但可接受「晚于页面脚本」的脚本 |
| 主档 | `CefLoadHandler.onLoadEnd`（主帧） | `complete` | 默认；`document-end` / `document-idle` |

**明确不做**：`document-start`。ADR-0002 已证明页面自身脚本永远早于任何可用注入点。

### D2：只在拿到「新文档 URL」之后做匹配

`onLoadStart` / `onAddressChange` 在导航早期报出的 `frame.url` 仍是**上一个文档**的 URL
（ADR-0002 C5）。若据此匹配，脚本会被注入到错误的页面。

因此：**匹配必须以「文档提交后」的 URL 为准**——即 `onLoadEnd` 的 `frame.url`，
或 `onAddressChange` 中 URL 确实发生变化的那些回调。

### D3：SPA 用地址变化重新触发匹配

`history.pushState` 会触发 `onAddressChange`（ADR-0002 C4），且额外触发一次
`onLoadingStateChange(true)`。SPA 路由变化时按新 URL 重新做 `@match` 匹配并注入。

因为同一文档内可能多次触发，**注入必须幂等**（见 D5）。

### D4：逐 frame 注入

按 frame 逐个注入；`@noframes` 用 `frame.isMain` 判定是否跳过子 frame。
ADR-0002 C3 已确认 `CefFrame.executeJavaScript` 对子 frame 可用。

### D5：注入幂等

以 `(frame identifier, 规范化文档 URL, 脚本 ID)` 为键去重，避免 SPA 路由变化或重复回调
导致同一脚本在同一文档里被注入多次。

### D6：`@run-at` 语义映射（必须对用户诚实）

| 脚本声明 | 本实现行为 | 对外说明 |
| --- | --- | --- |
| `document-start` | **降级**到 D1 的早档（`interactive`） | 明确标注「不支持 document-start」，不得声称兼容 |
| `document-body` | 无对应时机，降级到主档 | 同上 |
| `document-end`（默认） | 主档 `onLoadEnd` | 与声明一致 |
| `document-idle` | 主档 `onLoadEnd` | 与声明一致 |

### D7：桥接技术

注入本身用 `CefFrame.executeJavaScript`（已验证）。GM API 的 JS↔Kotlin 通信沿用
方案第 4.3 节的 `JBCefJSQuery`。

**⚠️ 受 D1 影响**：既然拿不到 `document-start`，就无法在页面脚本执行前把 GM 存储快照
预加载进页面。方案里「注入前预加载存储快照以逼近同步语义」的设想**在当前平台上不可行**，
`GM_getValue` 的兼容策略必须在后续 ADR 里重新设计。

## 实现偏差（0.3.0 落地时）

**D1 的「早档」没有按原计划实现。** 原计划在 `onAddressChange` 上注入以拿到 `interactive` 时机，
实现时发现它不安全：ADR-0002 C5 已证明导航早期的 `frame.url` 是**旧文档**的 URL，据此注入
可能把脚本打进旧文档、却按新 URL 记账，于是新文档真正就绪时被去重表挡掉——脚本永远不会执行。

因此 0.3.0 只在**文档已提交**的两个时机注入：

| 时机 | 用途 |
| --- | --- |
| `onLoadEnd`（各 frame） | 主路径，`frame.url` 是本帧已提交的 URL |
| `onLoadingStateChange(false)`（主 frame） | 补注，覆盖 SPA 在同一文档内改变 URL 的情况 |

代价：注入点固定在 `readyState = complete`，比 `document-end`（DOMContentLoaded）略晚。
对绝大多数用户脚本无影响；若后续需要 `interactive`，必须先解决「文档归属判定」问题。

**D5 去重键**：键为 `frame.identifier | 文档 URL | 脚本 id`。带 URL 是为了让同一 frame 导航到
新页面后能重新注入。

## 修订（0.13.3）：取消"加载结束"档，避免 SPA 滚动重复注入

原实现还有第三档：`onLoadingStateChange(false)` 时按当前 URL 补一次注入，用于覆盖 SPA 路由变化。
但去重键里**带文档 URL**，而 SPA 站点（linux.do / v2ex）在**无限滚动**时只改 URL、不换文档：

\`\`\`text
注入「Linux DO」-> https://linux.do/t/topic/3015897
注入「Linux DO」-> https://linux.do/t/topic/3015897/3      ← 只是滚动
注入「Linux DO」-> https://linux.do/t/topic/3015897/4      ← 又整份重跑
\`\`\`

后果：每次滚动都把 250–400KB 的换皮脚本重跑一遍，页面看起来像**刷新/闪一下**（用户实测）。

**决策：取消这一档。** 依据：
- Tampermonkey 只在**文档加载**时执行一次，不会因 `pushState` 重跑——我们的行为比 TM 激进，且不可见地消耗巨大；
- 真实导航仍由**早档（主 frame `onLoadStart`）+ 主档（`onLoadEnd`）**覆盖，不会漏注入；
- 落点不确定的情况仍由完成标记的来源比对自愈。

代价：纯前端路由变化的页面不会重新执行脚本——这与 TM 一致，属预期行为而非缺陷。

## 修订：早档探针方案 —— **已尝试并回滚**（发生在未发布的 0.17.0，未随任何版本发布）

> **结论：本方案已回滚**，`ScriptInjector` 恢复原实现（`lastMainUrl` 比较 + `onLoadStart` 直接注入）。
> 下面保留完整记录，供以后避免重走。回滚原因见本节末尾。

### 问题

0.13.3 之后早档的开关是「`onLoadStart` 的 `frame.url` 是否等于上一次记录的 URL」：

```kotlin
if (url == lastMainUrl[frameId]) registry.releaseUrl(...)   // 不提前注入
```

而 ADR-0002 C5 已实测：导航早期的 `frame.url` 是**上一个文档**的 URL。拿一个已知不可靠的值
判断「是否刷新」，会把**普通导航误判成刷新**，于是丢掉早档注入、只能等 `onLoadEnd`
（`readyState = complete`）——那时页面已按原样式画完一帧。

**实测代价**（2026-10-14，Rider 上逛 v2ex，58 次注入）：

| 脚本执行时的 `readyState` | 次数 | 用户可见后果 |
| --- | --- | --- |
| `loading` | 18 | 基本不闪 |
| `interactive` | 3 | 基本不闪 |
| **`complete`** | **37（64%）** | **必然闪原样式** |

规律稳定：带 `#fragment` 的帖子 URL 全部 `loading`，`?tab=all` 全部 `complete`。

### 决策

1. **刷新判定改用 `transitionType == TT_RELOAD`**（CEF 专为此提供的信号），不再比较 URL。
2. **早档只投「探针」，不投脚本正文。** 探针经 `executeJavaScript` 被 CEF 排进**新文档**的
   JS 上下文，回报真实的 `location.href`；Kotlin 用现有 matcher 对**真实 URL** 匹配后再投正文。

第 2 条正是本文档「实现偏差」一节留下的前置条件——**「若后续需要 `interactive`，必须先解决
『文档归属判定』问题」**。归属判定现在由「代码自己报告落在了哪个文档」闭合，而不是靠 Kotlin
在导航早期做不可靠的 URL 猜测。

**为什么不用「导出等价 JS 正则」**：`wildcardRegex` / `globRegex` 用的是 Kotlin 的
`Regex.escape`，产物是 `\Q...\E`，**JS 不支持**；要做到等价还得把 scheme 默认端口、
`*.domain` 含自身、`@exclude` 否决等语义全部用正则重写一遍——那是重复实现，必然漂移。
探针方案零重复：判定仍然只由 [UserscriptMatcher] 一处负责。

### 安全

探针回报走与 GM 通道同一条 console 链路，并用**不可伪造的 source 前缀**校验来源（ADR-0007）：
伪 scriptId 为 `__rm_probe__`，页面自己 `console.log` 的 source 套不上该前缀，
因此无法伪造 `location.href` 骗取注入。

### 兜底

`onLoadEnd`（主档）保持原样。探针没回来、frame 已失效、或脚本库为空时主档照常注入；
两档共用去重表且键里带**真实文档 URL**，所以同一文档不会被跑两次。

### 踩坑记录：不能在 console 回调里同步注入

首版实现把探针回报写成「在 `onConsoleMessage` 回调里直接 `frame.executeJavaScript` 投正文」，
**结果脚本一行都不执行**：

- 调用不抛异常，`注入「…」` 日志照常打印；**完成标记永远不回来**，也没有任何异常日志
  （`脚本抛异常` / `忽略来源可疑的完成标记` 均为 0）；
- 去重键在 `injectInto` 里已经标记，主档 `onLoadEnd` 因此被挡掉 ——
  **脚本彻底不生效，而且没有任何兜底**。

**判据**（可直接从日志看出来）：同一批日志里 `注入` 出现 107 次而 `脚本执行完毕` 只有 96 次，
缺的 11 次**全部紧跟在 `主 frame 开始导航` 之后几毫秒内** —— 即全部来自这条探针路径。

**修正**：探针回报后必须
`ApplicationManager.getApplication().invokeLater { injectInto(frame, actualUrl) }`
投递到下一轮事件循环再注入，**不得从这个回调里同步注入**。

**教训**：CEF 的 load handler 回调里注入是可行的（`onLoadStart` / `onLoadEnd` 长期如此），
但 **console/display 回调里同步注入不行**。今后新增注入点，必须先在日志里确认
「注入」之后跟着「脚本执行完毕」，否则注入等于没发生。

### 为什么回滚（两处实测致命问题）

**问题 1：探针执行在【旧文档】里，而不是推理中的「新文档」。**

ADR-0002 修订中「`executeJavaScript` 会被 CEF 排进新文档上下文」的推断，在**这个调用点不成立**。
日志直接给出反证：

```text
16:00:14,906 探针落地 -> about:blank          ← 面板的初始文档
15:59:58,329 探针落地 -> https://linux.do/
15:59:58,333 注入「Linux DO · Codex 外观（伪装版）」-> https://linux.do/
              （没有 脚本执行完毕）
```

后果：拿**旧 URL** 匹配 → 把正文投进**即将销毁的旧文档** → 用户看不到任何效果；同时去重键记的是
旧 URL，**同 URL 导航时主档 `onLoadEnd` 会被同一个键挡掉**，真文档永远拿不到脚本。
这就是「脚本完全不生效」的直接原因。

**问题 2：不能在 console 回调里同步注入。**

首版把 `frame.executeJavaScript` 写在 `onConsoleMessage` 回调内部，**脚本一行都不执行**：
调用不抛异常、`注入「…」` 日志照打，但完成标记永不回来，也没有任何异常日志。判据是同一批日志里
`注入` 107 次而 `脚本执行完毕` 只有 96 次，缺的 11 次全部紧跟 `主 frame 开始导航` 之后几毫秒内。
改为 `invokeLater` 投递后计数恢复配对（`探针落地` 11 次），但问题 1 仍在，故整体回滚。

### 教训

1. **CEF 的 load handler 回调里注入可行**（`onLoadStart` / `onLoadEnd` 长期如此）；
   **console/display 回调里同步注入不行**。新增注入点必须先在日志里确认「注入」后面跟着
   「脚本执行完毕」，否则注入等于没发生。
2. **不要用「先标记去重键、再注入」去赌注入一定成功**——注入被静默丢弃时，那个键会挡住
   唯一的兜底路径，脚本彻底失效且**没有任何日志**。这是本次最贵的教训。
3. **所有「注入会落在新文档」的假设，都必须先用日志证伪一次**（ADR-0002 那条推断在本处就是错的）。
4. **未解决**：早档仍有约 64% 退到 `complete`，即「闪原样式」。要动它必须先有**在 `loading` 期
   就能可靠拿到新文档 URL** 的手段——目前没有。`transitionType` 已加进 `onLoadStart` 的 DEBUG
   日志，留给下次排查用。

## 修订：修掉「无文档的 onLoadEnd」重复注入（闪原样式）

2026-10-14 用 DEBUG 日志实测定位（Rider、v2ex、5 次主 frame 导航）：

| 类型 | 次数 | onLoadEnd status | start→end | 注入时 readyState |
| --- | --- | --- | --- | --- |
| 真实导航 | 3 | 200 | 780–829 ms | `loading` |
| **导航到当前同一 URL** | **2** | **0** | **0 ms** | **`complete`** |

第二类是「导航到当前同一个 URL」：CEF 会**成对**触发 `onLoadStart` / `onLoadEnd`，两者落在
**同一毫秒**且 `httpStatusCode = 0`（没有任何网络文档）。

此时 `frame.url == lastMainUrl`，早档走 release 分支——**这是对的**（不注入即将销毁的文档）；
但紧接着的 `onLoadEnd` 又调 `injectInto` —— 于是 **248 KB 的换皮脚本在已经渲染完的文档里
又跑了一遍**，页面当场重排，就是用户反复报告的「闪原有样式」。

**修正**：主 frame 的 `onLoadEnd` 在 `httpStatusCode == 0` 且**不是 `file:`** 时**不注入**
（本地文档的 status 同样是 0，但它是真实文档）。

**为什么原生浏览器没这个问题**：Tampermonkey 以**文档创建**为触发点，这种不产生新文档的成对回调
根本不会让它重跑脚本；我们以 CEF 的 load 事件为触发点，就把它误当成了新文档。

## 待办

- ✅ `ScriptInjector` 已按 D1–D5 落地，去重键与 frame 判定均有单测。
- ✅ GM API 兼容策略已另开 ADR-0005（D7 的悲观结论已被 D1 修正：同步 `GM_getValue` 可实现）。