# ADR-0011：DevTools 入口——平台 API 存在，但 JCEF 没打包前端（已撤回）

- 日期：2026-10-13
- 状态：**已撤回**（0.13.5 引入，0.13.6 移除）
- 相关文档：ADR-0002、ADR-0006

## 结论

`JBCefBrowserBase.openDevtools()` 和 `JBCefBrowserBuilder.setEnableOpenDevToolsMenuItem(boolean)`
都是**真实存在的平台公开 API**：能编译、能调用、也会真的弹出 DevTools 窗口。

但**窗口内容恒为空白**，因为 JetBrains 的 JCEF 发行包**没有打包 DevTools 前端资源**。
一个点了只出空白窗口的按钮比没有更糟，因此该入口在 0.13.6 中被移除，本 ADR 保留完整证据。

## D1：API 确实存在（更正一次判断错误）

**错误的查证方式**：只对 `com.intellij.ui.jcef.JBCefBrowser` 执行 `javap`。
`javap` 默认**只列该类自己声明的成员，不含继承成员**，于是得到"没有 DevTools 方法"的错误结论，
并据此放弃了按钮方案、绕道远程调试端口。

实际核实（`javap -p -cp plugins/jcef-plugin/lib/modules/intellij.platform.ui.jcef.jar`）：

```text
com.intellij.ui.jcef.JBCefBrowser        -> (无 DevTools 方法)   ← 只看它就会误判
com.intellij.ui.jcef.JBCefBrowserBase    -> public void openDevtools();
com.intellij.ui.jcef.JBCefBrowserBuilder -> public JBCefBrowserBuilder setEnableOpenDevToolsMenuItem(boolean);
```

**教训：判断一个 API 是否存在，必须连继承链一起看；单看子类的 `javap` 不能作为"不存在"的证据。**

## D2：右键菜单项默认是关的

`JBCefBrowserBase$DefaultCefContextMenuHandler` 字节码：

```java
isOpenDevToolsItemEnabled = browser.myEnableOpenDevToolsMenuItem
                         || Registry.is("ide.browser.jcef.contextMenu.devTools.enabled");
// 文案 "Open DevTools" → 点击后调用 browser.openDevtools()
```

该 Registry 键的内置默认值在 `lib/util-8.jar!misc/registry.properties` 中为 **false**：

```properties
ide.browser.jcef.contextMenu.devTools.enabled.description=Adds the "Open DevTools" item to the JCEF context menu.
ide.browser.jcef.contextMenu.devTools.enabled=false
```

（这是"默认看不到该项"的原因，但**不是**窗口空白的原因——打开后同样是空白。）

## D3：窗口为什么是空白的（撤回依据）

打开 DevTools 后，从调试端口读到的 target 列表里，DevTools 窗口自己长这样：

```json
{ "title": "DevTools - v2ex.com/", "type": "page",
  "url": "devtools://devtools/bundled/devtools_app.html?remoteBase=https://chrome-devtools-frontend.appspot.com/serve_file/@25069532fb112040d83a2b71712f99b46025d12f" }
```

即：CEF 只提供 `devtools_app.html` 这个**外壳**，真正的 UI 通过 `remoteBase` 从 Google 拉取。

三组对照证据：

| 检查 | 结果 |
| --- | --- |
| JCEF `resources.pak` 字节扫描 `devtools` / `devtools_app` / `inspector.html` | **0 / 0 / 0 次** —— 未打包前端 |
| Chrome 155 的 `resources.pak` 体积 | **25.8 MB**（JCEF 仅 12.6 MB）—— Chrome 的前端在自己的 pak 里 |
| `remoteBase` 的 `inspector.html`：走 7897 代理 / 直连 | `http=200, 2.94s` / `http=000`（直连不可达） |

`inspector.html` 以 **ES module** 方式加载 `./entrypoints/inspector/inspector.js`，
也就是要**逐个文件**把整个前端拉下来；每个文件经代理约 3 秒，实际永远加载不完 → 空白窗口。

对照：外部 Chrome 打开同一个页面并 `inspect` 能正常工作，因为它用的是**自带的离线前端**，
完全不依赖 appspot。这正是外部链路可用、内嵌链路不可用的分界点。

## D4：可用的替代方案（这才是 DevTools 的正确用法）

```text
1. Help -> Edit Custom VM Options... 加一行：
     -Dide.browser.jcef.debug.port=9222
2. 重启 IDE
3. 用日常浏览器打开 chrome://inspect（Edge 为 edge://inspect）
4. Configure... 添加 127.0.0.1:9222，列表里选页面点 inspect
```

细节：

- 该键经 `Registry.intValue` 读取，取值优先级为
  `registry.properties(已存储) > System.getProperty(即 -D) > bundle 默认(-1)`，因此 `-D` 有效。
- `remote_debugging_port > 0` 时平台会**自动追加** `--remote-allow-origins=*`，无需手工补。
- **`http://127.0.0.1:9222/` 打开是空白的**：该 HTTP 服务只提供 `/json/version`、`/json/list` 等
  JSON 端点；返回项里的 `devtoolsFrontendUrl` 指向 appspot（需要外网），在受限网络下不可用。
  正确入口是 `chrome://inspect`。

## 遗留

若将来一定要让**内嵌** DevTools 可用，需要随插件分发 DevTools 前端（数十 MB）并以自定义 scheme 提供，
且它仍依赖调试端口。成本远超收益，暂不做。
