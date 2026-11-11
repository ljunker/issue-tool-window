package dev.issuetoolwindow

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.popup.list.SelectablePanel
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import dev.issuetoolwindow.IssuesBundle.message
import icons.CollaborationToolsIcons
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

/** Two-line issue row modeled on the pull request list: title, tags, state and comments above the meta line. */
internal class IssueListCellRenderer : ListCellRenderer<Issue> {
    private val title = JBLabel()
    private val meta = JBLabel().apply { font = JBFont.small() }
    private val tags = JPanel(FlowLayout(FlowLayout.TRAILING, JBUI.scale(4), 0)).apply { isOpaque = false }
    private val state = RoundedLabel().apply { font = JBFont.small().asBold() }
    private val comments = JBLabel(CollaborationToolsIcons.Comment).apply {
        iconTextGap = JBUI.scale(3)
        horizontalTextPosition = SwingConstants.RIGHT
    }
    private val content = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
        isOpaque = false
        border = JBUI.Borders.empty(5, 10)
        val trailing = JPanel(FlowLayout(FlowLayout.TRAILING, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(tags)
            add(state)
            add(comments)
        }
        add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(title, BorderLayout.CENTER)
            add(trailing, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(meta, BorderLayout.SOUTH)
    }
    private val cell = SelectablePanel.wrap(content).apply {
        selectionArc = JBUI.scale(8)
        selectionInsets = JBInsets.create(1, 6)
        border = JBUI.Borders.empty(1, 6)
    }

    override fun getListCellRendererComponent(
        list: JList<out Issue>,
        value: Issue?,
        index: Int,
        selected: Boolean,
        focused: Boolean,
    ): Component {
        val issue = value ?: return cell
        val focusedList = list.hasFocus()
        cell.background = list.background
        cell.selectionColor = if (selected) UIUtil.getListSelectionBackground(focusedList) else null
        val foreground = UIUtil.getListForeground(selected, focusedList)
        val secondary = if (selected && focusedList) foreground else NamedColorUtil.getInactiveTextColor()

        title.text = issue.title
        title.foreground = foreground
        cell.toolTipText = issue.title
        meta.text = issueMetaLine(issue)
        meta.foreground = secondary

        tags.removeAll()
        issue.labels.take(MAX_TAGS).forEach { name -> tags.add(labelTag(name, issue.labelColors[name])) }
        if (issue.labels.size > MAX_TAGS) {
            val more = JBLabel("+${issue.labels.size - MAX_TAGS}")
            more.setFont(JBFont.small())
            more.setForeground(secondary)
            tags.add(more)
        }
        tags.isVisible = issue.labels.isNotEmpty()

        state.text = message(if (issue.open) "state.open" else "state.closed")
        state.foreground = secondary
        state.fill = STATE_BACKGROUND

        comments.text = issue.commentCount.toString()
        comments.foreground = foreground
        return cell
    }

    private fun labelTag(name: String, color: String?) = RoundedLabel().apply {
        text = name
        font = JBFont.small()
        val background = color?.let(::labelColor)
        if (background == null) {
            fill = STATE_BACKGROUND
            foreground = NamedColorUtil.getInactiveTextColor()
        } else {
            fill = background
            foreground = if (isBright(background)) DARK_TEXT else Color.WHITE
        }
    }

    private companion object {
        const val MAX_TAGS = 2
        val STATE_BACKGROUND = JBColor(0xEBECF0, 0x393B40)
        val DARK_TEXT = Color(0x1F2328)
        val hexColor = Regex("[0-9a-fA-F]{6}")

        fun labelColor(hex: String): Color? = if (hexColor.matches(hex)) Color(hex.toInt(16)) else null

        fun isBright(color: Color) = (0.299 * color.red + 0.587 * color.green + 0.114 * color.blue) / 255 > 0.6
    }
}

/** `#16 · created Today, by ljunker`; date and author are left out when GitHub did not report them. */
internal fun issueMetaLine(issue: Issue, formatDate: (Long) -> String = DateFormatUtil::formatPrettyDate): String {
    val created = issue.createdAt?.let { value ->
        try {
            message("list.meta.created", formatDate(Instant.parse(value).toEpochMilli()))
        } catch (_: DateTimeParseException) {
            null
        }
    }
    val details = listOfNotNull(created, issue.author?.let { message("list.meta.author", it) })
    return if (details.isEmpty()) "#${issue.number}" else "#${issue.number} · ${details.joinToString(", ")}"
}

/** Small label on a rounded background, like the state badge of the pull request list. */
internal class RoundedLabel : JBLabel() {
    var fill: Color = JBColor.GRAY

    init {
        isOpaque = false
        border = JBUI.Borders.empty(0, 5)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = fill
            val arc = JBUI.scale(6)
            g2.fillRoundRect(0, 0, width, height, arc, arc)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}
