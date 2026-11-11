package dev.issuetoolwindow

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.issuetoolwindow.IssuesBundle.message
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

internal class IssuesPanel(
    private val model: IssuesModel,
    chooseRepository: (Repository) -> Unit,
    chooseAccount: (GithubAccount) -> Unit,
    signIn: () -> Unit,
    openOnGitHub: (Long) -> Unit,
) {
    val panel = JPanel(BorderLayout(JBUI.scale(6), JBUI.scale(6))).apply { border = JBUI.Borders.empty(8) }
    private val repositories = ComboBox<Repository>().apply {
        renderer = renderer { value -> value?.fullName ?: message("repository.prompt") }
        accessibleContext.accessibleName = message("repository.prompt")
        isVisible = false
    }
    private val accounts = ComboBox<GithubAccount>().apply {
        renderer = renderer { value -> value?.name ?: message("account.prompt") }
        accessibleContext.accessibleName = message("account.prompt")
        isVisible = false
    }
    private val signInLink = ActionLink(message("action.signIn")) { signIn() }.apply { isVisible = false }
    private val context = JBLabel(message("context.loading"))

    private val filters = ComboBox(IssueFilter.entries.toTypedArray()).apply {
        renderer = renderer { value -> value?.let { message(it.messageKey) }.orEmpty() }
    }
    private val refresh = button("action.refresh", model::refresh)
    private val newIssue = button("action.new", model::startCreate)
    private val listStatus = JBLabel()
    private val issueRows = DefaultListModel<Issue>()
    private val issueList = JBList(issueRows).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer =
            renderer { value -> value?.let { message("detail.title", it.number.toString(), it.title) }.orEmpty() }
        accessibleContext.accessibleName = message("list.name")
    }
    private val moreIssues = button("action.more", model::moreIssues)

    private val detailCards = CardLayout()
    private val details = JPanel(detailCards)
    private val detailStatus = JBLabel()
    private val issueText = textArea(6).apply { isEditable = false }
    private val moreComments = button("action.more", model::moreComments)
    private val toggleStatus = button("action.close", model::toggleStatus)
    private val edit = button("action.edit", model::startEdit)
    private val browser =
        ActionLink(message("action.browser")) { model.state.value.issue?.let { openOnGitHub(it.number) } }
    private val comment = textArea(3).apply {
        emptyText.text = message("detail.commentInput")
        accessibleContext.accessibleName = message("detail.commentInput")
    }
    private val post = button("action.comment", model::comment)

    private val editorHeading = JBLabel()
    private val title = JBTextField()
    private val description = textArea(5)
    private val save = button("action.create", model::saveEditor)
    private val cancel = button("action.cancel", model::cancelEditor)
    private val feedback = textArea(2).apply { isEditable = false }
    private val feedbackScroll = JBScrollPane(feedback).apply { isVisible = false }

    private val workspace: JPanel
    private var updating = false
    private var renderedIssues = emptyList<Issue>()
    private var renderedDetail: Long? = null
    private var wasEditing = false
    private var contextSignIn = false

    init {
        val selectors = stack(
            repositories, accounts,
            row(signInLink),
        )
        val list = JPanel(BorderLayout()).apply {
            add(stack(row(filters, refresh), row(newIssue), listStatus), BorderLayout.NORTH)
            add(JBScrollPane(issueList), BorderLayout.CENTER)
            add(row(moreIssues), BorderLayout.SOUTH)
        }
        val detail = JPanel(BorderLayout(JBUI.scale(4), JBUI.scale(4))).apply {
            add(stack(row(toggleStatus), row(edit), row(browser), detailStatus), BorderLayout.NORTH)
            add(JBScrollPane(issueText), BorderLayout.CENTER)
            add(stack(row(moreComments), JBScrollPane(comment), row(post)), BorderLayout.SOUTH)
        }
        val form = JPanel(BorderLayout(JBUI.scale(4), JBUI.scale(4))).apply {
            add(
                stack(editorHeading, labelFor("editor.title", title), title, labelFor("editor.body", description)),
                BorderLayout.NORTH,
            )
            add(JBScrollPane(description), BorderLayout.CENTER)
            add(row(save, cancel), BorderLayout.SOUTH)
        }
        details.add(JBLabel(message("detail.empty")), "empty")
        details.add(detail, "issue")
        details.add(form, "editor")

        workspace = JPanel(BorderLayout()).apply {
            add(JBSplitter(true, 0.36f).apply {
                firstComponent = list
                secondComponent = details
            }, BorderLayout.CENTER)
            add(feedbackScroll, BorderLayout.SOUTH)
            isVisible = false
        }

        panel.add(selectors, BorderLayout.NORTH)
        panel.add(JPanel(BorderLayout()).apply {
            add(context, BorderLayout.NORTH)
            add(workspace, BorderLayout.CENTER)
        }, BorderLayout.CENTER)

        repositories.addActionListener { if (!updating) (repositories.selectedItem as? Repository)?.let(chooseRepository) }
        accounts.addActionListener { if (!updating) (accounts.selectedItem as? GithubAccount)?.let(chooseAccount) }
        filters.addActionListener { if (!updating) (filters.selectedItem as? IssueFilter)?.let(model::filter) }
        issueList.addListSelectionListener {
            if (!updating && !it.valueIsAdjusting) issueList.selectedValue?.let { issue -> model.select(issue.number) }
        }
        comment.onEdit { if (!updating) model.editComment(comment.text) }
        title.onEdit { if (!updating) updateDraft() }
        description.onEdit { if (!updating) updateDraft() }
    }

    fun showContext(
        repos: List<Repository>,
        repo: Repository?,
        savedAccounts: List<GithubAccount>,
        account: GithubAccount?
    ) {
        updating = true
        try {
            repositories.model = DefaultComboBoxModel(repos.toTypedArray())
            repositories.selectedItem = repo
            repositories.isVisible = repos.isNotEmpty()
            accounts.model = DefaultComboBoxModel(savedAccounts.toTypedArray())
            accounts.selectedItem = account
            accounts.isVisible = repo != null && savedAccounts.isNotEmpty()
            val hint = when {
                repos.isEmpty() -> "context.noRepository"
                repo == null -> "context.chooseRepository"
                savedAccounts.isEmpty() -> "context.signIn"
                account == null -> "context.chooseAccount"
                else -> null
            }
            context.isVisible = hint != null
            context.text = hint?.let(IssuesBundle::message).orEmpty()
            contextSignIn = repo != null && savedAccounts.isEmpty()
            renderSignIn(model.state.value)
        } finally {
            updating = false
        }
    }

    fun render(state: IssuesState) {
        updating = true
        try {
            workspace.isVisible = state.connected
            renderSignIn(state)
            repositories.isEnabled = !state.writing
            accounts.isEnabled = !state.writing
            val editor = state.editor
            val canNavigate = !state.writing && editor == null
            filters.selectedItem = state.filter
            filters.isEnabled = canNavigate
            issueList.isEnabled = canNavigate
            refresh.isEnabled = !state.writing
            newIssue.isEnabled = canNavigate && !state.needsSignIn
            moreIssues.isVisible = state.issues.next != null
            moreIssues.isEnabled = !state.listLoading && canNavigate

            if (renderedIssues != state.issues.items) {
                renderedIssues = state.issues.items
                issueRows.clear()
                issueRows.addAll(renderedIssues)
            }
            issueList.selectedIndex = renderedIssues.indexOfFirst { it.number == state.selected }
            listStatus.text = when {
                state.listLoading -> message("list.loading")
                state.listError == null && renderedIssues.isEmpty() -> message("list.empty")
                else -> ""
            }
            listStatus.isVisible = listStatus.text.isNotEmpty()

            when {
                editor != null -> {
                    val (draft, number) = editor
                    editorHeading.text = number?.let { message("editor.issue", it.toString()) }.orEmpty()
                    editorHeading.isVisible = number != null
                    title.syncText(draft.title)
                    description.syncText(draft.body)
                    title.isEditable = !state.writing
                    description.isEditable = !state.writing
                    save.text = message(if (number == null) "action.create" else "action.save")
                    save.isEnabled = !state.writing && !state.needsSignIn && draft.title.isNotBlank()
                    cancel.isEnabled = !state.writing
                    detailCards.show(details, "editor")
                    if (!wasEditing) title.requestFocusInWindow()
                }

                state.issue != null -> {
                    renderIssue(state, state.issue)
                    detailCards.show(details, "issue")
                }

                else -> detailCards.show(details, "empty")
            }
            wasEditing = editor != null

            val error = state.writeError ?: state.detailError ?: state.listError
            val status = if (state.writing) message("feedback.writing")
            else listOfNotNull(state.feedback, error?.message).joinToString("\n")
            feedback.syncText(status)
            feedbackScroll.isVisible = status.isNotBlank()
        } finally {
            updating = false
        }
    }

    private fun renderIssue(state: IssuesState, issue: Issue) {
        val content = buildString {
            appendLine(message("detail.title", issue.number.toString(), issue.title))
            appendLine(message("detail.status", message(if (issue.open) "filter.open" else "filter.closed")))
            appendLine(message("detail.author", issue.author ?: message("detail.unknown")))
            appendLine(message("detail.labels", issue.labels.joinToString().ifEmpty { message("detail.none") }))
            appendLine(message("detail.assignees", issue.assignees.joinToString().ifEmpty { message("detail.none") }))
            appendLine()
            appendLine(issue.body.ifBlank { message("detail.noDescription") })
            appendLine()
            appendLine(message("detail.comments"))
            if (state.comments.items.isEmpty() && !state.detailLoading && state.detailError == null) {
                appendLine(message("detail.noComments"))
            }
            for (entry in state.comments.items) {
                appendLine()
                val author = entry.author ?: message("detail.unknown")
                appendLine(entry.createdAt?.let { message("detail.commentAuthor", author, it) } ?: author)
                appendLine(entry.body)
            }
        }
        issueText.syncText(content)
        if (renderedDetail != issue.number) {
            issueText.caretPosition = 0
            renderedDetail = issue.number
        }
        detailStatus.text = message("detail.loading")
        detailStatus.isVisible = state.detailLoading
        moreComments.isVisible = state.comments.next != null
        moreComments.isEnabled = !state.detailLoading && !state.writing
        toggleStatus.text = message(if (issue.open) "action.close" else "action.reopen")
        toggleStatus.isEnabled = !state.writing && !state.needsSignIn
        edit.isEnabled = !state.writing && !state.detailLoading && !state.needsSignIn
        comment.syncText(state.commentDraft)
        comment.isEditable = !state.writing
        post.isEnabled = !state.writing && !state.needsSignIn && state.commentDraft.isNotBlank()
    }

    private fun renderSignIn(state: IssuesState) {
        signInLink.text = message(if (state.needsSignIn) "action.signInAgain" else "action.signIn")
        signInLink.isVisible = contextSignIn || state.needsSignIn
        signInLink.isEnabled = !state.writing
    }

    private fun updateDraft() = model.editDraft(IssueDraft(title.text, description.text))

    private fun JTextComponent.onEdit(onChange: () -> Unit) {
        document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = onChange()
        })
    }

    private fun JTextComponent.syncText(value: String) {
        if (text == value) return
        val oldPosition = caretPosition
        text = value
        caretPosition = oldPosition.coerceAtMost(document.length)
    }

    private fun labelFor(key: String, field: JTextComponent) = JBLabel(message(key)).apply { labelFor = field }

    private fun button(key: String, action: () -> Unit) = JButton(message(key)).apply {
        addActionListener { action() }
    }

    private fun textArea(rows: Int) = JBTextArea(rows, 1).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    private fun stack(vararg components: Component) = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        components.forEach(::add)
    }

    private fun row(vararg components: Component) = JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(4), 0)).apply {
        components.forEach(::add)
    }

    private fun <T> renderer(display: (T?) -> String): ListCellRenderer<T> {
        val renderer = DefaultListCellRenderer().apply { putClientProperty("html.disable", true) }
        return ListCellRenderer { list, value, index, selected, focus ->
            renderer.getListCellRendererComponent(list, display(value), index, selected, focus)
        }
    }
}
