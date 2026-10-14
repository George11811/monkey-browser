# ADR-0004：用户脚本 URL 匹配语义

- 日期：2026-10-12
- 状态：已采纳
- 相关文档：Monkey-Browser-Development-Plan.md 第 4.2 节

## 背景

首版 `UserscriptMatcher` 由子代理实现后经只读复审，发现 4 条重要问题（1 条确凿缺陷）。
本 ADR 记录修复后的语义与**有意偏离 Chrome** 之处。

## 决策

### D1：`@match` 忽略 query 与 fragment（有意偏离 Chrome）

- 本实现只看 path。Chrome 扩展的 match pattern 用 `GURL::PathForRequest()`（path+query）做
  路径匹配，因此两者行为不同。
- 选择依据：Violentmonkey 官方文档写明 `@match` 忽略 query/hash；本项目的目标是
  Tampermonkey/Violentmonkey 用户脚本，不是 Chrome 扩展。
- **风险与待办**：Tampermonkey 自身行为未在本机实测。若后续实测发现 TM 采用 Chrome 语义，
  需重新决策（改动集中在 `ParsedUrl.parse` 的 path 截断处）。

### D2：支持可选端口

pattern 形如 `scheme://host[:port]/path`：端口省略或写 `*` 匹配任意端口；写数字时只匹配
该有效端口（scheme 默认端口参与比较，如 https 未写端口视作 443）。非法端口（空、非数字、
超范围）视为非法 pattern 返回 false。方括号 IPv6 pattern 暂不支持。

### D3：正则字面量的判定只认合法 flags

`include`/`exclude` 中，只有「以 `/` 开头、最后一个 `/` 之后是合法 JS flags
（`gimsuy`，不允许重复）」才判为正则，其余按 glob。

**这一条是修复一条确凿缺陷**：早期实现把「末尾斜杠后全是 ASCII 字母」都当 flags，
于是 `@include /a/b` 被判成正则 `a`（flags=`b`），只要 URL 含字母 `a` 就命中几乎所有页面；
`@exclude /admin/panel` 的 `admin` 也会误排除 `/administrator`。修复后这两者都正确落回 glob。
`g`/`u`/`y` 接受但忽略，`i`/`m`/`s` 映射到 Kotlin 正则。

### D4：glob 为整串匹配

无通配符的 glob 必须整串匹配（同 Violentmonkey 文档）。因此 `@include /a/b` 这种裸路径
不会命中任何完整 URL——这是预期行为，不是缺陷；需要前缀匹配时应写 `*/a/b`。

### D5：无 URL 规则时不匹配，但必须可被上层发现

`@match` 与 `@include` 都为空时返回 false（保守，避免误注入所有页面）。
但真实 VM 的默认是「匹配所有 URL」，**我们偏离了它**，因此提供
`UserscriptMatcher.hasNoUrlRules(metadata)`，要求脚本管理界面据此给出警告，
不能让这类脚本静默不生效。

### D6：`<all_urls>` 与 scheme 集合

`<all_urls>` 覆盖 http / https / file / ftp；match pattern 的 `*` scheme 等价于 http|https；
显式 scheme 只允许这四种。未知 scheme 一律 false。

## 影响

- 单元测试从 27 个增加到 35 个，覆盖上述全部分支（含 `/a/b`、`/some/path/file`、
  `*/admin/panel`、端口、flags 校验等回归点）。
- 待补：`@exclude-match` 未建模；IPv6 pattern 未支持；子域通配未排除 IP 字面量。
