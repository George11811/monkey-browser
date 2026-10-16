package com.ridermonkey.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.ridermonkey.browser.PopupDecision
import com.ridermonkey.browser.PopupRouting
import com.ridermonkey.storage.BookmarkStore
import com.ridermonkey.storage.GmStorage
import com.ridermonkey.storage.ResourceCache
import com.ridermonkey.storage.ScriptFileStore
import com.ridermonkey.storage.ScriptStoreFactory
import com.ridermonkey.userscript.ConnectRules
import com.ridermonkey.userscript.GmBridge
import com.ridermonkey.userscript.GmHost
import com.ridermonkey.userscript.ScriptInjector
import com.ridermonkey.util.UrlUtils
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.network.CefRequest
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.Insets
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.util.concurrent.atomic.AtomicBoolean
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 内嵌浏览器面板：工具栏 + 地址栏 + JCEF 视图 + 状态栏。
 *
 * 本类是浏览器会话在 ToolWindow 容器中的视图。JBCefBrowser 作为子 disposable 注册到
 * 本面板，面板释放时会一并释放底层的 JCEF 资源。
 *
 * 状态栏文本是 [isLoading] / [lastError] / [hasActivity] 三个字段的纯函数（[renderStatus]），
 * 不允许任何回调直接写 statusLabel.text——否则 CEF 的 onLoadingStateChange(false)
 * 会把错误信息覆盖成「完成」。
 *
 * @param project 目前未直接使用，为后续接入工程级服务（Editor Tab、脚本管理）预留。
 */
class BrowserPanel(
    private val project: Project,
    /** 请求在「侧边栏 ↔ 中央编辑区」之间切换容器；由 BrowserContainer 实现。 */
    private val togglePlacement: () -> Unit = {},
    /** 新建标签页；由 BrowserContainer 实现。 */
    private val onNewTab: () -> Unit = {},
    /** 页面标题变化，用于刷新标签页文案。 */
    private val onTitleChanged: (String) -> Unit = {},
    /** 主 frame 地址变化，用于会话恢复记录。 */
    private val onUrlChanged: (String) -> Unit = {},
    /** GM_openInTab / 句柄 close() 需要的标签页能力；由 BrowserContainer 实现。 */
    private val gmHost: GmHost? = null,
    /**
     * 页面弹窗（`target="_blank"` 链接 / `window.open`）要开的标签页；由 BrowserContainer 实现。
     * 为 null（宿主未装配）时弹窗一律退到系统浏览器，不再让它变成一个不受管的窗口。
     */
    private val openUrlInNewTab: ((String) -> Unit)? = null
) : Disposable {

    private val root = JPanel(BorderLayout())
    private val addressField = JBTextField()
    private val statusLabel = JBLabel(" ")
    private val backButton = ToolbarButton(AllIcons.Actions.Back)
    private val forwardButton = ToolbarButton(AllIcons.Actions.Forward)
    private val reloadButton = ToolbarButton(AllIcons.Actions.Refresh)
    private val stopButton = ToolbarButton(AllIcons.Actions.Suspend)

    /** 书签：收藏当前页 + 查看/管理已有书签。 */
    private val bookmarkButton = ToolbarButton(AllIcons.Nodes.Favorite)

    /** 在系统浏览器中打开当前页：JCEF 访问不了的站点（如 Cloudflare 挑战）用它绕开。 */
    private val externalButton = ToolbarButton(AllIcons.General.Web)

    /** 容器切换按钮：在侧边栏时显示「编辑区」，在编辑区时显示「侧边栏」。 */
    private val editorButton = ToolbarButton("编辑区")

    /** 新建标签页。 */
    private val newTabButton = ToolbarButton(AllIcons.General.Add)

    /** 当前页面标题，用于标签页文案。 */
    private var pageTitle: String? = null

    /** 当前主 frame 地址（会话恢复用）。 */
    private var currentUrl: String? = null

    /** 已构建、但内核可能尚未创建的 JBCefBrowser 实例。 */
    private var kernelBrowser: JBCefBrowser? = null

    /** 内核是否已就绪（就绪前不锁定任何"已尝试"状态）。 */
    private val kernelReady = java.util.concurrent.atomic.AtomicBoolean(false)

    private var kernelAttempts = 0
    private var retryScheduled = false

    /** 尺寸推送节流：待推送的尺寸、上次推送时间、尾随计时器。 */
    private var pendingPushWidth = 0
    private var pendingPushHeight = 0
    private var lastPushAtMillis = 0L
    private var sizePushTimer: Timer? = null

    /** 内核尚未就绪时暂存的目标地址；就绪后自动导航。 */
    private var pendingUrl: String? = null


    /** 仅在 [JBCefApp.isSupported] 且内核已就绪时非空；为空时所有导航入口统一给出提示。 */
    private var browser: JBCefBrowser? = null

    /** CEF 回调经 invokeLater 排队，释放后必须丢弃，否则会写已释放的 Swing 组件。 */
    private val disposed = AtomicBoolean(false)

    private val logger = Logger.getInstance("RiderMonkeyBrowser")

    /** 脚本库目录：<配置目录>/rider-monkey/scripts，存放 .user.js 文件；丢文件即导入。 */
    private val scriptStore: ScriptFileStore = ScriptStoreFactory.defaultStore()

    /** 书签存储（纯文本文件，用户可直接编辑）。 */
    private val bookmarks = BookmarkStore(ScriptStoreFactory.bookmarksFile())

    /** GM 存储与桥：脚本的 GM_setValue 系列通过它落盘。 */
    private val gmStorage = GmStorage(ScriptStoreFactory.storageDir())
    private val gmBridge = GmBridge(gmStorage)

    init {
        // GmBridge 不反向依赖 UI：这里把容器提供的能力注册进去。
        gmHost?.let { gmBridge.setHost(it) }
    }

    /** @resource 的本地缓存（后台抓取，注入时只读缓存，避免卡导航）。 */
    private val gmResources = ResourceCache(ScriptStoreFactory.resourcesDir())

    private companion object {

        /**
         * 全局串行化内核创建。
         *
         * 多个标签页同时创建 JCEF 内核既容易拿到空内核（见 [ensureKernel]），
         * 也是偶发卡死的可疑来源，因此同一时刻只允许一个创建流程。
         */
        val KERNEL_CREATION_LOCK = java.util.concurrent.atomic.AtomicBoolean(false)

        /** 最多尝试次数与重试间隔；12 × 200ms ≈ 2.4 秒。 */
        const val MAX_KERNEL_ATTEMPTS = 12
        const val KERNEL_RETRY_DELAY_MS = 200

        /** 尺寸推送的最小间隔；见 schedulePushSize 的说明。 */
        const val SIZE_PUSH_MIN_INTERVAL_MS = 100L

    }

    /** 上一次推给内核的尺寸，避免重复推送与刷日志。 */
    private var lastPushedWidth = -1
    private var lastPushedHeight = -1

    private var isLoading = false
    private var lastError: String? = null
    private var hasActivity = false

    /** 程序性写入地址栏期间置位，避免被当成用户输入。 */
    private var syncingAddress = false

    /**
     * 用户是否手动改过地址栏且尚未提交。
     *
     * 置位：用户在地址栏敲键（DocumentListener）。
     * 复位：程序性写入（[setAddressText]），或用户离开地址栏（焦点丢失，视为放弃编辑），
     *       或用户按回车提交。
     * 没有 focusLost 复位会导致「敲一个字符后，本会话所有链接跳转都不再同步地址栏」。
     */
    private var addressEditedByUser = false

    val component: JComponent get() = root

    init {
        root.add(createToolbar(), BorderLayout.NORTH)
        root.add(createBrowserComponent(), BorderLayout.CENTER)
        root.add(statusLabel, BorderLayout.SOUTH)
        resetNavigationState()
        renderStatus()
    }

    private fun createToolbar(): JComponent {
        // ToolWindow 通常停靠在侧边，横向空间很紧。用扁平紧凑按钮把宽度让给地址栏：
        // 标准 JButton 带边框和内边距时每个约 50px，4 个就吃掉 200px，地址栏只剩一百来像素。
        backButton.toolTipText = "后退"
        forwardButton.toolTipText = "前进"
        reloadButton.toolTipText = "刷新"
        stopButton.toolTipText = "停止加载"

        // 与导航按钮同一套外观：空边框 + 收紧留白 + hover/按下反馈，
        // 宽度随文案自适应（「编辑区」与「侧边栏」同为三字，切换时不会跳动）。
        editorButton.addActionListener { togglePlacement() }
        setPlacementInEditor(false)

        newTabButton.toolTipText = "新建标签页"
        newTabButton.addActionListener { onNewTab() }


        backButton.addActionListener { browser?.cefBrowser?.goBack() }
        forwardButton.addActionListener { browser?.cefBrowser?.goForward() }
        reloadButton.addActionListener {
            // 内核还没就绪时，刷新按钮兼作"重新尝试创建内核"的入口。
            if (!retryKernelIfNeeded()) browser?.cefBrowser?.reload()
        }
        stopButton.addActionListener { browser?.cefBrowser?.stopLoad() }

        // 书签：点开是一个弹出菜单（收藏当前页 / 打开已有书签 / 删除），
        // 而不是再加一个常在的列表——工具窗横向空间已经很紧。
        bookmarkButton.toolTipText = "书签（收藏当前页面 / 打开已有书签）"
        bookmarkButton.addActionListener { showBookmarks() }

        externalButton.toolTipText = "在系统浏览器中打开当前页"
        externalButton.addActionListener { openInSystemBrowser() }
        addressField.addActionListener { navigateFromAddressBar() }

        addressField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = markAddressEdited()
            override fun removeUpdate(e: DocumentEvent) = markAddressEdited()
            override fun changedUpdate(e: DocumentEvent) = markAddressEdited()
        })
        addressField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                // 用户离开地址栏即视为放弃未提交的编辑，之后导航结果可以正常回写。
                addressEditedByUser = false
            }
        })

        // 导航按钮靠左、编辑区按钮靠右。用 BorderLayout 而不是单个 FlowLayout：
        // FlowLayout 在宽度不够时会**静默把放不下的组件裁掉**（0.6.0 的实际故障），
        // BorderLayout 的 EAST 永远保留，宁可让中间的导航按钮收缩。
        // 紧凑靠左，**不要** GridLayout：GridLayout 会把按钮拉满整行，在中央编辑区（行很宽）时
        // 每个按钮被撑到 100px+，hover 反馈就变成一整块背景（0.8.0 实测到的「奇怪样式」）。
        // 按钮宽度已显式给到 26px 绕开了 L&F 的 82px 下限，六个按钮合计 156px，任何可用宽度都放得下。
        val navButtons = JPanel(FlowLayout(FlowLayout.LEFT, 1, 0))
        navButtons.add(backButton)
        navButtons.add(forwardButton)
        navButtons.add(reloadButton)
        navButtons.add(stopButton)
        navButtons.add(bookmarkButton)
        navButtons.add(externalButton)

        // 右侧两个按钮放进 EAST：BorderLayout 的 EAST 永远按 preferred 宽度保留，
        // 面板再窄也不会压缩它（代价是这部分宽度无条件从 CENTER 的导航按钮区扣走）。
        val rightButtons = JPanel(FlowLayout(FlowLayout.RIGHT, 1, 0))
        rightButtons.add(newTabButton)
        rightButtons.add(editorButton)

        val buttonsRow = JPanel(BorderLayout())
        buttonsRow.add(navButtons, BorderLayout.CENTER)
        buttonsRow.add(rightButtons, BorderLayout.EAST)

        // 侧边 ToolWindow 的横向空间太紧，一行塞不下「5 个按钮 + 有可用宽度的地址栏」，
        // 实测地址栏会被压到只剩几十像素。拆成两行：上行工具栏按钮，下行地址栏独占整宽。
        // 想换成「地址栏在上、按钮在下」只需调换下面两行的添加顺序。
        val bar = JPanel(GridLayout(2, 1, 0, 2))
        bar.border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
        bar.add(buttonsRow)
        bar.add(addressField)
        return bar
    }

    private fun createBrowserComponent(): JComponent {
        if (!JBCefApp.isSupported()) {
            // 只在中间区域提示，不再同时写状态栏，避免同一句话出现两次。
            return JBLabel("当前 IDE 或运行时不支持 JCEF，内嵌浏览器不可用。")
        }

        // 关键：这里**不能** setCreateImmediately(true)。
        //
        // 实测（0.2.1 的 [RM-SIZE] 日志）：组件宽度确实跟随工具窗变化（363→402→347），
        // 但网页仍按更宽的视口排版、出现横向滚动条。原因是内核在组件完成布局之前就被创建，
        // 初始视口取到了组件的首选尺寸 800x600。
        // 改为等组件真正显示、拿到非零尺寸后再创建内核并注册回调。
        val created = JBCefBrowser.createBuilder()
            .setUrl(UrlUtils.START_PAGE)
            .build()

        Disposer.register(this, created)

        kernelBrowser = created
        val component = created.component
        component.addComponentListener(object : ComponentAdapter() {
            override fun componentShown(e: ComponentEvent) {
                ensureKernel()
                schedulePushSize(created)
            }

            override fun componentResized(e: ComponentEvent) {
                ensureKernel()
                schedulePushSize(created)
            }
        })

        return component
    }

    /**
     * 创建 JCEF 内核并按需注册回调。
     *
     * **不能"尝试过就锁定状态"**。0.13.0 的写法是进入方法就置 \`started = true\`，
     * 而 \`createImmediately()\` 在并发创建（例如连续新增标签页）时**不保证同步返回内核**——
     * 一旦返回 null 就永久置为「内核尚未就绪」且不再重试，那个标签页从此刷不出页面
     * （用户实测：新增标签页偶现该提示）。
     *
     * 现在：拿到内核才置 ready；没拿到就**有限次、带延迟**地重试；耗尽后给出可手动恢复的提示。
     * 创建过程用全局锁**串行化**，避免多个标签页同时创建内核（也是卡死风险的来源之一）。
     */
    private fun ensureKernel() {
        // 已释放的面板绝不能再创建内核。
        // `scheduleKernelRetry()` 的 Timer 回调**不经过 onEdt**（那里才有 disposed 检查），
        // 面板被 dispose 后它仍可能醒来；此时继续 createImmediately() 会造出一个
        // **没有任何人持有的 CEF 内核** —— 幽灵 target + 内存泄漏。
        if (disposed.get()) return
        if (kernelReady.get()) return
        val created = kernelBrowser ?: return
        val component = created.component
        if (component.width <= 0 || component.height <= 0) return

        if (!KERNEL_CREATION_LOCK.compareAndSet(false, true)) {
            // 另一个标签页正在创建内核：排队稍后重试，不并发创建。
            scheduleKernelRetry()
            return
        }
        try {
            kernelAttempts++
            logger.info(
                "[RM-BROWSER] 面板#" + System.identityHashCode(this) +
                    " 创建内核（第 " + kernelAttempts + " 次尝试，" +
                    component.width + "x" + component.height + "）"
            )
            created.createImmediately()

            // 注意：这里显式调用 getJBCefClient()。Kotlin 对 getJBCefClient 推导出的属性名是
            // JBCefClient（前两个字母都是大写时不剥离），直接写 jcefClient 会编译失败。
            val cef: CefBrowser? = created.cefBrowser
            if (cef == null) {
                if (kernelAttempts < MAX_KERNEL_ATTEMPTS) {
                    lastError = "正在初始化浏览器内核…"
                    renderStatus()
                    logger.info("[RM-BROWSER] 内核尚未就绪，稍后重试")
                    scheduleKernelRetry()
                } else {
                    lastError = "浏览器内核创建失败（已尝试 " + kernelAttempts + " 次）；点刷新或回车重试"
                    renderStatus()
                    logger.info("[RM-BROWSER] 内核创建失败，已停止自动重试")
                }
                return
            }

            kernelReady.set(true)
            logger.info("[RM-BROWSER] 面板#" + System.identityHashCode(this) + " 内核就绪")
            created.getJBCefClient().addLoadHandler(LoadHandler(), cef)
            created.getJBCefClient().addDisplayHandler(TitleHandler(), cef)
            created.getJBCefClient().addDisplayHandler(UrlHandler(), cef)
            created.getJBCefClient().addDisplayHandler(TooltipHandler(), cef)
            created.getJBCefClient().addLifeSpanHandler(PopupHandler(), cef)

            // 会话恢复：标签页创建时可能内核还没就绪，这里补上那次导航。
            pendingUrl?.let { target ->
                pendingUrl = null
                setAddressText(target)
                cef.loadURL(target)
            }

            // 用户脚本注入器：每次导航到已提交的文档时，按 URL 匹配并注入。
            // 全局暂停时不给注入器任何脚本：这是「脚本改坏页面」时的逃生出口。
            val injector = ScriptInjector(
                created,
                { if (scriptStore.isPaused()) emptyList() else scriptStore.loadCached() },
                gmStorage,
                gmResources,
                gmBridge
            )
            injector.attach()
            // 注意：这里在匿名 ComponentAdapter 内部，this 指的是适配器本身，必须限定。
            Disposer.register(this@BrowserPanel, injector)

            browser = created
            setAddressText(UrlUtils.START_PAGE)
            resetNavigationState()
            schedulePushSize(created)
        } finally {
            KERNEL_CREATION_LOCK.set(false)
        }
    }

    /** 延迟重试内核创建。用 Swing Timer 在 EDT 上触发，避免忙等。 */
    private fun scheduleKernelRetry() {
        if (kernelReady.get() || retryScheduled) return
        retryScheduled = true
        Timer(KERNEL_RETRY_DELAY_MS) {
            retryScheduled = false
            ensureKernel()
        }.apply {
            isRepeats = false
            start()
        }
    }

    /**
     * 手动重试入口（刷新按钮 / 地址栏回车）。
     *
     * @return true 表示当前正在等待内核，调用方不应再走普通导航分支
     */
    private fun retryKernelIfNeeded(): Boolean {
        if (kernelReady.get()) return false
        kernelAttempts = 0
        ensureKernel()
        return true
    }

    /**
     * 直接导航到指定地址（会话恢复、书签「在当前标签页打开」都走这里）。
     *
     * 内核未就绪时先记下地址，并驱动一次内核重试，就绪后自动执行。
     *
     * 为什么不能只记下地址就返回：内核是延迟创建的（见 [createBrowserComponent] 里不能
     * `setCreateImmediately(true)` 的说明），刚新建的标签页里 [browser] 为 null；只存
     * [pendingUrl] 的话，本次导航能否发生就完全取决于「之后还会不会再来一次 componentResized」。
     *
     * 边界（如实记录，勿夸大）：组件尺寸仍为 0 时 [ensureKernel] 会在尺寸检查处直接 return，
     * **既不创建也不排程**，因此该场景依然要等后续组件事件。驱动重试是把「内核已在创建流程中」
     * 这一类从被动等待变为主动推进，并**没有**消除对组件事件的依赖。
     */
    fun navigateTo(url: String) {
        val target = url.trim()
        if (target.isEmpty()) return
        logger.info("[RM-BROWSER] 程序化导航 -> " + target)
        val cef = browser?.cefBrowser
        if (cef == null) {
            pendingUrl = target
            setAddressText(target)
            // 返回值此处用不上：本分支无论重试结果如何都要 return。
            // 顺序要紧——pendingUrl 必须先设好，ensureKernel 同步成功时会立刻消费它。
            retryKernelIfNeeded()
            return
        }
        setAddressText(target)
        cef.loadURL(target)
    }

    /** 当前主 frame 地址（会话恢复用）。 */
    fun currentUrl(): String? = currentUrl ?: addressField.text?.takeIf { it.isNotBlank() }

    /** 标签页文案：优先页面标题，其次当前地址。 */
    fun tabTitle(): String =
        pageTitle?.takeIf { it.isNotBlank() }
            ?: addressField.text?.takeIf { it.isNotBlank() && it != "about:blank" }
            ?: "新标签页"

    /** 由容器回填已知标题（例如标签页从编辑区移回侧边栏时）。 */
    fun memorizeTitle(title: String) {
        if (title.isNotBlank()) pageTitle = title
    }

    /** 容器位置变化时刷新按钮文案：在编辑区时，点它是「回侧边栏」。 */
    fun setPlacementInEditor(inEditor: Boolean) {
        editorButton.text = if (inEditor) "侧边栏" else "编辑区"
        // 文字按钮的尺寸同样显式给出（绕开 L&F 的 82px 下限），宽度随文案自适应。
        editorButton.preferredSize = Dimension(
            editorButton.getFontMetrics(editorButton.font).stringWidth(editorButton.text) + 16,
            iconButtonHeight()
        )
        editorButton.toolTipText = if (inEditor) {
            "移回侧边栏（工具窗）"
        } else {
            "在中央编辑区打开（编辑区更宽，适合看网页）"
        }
    }


    /** 图标按钮的高度（随图标尺寸，避免 HiDPI 下被压扁）。 */
    private fun iconButtonHeight(): Int = (backButton.icon?.iconHeight ?: 16) + 6

    /** 接收主 frame 地址变化，用于会话恢复。 */
    private inner class UrlHandler : CefDisplayHandlerAdapter() {
        override fun onAddressChange(cefBrowser: CefBrowser?, frame: CefFrame?, url: String?) {
            if (frame == null || !frame.isMain) return
            val text = url?.trim().orEmpty()
            if (text.isEmpty()) return
            currentUrl = text
            onUrlChanged(text)
        }
    }

    /** 接收页面标题，用于刷新标签页文案。 */
    private inner class TitleHandler : CefDisplayHandlerAdapter() {
        override fun onTitleChange(cefBrowser: CefBrowser?, title: String?) {
            val text = title?.trim().orEmpty()
            if (text.isEmpty()) return
            pageTitle = text
            onTitleChanged(text)
        }
    }

    /**
     * 把页面里的 HTML `title` 提示转成 Swing 提示（悬停显示完整内容）。
     *
     * **为什么必须自己实现**：CEF **不自己绘制** tooltip，而是通过
     * `CefDisplayHandler.onTooltip` 把文本交给**宿主 UI** 去显示。JCEF 在 IntelliJ 里走
     * windowless(OSR) 渲染、没有原生窗口，而 IntelliJ 的 `JBCefClient` 只是把这个回调转发出来
     * （`JBCefClient$3.onTooltip`）—— `com.intellij.ui.jcef` 整个包里**没有任何一处调用
     * `setToolTipText`**，也就是说它不会替你显示。
     *
     * 不实现它，页面里 `<a title="…">` 这类提示在插件内**完全不出现**。
     * 用户实测：帖子标题被 CSS 省略后，普通浏览器悬停可看全，插件里不能。
     */
    private inner class TooltipHandler : CefDisplayHandlerAdapter() {

        /** 只在提示内容变化时记一条日志，避免悬停刷屏。 */
        private var lastTooltip: String? = null

        override fun onTooltip(cefBrowser: CefBrowser?, text: String?): Boolean {
            val target = browser?.component ?: return false
            val desired = text?.takeIf { it.isNotBlank() }
            // **必须写遍整棵子树**：Swing 的 ToolTipManager 只认「鼠标事件落在哪个组件」上的
            // toolTipText，而 JCEF 的 OSR 实现里鼠标事件落在内层画布上；写在外层容器上不会弹。
            // 2026-10-14 实测：只写 browser.component 时提示不出现，即由此而来。
            applyTooltip(target, desired)
            if (desired != null && desired != lastTooltip) {
                lastTooltip = desired
                logger.info(
                    "[RM-BROWSER] tooltip -> " +
                        (if (desired.length > 60) desired.take(60) + "…" else desired)
                )
            }
            // true = 已由我们处理。
            return true
        }
    }

    /** 把提示文本写到该组件**及其所有子孙**上（Swing 只认鼠标所在那一层的 toolTipText）。 */
    private fun applyTooltip(component: java.awt.Component, text: String?) {
        if (component is JComponent && component.toolTipText != text) {
            component.toolTipText = text
        }
        if (component is java.awt.Container) {
            component.components.forEach { applyTooltip(it, text) }
        }
    }

    /** 供外部（脚本管理）触发重新加载，使脚本启停立刻生效。 */
    fun reloadPage() {
        if (retryKernelIfNeeded()) return
        browser?.cefBrowser?.reload()
    }

    private fun navigateFromAddressBar() {
        val target = browser
        if (target == null) {
            if (!JBCefApp.isSupported()) {
                lastError = "当前环境不支持内嵌浏览器。"
                renderStatus()
                return
            }
            // 内核可能仍在创建中（新增标签页时并发创建会更慢）。这里记下地址并立刻重试，
            // 不再直接给出"导航不可用"的死结论。
            UrlUtils.normalizeInput(addressField.text)?.let { pendingUrl = it }
            lastError = "正在初始化浏览器内核…"
            renderStatus()
            retryKernelIfNeeded()
            return
        }

        val url = UrlUtils.normalizeInput(addressField.text)
        if (url == null) {
            lastError = "无法识别的地址：请输入完整 URL 或域名"
            renderStatus()
            return
        }

        setAddressText(url)
        lastError = null
        hasActivity = true
        isLoading = true
        renderStatus()
        // 冻结排查用：这一行之后如果没有 onLoadStart，说明卡在导航调用本身。
        logger.info("[RM-BROWSER] 地址栏导航 -> " + url)
        target.loadURL(url)
    }

    /**
     * 在系统浏览器中打开当前页。
     *
     * 存在的理由：JCEF 并不总能访问目标站点。实测 IDEA 的 JCEF 默认带
     * `--disable-site-isolation-trials`，跨域 iframe 与顶层文档同进程，Cloudflare 的 Turnstile
     * 永远无法完成验证（详见 ADR-0015），此时内嵌浏览器对该站点等于不可用。
     *
     * 这条通道是把地址交给系统默认浏览器，**绕开**整个 JCEF 层——`--disable-site-isolation-trials`
     * 是 CEF 进程级参数，插件既不能设置也不能覆盖，所以只能绕开，修不了。
     */
    private fun openInSystemBrowser() {
        val target = currentUrl()?.trim().orEmpty()
        if (target.isEmpty() || target == UrlUtils.START_PAGE) {
            lastError = "当前页没有可打开的地址"
            renderStatus()
            return
        }
        browseExternally(target)
    }

    /**
     * 把地址交给系统默认浏览器。
     *
     * 两个调用方共用：工具栏按钮（当前页）与**弹窗兜底**（宿主未装配时）。
     */
    private fun browseExternally(target: String) {
        val url = target.trim()
        if (url.isEmpty()) return
        runCatching { BrowserUtil.browse(url) }
            .onSuccess {
                logger.info("[RM-BROWSER] 已交给系统浏览器 -> " + url)
                lastError = "已在系统浏览器中打开"
                renderStatus()
            }
            .onFailure {
                logger.info("[RM-BROWSER] 打开系统浏览器失败：" + it.message)
                lastError = "打开系统浏览器失败：" + it.message
                renderStatus()
            }
    }

    /**
     * 让 CEF 内核尺寸跟随 Swing 组件。
     *
     * 实测问题：面板变窄后网页仍按原宽度渲染，出现横向滚动条、内容不跟随窗口大小。
     * JBCefBrowser 内部虽然注册了 ComponentListener，但内核创建于组件尚未完成布局时，
     * 拿到的可能是旧尺寸；这里在组件显示/尺寸变化时显式把当前尺寸推给内核。
     *
     * 同时记录一次尺寸日志：若组件尺寸本身正确而网页仍不适配，说明问题在
     * 内核渲染缩放而非 Swing 布局，可据此继续定位。
     */
    /**
     * 请求把组件尺寸推给内核（**节流**）。
     *
     * 2026-10-13 的日志给出了硬证据：窗口逐步长大时，`componentResized` 会对每个尺寸变化都触发一次，
     * 而原来的去重只在「尺寸与上次完全相同」时才跳过——逐步长大完全绕过它，
     * 结果 1.5 秒内产生了**近百次** JCEF 调用（`wasResized` + `notifyScreenInfoChanged`，
     * 当时还带一次 `executeJavaScript` 探针），而且全部在 **EDT** 上 → UI 冻住。
     *
     * 现在改为前缘 + 尾缘节流：距上次推送超过 [SIZE_PUSH_MIN_INTERVAL_MS] 就立即推，
     * 否则合并成一次尾随推送。拖动窗口时仍然跟手（每秒最多 10 次），但不会出现风暴。
     */
    private fun schedulePushSize(created: JBCefBrowser) {
        val component = created.component
        val width = component.width
        val height = component.height
        if (width <= 0 || height <= 0) return
        pendingPushWidth = width
        pendingPushHeight = height

        val now = System.currentTimeMillis()
        val sinceLast = now - lastPushAtMillis
        if (sinceLast >= SIZE_PUSH_MIN_INTERVAL_MS) {
            pushSizeNow(created)
        } else if (sizePushTimer?.isRunning != true) {
            sizePushTimer = Timer((SIZE_PUSH_MIN_INTERVAL_MS - sinceLast).toInt()) {
                pushSizeNow(created)
            }.apply {
                isRepeats = false
                start()
            }
        }
    }

    private fun pushSizeNow(created: JBCefBrowser) {
        val width = pendingPushWidth
        val height = pendingPushHeight
        if (width <= 0 || height <= 0) return
        if (width == lastPushedWidth && height == lastPushedHeight) return
        lastPushedWidth = width
        lastPushedHeight = height
        lastPushAtMillis = System.currentTimeMillis()
        // 尺寸推送是高频事件（拖拽窗口时每次重绘都会触发），降为 DEBUG。
        logger.debug("[RM-BROWSER] 推送内核尺寸 " + width + "x" + height)
        runCatching {
            val cef = created.cefBrowser
            cef.wasResized(width, height)
            cef.notifyScreenInfoChanged()
        }.onFailure { logger.info("[RM-BROWSER] 推送内核尺寸失败（可能已释放）：" + it.message) }
    }



    /**
     * 书签弹出菜单。
     *
     * 每次打开都重新读盘（书签文件用户可以直接编辑，读缓存反而会看不到改动）。
     * 当前页已收藏时，第一项变成「取消收藏」。
     */
    private fun showBookmarks() {
        val items = bookmarks.load()
        val current = currentUrl()?.trim().orEmpty().takeIf { it.isNotEmpty() && it != "about:blank" }
        val starred = current != null && bookmarks.contains(items, current)

        val popup = JPopupMenu()
        if (current != null) {
            popup.add(JMenuItem(if (starred) "★ 取消收藏当前页面" else "☆ 收藏当前页面").apply {
                addActionListener {
                    val next = if (starred) {
                        bookmarks.without(items, current)
                    } else {
                        bookmarks.withAdded(items, defaultBookmarkTitle(current), current)
                    }
                    bookmarks.save(next)
                    lastError = if (starred) "已取消收藏" else "已收藏：" + defaultBookmarkTitle(current)
                    renderStatus()
                }
            })
            popup.addSeparator()
        }

        if (items.isEmpty()) {
            popup.add(JMenuItem("（暂无书签）").apply { isEnabled = false })
        } else {
            for (item in items) {
                // 每个书签做成子菜单：打开方式与删除都在里面，避免菜单本身被撑得过宽。
                val group = JMenu(menuLabel(item.title))
                group.toolTipText = item.url
                group.add(JMenuItem("在当前标签页打开").apply {
                    // 必须走 navigateTo，**不能**写成 browser?.cefBrowser?.loadURL()：
                    // 新标签页的内核是延迟创建的，刚新建时 browser 还是 null，安全调用会把
                    // 「内核未就绪」静默吞掉，表现就是点了完全没反应。
                    // 同菜单里的「在新标签页打开」走 openTab -> navigateTo，所以那条一直正常。
                    addActionListener { navigateTo(item.url) }
                })
                group.add(JMenuItem("在新标签页打开").apply {
                    addActionListener { gmHost?.openTab(item.url, true) }
                })
                group.addSeparator()
                group.add(JMenuItem("重命名…").apply {
                    addActionListener {
                        val renamed = Messages.showInputDialog(
                            project,
                            "书签名称",
                            "重命名书签",
                            null,
                            item.title,
                            null
                        )
                        if (renamed != null) {
                            bookmarks.save(bookmarks.withRenamed(items, item.url, renamed))
                            lastError = "已重命名书签"
                            renderStatus()
                        }
                    }
                })
                group.add(JMenuItem("删除").apply {
                    addActionListener {
                        bookmarks.save(bookmarks.without(items, item.url))
                        lastError = "已删除书签：" + item.title
                        renderStatus()
                    }
                })
                popup.add(group)
            }
        }
        popup.show(bookmarkButton, 0, bookmarkButton.height)
    }

    /**
     * 书签默认标题：**只取域名**（去掉 www.）。
     *
     * 为什么不用页面标题：换皮脚本会改写 document.title 做伪装，默认名会变成
     * 「topic_cache.rs — platform」这类看不出所以然的东西，反而要用户手工改。
     * 域名解析不出来时回落成完整 URL —— 宁可难看，也不要空标题。
     */
    private fun defaultBookmarkTitle(url: String): String {
        val host = ConnectRules.hostOf(url)?.removePrefix("www.")
        return if (host.isNullOrEmpty()) url else host
    }

    /** 弹出菜单里的书签文案：过长会把菜单撑得比工具窗还宽。 */
    private fun menuLabel(title: String): String =
        if (title.length <= 36) title else title.take(36) + "…"

    private fun markAddressEdited() {
        if (!syncingAddress) {
            addressEditedByUser = true
        }
    }

    private fun setAddressText(text: String) {
        syncingAddress = true
        try {
            addressField.text = text
        } finally {
            syncingAddress = false
        }
        addressEditedByUser = false
    }

    private fun renderStatus() {
        statusLabel.text = when {
            lastError != null -> lastError
            isLoading -> "加载中…"
            hasActivity -> "完成"
            else -> " "
        }
    }

    private fun resetNavigationState() {
        backButton.isEnabled = false
        forwardButton.isEnabled = false
        reloadButton.isEnabled = browser != null
        stopButton.isEnabled = false
    }

    override fun dispose() {
        // JBCefBrowser 是 this 的子 disposable，Disposer 会负责真正的释放。
        // 这里置位 disposed 拦住已排队的 EDT 任务，并解除引用避免回调再触碰视图。
        disposed.set(true)
        browser = null
        logger.info("[RM-BROWSER] 面板#" + System.identityHashCode(this) + " 已释放")
    }

    /**
     * 接管页面自己弹出的窗口（`target="_blank"` 链接、`window.open`）。
     *
     * **不接管会怎样**：走 CEF 默认弹窗路径，那个窗口不在本插件的标签页模型里、也没有加载链路，
     * 用户看到的就是一个不知名空白窗口（实测反馈）。
     *
     * 三个必须守住的点：
     * - **返回 true = 取消 CEF 建这个弹窗**，随后由我们自己开标签页，否则会两个都出来。
     * - **回调在 CEF 线程**：开标签页动的是 Swing（JBTabs / TabInfo），必须切回 EDT（走 [onEdt]）。
     * - **只覆盖 onBeforePopup**：JCEF 对这类回调是 any-true 聚合语义，动 doClose 会干扰
     *   IntelliJ 自己的生命周期处理。
     *
     * 已知代价（如实记录）：本构建的 `onBeforePopup` 只有 `(browser, frame, targetUrl, targetFrameName)`
     * 四个参数，**拿不到弹窗浏览器句柄**，所以 `w = window.open(); w.document.write(...)` 这类流程
     * 的返回值语义救不回来 —— 这类弹窗（无真实地址）走 [PopupDecision.Ignore]，不拦截。
     */
    private inner class PopupHandler : CefLifeSpanHandlerAdapter() {

        override fun onBeforePopup(
            cefBrowser: CefBrowser?,
            frame: CefFrame?,
            targetUrl: String?,
            targetFrameName: String?
        ): Boolean {
            val decision = PopupRouting.decide(targetUrl)
            // targetFrameName 一并记下：它是排查「谁弹的」最直接的线索。
            logger.info(
                "[RM-BROWSER] 弹窗请求 target=" + clip(targetUrl) +
                    " frameName=" + (targetFrameName ?: "-") +
                    " 决策=" + decision::class.simpleName
            )
            return when (decision) {
                // 无真实地址时：拦截会把对方的 window 句柄变成 null；
                // 不认识的协议时：不替用户去调系统处理器。两种情况都放行 CEF 默认行为。
                is PopupDecision.Ignore -> false
                is PopupDecision.OpenTab -> {
                    openUrlInNewTabSafely(decision.url)
                    true
                }
                is PopupDecision.SystemBrowser -> {
                    onEdt { browseExternally(decision.url) }
                    true
                }
            }
        }

        private fun clip(text: String?): String {
            val value = text?.trim().orEmpty()
            if (value.isEmpty()) return "(空)"
            return if (value.length > 120) value.take(120) + "…" else value
        }
    }

    /**
     * 为新窗口开一个插件标签页；宿主未装配时退到系统浏览器。
     *
     * 走 [onEdt] 而不是裸 invokeLater：既保证在 EDT，又带上 `disposed` 守卫与
     * `ModalityState.any()`（模态对话框弹出时不会把这次开标签页排在模态之后）。
     */
    private fun openUrlInNewTabSafely(url: String) {
        onEdt {
            val open = openUrlInNewTab
            if (open == null) {
                logger.info("[RM-BROWSER] 弹窗没有标签页宿主，改用系统浏览器 -> " + url)
                browseExternally(url)
                return@onEdt
            }
            open(url)
            logger.info("[RM-BROWSER] 弹窗已开成标签页 -> " + url)
        }
    }

    private inner class LoadHandler : CefLoadHandler {

        override fun onLoadingStateChange(
            cefBrowser: CefBrowser,
            isLoading: Boolean,
            canGoBack: Boolean,
            canGoForward: Boolean
        ) {
            onEdt {
                backButton.isEnabled = canGoBack
                forwardButton.isEnabled = canGoForward
                stopButton.isEnabled = isLoading
                hasActivity = true
                this@BrowserPanel.isLoading = isLoading
                renderStatus()
            }
        }

        override fun onLoadStart(
            cefBrowser: CefBrowser,
            frame: CefFrame,
            transitionType: CefRequest.TransitionType
        ) {
            if (!frame.isMain) return
            onEdt {
                // 只有新一轮主帧加载才清错误；子 frame 的动态加载不该抹掉主帧的错误状态。
                lastError = null
                renderStatus()
            }
        }

        override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
            if (!frame.isMain) return
            val url = frame.url
            onEdt {
                // 用户手动改过地址栏就尊重用户的输入，重定向/站内跳转不再覆写。
                if (!addressEditedByUser) {
                    setAddressText(url)
                }
                if (httpStatusCode >= 400) {
                    lastError = "HTTP " + httpStatusCode
                    renderStatus()
                }
            }
        }

        override fun onLoadError(
            cefBrowser: CefBrowser,
            frame: CefFrame,
            errorCode: CefLoadHandler.ErrorCode,
            errorText: String,
            failedUrl: String
        ) {
            if (!frame.isMain) return
            // 用户点「停止加载」或被新导航取代都会产生 ERR_ABORTED，这不是错误。
            if (errorCode == CefLoadHandler.ErrorCode.ERR_ABORTED) {
                onEdt {
                    isLoading = false
                    renderStatus()
                }
                return
            }
            onEdt {
                isLoading = false
                lastError = "加载失败：" + errorText
                renderStatus()
            }
        }
    }

    private fun onEdt(action: () -> Unit) {
        // CEF 回调不在 EDT 上，所有 Swing 改动都必须切回 EDT。
        // 显式传 ModalityState.any()，避免回调被模态对话框挡住导致状态栏滞后。
        // 注意：262 的 Application 没有 invokeLater(Runnable, ModalityState, Disposable) 重载
        // （三参版本是 Condition<?> 过期条件），因此「释放后不再执行」由 disposed 标志保证。
        ApplicationManager.getApplication().invokeLater({
            if (!disposed.get()) {
                action()
            }
        }, ModalityState.any())
    }
}