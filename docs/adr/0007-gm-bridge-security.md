# ADR-0007：GM Bridge 安全模型（来源指纹）

- 日期：2026-10-13
- 状态：**已实测确认并收紧为硬校验**（0.9.1）
- 相关文档：方案 §7 第 3 项、§4.3；ADR-0005

## 背景

方案 §7 把「GM Bridge 安全 PoC：阻止普通页面越权调用」列为三项必做验证之一。
ADR-0005 记录了当时的实现：console 通道 + 一次性 token，并如实承认它**不是沙箱**——
页面若提前 hook \`console.log\`，可以截获消息、窃取 token，进而伪造 GM 写入。

## 决策

### D1：**isolated world 在本技术路线上不可用**（已核实，非取舍）

最初设想的正解是「把用户脚本放进独立 JS 上下文」——这样页面根本看不到脚本、也 hook 不到。
为此我反编译核对了平台的 JCEF 绑定（\`intellij.libraries.jcef.jar\` 里的 \`org.cef.browser.CefFrame\`）：

\`\`\`text
getURL()Ljava/lang/String;
executeJavaScript(Ljava/lang/String;Ljava/lang/String;I)V
\`\`\`

**只有这一个执行入口，没有任何 World / V8Context / IsolatedWorld 相关 API。**
JCEF（Java 绑定）没有暴露 CEF 的 \`ExecuteJavaScriptInIsolatedWorld\`。

结论：**沙箱做不了**，这是平台硬限制。任何「用独立上下文隔离脚本」的方案在本项目都不成立。

### D2：改用 **console 消息的 \`source\`** 做不可伪造的身份指纹

\`CefDisplayHandler.onConsoleMessage\` 的签名里有一个 \`source\` 参数：

\`\`\`text
onConsoleMessage(CefBrowser; CefSettings$LogSeverity; String message; String source; int line)Z
\`\`\`

它与 \`CefFrame.executeJavaScript(code, scriptUrl, line)\` 的 **scriptUrl** 对应：CEF 把该 URL 作为
「执行这段代码的脚本地址」回填到 console 消息里。于是：

- 注入时把 scriptUrl 设为专属来源：
  \`https://rider-monkey.invalid/userscript/<scriptId>/<frameId>/<token>\`
  （\`.invalid\` 是 RFC 2606 保留域名，保证不存在，避免与真实站点混淆）
- 校验时比对 \`source\` 是否等于该 URL。

**为什么这能挡住重放**：页面 hook \`console.log\` 后确实能读到我们的消息文本（含 token），
但它自己调用 \`console.log\` 时，**source 是页面自己的脚本地址**——这是由引擎填充的元数据，
页面无法伪造。所以重放的消息会在 source 上暴露。

### D3：分两步落地——先观测，后拦截

「平台是否真的这样回填 source」**必须实测**，不能凭签名推断。如果猜错，收紧后会**误伤全部 GM 写入**
（正是我们要避免的回归）。因此：

| 步骤 | 行为 |
| --- | --- |
| 0.9.0 | 注入带专属 scriptUrl；校验**只记录结果**、仍放行 |
| **0.9.1（现行）** | **硬校验**：source 与期望不符即拒绝该次调用并记日志 |

**实测证据**（0.9.0 在真实 Rider 中的日志）：

\`\`\`text
[RM-GM] 来源校验通过：source=https://rider-monkey.invalid/userscript/hello.user.js/6-C5CFF8B9AB84089406C2B040DB6EDD7E/6a11c64c-da47-48da-a7ae-724001627cf9
\`\`\`

来源校验通过 1 次、来源不符 0 次，且 GM 计数器写入正常 —— 平台确实按 scriptUrl 回填 source，
机制成立，遂收紧。

## 影响与限制（必须说清）

- \`@grant none\` 的脚本**本来就不注入任何 GM API**，因此没有可攻击面；Tampermonkey 对这类脚本
  同样是直接注入页面上下文。
- 需要保护的是声明了 GM 能力的脚本。它们的写入能力**被限制在 GM 存储**，无法触达 Kotlin 的其它能力
  （没有通用「调用任意后端」的通道）。
- **仍然不是沙箱**：页面可以读到我们发送的 GM 数据（hook console.log），也可以**拒绝服务**
  （让我们的写入静默失败）；但无法伪造写入。
- 页面若 hook 了 \`console.log\` 并转发我们的消息，合法写入也可能会因 source 变化被拒——这是
  **fail-closed**，方向安全。

### D4：完成标记同样校验来源

注入完成标记会**改动去重表状态**（撤销或占用去重键），属状态变更，因此也做来源校验：
标记里没有 token（无法比对完整 URL），改比对不可伪造的**来源前缀**
（\`GmBridge.isTrustedSource\`）。否则页面可以伪造标记，把去重键撤销（导致重复注入）
或提前占位（导致目标页面拿不到脚本）。

## 待办

- 按 \`@grant\` 裁剪能力（ADR-0005 待办）。
- 同步 GM API 的其余部分（\`GM_xmlhttpRequest\` 等，属 P2）。