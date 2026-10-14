package com.ridermonkey.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.ridermonkey.userscript.ScriptUpdates
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.ridermonkey.browser.BrowserContainers
import com.ridermonkey.storage.ScriptFileStore
import com.ridermonkey.storage.ScriptStoreFactory
import com.ridermonkey.userscript.GmCapabilities
import com.ridermonkey.userscript.UserscriptEntry
import com.ridermonkey.userscript.UserscriptMatcher
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JToggleButton
import javax.swing.table.AbstractTableModel

/**
 * 脚本管理面板：列出脚本库里的脚本，支持启用/禁用、刷新、打开目录、把脚本移出库。
 *
 * 首版刻意不引入平台的文件选择器：脚本库本身就是「一个文件夹」，导入 = 把文件丢进去，
 * 这样导入路径与「文件夹即脚本库」的存储设计保持一致，也少一层 API 依赖。
 *
 * 表格列：启用（可勾选）/ 脚本 / 匹配规则 / 备注。
 * 备注列会明确提示两类「装了却不会执行」的情况——没有 URL 规则、以及已禁用，
 * 避免用户一头雾水。
 *
 * @param project 预留：后续接入导入向导与工程级服务时使用。
 */
class ScriptManagerPanel(private val project: Project) : Disposable {

    private val store: ScriptFileStore = ScriptStoreFactory.defaultStore()
    private val root = JPanel(BorderLayout())
    private val model = ScriptTableModel()
    private val table = JTable(model)
    private val hint = javax.swing.JLabel(" ")

    val component: JComponent get() = root

    init {
        root.border = BorderFactory.createEmptyBorder(6, 6, 6, 6)

        // 图标按钮：窄工具窗里文字按钮放不下（0.12.0 实测「打开目录 / 移出 / 编辑」被裁掉）。
        // ToolbarButton 会显式给出尺寸，绕开这个 L&F 的 82px 最小宽。
        val refreshButton = ToolbarButton(AllIcons.Actions.Refresh)
        refreshButton.toolTipText = "刷新脚本库"
        refreshButton.addActionListener { reload() }

        val openDirButton = ToolbarButton(AllIcons.Nodes.Folder)
        openDirButton.toolTipText = "打开脚本目录"
        openDirButton.addActionListener { openScriptsDir() }

        val moveOutButton = ToolbarButton(AllIcons.Actions.GC)
        moveOutButton.toolTipText = "移出脚本库（不删除，移动到 trash 子目录）"
        moveOutButton.addActionListener { moveSelectionToTrash() }

        // 编辑：脚本就是磁盘上的真实文件，直接用 IDE 的编辑器打开（不自己造编辑器）。
        val editButton = ToolbarButton(AllIcons.Actions.Edit)
        editButton.toolTipText = "用编辑器打开选中脚本（也可双击行）"
        editButton.addActionListener { openSelectedInEditor() }

        // 检查更新：只对声明了 @updateURL 的脚本生效；联网+写盘都要用户触发。
        val updateButton = ToolbarButton(AllIcons.Actions.Download)
        updateButton.toolTipText = "检查脚本更新（@updateURL）"
        updateButton.addActionListener { checkUpdates() }

        // 全局暂停：脚本把页面改坏（例如盖住 Cloudflare 验证控件）时的逃生出口。
        // 放在脚本管理里而不是浏览器工具栏，是为了让「脚本启停」只在同一个地方管。
        val pauseToggle = JToggleButton("暂停全部脚本")
        pauseToggle.border = BorderFactory.createEmptyBorder()
        pauseToggle.isFocusPainted = false
        pauseToggle.margin = java.awt.Insets(2, 6, 2, 6)
        pauseToggle.isSelected = store.isPaused()
        pauseToggle.toolTipText = "暂停后立即重新加载页面；状态会持久化"
        pauseToggle.addActionListener {
            store.setPaused(pauseToggle.isSelected)
            BrowserContainers.get(project)?.reloadAllTabs()
            reload()
        }

        // 图标按钮用 GridLayout 均分：FlowLayout 在宽度不够时会**静默裁掉**后续按钮，
        // 而这里被裁掉的全是功能入口（0.12.0 实测：打开目录 / 移出 / 编辑 三个一起消失）。
        // GridLayout 只会让每个按钮变窄，任何宽度下五个都在。
        val actions = JPanel(GridLayout(1, 5, 2, 0))
        actions.add(refreshButton)
        actions.add(openDirButton)
        actions.add(editButton)
        actions.add(updateButton)
        actions.add(moveOutButton)

        // 暂停开关按 preferred 宽度固定右侧，同样不会被裁。
        val pauseRow = JPanel(BorderLayout())
        pauseRow.add(actions, BorderLayout.CENTER)
        pauseRow.add(pauseToggle, BorderLayout.EAST)

        // 右键菜单兜底：即使窗口窄到按钮很小，所有操作仍然可达。
        val popup = JPopupMenu()
        popup.add(JMenuItem("编辑").apply { addActionListener { openSelectedInEditor() } })
        popup.add(JMenuItem("移出脚本库").apply { addActionListener { moveSelectionToTrash() } })
        popup.addSeparator()
        popup.add(JMenuItem("打开脚本目录").apply { addActionListener { openScriptsDir() } })
        popup.add(JMenuItem("检查更新").apply { addActionListener { checkUpdates() } })
        popup.add(JMenuItem("刷新").apply { addActionListener { reload() } })
        table.componentPopupMenu = popup

        table.setShowGrid(false)
        table.rowHeight = table.rowHeight + 4
        // 双击直接打开脚本编辑，符合表格的直觉操作。
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openSelectedInEditor()
            }

            override fun mousePressed(e: MouseEvent) = selectRowUnderCursor(e)

            override fun mouseReleased(e: MouseEvent) = selectRowUnderCursor(e)

            /** 右键先选中命中的行，否则菜单会作用在旧选中项上。 */
            private fun selectRowUnderCursor(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val row = table.rowAtPoint(e.point)
                if (row >= 0) table.setRowSelectionInterval(row, row)
            }
        })

        root.add(pauseRow, BorderLayout.NORTH)
        root.add(JScrollPane(table), BorderLayout.CENTER)
        root.add(hint, BorderLayout.SOUTH)

        reload()
    }

    /**
     * 检查脚本更新（`@updateURL`）。
     *
     * 只在用户点按钮/菜单时才联网 —— `@updateURL` 是外部地址，定时静默联网既不可预期，
     * 也会把 IDE 启动拖慢。
     *
     * 发现新版本后**先报告、由用户确认**才写盘；写盘前旧版自动备份为 `<名字>.user.js.bak`
     * （`ScriptFileStore.load` 只收集 `*.user.js`，备份不会被当成脚本读入）。
     */
    private fun checkUpdates() {
        val targets = store.load().filter { it.metadata.updateURL != null }
        if (targets.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "脚本库里没有任何脚本声明 @updateURL，无从检查更新。",
                "检查脚本更新"
            )
            return
        }
        val updates = ScriptUpdates(store)
        // 网络请求绝不能占 EDT，放到平台线程池。
        ApplicationManager.getApplication().executeOnPooledThread {
            val lines = mutableListOf<String>()
            val applicable = mutableListOf<Pair<UserscriptEntry, ScriptUpdates.CheckResult.UpdateAvailable>>()
            for (entry in targets) {
                when (val result = updates.check(entry)) {
                    is ScriptUpdates.CheckResult.UpdateAvailable -> {
                        applicable += entry to result
                        lines += "• " + entry.displayName + "：" +
                            (entry.metadata.version ?: "未声明") + " → " + result.remoteVersion
                    }
                    is ScriptUpdates.CheckResult.UpToDate ->
                        lines += "• " + entry.displayName + "：已是最新（" + result.localVersion + "）"
                    is ScriptUpdates.CheckResult.Failed ->
                        lines += "• " + entry.displayName + "：检查失败 - " + result.message
                    ScriptUpdates.CheckResult.NotDeclared -> Unit
                }
            }
            ApplicationManager.getApplication().invokeLater {
                if (applicable.isEmpty()) {
                    Messages.showInfoMessage(project, lines.joinToString("\n"), "检查脚本更新")
                    return@invokeLater
                }
                val confirmed = Messages.showYesNoDialog(
                    project,
                    lines.joinToString("\n") + "\n\n是否现在应用这些更新？（旧版本会备份为 .user.js.bak）",
                    "检查脚本更新",
                    null
                )
                if (confirmed != Messages.YES) return@invokeLater
                val failures = mutableListOf<String>()
                for ((entry, update) in applicable) {
                    updates.apply(entry, update.remoteBody)?.let { failures += entry.displayName + "：" + it }
                }
                reload()
                BrowserContainers.get(project)?.reloadAllTabs()
                if (failures.isEmpty()) {
                    Messages.showInfoMessage(project, "已更新 " + applicable.size + " 个脚本。", "检查脚本更新")
                } else {
                    Messages.showWarningDialog(project, failures.joinToString("\n"), "部分更新失败")
                }
            }
        }
    }

    private fun reload() {
        store.invalidate()
        val entries = store.load()
        model.setEntries(entries)
        hint.text = if (store.isPaused()) {
            "已暂停全部脚本 · 脚本库：" + store.scriptsDir()
        } else {
            "脚本库：" + store.scriptsDir() + "   （把 .user.js 放进去后点「刷新」）"
        }
    }

    /** 用 IDE 编辑器打开选中的脚本文件。 */
    private fun openSelectedInEditor() {
        val row = table.selectedRow
        if (row < 0) {
            hint.text = "请先选中一个脚本"
            return
        }
        val entry = model.entryAt(row) ?: return
        val file = LocalFileSystem.getInstance().findFileByPath(entry.filePath)
        if (file == null) {
            hint.text = "找不到脚本文件：" + entry.filePath
            return
        }
        FileEditorManager.getInstance(project).openFile(file, true)
        hint.text = "已在编辑器打开：" + entry.id
    }

    private fun openScriptsDir() {
        try {
            val dir = store.scriptsDir()
            Files.createDirectories(dir)
            Desktop.getDesktop().open(dir.toFile())
        } catch (t: Throwable) {
            hint.text = "无法打开目录：" + t.message
        }
    }

    /**
     * 把选中的脚本移出脚本库。
     *
     * 注意不是删除：文件被移动到脚本库下的 trash 子目录，用户可自行找回。
     */
    private fun moveSelectionToTrash() {
        val row = table.selectedRow
        if (row < 0) {
            hint.text = "请先选中一个脚本"
            return
        }
        val entry = model.entryAt(row) ?: return
        try {
            val trashDir = store.scriptsDir().resolve("trash")
            Files.createDirectories(trashDir)
            Files.move(
                Path.of(entry.filePath),
                trashDir.resolve(entry.id),
                StandardCopyOption.REPLACE_EXISTING
            )
            reload()
            hint.text = "已移出脚本库（在 trash 子目录）:" + entry.id
        } catch (t: Throwable) {
            hint.text = "移出失败：" + t.message
        }
    }

    override fun dispose() = Unit

    private inner class ScriptTableModel : AbstractTableModel() {

        private val columns = arrayOf("启用", "脚本", "匹配规则", "备注")
        private var entries: List<UserscriptEntry> = emptyList()

        fun setEntries(list: List<UserscriptEntry>) {
            entries = list
            fireTableDataChanged()
        }

        fun entryAt(row: Int): UserscriptEntry? = entries.getOrNull(row)

        override fun getRowCount(): Int = entries.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns[column]

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 0

        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) Boolean::class.javaObjectType else String::class.java

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val entry = entries[rowIndex]
            return when (columnIndex) {
                0 -> entry.enabled
                1 -> entry.displayName + "  (" + entry.id + ")"
                2 -> ruleSummary(entry)
                else -> note(entry)
            }
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex != 0) return
            val entry = entries.getOrNull(rowIndex) ?: return
            val enabled = aValue as? Boolean ?: return
            store.setEnabled(entry.id, enabled)
            reload()
        }

        private fun ruleSummary(entry: UserscriptEntry): String {
            val rules = entry.metadata.match + entry.metadata.include
            return if (rules.isEmpty()) "（无）" else rules.joinToString(", ")
        }

        private fun note(entry: UserscriptEntry): String = when {
            UserscriptMatcher.hasNoUrlRules(entry.metadata) -> "无 @match/@include，永不执行"
            GmCapabilities.looksLikeItUsesGmWithoutGrant(entry.body, entry.metadata) ->
                "用到 GM API 但未声明 @grant，不会生效"
            !entry.enabled -> "已禁用"
            entry.metadata.noframes -> "仅主 frame（@noframes）"
            else -> ""
        }
    }
}