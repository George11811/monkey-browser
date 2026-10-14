package com.ridermonkey.ui

import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JButton

/**
 * 工具栏小按钮：图标尺寸自适应 + 自绘 hover / 按下反馈。
 *
 * 三个平台坑都收在这个类里（浏览器工具栏与脚本管理面板共用）：
 * - `isBorderPainted = false` **不会**移除边框 insets，不换成空边框的话图标按钮会宽到 65px；
 * - 这个 L&F 会把普通 JButton 的 preferred 宽抬到 **82px**（图标按钮与文字按钮都一样），
 *   必须显式给出尺寸才能绕开，否则窄工具窗里的按钮会被静默裁掉；
 * - 用「改 background + `isOpaque = true`」做反馈是**错的**——`isOpaque = true` 会让 Swing 跳过
 *   父容器绘制，半透明填充叠在旧像素上出现脏区残留。正确做法是保持 `isOpaque = false` 自绘。
 */
class ToolbarButton : JButton {

    private var hovered = false

    constructor(icon: Icon) : super(icon) {
        configure()
    }

    constructor(text: String) : super(text) {
        configure()
    }

    private fun configure() {
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        isOpaque = false
        border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
        margin = Insets(0, 0, 0, 0)
        minimumSize = Dimension(0, 0)

        val icon = this.icon
        if (icon != null) {
            preferredSize = Dimension(icon.iconWidth + 12, icon.iconHeight + 8)
        }

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovered = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false
                repaint()
            }

            override fun mousePressed(e: MouseEvent) = repaint()

            override fun mouseReleased(e: MouseEvent) = repaint()
        })
    }

    override fun paintComponent(g: Graphics) {
        if (hovered || model.isPressed) {
            val g2 = g.create() as Graphics2D
            g2.color = if (model.isPressed) PRESSED_BACKGROUND else HOVER_BACKGROUND
            g2.fillRoundRect(0, 0, width - 1, height - 1, 6, 6)
            g2.dispose()
        }
        super.paintComponent(g)
    }

    private companion object {
        /** hover / 按下的半透明反馈色；随明暗主题自适应。 */
        val HOVER_BACKGROUND = JBColor(Color(0, 0, 0, 18), Color(255, 255, 255, 26))
        val PRESSED_BACKGROUND = JBColor(Color(0, 0, 0, 38), Color(255, 255, 255, 48))
    }
}
