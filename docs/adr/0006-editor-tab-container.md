# ADR-0006：把浏览器放进中央编辑区（跨容器迁移）

- 日期：2026-10-12
- 状态：已实现并**人工实测通过**（2026-10-12）
- 相关文档：Monkey-Browser-Development-Plan.md 第 3、4.1、7 节；ADR-0002

## 背景

方案第 3 节要求「浏览器会话与 UI 容器解耦」，并明确警告：**同一个 Swing JComponent 不能同时挂在
两个父容器下**，切换容器时必须在 EDT 上先移除再挂载。方案第 7 节把「跨容器组件迁移 PoC」
列为必须先验证的三项之一。

实测动机很直接：侧边 ToolWindow 宽度通常只有 300–400px，网页体验差；中央编辑区才是看网页的地方。

## 决策

### D1：用自实现的虚拟文件承载「浏览器标签页」，不借助 testFramework / impl 模块

编辑区需要 `FileEditorProvider`，而它按 `VirtualFile` 识别内容。可选方案与取舍：

| 方案 | 结论 |
| --- | --- |
| `com.intellij.testFramework.LightVirtualFile` | ❌ 属于 testFramework，生产代码使用不当 |
| `com.intellij.openapi.vfs.ex.dummy.DummyFileSystem` | ❌ 位于 impl 模块，Plugin Verifier 会判为使用非公开 API |
| 自实现最小 VFS | ✅ 采用 |

自实现成本可控且已实测：`VirtualFile` **14** 个抽象方法、`VirtualFileSystem` **7+6** 个
（后者另有 6 个文件操作入口，一律抛 `UnsupportedOperationException`）。

**关键约束**：`VirtualFile` 按实例身份比较，因此**整个工程内必须复用同一个
`BrowserVirtualFile` 实例**（由 BrowserContainer 持有），否则每点一次「编辑区」就会多开一个标签。

### D2：迁移顺序——先从工具窗摘掉，再交给编辑区

```text
openInEditor():
  1. root.removeAll() + 换成占位提示（含「移回侧边栏」按钮）
  2. FileEditorManager.openFile(virtualFile, true)   // FileEditor.getComponent() 返回同一个面板组件
```

顺序不能反：Swing 不允许一个组件同时挂在两个父容器下。反向操作（移回）同理，
先 `closeFile` 让编辑区释放组件，再挂回工具窗。

编辑标签被用户**直接关闭**（而不是点「移回侧边栏」）时，由 `FileEditor.dispose()` 回调
`BrowserContainer.onEditorClosed()` 把面板还给工具窗；用 `movingBack` 标志避免与显式移回互相打架。

### D3：`FileEditorPolicy.HIDE_DEFAULT_EDITOR`

我们的虚拟文件没有「默认编辑器」，用 HIDE_DEFAULT_EDITOR 让本 provider 直接成为主编辑器。
（该枚举在 262 的取值：NONE / HIDE_DEFAULT_EDITOR / HIDE_OTHER_EDITORS /
PLACE_BEFORE_DEFAULT_EDITOR / PLACE_AFTER_DEFAULT_EDITOR，已实测核对。）

## ⚠️ 尚未验证的风险（本文档最重要的一节）

**JCEF 的组件在被重新挂载到另一个父容器后，页面是否还活着？**

- JCEF 在 Windows 上是**原生子窗口**（HWND）渲染，不是纯 Swing 绘制；
- 重新挂载会触发原生化组件的 parent 变更，可能表现为：页面正常、页面变白、或需要重新加载；
- **这一点无法靠静态分析或单测确认，必须实测**。方案的跨容器迁移 PoC 正是冲它来的。

因此 0.6.0 的验收必须包含人工实测：
1. 在工具窗里打开网页 → 点「编辑区」→ 页面是否仍显示、滚动位置是否保留、GM 脚本是否还生效；
2. 点「移回侧边栏」→ 同样检查；
3. 若页面变白/丢失：回退方案是**迁移时新建浏览器并重载 URL**（牺牲状态换取可用性），
   或改用离屏渲染（`setOffScreenRendering(true)`）。届时另开 ADR。

## 追加决策（0.8.0）：多标签页模型

每个标签页 = **一个 \`BrowserPanel\`**，各自拥有独立的 \`JBCefBrowser\`、脚本注入器与 GM 桥；
标签页要么挂在标签条上（侧边栏），要么挂在中央编辑区，二选一。

- 标签条用平台原生 \`JBTabs\`（\`JBTabsFactory.createTabs(project, this)\`），不用 Swing 的 \`JTabbedPane\`，
  以保持 IDE 观感一致。**262 的 API 细节**：\`TabInfo\` 的 getter 是 \`getObject()\`，
  Kotlin 属性名推导为关键字 \`object\`，必须写反引号；\`ActionGroup\` 是抽象类，
  抽象成员是 \`getChildren\`，**没有** 旧的 \`add(...)\` 系列方法。
- 每个标签页一个 \`BrowserVirtualFile\` 实例，由容器长期持有；Provider 通过它反查标签页。
- 标签文案跟随页面标题（\`CefDisplayHandler.onTitleChange\`）。

## 追加修正（0.13.1）：内核创建不能"尝试过就锁定"

用户实测：**新增标签页时偶现「浏览器内核尚未就绪，导航不可用」，页面永久刷不出来**。
根因在旧的 `startOnce()`：

```kotlin
started = true                 // ← 先锁定
created.createImmediately()
if (created.cefBrowser == null) {
    lastError = "浏览器内核尚未就绪，导航不可用。"
    return                     // ← 永久放弃，started 已为 true
}
```

`createImmediately()` 在**并发创建**（连续新增标签页）时并不保证同步返回内核；
一旦返回 null，该标签页就被永久判死。修正为：

- **拿到内核才置 ready**（不在进入方法时就锁定）；
- 没拿到就**有限次（12 次 × 200ms）带延迟**重试，用 Swing `Timer` 在 EDT 上触发，不忙等；
- 重试耗尽后给出可手动恢复的提示，**刷新按钮与地址栏回车都会重新尝试创建内核**；
- 地址栏在内核未就绪时不再报"导航不可用"，而是记下地址并立即触发重试。

另外把内核创建**全局串行化**：多个标签页不在同一时刻创建 JCEF 内核。
这一条同时是对"偶发整体卡死"的防御——见下。

## 已知限制（本版）

- 标签条**始终显示**（即使只有一个标签页）。侧边栏垂直空间紧张，可考虑只在多标签时显示。
- **关闭标签页只能右键菜单**（新建/关闭当前），标签上没有 × 按钮：262 的 \`JBTabs\` 没有
  \`setTabLabelActions\`，要加可见的关闭按钮需要另找 API。
- 没有把容器位置持久化：重启后所有标签页回到工具窗。
- 标签页顺序、URL 都不持久化（会话恢复未做）。
- 迁移时不做状态快照（URL/滚动位置），完全依赖 JCEF 组件本身保活。

## 卡死排查（0.13.2）：尺寸推送风暴（已验证的机制）

用户给出了精确复现：**重启后新增标签页 → 输入 v2ex.com → 回车 → Rider 冻结**。

日志取证（更早一次会话留下的完整记录，当时还带视口探针）显示了一场**尺寸推送风暴**：

\`\`\`text
[RM-SIZE] 内核尺寸 -> 323x67 → 323x73 → 323x79 → 323x104 → 323x112 → ... → 323x176
[RM-SIZE] 网页视口 {...}     ← 每次推送还附带一次 executeJavaScript 探针
\`\`\`

**约 1.5 秒内近百次 JCEF 调用，全部在 EDT 上。** 根因：\`pushSize\` 的去重条件是
「尺寸与上次**完全相同**才跳过」，而窗口是在**逐步长大**——每一步都绕过它，直接调
\`wasResized\` + \`notifyScreenInfoChanged\`（当时还多一次 \`executeJavaScript\`）。
EDT 被这样灌就会表现为 UI 冻结。

**修法**：\`schedulePushSize\` 改为**前缘 + 尾缘节流**（最小间隔 100ms）：
距上次推送够久就立即推，否则合并成一次尾随推送。拖动时仍跟手（≤10 次/秒），但不再有风暴。

**诚实说明**：这是一条**有实证支撑的机制**，但我**没有证明它就是卡死的唯一原因**。
因此同时补齐了整条导航链路的日志（地址栏导航 → onLoadStart → onLoadEnd → 注入），
下次若再冻结，日志会直接指出卡在哪一步。

## 未解释的现象：偶发整体卡死（旧记录）

用户报告（上一个版本，当前版本无法重现）：**重启后重新加载页面偶尔导致整个 Rider 卡死**。

（本节记录的是 0.13.1 之前的判断。0.13.2 起有了上面那节的实证方向。）

日志里能找到的 SEVERE 与插件无关（NavBar 的 findDirectory 空参、Junie 扩展点、Grazie 410），
因此不怀疑它们。当前能做的只有降低可疑面并留下证据：

- 内核创建已全局串行化（0.13.1），排除"多标签页并发创建 JCEF 内核"这一可疑路径；
- 创建流程每一步都有 `[RM-BROWSER] 创建内核（第 N 次尝试，WxH）` / `内核就绪` / `内核尚未就绪，稍后重试` 日志，
  卡死时可以据此判断停在哪一步、以及是否有创建重叠。

若再次复现，请保留 `idea.log`（Rider 一般还会写入 freeze / 线程转储条目），据此再定位。

## 待办

- 上述跨容器迁移的人工实测，结果回填到本文档。
- 多标签页与 BrowserManager 多会话化（方案 §4.1）。
- 容器位置持久化（`BrowserSessionState.placement` 已具备字段）。