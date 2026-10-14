# ADR-0005：GM API 语义、桥接通道与安全模型

- 日期：2026-10-12
- 状态：已采纳（0.5.0 实现）
- 相关文档：Monkey-Browser-Development-Plan.md 第 4.3 节；ADR-0003

## 背景

方案第 4.3 节提出两个硬约束：
1. 用 `JBCefJSQuery` 做 JS↔Kotlin 桥；
2. 「注入前预加载存储快照」以逼近同步 `GM_getValue` 语义，且**不得用异步 Promise 冒充同步 API**。

ADR-0003 D7 当时判断第 2 条在当前平台不可行（拿不到 `document-start`）。本 ADR 修正该判断，
并记录通道选择与安全模型。

## 决策

### D1：同步 `GM_getValue` **可以**实现——用注入时嵌入的存储快照

ADR-0003 D7 的推理有误。当时认为「必须在页面脚本执行前预加载快照」，但**脚本本身就由我们注入**，
我们只需要在**注入那一刻**把快照嵌进注入的代码里即可：

```js
var __rmStore = {"key": <原始 JSON 值>, ...};   // 注入时由 Kotlin 从磁盘读出并嵌入
function GM_getValue(key, defaultValue) {
  if (Object.prototype.hasOwnProperty.call(__rmStore, key)) {
    return JSON.parse(__rmStore[key]);           // 同步返回
  }
  return defaultValue;
}
```

这与 Tampermonkey 的语义一致：TM 的同步 `GM_getValue` 返回的也是**脚本启动时刻**的值。
我们唯一的差异是脚本启动时刻更晚（ADR-0002：最快只能到 `readyState = complete`）。

**写入**为「先改内存快照、再异步回传落盘」：`GM_setValue` 之后本页后续读取立即可见，
落盘是异步的。若页面在落盘前被关闭，最后一次写入可能丢失——这一点在 KDoc 中如实标注。

### D2：桥接走 console 通道，而不是 `JBCefJSQuery`（**偏离方案 §4.3**）

- 方案原文要求用 `JBCefJSQuery`。
- 实现时发现：`JBCefJSQuery.inject()` 生成的 JS 模板无法静态验证（字节码里是拼接的），
  而**失败方式会是静默的**（它自身用 try/catch 包住，调用不成功也不报错），
  意味着可能白烧一轮用户实测。
- 而 `console.log` → `CefDisplayHandler.onConsoleMessage` 这条通道**在本项目已被实测验证**：
  PoC 的时间线回传、注入器的执行结果回传都走它，稳定可靠。

**安全性上两者等价**，因此这个替换不降低安全性：
- `JBCefJSQuery` 注入的函数挂在 `window` 上，页面脚本同样可以调用、hook、窃取参数；
- 两者都不是沙箱。真正的隔离需要 CEF 的 isolated world / 独立 JS 上下文，属后续工作。

替换成本很低（协议封装在 [GmBridge] 与资源 js/gm-api.js 的 `__rmSend` 里），
若后续验证 `JBCefJSQuery` 可用，只需改这一处。

### D3：安全模型——一次性 token，但**不是沙箱**

每次注入为每个脚本生成一个随机 token，登记 `scriptId -> token`；收到的 GM 消息必须携带
匹配的 `scriptId + token`，否则拒绝并记日志（`[RM-GM] 拒绝未授权调用`）。

**必须说清的局限**：页面脚本若在注入之前 hook 了 `console.log`，可以截获我们发出的消息并
窃取 token，进而伪造写入。因此当前防护能挡住「无意的串扰」与「随手伪造」，
**挡不住有针对性的恶意页面**。彻底解决需要沙箱（方案 P1「脚本权限」）。

### D4：存储布局——一键一文件

`<config>/rider-monkey/storage/<scriptId>/<URL 编码后的 key>`，文件内容是**原始 JSON 值**。

- 避免自己写 JSON 解析器（值可以是任意嵌套 JSON）；
- 单键写入是独立文件替换，一次写坏不会丢掉整个脚本的存储；
- key 用 URL 编码落到文件名，任意 UTF-8 键都能存取；scriptId 再做白名单过滤防路径穿越。

### D5：`@grant` 的处理（本版从宽）

- `@grant none` → 不注入 GM 运行时（脚本在页面上下文运行，无 GM API），与 TM 一致。
- 其余情况一律注入我们已实现的 API，**暂不按 grant 列表做能力裁剪**。
  按声明授权属于「脚本权限」工作（方案 P1），届时再收紧并补 ADR。

### D6：本版实现的 API 范围

| API | 说明 |
| --- | --- |
| `GM_addStyle(css)` | 注入 style 元素，返回该元素 |
| `GM_getValue(key, default)` | 同步读；快照取不到时返回 default |
| `GM_setValue(key, value)` | 立即改内存快照 + 异步落盘；value 可为任意可 JSON 序列化的值 |
| `GM_deleteValue(key)` | 删除键并异步落盘 |
| `GM_listValues()` | 返回当前快照里的键数组 |
| `GM.getValue/ setValue/ deleteValue/ listValues/ addStyle` | 上述的 Promise 风格别名（Tampermonkey `GM.*` 命名） |

**未实现**：`GM_registerMenuCommand` / `GM_unregisterMenuCommand`（需要脚本菜单入口）。
其余批次均已完成：网络见 ADR-0009，资源与通知见 ADR-0010，下载与 `GM_openInTab` 见 ADR-0012。

**已知差异**：GM 函数是注入 IIFE 里的**闭包局部变量**（与 TM 的 `@grant` 沙箱模型一致），
不是 `window` 上的全局；依赖 `window.GM_getValue` 的脚本不适用。

## 待办

- 沙箱 / isolated world：解决 token 可被页面窃取的根因。
- 按 `@grant` 裁剪能力。
- `GM_xmlhttpRequest` 与按 `@connect` 的跨域授权。
