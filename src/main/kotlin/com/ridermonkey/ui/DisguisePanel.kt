package com.ridermonkey.ui

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.colors.TextAttributesKey
import java.awt.Color
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JPanel

/**
 * 「摸鱼遮罩」：把浏览器面板画成一个普通 IDE 编辑器窗口。
 *
 * 为什么自己画而不是塞一个 JTextArea：这里只需要「看起来像」——
 * 文件名页签、行号槽、语法着色都是纯绘制，不引入可编辑控件，
 * 因此不会抢焦点、不会被误触，也不需要任何文档模型。
 *
 * 颜色与字体全部取自当前编辑器配色方案（[EditorColorsManager]），
 * 所以浅色 / 深色主题下都不会突兀；字体缺失时也跟随 scheme 的默认等宽字体。
 *
 * 内容是一段固定的 Kotlin 代码（[LINES]），刻意不做成「可配置」：
 * 遮罩的作用是盖住页面，不是给用户当便签用。
 */
class DisguisePanel : JPanel() {

    init {
        isOpaque = true
    }

    override fun paintComponent(g: Graphics) {
        // 不调用 super.paintComponent 再补一层背景：这里完全自绘，避免先用面板底色刷一遍再覆盖。
        val scheme = EditorColorsManager.getInstance().globalScheme
        val palette = Palette.of(scheme)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            g2.color = palette.background
            g2.fillRect(0, 0, width, height)

            g2.font = scheme.getFont(EditorFontType.PLAIN)
            val metrics = g2.fontMetrics
            val lineHeight = metrics.height.coerceAtLeast(1)

            val codeTop = paintTabStrip(g2, palette, metrics)
            val gutterWidth = (metrics.stringWidth("9999") + 18).coerceAtLeast(44)
            g2.color = palette.gutterBackground
            g2.fillRect(0, codeTop, gutterWidth, height - codeTop)
            g2.color = palette.gutterBorder
            g2.drawLine(gutterWidth, codeTop, gutterWidth, height)

            var baseline = codeTop + metrics.ascent + 6
            for ((index, line) in LINES.withIndex()) {
                if (baseline - metrics.ascent > height) break
                if (index == CURRENT_LINE_INDEX) {
                    g2.color = palette.currentLine
                    g2.fillRect(gutterWidth + 1, baseline - metrics.ascent - 2, width - gutterWidth - 1, lineHeight)
                }
                g2.color = palette.lineNumber
                val number = (index + 1).toString()
                g2.drawString(number, gutterWidth - 8 - metrics.stringWidth(number), baseline)
                paintCodeLine(g2, line, gutterWidth + 10, baseline, palette, metrics)
                baseline += lineHeight
            }
        } finally {
            g2.dispose()
        }
    }

    /** 顶部一条假页签，返回正文起始 y。 */
    private fun paintTabStrip(g2: Graphics2D, palette: Palette, metrics: FontMetrics): Int {
        val stripHeight = metrics.height + 10
        g2.color = palette.tabBackground
        g2.fillRect(0, 0, width, stripHeight)
        val tabWidth = (metrics.stringWidth(FILE_NAME) + 28).coerceAtMost(width)
        g2.color = palette.background
        g2.fillRect(2, 4, tabWidth.coerceAtLeast(1), stripHeight - 4)
        g2.color = palette.tabText
        // 面板比页签还窄时（工具窗拖到很细），把文字裁在页签矩形内，别画到 tab 条上。
        val clip = g2.clip
        g2.clipRect(2, 4, tabWidth.coerceAtLeast(1), stripHeight - 4)
        g2.drawString(FILE_NAME, 12, 4 + metrics.ascent + (stripHeight - 4 - metrics.height) / 2)
        g2.clip = clip
        g2.color = palette.border
        g2.drawLine(0, stripHeight - 1, width, stripHeight - 1)
        return stripHeight
    }

    /** 一行代码：注释整行一个颜色，其余按「字符串 / 数字 / 关键字 / 普通标识符」分词着色。 */
    private fun paintCodeLine(
        g2: Graphics2D,
        line: String,
        startX: Int,
        baseline: Int,
        palette: Palette,
        metrics: FontMetrics
    ) {
        var x = startX
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
            g2.color = palette.comment
            g2.drawString(line, x, baseline)
            return
        }
        for (token in TOKEN.findAll(line)) {
            val text = token.value
            g2.color = when {
                text.startsWith("\"") -> palette.string
                text[0].isDigit() -> palette.number
                text in KEYWORDS -> palette.keyword
                else -> palette.foreground
            }
            g2.drawString(text, x, baseline)
            x += metrics.stringWidth(text)
            if (x > width) return
        }
    }

    /** 一套从当前配色方案推导出的颜色；推导不出来的（如未配置的关键字色）退回默认前景色。 */
    private class Palette(
        val background: Color,
        val foreground: Color,
        val keyword: Color,
        val string: Color,
        val number: Color,
        val comment: Color,
        val lineNumber: Color,
        val gutterBackground: Color,
        val gutterBorder: Color,
        val currentLine: Color,
        val tabBackground: Color,
        val tabText: Color,
        val border: Color
    ) {
        companion object {
            fun of(scheme: EditorColorsScheme): Palette {
                val bg = scheme.defaultBackground
                val fg = scheme.defaultForeground
                fun hl(key: TextAttributesKey): Color = scheme.getAttributes(key)?.foregroundColor ?: fg
                return Palette(
                    background = bg,
                    foreground = fg,
                    keyword = hl(DefaultLanguageHighlighterColors.KEYWORD),
                    string = hl(DefaultLanguageHighlighterColors.STRING),
                    number = hl(DefaultLanguageHighlighterColors.NUMBER),
                    comment = hl(DefaultLanguageHighlighterColors.LINE_COMMENT),
                    lineNumber = mix(fg, bg, 0.55),
                    gutterBackground = mix(fg, bg, 0.04),
                    gutterBorder = mix(fg, bg, 0.12),
                    currentLine = mix(fg, bg, 0.06),
                    tabBackground = mix(fg, bg, 0.10),
                    tabText = mix(fg, bg, 0.75),
                    border = mix(fg, bg, 0.18)
                )
            }

            /** 把 [fg] 按 [ratio] 叠到 [bg] 上（0 = 纯 bg，1 = 纯 fg）。 */
            private fun mix(fg: Color, bg: Color, ratio: Double): Color = Color(
                (bg.red + (fg.red - bg.red) * ratio).toInt().coerceIn(0, 255),
                (bg.green + (fg.green - bg.green) * ratio).toInt().coerceIn(0, 255),
                (bg.blue + (fg.blue - bg.blue) * ratio).toInt().coerceIn(0, 255)
            )
        }
    }

    private companion object {

        /** 假页签名：取一个「像本工程文件」的名字。 */
        const val FILE_NAME: String = "TaskScheduler.kt"

        /** 高亮行（模拟光标所在行）。 */
        const val CURRENT_LINE_INDEX: Int = 4

        /** 分词：字符串 / 标识符 / 数字 / 连续空白 / 其它单个字符。 */
        val TOKEN = Regex("\"[^\"]*\"|[A-Za-z_][A-Za-z0-9_]*|[0-9]+|[ \t]+|.")

        val KEYWORDS = setOf(
            "package", "import", "class", "object", "interface", "fun", "val", "var", "private",
            "internal", "public", "override", "return", "if", "else", "when", "for", "while", "in",
            "is", "as", "data", "constructor", "require", "this", "true", "false", "null"
        )

        /** 固定的假代码：内容本身没有意义，只要「像真的」即可。 */
        val LINES = listOf(
            "package com.example.core",
            "",
            "import java.time.Duration",
            "",
            "/** Priority queue scheduler: fires each task when it is due. */",
            "class TaskScheduler(private val clock: Clock) {",
            "",
            "    private val queue = PriorityQueue<Task>(compareByDescending { it.priority })",
            "",
            "    fun schedule(task: Task): Duration {",
            "        require(task.deadline.isAfter(clock.instant())) { \"deadline passed\" }",
            "        queue += task",
            "        return Duration.between(clock.instant(), task.deadline)",
            "    }",
            "",
            "    fun poll(): Task? = queue.poll()",
            "",
            "    override fun toString(): String =",
            "        \"TaskScheduler(size=\" + queue.size + \")\"",
            "}",
            "",
            "internal data class Task(",
            "    val id: String,",
            "    val priority: Int,",
            "    val deadline: Instant",
            ")"
        )
    }
}
