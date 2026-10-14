# ADR-0012：GM_download / GM_openInTab 与 xhr 的 responseType·onprogress·abort

- 日期：2026-10-13
- 状态：已实现（0.14.0）
- 相关文档：ADR-0005、ADR-0007、ADR-0009

## 本批新增

| API | 说明 |
| --- | --- |
| `GM_xmlhttpRequest` 的 `responseType` | `text` / `json` / `arraybuffer` / `blob`；`document` / `stream` **明确拒绝** |
| `GM_xmlhttpRequest` 的 `onprogress` | 读响应流时投递进度（`readyState = 3`） |
| `GM_xmlhttpRequest` 的 `abort()` | 插件的取消 + 中断 HTTP 请求 |
| `GM_download` / `GM.download` | 后台下载并落盘到系统下载目录 |
| `GM_openInTab` / `GM.openInTab` | 打开新标签页并返回句柄（`close()` / `closed` / `onclose`） |

全部纳入 [GmCapabilities] 的 `@grant` 白名单；下载与 xhr 共用 `@connect` 授权。

## D1：二进制响应为什么走 base64

投递通道是 `CefFrame.executeJavaScript(DELIVER_FUNCTION + "(<json>)")`，只能传字符串。
`arraybuffer` / `blob` 因此以 **base64** 放在 `responseBase64` 字段里，JS 侧用 `atob` 还原成
`Uint8Array`，再按 `responseType` 给 `ArrayBuffer` 或 `Blob`。

代价是 base64 带来约 33% 的传输膨胀，且整包要经一次 JS 字符串。对脚本用途（小图标、字体、数据文件）
可以接受；**大文件请用 `GM_download`**（它边读边落盘，不经过投递通道）。

## D2：进度必须节流

响应流按 16KB 缓冲读取，但**不是每读一块就投递**：累计新增达到 `PROGRESS_STEP_BYTES`（64KB）
才投递一次。原因是投递走 `executeJavaScript`，频率过高会挤占页面主线程与 console 通道
（本项目此前已经因为高频 JCEF 调用吃过一次 IDE 冻结）。

收尾时会补投一次，让 `content-length` 已知的请求能走到 100%。`lengthComputable` 直接由
是否存在 `content-length` 决定。

## D3：abort 的语义

`abort()` 做两件事：置取消标志 + `future.cancel(true)`（打断尚未拿到响应头的请求）。
读取循环每轮检查标志，因此已开始的下载也会尽快停下。

**取消后不再投递任何回调**（既不发 `onload` 也不发 `onerror`）——与 XHR 语义一致。
下载被取消时会把已写了一半的文件**删掉**，避免留下一个看起来完整的坏文件。

## D4：GM_download 的落盘策略

- **位置**：系统下载目录 `~/Downloads`；不存在时尝试创建，创建失败则回可读错误，不静默换地方。
- **文件名清洗**（安全关键）：脚本给的 `details.name` **不可信**。先按 `/` 与 `\\` 取 basename
  （挡掉 `../../evil`），再过滤控制字符与 Windows 非法字符（`<>:"|?*`），最后兜底 `download`。
- **不覆盖已有文件**：重名追加 ` (1)`、` (2)`…。
- **边读边写**：用 `BodyHandlers.ofInputStream()` + `FileOutputStream`，不把整包读进内存。
- 实际落盘路径回传给脚本（`response.path` / `response.name`）并写进 INFO 日志。

## D5：GM_openInTab 的句柄是「异步补全」的

打开标签页本身是同步动作，但**句柄编号要异步回传**：插件要先建出标签页才能给出 id，
所以 JS 侧先返回句柄、再通过投递通道收到 `tabId` 填充。

由此产生一个必须处理的竞态：**脚本可能在 id 到达之前就调 `close()`**。
实现上记 `closeRequested`，等 `tabId` 到达时补发关闭请求，避免"关不掉"。
宿主未装配（`tabId = -1`）时 `close()` 是**安全的空操作**，不会误关别的标签页。

为了让桥不反向依赖 UI，新增了 [GmHost] 接口（`openTab` / `closeTabById`），由
`BrowserContainer` 实现并注册进 `GmBridge`；回投递通道则复用 `GmNetwork.deliverToFrame`，
不另写一份。

**已知限制**：`onclose` 只在**脚本自己调 `close()`** 时触发。用户手动关掉那个标签页**不会**回调
（需要在容器关闭路径上把通知送回原 frame）。这属于后续工作，已在本文档显式记录，避免被当成已实现。

## 验证

Kotlin 侧编译 0 错误 0 告警；JS 运行时用 Node 沙箱跑行为检查（临时脚本，跑完即清理）：

```text
xhr 批：responseType 透传 / onprogress(readyState=3, loaded, total) / ArrayBuffer 内容 / Blob /
        json 解析 / text 默认 / abort 带同一 id / document 抛错 / Promise 上的 abort     20 项
本批：  download 发出与 name·header 透传 / path·name 回传 / Promise 化 / openTab 的
        url·active / 句柄字段 / close() 发 closeTab / onclose / 提前 close 的补发 / 默认 active   23 项
```
