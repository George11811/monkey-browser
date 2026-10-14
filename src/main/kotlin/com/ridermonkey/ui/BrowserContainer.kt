package com.ridermonkey.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo
import com.ridermonkey.browser.BrowserContainers
import com.ridermonkey.userscript.GmHost
import com.ridermonkey.browser.BrowserStateService
import com.ridermonkey.browser.BrowserVirtualFile
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.Timer

/**
 * 工具窗内容的宿主：管理多个浏览器标签页，并在「侧边栏 ↔ 中央编辑区」之间搬运它们。
 *
 * 模型：**每个标签页 = 一个 [BrowserPanel]**（各自拥有独立的 JBCefBrowser、脚本注入器与 GM 桥），
 * 标签页要么挂在标签条上（侧边栏），要么挂在中央编辑区，二选一。
 *
 * 跨容器迁移的顺序不能反（方案 §3）：必须先 removeTab / 摘掉组件，再交给编辑区，
 * 因为 Swing 不允许一个组件同时挂在两个父容器下。
 *
 * 本版未做：标签页顺序持久化、会话恢复（重启后回到单标签）。
 */
class BrowserContainer(private val project: Project) : Disposable, GmHost {

    /** 一个标签页：面板 + 在编辑区里的虚拟文件 +（在侧边栏时的）标签项。 */
    inner class BrowserTab(val panel: BrowserPanel, val virtualFile: BrowserVirtualFile) {
        var tabInfo: TabInfo? = null
        var inEditor: Boolean = false

        /** GM_openInTab 给的句柄编号；0 表示不是由 GM 打开的（GmHost 约定不返回 0）。 */
        var gmId: Int = 0

        /** 标题防抖用的状态（每个标签页各一份，避免多标签互相覆盖）。 */
        var pendingTitle: String? = null
        var appliedTitle: String? = null
        var titleTimer: Timer? = null
    }

    private val logger = Logger.getInstance("RiderMonkeyBrowser")
    private val root = JPanel(BorderLayout())
    private val placeholder = JPanel(BorderLayout())
    private val tabs: JBTabs = JBTabsFactory.createTabs(project, this)
    private val tabList = mutableListOf<BrowserTab>()

    /** 全局暂停时由脚本管理界面调用，用来刷新页面让启停立刻生效。 */
    private var nextTabNumber = 1

    /** GM_openInTab 的句柄编号，从 1 开始（0 保留为「非 GM 打开」）。 */
    private var nextGmTabId = 1

    private companion object {
        /** 标题需要稳定多久才更新标签；见 updateTabTitle 的说明。 */
        const val TITLE_STABLE_DELAY_MS = 700

        /**
         * 标签文案最多占多少**显示列**（中日韩字符算 2 列、拉丁字符 1 列）。
         *
         * 工具窗标签条通常只有 300–400px 宽。此前按 24 **字符**截断，而 24 个中文等于 48 列
         * （约 300px），必然溢出——JBTabs 放不下就把标题丢进下拉菜单，用户看到的就是
         * 「名字太长」（实测反馈）。26 列约等于 13 个中文或 26 个拉丁字符。
         */
        const val TAB_TITLE_MAX_COLUMNS = 26
    }

    val component: JComponent get() = root

    init {
        buildPlaceholder()
        tabs.setPopupGroup(buildTabPopup(), "RiderMonkeyBrowserTabs", true)
        root.add(placeholder, BorderLayout.CENTER)
        BrowserContainers.put(project, this)

        // 会话恢复：上次退出时开着的标签页按原顺序重建（只恢复 URL，位置一律回到侧边栏）。
        val service = BrowserStateService.getInstance(project)
        val saved = service.savedUrls()
        if (saved.isEmpty()) {
            newTab(source = "init")
        } else {
            for (url in saved) newTab(url, source = "restore")
            val index = service.savedActiveIndex().coerceIn(0, saved.size - 1)
            tabList.getOrNull(index)?.tabInfo?.let { tabs.select(it, true) }
            logger.info("[RM-BROWSER] 已恢复 " + saved.size + " 个标签页")
        }
    }

    /**
     * 把当前标签页写回工程级状态。
     *
     * 只记会真正落盘的标签页（空白 / about:blank 不记），活动索引也在这份子集里算，
     * 否则索引会和落盘列表错位。
     */
    fun persistState() {
        val selected = tabs.selectedInfo
        val kept = tabList.filter {
            val url = it.panel.currentUrl().orEmpty().trim()
            url.isNotEmpty() && url != "about:blank"
        }
        val activeIndex = kept.indexOfFirst { it.tabInfo === selected }.coerceAtLeast(0)
        BrowserStateService.getInstance(project)
            .remember(kept.map { it.panel.currentUrl().orEmpty() }, activeIndex)
    }

    fun tabFor(file: VirtualFile): BrowserTab? =
        tabList.firstOrNull { it.virtualFile === file }

    /** 当前选中的标签页（可能为空：所有标签页都在编辑区）。 */
    fun selectedTab(): BrowserTab? = tabs.selectedInfo?.`object` as? BrowserTab

    /**
     * 新建标签页并选中它。
     *
     * @param source 仅用于诊断日志，标明是谁触发了这次新建（init / restore / toolbar+ / tabPopup /
     *   placeholder / closeTab-empty）。幽灵内核排查靠它定位。
     */
    fun newTab(url: String? = null, source: String = "unknown", select: Boolean = true): BrowserTab {
        val tabNumber = nextTabNumber++
        var tab: BrowserTab? = null
        val panel = BrowserPanel(
            project,
            togglePlacement = { tab?.let { togglePlacement(it) } },
            onNewTab = { newTab(source = "toolbar+") },
            onTitleChanged = { title -> updateTabTitle(tab, title) },
            onUrlChanged = { persistState() },
            // GM_openInTab 需要打开/关闭标签页，能力以接口形式注入，避免桥反向依赖 UI。
            gmHost = this
        )
        tab = BrowserTab(panel, BrowserVirtualFile(tabNumber))
        Disposer.register(this, panel)
        tabList.add(tab)
        logger.info(
            "[RM-BROWSER] 新建标签页：容器#" + System.identityHashCode(this) +
                " 面板#" + System.identityHashCode(panel) +
                " 来源=" + source +
                " 现有标签数=" + tabList.size
        )

        val info = TabInfo(panel.component)
        info.setText("新标签页")
        info.setObject(tab)
        tab.tabInfo = info
        tabs.addTab(info)
        // GM_openInTab 的 options.active=false 时不抢焦点。
        if (select) tabs.select(info, true)
        refreshContent()
        url?.let { panel.navigateTo(it) }
        persistState()
        // 刚 add 进去的那个就是本次新建的标签页（本方法内不会再新增别的）。
        return tabList.last()
    }

    // ── GmHost：只暴露 GM API 需要的两件事 ──

    override fun openTab(url: String, active: Boolean): Int {
        val tab = newTab(url, source = "GM_openInTab", select = active)
        val id = nextGmTabId++
        tab.gmId = id
        return id
    }

    override fun closeTabById(tabId: Int): Boolean {
        val tab = tabList.firstOrNull { it.gmId == tabId } ?: return false
        closeTab(tab)
        return true
    }

    fun closeTab(tab: BrowserTab) {
        if (!tabList.remove(tab)) return
        if (tab.inEditor) {
            movingBack = true
            try {
                FileEditorManager.getInstance(project).closeFile(tab.virtualFile)
            } finally {
                movingBack = false
            }
        }
        tab.titleTimer?.stop()
        tab.tabInfo?.let { tabs.removeTab(it) }
        Disposer.dispose(tab.panel)
        if (tabList.isEmpty()) newTab(source = "closeTab-empty") else refreshContent()
        persistState()
    }

    /** 侧边栏 ↔ 中央编辑区 切换。 */
    private fun togglePlacement(tab: BrowserTab) {
        if (tab.inEditor) moveBackToToolWindow(tab) else moveToEditor(tab)
    }

    private fun moveToEditor(tab: BrowserTab) {
        tab.tabInfo?.let { info ->
            if (tabs.tabCount > 1) tabs.select(neighbourOf(info), true)
            tabs.removeTab(info)
        }
        tab.tabInfo = null
        tab.inEditor = true
        tab.panel.setPlacementInEditor(true)
        refreshContent()
        FileEditorManager.getInstance(project).openFile(tab.virtualFile, true)
    }

    private fun moveBackToToolWindow(tab: BrowserTab) {
        if (!tab.inEditor) return
        movingBack = true
        try {
            FileEditorManager.getInstance(project).closeFile(tab.virtualFile)
        } finally {
            movingBack = false
        }
        attachToToolWindow(tab)
    }

    /** 供 FileEditor.dispose 调用：编辑标签被直接关闭时把面板还给工具窗。 */
    fun onEditorClosed(tab: BrowserTab) {
        if (movingBack) return
        attachToToolWindow(tab)
    }

    private fun attachToToolWindow(tab: BrowserTab) {
        if (!tabList.contains(tab)) return
        tab.inEditor = false
        tab.panel.setPlacementInEditor(false)
        if (tab.tabInfo == null) {
            val info = TabInfo(tab.panel.component)
            // 这里以前直接 setText(全名)，从编辑区移回侧边栏时会露出完整标题——补上截断与 tooltip。
            val title = tab.panel.tabTitle()
            info.setText(shorten(title))
            info.setTooltipText(HtmlChunk.text(title))
            info.setObject(tab)
            tab.tabInfo = info
            tabs.addTab(info)
        }
        tab.tabInfo?.let { tabs.select(it, true) }
        refreshContent()
    }

    /** 重载所有标签页，让脚本启停立刻生效。 */
    fun reloadAllTabs() {
        tabList.forEach { it.panel.reloadPage() }
    }

    /**
     * 刷新标签页文案（**纯尾缘防抖**，不是节流）。
     *
     * 关键区别：节流只是"按固定频率采样"，遇到**标题本身在震荡**时照样来回跳。
     * 实测场景——用户脚本是"伪装版"，会改写 \`document.title\` 做伪装，
     * 而页面原生代码（SPA 路由同步）又把它改回去，两边抢着写，于是标签在两个名字间闪烁。
     *
     * 因此改为：每次标题变化都**重置**计时器，只有标题**稳定** [TITLE_STABLE_DELAY_MS] 毫秒
     * 才真正更新标签。震荡时计时器永远不触发，标签纹丝不动；真实的新页面标题仍会正常生效。
     */
    private fun updateTabTitle(tab: BrowserTab?, title: String) {
        val target = tab ?: return
        if (title.isBlank()) return
        target.panel.memorizeTitle(title)
        target.pendingTitle = title

        target.titleTimer?.stop()
        target.titleTimer = Timer(TITLE_STABLE_DELAY_MS) {
            applyTabTitle(target)
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun applyTabTitle(tab: BrowserTab) {
        val title = tab.pendingTitle ?: return
        // 值没变就不碰 UI，避免无意义重绘。
        if (title == tab.appliedTitle) return
        tab.appliedTitle = title
        setTabLabel(tab, title)
    }

    /**
     * 按**显示列数**截断标签文案，并预留 1 列给省略号。
     *
     * 为什么不能按字符数：中文一个字占两列，24 个中文等于 48 列，在工具窗标签条里必然放不下。
     * 按列数算对中英混排才准确。
     */
    private fun shorten(title: String): String {
        var columns = 0
        var end = 0
        while (end < title.length) {
            val width = columnsOf(title[end])
            if (columns + width > TAB_TITLE_MAX_COLUMNS - 1) break
            columns += width
            end++
        }
        return if (end >= title.length) title else title.substring(0, end) + "…"
    }

    /** 粗略估计一个字符占几列：全角区间 2 列，其余 1 列（代理对由两个字符各算 1 列，合计 2）。 */
    private fun columnsOf(ch: Char): Int {
        val code = ch.code
        return when {
            code < 0x1100 -> 1
            code in 0x1100..0x115F -> 2     // 韩文字母
            code in 0x2E80..0xA4CF -> 2     // 中日韩部首、汉字、假名
            code in 0xAC00..0xD7A3 -> 2     // 韩文音节
            code in 0xD800..0xDFFF -> 1     // 代理对的一半：成对后合计 2 列
            code in 0xF900..0xFAFF -> 2     // 中日韩兼容汉字
            code in 0xFE30..0xFE6F -> 2     // 中日韩兼容形式
            code in 0xFF00..0xFF60 -> 2     // 全角形式
            code in 0xFFE0..0xFFE6 -> 2     // 全角符号
            else -> 1
        }
    }

    /** 写标签文案：**显示截断 + 完整标题进 tooltip**，信息不丢。 */
    private fun setTabLabel(tab: BrowserTab, title: String) {
        val info = tab.tabInfo ?: return
        info.setText(shorten(title))
        // 用 HtmlChunk 版本：String 重载已废弃（它会把标题当 HTML 解析，有注入风险）。
        info.setTooltipText(HtmlChunk.text(title))
    }

    /** 侧边栏还剩标签页就显示标签条，一个都不剩才显示占位提示。 */
    private fun refreshContent() {
        root.removeAll()
        if (tabList.any { !it.inEditor }) {
            root.add(tabs.component, BorderLayout.CENTER)
        } else {
            root.add(placeholder, BorderLayout.CENTER)
        }
        root.revalidate()
        root.repaint()
    }

    private fun neighbourOf(info: TabInfo): TabInfo {
        val all = tabs.tabs
        val index = all.indexOf(info)
        return all.getOrElse(if (index > 0) index - 1 else 0) { info }
    }

    /**
     * 标签条右键菜单。
     *
     * 262 的 `ActionGroup` 是抽象类，抽象成员是 `getChildren`，没有旧的 `add(...)` 系列方法。
     */
    private fun buildTabPopup(): ActionGroup = object : ActionGroup() {
        init {
            isPopup = true
        }

        override fun getChildren(e: AnActionEvent?): Array<AnAction> = arrayOf(
            object : AnAction("新建标签页") {
                // 必须用块体：newTab 现在返回 BrowserTab，表达式体会把返回类型推断成 BrowserTab，
                // 与 AnAction.actionPerformed 的 Unit 冲突。
                override fun actionPerformed(e: AnActionEvent) {
                    newTab(source = "tabPopup")
                }
            },
            object : AnAction("关闭当前标签页") {
                override fun actionPerformed(e: AnActionEvent) {
                    selectedTab()?.let { closeTab(it) }
                }
            }
        )
    }

    private fun buildPlaceholder() {
        val label = JLabel("所有浏览器标签页都在中央编辑区", SwingConstants.CENTER)
        val restore = JButton("移回侧边栏")
        restore.addActionListener {
            tabList.firstOrNull { it.inEditor }?.let { moveBackToToolWindow(it) }
        }
        val create = JButton("新建标签页")
        create.addActionListener { newTab(source = "placeholder") }

        val buttons = JPanel()
        buttons.add(restore)
        buttons.add(create)

        placeholder.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        placeholder.add(label, BorderLayout.CENTER)
        placeholder.add(buttons, BorderLayout.SOUTH)
    }

    override fun dispose() {
        logger.info("[RM-BROWSER] 容器#" + System.identityHashCode(this) + " 已释放")
        // 只在"仍是当前注册的那个实例"时撤销注册。
        // 无条件 remove(project) 会把**后创建**的容器的注册一起抹掉，
        // 之后 BrowserContainers.get() 返回 null，「移回侧边栏」就会报"容器尚未创建"。
        BrowserContainers.remove(project, this)
    }

    /** 编辑区关闭时由 FileEditor.dispose 回调；用标志避免与显式移回互相打架。 */
    private var movingBack = false
}