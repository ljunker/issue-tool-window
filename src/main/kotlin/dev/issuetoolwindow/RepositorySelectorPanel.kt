package dev.issuetoolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.issuetoolwindow.IssuesBundle.message
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel

/** Start screen like the one of the pull request tool window: repository, account and a button to open the list. */
internal class RepositorySelectorPanel(
    private val chooseRepository: (Repository) -> Unit,
    private val chooseAccount: (GithubAccount) -> Unit,
    private val view: () -> Unit,
    private val signIn: () -> Unit,
) {
    private val repositories = ComboBox<Repository>().apply {
        accessibleContext.accessibleName = message("repository.prompt")
    }
    private var remoteNames = emptyMap<String, String>()
    private val avatar = JBLabel().apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        accessibleContext.accessibleName = message("account.prompt")
    }
    private val viewIssues = JButton(message("action.viewIssues")).apply {
        putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
    }
    private val hint = JBLabel(message("selector.hint"), UIUtil.ComponentStyle.REGULAR, UIUtil.FontColor.BRIGHTER)
    private val context = JBLabel(message("context.loading"), UIUtil.ComponentStyle.REGULAR, UIUtil.FontColor.BRIGHTER)

    private var accounts = emptyList<GithubAccount>()
    private var account: GithubAccount? = null
    private var updating = false
    private var avatars: (GithubAccount) -> Icon? = { null }

    val panel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(16, 12)
        val selection = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(repositories, BorderLayout.CENTER)
            add(avatar, BorderLayout.EAST)
        }
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            listOf(
                selection,
                JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply { add(viewIssues) },
                hint,
                context,
            ).forEach { component ->
                (component as? javax.swing.JComponent)?.alignmentX = Component.LEFT_ALIGNMENT
                if (componentCount > 0) add(javax.swing.Box.createVerticalStrut(JBUI.scale(12)))
                add(component)
            }
            selection.maximumSize = java.awt.Dimension(Int.MAX_VALUE, selection.preferredSize.height)
        }
        add(content, BorderLayout.NORTH)
    }

    init {
        repositories.renderer = object : ColoredListCellRenderer<Repository>() {
            override fun customizeCellRenderer(
                list: JList<out Repository>,
                value: Repository?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) {
                    append(message("repository.prompt"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    return
                }
                append(value.fullName)
                remoteNames[value.fullName.lowercase()]?.let { append(" $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            }
        }
        repositories.addActionListener {
            if (!updating) (repositories.selectedItem as? Repository)?.let(chooseRepository)
        }
        avatar.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) = showAccounts()
        })
        viewIssues.addActionListener { if (accounts.isEmpty()) signIn() else view() }
    }

    fun show(
        repos: List<Repository>,
        remotes: Map<String, String>,
        repo: Repository?,
        savedAccounts: List<GithubAccount>,
        selected: GithubAccount?,
        avatarFor: (GithubAccount) -> Icon?,
    ) {
        updating = true
        try {
            remoteNames = remotes
            accounts = savedAccounts
            account = selected
            avatars = avatarFor
            repositories.model = DefaultComboBoxModel(repos.toTypedArray())
            repositories.selectedItem = repo
            val hasRepos = repos.isNotEmpty()
            repositories.isVisible = hasRepos
            avatar.isVisible = hasRepos
            viewIssues.isVisible = hasRepos
            hint.isVisible = hasRepos
            viewIssues.text = message(if (savedAccounts.isEmpty()) "action.signIn" else "action.viewIssues")
            viewIssues.isEnabled = repo != null && (savedAccounts.isEmpty() || selected != null)
            refreshAvatar()
            val status = when {
                !hasRepos -> "context.noRepository"
                savedAccounts.isNotEmpty() && selected == null -> "context.chooseAccount"
                else -> null
            }
            context.text = status?.let(IssuesBundle::message).orEmpty()
            context.isVisible = status != null
        } finally {
            updating = false
        }
    }

    fun showLoading() {
        listOf(repositories, avatar, viewIssues, hint).forEach { it.isVisible = false }
        context.text = message("context.loading")
        context.isVisible = true
    }

    /** Called when an avatar finished loading. */
    fun refreshAvatar() {
        val current = account
        avatar.icon = current?.let { avatars(it) ?: InitialsIcon(it.name) } ?: AllIcons.General.User
        avatar.toolTipText = current?.name ?: message("account.prompt")
    }

    private fun showAccounts() {
        val group = DefaultActionGroup()
        accounts.forEach { candidate ->
            val icon = if (candidate == account) AllIcons.Actions.Checked else null
            group.add(object : DumbAwareAction(candidate.name, null, icon) {
                override fun actionPerformed(e: AnActionEvent) = chooseAccount(candidate)
            })
        }
        if (accounts.isNotEmpty()) group.addSeparator()
        group.add(object : DumbAwareAction(message("action.signIn")) {
            override fun actionPerformed(e: AnActionEvent) = signIn()
        })
        JBPopupFactory.getInstance().createActionGroupPopup(
            null, group, DataManager.getInstance().getDataContext(avatar),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true,
        ).showUnderneathOf(avatar)
    }
}

/** Round placeholder avatar with the first letter of the account name. */
internal class InitialsIcon(name: String, private val size: Int = 24) : Icon {
    private val letter = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    private val color = palette[Math.floorMod(name.hashCode(), palette.size)]

    override fun getIconWidth() = JBUI.scale(size)
    override fun getIconHeight() = JBUI.scale(size)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = color
            g2.fillOval(x, y, iconWidth, iconHeight)
            g2.color = Color.WHITE
            g2.font = JBFont.label().asBold().deriveFont(iconHeight * 0.5f)
            val metrics = g2.fontMetrics
            g2.drawString(
                letter,
                x + (iconWidth - metrics.stringWidth(letter)) / 2,
                y + (iconHeight - metrics.height) / 2 + metrics.ascent,
            )
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        val palette = listOf(
            JBColor(0x5C8DD6, 0x4A74B3), JBColor(0x5FA36B, 0x4C8556), JBColor(0xC9734B, 0xA65F3E),
            JBColor(0x9168C9, 0x7656A6), JBColor(0xC95A86, 0xA64A6E),
        )
    }
}
