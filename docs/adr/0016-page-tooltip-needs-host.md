# ADR-0016：页面 tooltip 必须由插件自己接（JCEF 的宿主责任）

- 日期：2026-10-14
- 状态：已实现（0.17.4）
- 相关文档：ADR-0002（JCEF 注入时机）、ADR-0011（DevTools 前端未打包）

## 现象

用户实测：帖子标题被 CSS 省略（`text-overflow: ellipsis`）后，在**普通浏览器里悬停能看到完整标题**，
但在本插件的内嵌浏览器里**什么提示都不弹**。侧边栏模式尤其明显。

## 原因

CEF **不自己绘制** tooltip：它通过 `CefDisplayHandler.onTooltip(CefBrowser, String)` 把文本交给
**宿主 UI** 去显示。JCEF 在 IntelliJ 里走 windowless(OSR) 渲染、**没有原生窗口**，
所以"把提示显示出来"这一步必须由 Java 侧完成。

而 IntelliJ 的 JCEF **只转发、不显示**，验证如下：

- `com.intellij.ui.jcef.JBCefClient$3` 里确实有 `onTooltip`（说明回调被转出来了）；
- 但对整个 `intellij.platform.ui.jcef.jar` 做字符串扫描：`com.intellij.ui.jcef` 包下
  **没有任何一个 class 调用 `setToolTipText`**。

结论：**不实现它，页面里 `<a title="…">` 这类提示在插件内完全不出现。**

## 决策

在 `BrowserPanel` 增加 `TooltipHandler`（`CefDisplayHandlerAdapter` 子类），把回调文本写到
浏览器 Swing 组件的 `toolTipText` 上：

```kotlin
override fun onTooltip(cefBrowser: CefBrowser?, text: String?): Boolean {
    val target = browser?.component ?: return false
    val desired = text?.takeIf { it.isNotBlank() }
    if (target.toolTipText != desired) target.toolTipText = desired
    return true
}
```

做成 **Swing 提示**（而不是自绘覆盖层）的好处：侧边栏与中央编辑区两种容器**自动都生效**，
不必分别处理定位与层级。

## 实测记录（2026-10-14）

**已证实的三条**：

1. IntelliJ 会把回调转发给所有已注册的 display handler —— 字节码：
   `JBCefClient$3.onTooltip` → `myDisplayHandler.handleBooleanReturnAnyOf(browser, (b,text) -> handler.onTooltip(b,text))`；
2. CEF 在 OSR 下**确实会发 `OnTooltip` 并送到本插件** —— 0.17.5 的日志实测到
   `[RM-BROWSER] tooltip -> image` / `有序列表` / `无序列表`；
3. 插件侧的处理是**把文本写到浏览器组件及其整棵子树**的 `toolTipText` 上（0.17.5）。

**用户实测反馈**：改成子树写入后，**多数站点的悬停提示正常了**；只有 **linux.do** 仍不显示，
怀疑是该站用户脚本没给被省略的标题设 `title`。

> ⚠️ **本 ADR 早先一版曾断言"IntelliJ 不会为 JCEF 内容渲染 title 提示、插件不再追这条"，
> 那是过度推断，已被上述实测推翻。** 当时只证明了"只写外层组件不生效"和
> "`com.intellij.ui.jcef` 包里没有 `setToolTipText`"，**不足以推出"宿主完全不渲染"**——
> 后者是错误结论，此处留档，避免以后再犯同样的推断错误。

## 待定论：linux.do 为什么不行

两种可能，**尚未确定**：

- **脚本侧**：该站脚本渲染的标题元素**没有 `title` 属性** → CEF 根本不会发 `OnTooltip`，无从显示；
- **宿主侧**：元素有 `title`，但 CEF 未对该元素触发 `OnTooltip`。

**判据（一次悬停 + 一次对照，两条一起做就能分清）**：

1. 在 linux.do 悬停那条被省略的标题，看插件日志里有没有对应的 `[RM-BROWSER] tooltip ->`；
2. 用系统浏览器（Edge + 同一份脚本）悬停同一条标题，看是否弹提示。

两边都不弹 → **脚本侧**（元素没有 `title`）；系统浏览器弹而插件不弹 → **宿主侧**，继续查。
