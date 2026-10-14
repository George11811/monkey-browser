# ADR-0010：补齐 GM API、资源缓存与运行时验证

- 日期：2026-10-13
- 状态：已实现（0.13.0）
- 相关文档：方案 §4.3、§8；ADR-0005、ADR-0007、ADR-0009

## 本批新增

| API | 说明 |
| --- | --- |
| `GM_info` | 脚本自省对象（name / namespace / version / description / matches / grants / runAt + scriptHandler / version） |
| `@resource` + `GM_getResourceText` / `GM_getResourceURL` | 脚本声明的外部资源；URL 形式给出 `data:...;base64,...` |
| `GM_setClipboard` | 写系统剪贴板（`CopyPasteManager`） |
| `GM_notification` | 平台通知（`Notifications.Bus`），不支持 `onclick` 回调 |

现代别名同步提供：`GM.getResourceText` / `GM.getResourceUrl` / `GM.setClipboard` / `GM.notification`。
全部纳入 [GmCapabilities] 的 `@grant` 白名单。

## D1：`@resource` 采「后台抓取 + 本地缓存」，注入时只读缓存

`GM_getResourceText` 必须**同步**返回，因此资源要在注入前就绪。但注入发生在 CEF 回调线程上，
在那里做网络请求会**卡住导航**。取舍：

1. 注入时只读本地缓存（`<base>/resources/<scriptId>/<name>`），有就嵌入，没有就先不嵌；
2. 缺失的排到后台抓取并落盘，**下一次注入**即可用。

代价：脚本**首次**运行的页面上拿不到资源（`GM_getResourceText` 返回 `null`，与 TM 对缺失资源的行为一致），
刷新一次即可。选择牺牲首次可用性、保住导航延迟。

另外只嵌入**脚本被授权读取的那部分**资源：一个只 `@grant GM_addStyle` 的脚本不会被塞进几百 KB base64。

## D2：修掉注入路径的重复读盘（性能）

`injectInto` 每次都调 `scriptsProvider()`，而它原来是 `store.load()` —— **每次注入都把整个脚本库
从磁盘重读并重新解析**，且每个 frame 各一次。脚本库有几 MB 时这是纯浪费。

改为 `loadCached()`：2 秒 TTL 的缓存，既覆盖「一次导航内多次询问」的突发，又不会让脚本编辑长时间不生效；
管理界面的「刷新」会显式 `invalidate()`。

## D3：`GM_info.version` 用「常量 + 构建期校验」而不是读插件描述符

读 `PluginManagerCore` 是**内部 API**，Plugin Verifier 会判为 `INTERNAL_API_USAGES` 并**直接让构建失败**
（本轮实测到了）。改为在 [GmInfo] 里写常量，并加 `checkHandlerVersion` 任务拿它与
`build.gradle.kts` 的 `version` 比对，不一致即构建失败——不靠人记得同步。

## D4：**用 Node 直接跑 GM 运行时**做功能验证（本轮最有价值的做法）

`gm-api.js` 是字符串模板，一个语法错误会让**全部 GM API 静默失效**，而这类错误在 IDE 里
要等用户实测才暴露。现在可以离线验证：

1. 按占位符填充模板（**用全量替换**，注意 JS 的 `String.replace(字符串, ...)` 只替换第一处，
   而 Kotlin 的 `String.replace` 替换全部——两者语义不同，本轮正是被这一点绊了一下）；
2. 放进 `node:vm`，用桩 `window` / `document` / `console` 运行；
3. 断言存储读写、`GM_info`、资源、能力裁剪、XHR 请求编码与响应投递、剪贴板/通知回传。

本轮 27 项检查全部通过（其中 2 条最初"失败"经查是**测试自己检查太早**：Promise 在微任务里 resolve）。

### 后续（0.16.1）：这套检查已固化进仓库

最初这 27 项是**临时脚本、跑完就删** —— 等于没有回归保护。现已固化为
[tools/check-gm-runtime.mjs](../../tools/check-gm-runtime.mjs)，并接到 Gradle 的 `check` 上：

- 任务名 `checkGmRuntime`；**`check` 会自动带上它**（所以用 `buildPlugin check ...` 即可）。
- 断言扩到 **56 项**：`@grant` 裁剪、回传协议必带字段、`GM_xmlhttpRequest`（含
  `responseType` / `onprogress` / `abort`）、`GM_download`、`GM_openInTab` 的句柄语义。
- **找不到 `node` 时明确失败，而不是静默跳过** —— 静默跳过等于没有校验。确实没有 node 的
  机器用 `-PskipGmRuntimeCheck=true` 显式跳过。

顺带记录一个坑：校验脚本最初把 `scriptId` 那条断言写挂了，原因是没剥掉协议前缀
（第一段是 `__RM_GM__scriptId=...` 而不是 `scriptId=...`）。**校验脚本自己也会有 bug**，
所以它必须和被测代码一样被 review —— 这次是它先「红」了，正好说明断言确实有效。

## 待办（尚未实现）

- `GM_registerMenuCommand` / `GM_unregisterMenuCommand`（需要脚本菜单入口）
- `GM_xmlhttpRequest` 的 `onreadystatechange`

本节原有清单的后续结果（保留以便对照，避免读者以为是漏做）：

| 原列项 | 结果 |
| --- | --- |
| `GM_openInTab` | 0.14.0 完成，见 ADR-0012 |
| `GM_download` | 0.14.0 完成，见 ADR-0012 |
| `GM_xmlhttpRequest` 的 `responseType` / `onprogress` / `abort()` | 0.14.0 完成，见 ADR-0012 |
| DevTools 入口 | 评估后**撤回**（JetBrains 的 JCEF 未打包 DevTools 前端），见 ADR-0011 |
