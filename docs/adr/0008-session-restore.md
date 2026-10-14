# ADR-0008：浏览器会话恢复

- 日期：2026-10-13
- 状态：已实现（0.10.0）
- 相关文档：方案 §1.2（P1「会话恢复」）、ADR-0006

## 背景

多标签页（0.8.0）之后，关闭 IDE 再打开会丢掉全部标签页，回到一个空白页 —— 这是明显的体验断点。
方案把「会话恢复」列在 P1。

## 决策

### D1：用平台标准 `PersistentStateComponent`，不自造文件格式

`BrowserStateService` 是工程级服务：

```kotlin
@State(name = "RiderMonkeyBrowser", storages = [Storage("rider-monkey-browser.xml")])
@Service(Service.Level.PROJECT)
class BrowserStateService : PersistentStateComponent<BrowserStateService.BrowserState>
```

容器在标签页增删与主 frame 地址变化时回写**内存状态**，平台在关闭工程 / 退出 IDE 时序列化。
不自己写文件：平台已经处理好原子写、工程隔离与路径，自己造只会多一类失败模式。

### D2：**只恢复 URL，不恢复「哪个标签在编辑区」**

启动时自动把标签页塞进中央编辑区会打扰用户，而且编辑区标签本身由平台的会话恢复机制管理，
两边都做容易互相打架。恢复后一律回到侧边栏。

### D3：过滤与上限

- 地址为空或 `about:blank` 的标签页**不记**（否则会恢复出一堆无意义空白页）；
- 上限 **8 个**，避免恢复出几十个标签页把内存吃光；
- 活动标签页索引**在过滤后的子集里重算**，否则会与落盘列表错位（`persistState` 里就是这么做的）。

### D4：与延迟创建内核的配合

标签页的内核是**延迟创建**的（ADR 里记录的尺寸问题所致，见 BrowserPanel 的 `startOnce`）。
恢复时非活动标签页可能还没有内核，因此 `navigateTo` 会在内核缺失时把地址暂存到 `pendingUrl`，
等组件显示、内核创建后补上导航。

## 限制

- 位置（侧边栏 / 编辑区）不恢复。
- 标签页的滚动位置、前进后退历史不恢复（JCEF 的会话本身是进程内的）。
- 崩溃时可能丢最后一次状态（平台按关闭/退出时机落盘）。

## 待办

- 可选：把「恢复上次标签页」做成开关（当前默认开启）。
