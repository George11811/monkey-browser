# ADR-0009：GM 网络 API 与 `@connect` 授权

- 日期：2026-10-13
- 状态：已实现（0.12.0）
- 相关文档：方案 §4.3、§8；ADR-0005、ADR-0007

## 背景

方案 §4.3 把 `GM_xmlhttpRequest` 列为 V0.2 之后的能力；§8 明确要求
「按 `@connect` 等元数据授权，敏感操作显式确认」。

难点在于**通道是单向的**：GM 写操作只靠 console 回传（页面 → Kotlin），
而网络请求需要把**响应送回给脚本**（Kotlin → 页面）。

## 决策

### D1：请求走既有 console 通道，响应用 `executeJavaScript` 投递

不引入新的桥接 API（既有的 `JBCefJSQuery` 可行性仍未验证，见 ADR-0005 D2）：

```text
脚本  --console(来源指纹已校验)-->  Kotlin  --java.net.http.HttpClient-->  目标主机
脚本  <--executeJavaScript(window.__rmDeliver(id, json))--  Kotlin
```

请求方向**天然继承 ADR-0007 的来源指纹校验**：伪造的 `op=xhr` 消息会在 source 上暴露而被拒。
响应投递用 `executeJavaScript` 把一段**字符串字面量**传进页面（不是拼接代码），因此不存在注入风险。

### D2：跨域授权取**最小权限**——没声明 `@connect` 就一个外部主机都不许访问

[ConnectRules] 支持：`example.com`（精确）、`*.example.com`（含域自身，与
`UserscriptMatcher.hostMatches` 同口径）、`*`（显式放弃限制）、`self`（当前文档主机）。
拒绝时不发请求，直接以 `onerror` 回一个说明性错误，并在日志留 `[RM-XHR] 拒绝未授权请求`。

### D3：能力同样受 `@grant` 裁剪

`GM_xmlhttpRequest` / `GM.xmlHttpRequest` 已加入 [GmCapabilities] 白名单：
没声明就拿不到这两个函数（与 GM 存储同一套最小权限模型）。

### D4：多脚本共存的投递分发

`window.__rmDeliver` 只能有一个，而每个注入实例有自己的挂起请求表。
因此页面全局放的是**分发器**（`window.__rmDeliverHandlers` 数组），每个注入实例注册自己的处理器，
处理器返回 `true` 表示「这个 id 归我」，避免后注入的脚本覆盖先注入的脚本（否则先者的响应永远收不到）。

## 已知限制（必须说清）

- **投递入口必须挂在 `window` 上**，因此页面可以：覆盖 `__rmDeliver`（让响应收不到，拒绝服务）、
  或伪造响应喂给脚本。它**无法**伪造跨域请求本身（请求方向仍受来源指纹约束）。
  根因依旧是「JCEF 没有 isolated world」（ADR-0007 D1）。
- `responseType`（text / json / arraybuffer / blob）、`onprogress`、`abort()` **已在 0.14.0 补齐**
  （见 ADR-0012）；`onreadystatechange` 仍未实现——本实现只在结束时回调一次。
- 请求走后端（Kotlin）而非浏览器，因此**不带页面 Cookie**；需要登录态的场景要脚本自己带凭据。

## 待办

- `onreadystatechange` 回调（`responseType` / `onprogress` / `abort()` 与 `GM_download` 已在 0.14.0 完成，
  见 ADR-0012）。
- 可选：把被拒的授权请求做成一次「询问用户」的交互（方案 §8 的「敏感操作显式确认」）。
