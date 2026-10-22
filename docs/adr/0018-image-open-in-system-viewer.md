# ADR-0018：图片右键菜单「在系统看图软件打开」

- 日期：2026-10-21
- 状态：已实现（0.17.9）
- 相关文档：ADR-0017（菜单安全边界）、ADR-0015（系统浏览器逃生通道）

## 需求

在页面图片上点右键时，CEF 原生菜单只有「在新标签页打开图片 / 复制图片地址 / 另存为图片」。
内嵌浏览器的图片预览受内核缩放限制，想看原始尺寸、放大细节或对比像素时，用户只能另存再自己去找。
需要补一个入口：**把这张图片交给系统默认看图软件打开**。

## 决策

注册 `CefContextMenuHandlerAdapter`：

- `onBeforeContextMenu`：上下文落在图片上、且图片地址是 http(s) 时，往现有菜单**追加**
  分隔线 + 「在系统看图软件打开」（命令号 27000）。
- `onContextMenuCommand`：命中 27000 时，后台线程 HTTP 取字节 → 落系统临时文件 →
  `java.awt.Desktop.open` 交给系统默认程序。

判定与格式识别抽成纯函数 `ImageContextAction`（可单测）：
`pickSourceUrl` / `extensionFor` / `filePrefixFor`。

## D1：为什么是「追加」菜单项，而不是重建菜单

`JBCefClient` 对 context menu handler 是 **handleAll** 语义（逐个调用、共享同一个
`CefMenuModel`），而 IntelliJ 自己的 `DefaultCefContextMenuHandler` 在 `JBCefBrowser`
构造时就已注册、先于我们执行。它只做两件事：`noContextMenu` 属性为真时 clear，以及
可选地加一项 Open DevTools（28500）。

因此：

- 我们**绝不能 clear**，否则会把 IntelliJ/Chromium 的复制、粘贴、后退、检查等原生项抹掉；
- 我们注册在它之后，往模型末尾追加即可，两侧互不干扰；
- 命令号避开 28500 与 CEF 内置区间（< 26500）。

JCEF 对 `onContextMenuCommand` 是 any-true 语义，所以只认自己的 id，其余返回 false 交回。

## D2：为什么不用 CEF 自己的下载

`CefBrowser.startDownload(url)` 走的是 `CefBrowserHost::StartDownload`。按 CEF 的约定，
**这类下载不会回调 `onBeforeDownload`**，我们因此没有机会用
`CefBeforeDownloadCallback.Continue(path, false)` 指定落盘路径——只能落进 CEF 默认目录或弹
系统保存框，与「静默打开看图软件」的目标相反。CEF 的下载链路（DownloadManager、用户点击语义）
在 OSR 内核里也更难保证。

## D3：为什么自己发 HTTP，而不是复用浏览器会话

JCEF 没有「取某个 URL 的字节」的 API；用页面里 `fetch` 取图又会撞 CORS（跨域图片不带
`Access-Control-Allow-Origin` 时读不到 body，canvas 也会被污染）。所以只能自己发一次 GET：

- 带 `Referer`：优先 `frameUrl`（iframe 里的图片，浏览器实际带的就是 frame 地址），
  退到 `pageUrl`。绕开常见热链校验，但**不读取页面的 `Referrer-Policy`**（见已知限制 4）；
- 带图片 `Accept` 与浏览器形态的 `User-Agent`（JDK 默认 UA 会被部分图床直接拒绝）；
- 不显式设置 proxy：JDK `HttpClient` 未指定时取 `ProxySelector.getDefault()`，
  即 IDE / 系统代理设置（已实测：把代理端口指向关闭端口即 `ConnectException`）。

代价见「已知限制」第 1 条。

## D4：安全边界与「宁可不出菜单，也不给死项」

- 只接受 **http/https**。`data:` / `blob:` 的字节取不回来，`file:` 属于「页面不该决定要不要
  交给系统程序」的一类（与 ADR-0017 D2 同一条边界）。这些情况**根本不显示菜单项**，
  避免用户点下去得到一个必然失败的提示。
- 判定必须基于 `hasImageContents()` / `CM_MEDIATYPE_IMAGE`，否则普通链接也会冒出这一项。
- **格式判定顺序：二进制魔数 > 明确声明 > HTML 否定 > 文本嗅探 > URL 后缀**。
  图床常用 `application/octet-stream` 发图片（属「未知」），而 403/404 常返回 HTML 错误页（属
  「明确不是图片」）。后者不再靠 URL 后缀去猜，否则会把一页 HTML 存成 `.jpg` 交给看图软件。
  **文本嗅探（SVG）刻意排在 Content-Type 之后、且带 HTML 否定**：否则一页内联了
  `<svg>` 图标的 HTML 错误页会被判成 `.svg` 落盘（评审指出的缺口，已收口）。
- 下载上限 32 MB，超限直接放弃并报错（防止「其实是视频」的地址被整个读进内存）。

## D5：落盘与打开方式

- 临时文件：`Files.createTempFile(前缀, 扩展名)`。前缀由 URL 最后一段清洗得到、限长 48，
  保证 ≥ 3 字符（否则 `createTempFile` 直接抛异常），且**末尾统一拼 `-`**：Windows 的保留名
  （`CON` / `NUL` / `COM1`…）只按主名匹配，加个尾巴就不会命中。
- 打开：`Desktop.isDesktopSupported()` + `Desktop.Action.OPEN` 双检查后
  `Desktop.open(file)`。失败（无关联程序等）以状态栏 + 日志报错，不弹模态框。
- 线程：`HttpClient.send` 是阻塞调用，整个流程丢到 `executeOnPooledThread`；
  状态栏只在 `onEdt` 里写（`renderStatus` 是字段的纯函数，见 BrowserPanel 类注释）。

## 验证

- `ImageContextActionTest`（9 项）：非图片上下文不出项、只认 http(s)（含 `http://x` 这种
  长度刚好等于前缀的边界）、魔数压过撒谎的 Content-Type、Content-Type/URL 兜底、
  HTML 错误页（含内联 `<svg>`）不被存成图片、命令号落在 CEF 用户区间、临时文件前缀合法且
  能被 `createTempFile` 接受、Windows 保留名不会命中。
- `SystemImageViewer`（HTTP 状态、大小上限、`Desktop` 打开）**没有单测**：它把
  `HttpClient` 与 `Desktop` 直接耦合在实现里，可测性需要注入依赖，本批不做。
  这部分只有下面这条手工验证。
- 手工验证：图片上右键 → 菜单末尾出现「在系统看图软件打开」→ 点击后系统看图软件打开该图；
  日志 `[RM-BROWSER] 图片右键菜单追加…` 与 `[RM-BROWSER] 图片已交给系统看图软件 -> <临时文件路径>`。

## 已知限制

1. **登录态图片取不到**：本构建的 JCEF `CefRequestContext` 没有 cookie 访问入口
   （只有 `CefCookieManager.getGlobalManager()`，与 `JBCefBrowser` 的请求上下文不保证同分区），
   所以请求里不带浏览器 cookie。只在登录会话下才能访问的图片会报 `HTTP 403/401`，
   而不是静默失败。
2. **临时文件不自动清理**：一次打开一个文件，落在系统临时目录（`%TEMP%`），由系统清理策略回收。
   插件不主动删（工程约定：不做删除动作）。
3. **依赖系统关联**：SVG / HEIC 之类在没有对应看图软件时打开会失败，错误来自系统而非插件。
   打开失败时状态栏会带上已下载文件的路径，用户可以自己去开。
4. **不读页面的 `Referrer-Policy`**：无论页面是否声明 `no-referrer`，都会把文档地址
   （可能含 query token）发给图片主机。这是「让热链校验通过」与「隐私」之间的取舍，选前者。
5. **SVG 是活动内容**：`.svg` 可能带 script / 外部引用，交给系统默认程序（Windows 上通常是
   浏览器）等于把页面可控的标记落盘再以 `file://` 打开。判断为可接受：这只在用户显式右键
   并选择该项时发生，且与浏览器自己「下载后用默认程序打开」的行为一致。若将来要收紧，
   做法是把 svg 从 `MIME_EXTENSIONS` / `URL_EXTENSIONS` 里移除（代价是 SVG 图片报「无法识别」）。
6. **body 读取没有独立超时**：`HttpRequest.timeout(30s)` 主要覆盖到响应头返回；服务端发完头
   后慢速滴流会让一个池线程被占用较久。没有加读取总时长限制，接受该风险。
7. **没有节流**：连着点几次就会下载几份。菜单操作是用户显式动作，暂不额外限制。
