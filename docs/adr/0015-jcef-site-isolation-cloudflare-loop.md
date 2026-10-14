# ADR-0015：Cloudflare 挑战页死循环——JCEF 站点隔离

- 日期：2026-10-14
- 状态：已定位并验证（**非插件缺陷**，IDE 设置可解）；配套逃生通道已实现
- 相关文档：ADR-0002（JCEF 注入时机）、ADR-0011（DevTools 前端未打包）

## 现象

在 IntelliJ IDEA 2026.2.3 的内嵌浏览器里访问受 Cloudflare 托管挑战保护的站点（实测
`v2ex.com`、`linux.do`），页面停在 "Just a moment..." / "Verify you are human"，
**每 ~140 秒重新挑战一次、永不通过**。同一台机器上用本插件在 **Rider** 里访问同一站点**正常**。

日志里能直接看到这个周期（`[RM-INJECT]` 的整份脚本重注入，恒定 140 秒间隔，持续 27 分钟以上）。

## 排查：逐条排除，而不是猜

| 假设 | 证据 | 结论 |
| --- | --- | --- |
| 用户脚本注入破坏了 Turnstile | 关掉全部脚本后依然循环 | 排除 |
| 代理 / 出口 IP | IDEA 侧 CEF **根本没有** `--proxy-server`，依然循环；后来给它加上代理，挑战反而能过 | 排除 |
| JCEF 整体过不了 Cloudflare | 同机 Rider 正常；两个 IDE 的 `libcef.dll` **版本与字节数完全相同**（`144.0.15+chromium-144.0.7559.172`，253,037,408 字节） | 排除 |
| Cookie / 凭据缓存差异 | 两个 IDE 的 `jcef_cache` 里都**没有** `Cookies` 文件（`cs:persist_session_cookies=false`），cookie 仅存内存 | 排除 |
| 调试端口 / DevTools | Rider 开着 9222 正常，IDEA 关着也坏 | 排除 |
| Chromium 版本 | 见上，完全相同 | 排除 |

逐项对齐两个 IDE 的 `cef_server.exe` 启动参数后，**唯一影响网页行为的差异**是：

```text
Rider: （无此项）
IDEA : arg:--disable-site-isolation-trials
```

## 根因

IDEA 那个 JCEF 插件构建把注册表键 `ide.browser.jcef.osr.siteIsolation.disable` 默认为 **true**；
映射关系在 `SettingsHelper.class` 里可直接看到（该键 → `--disable-site-isolation-trials`）。
Rider 的构建没有这个默认值，所以 Rider 不受影响——这就是"同一个插件、同一份代码、两个 IDE 表现不同"的原因。

站点隔离被关闭后，**跨域 iframe 与顶层文档跑在同一个渲染进程里**。Cloudflare 的 Turnstile 正是
`challenges.cloudflare.com` 的跨域 iframe；在这种进程架构下它无法完成验证，于是永远拿不到放行凭据，
Cloudflare 按自己的重试周期（实测恒定 **140 秒**）反复重发挑战页，表现为死循环。

## 解法（IDE 设置，不是插件代码）

```text
Help | Find Action → Registry...
  把 ide.browser.jcef.osr.siteIsolation.disable 设为 false（取消勾选）
必须重启 IDE（该键带 restartRequired）
```

**验证结果**：重启后 `cef_server.exe` 命令行里 `--disable-site-isolation-trials` 消失，
v2ex 挑战通过，页面标题从 "Just a moment..." 变为真实标题（并已跑上对应的用户脚本）。
且此时 CEF **带着** `--proxy-server` 依然能过——反向确认了代理无关。

## 为什么插件侧无解

`--disable-site-isolation-trials` 是 **CEF 进程级**启动参数，在任何 `JBCefBrowser` 被创建之前就已确定；
`JBCefBrowser` 也没有暴露站点隔离、UA、Cookie 策略、OSR 的任何接口。所以这是 IDE/JCEF 层配置，
插件既不能设置也不能覆盖。**它不是插件缺陷**，但文档必须告诉用户怎么自救。

## 插件侧的配套：逃生通道

进程级参数改不了，但可以给用户一条**绕开 JCEF** 的路：导航工具栏新增
**「在系统浏览器中打开当前页」**（按钮图标 `AllIcons.General.Web`，动作走 `BrowserUtil.browse`）。
这样即使某个 IDE 的 JCEF 组合过不了挑战，用户也不至于在该站点上完全不可用。

**这只是绕开，不是修好**——真正的修法仍是上面那条 IDE 设置。之所以两条都给，是因为
"绕开"能立刻解除阻塞，"修好"需要用户改注册表并重启。

## 排查方法（可复用）

两个 IDE 各自独立运行 JCEF，因此"一个正常一个坏"时，**对齐两者的进程启动参数**是最快的收窄手段：

```powershell
Get-CimInstance Win32_Process -Filter "Name='cef_server.exe'" |
  Where-Object { $_.CommandLine -notmatch '--type=' } | ForEach-Object { $_.CommandLine }
```

配合 `ide.browser.jcef.debug.port` + CDP（`http://127.0.0.1:<port>/json/list`）可以直接读到
页面内部状态。注意：**`cf_clearance` 是 HttpOnly，`document.cookie` 读不到**，不能用它判断是否放过行。

## 一条未验证的关联（勿当结论）

本工程此前记录过一例 v2ex「白条」问题（Google CMP 的第三方 iframe 显示异常），当时结论为
"JCEF 环境特有"。那个问题同样出在**第三方 iframe** 上，与本 ADR 的站点隔离缺陷**可能同源**。
尚未验证；若要确认，可在站点隔离已开启的状态下重测该页面。
