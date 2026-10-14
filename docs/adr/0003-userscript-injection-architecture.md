# ADR-0003：用户脚本注入架构

- 日期：2026-10-12
- 状态：已采纳（实现待落地）
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

## 待办

- ✅ `ScriptInjector` 已按 D1–D5 落地，去重键与 frame 判定均有单测。
- ✅ GM API 兼容策略已另开 ADR-0005（D7 的悲观结论已被 D1 修正：同步 `GM_getValue` 可实现）。