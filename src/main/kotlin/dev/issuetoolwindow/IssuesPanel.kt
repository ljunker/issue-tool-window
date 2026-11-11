package dev.issuetoolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CheckBoxList
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBHtmlPaneConfiguration
import com.intellij.ui.components.JBHtmlPaneStyleConfiguration
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.ExtendableHTMLViewFactory
import com.intellij.util.ui.JBUI
import dev.issuetoolwindow.IssuesBundle.message
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.HyperlinkEvent
import javax.swing.text.JTextComponent

internal class IssuesPanel(
    private val model: IssuesModel,
    chooseRepository: (Repository) -> Unit,
    chooseAccount: (GithubAccount) -> Unit,
    signIn: () -> Unit,
    private val showDetails: (focus: Boolean) -> Unit,
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
    private val searchInput = JBTextField().apply {
        emptyText.text = message("search.prompt")
        accessibleContext.accessibleName = message("search.prompt")
    }
    private val search = button("action.search", ::submitSearch)
    private val refresh = button("action.refresh", model::refresh)
    private val newIssue = button("action.new") {
        if (model.state.value.editor == null) model.startCreate()
        if (model.state.value.editor != null) showDetails(true)
    }
    private val listStatus = JBLabel()
    private val issueRows = DefaultListModel<Issue>()
    private val issueList = JBList(issueRows).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer =
            renderer { value -> value?.let { message("detail.title", it.number.toString(), it.title) }.orEmpty() }
        accessibleContext.accessibleName = message("list.name")
    }
    private val moreIssues = button("action.more", model::moreIssues)

    private val feedback = textArea(2).apply { isEditable = false }
    private val feedbackScroll = JBScrollPane(feedback).apply { isVisible = false }

    private val workspace: JPanel
    private var updating = false
    private var renderedIssues = emptyList<Issue>()
    private var contextSignIn = false

    init {
        val selectors = stack(
            repositories, accounts,
            row(signInLink),
        )
        val searchBar = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            add(searchInput, BorderLayout.CENTER)
            add(search, BorderLayout.EAST)
        }
        workspace = JPanel(BorderLayout()).apply {
            add(stack(row(filters, refresh), searchBar, row(newIssue), listStatus), BorderLayout.NORTH)
            add(JBScrollPane(issueList), BorderLayout.CENTER)
            add(stack(row(moreIssues), feedbackScroll), BorderLayout.SOUTH)
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
        searchInput.addActionListener { submitSearch() }
        searchInput.onEdit { if (!updating) model.editSearch(searchInput.text) }
        issueList.addListSelectionListener {
            if (!updating && !it.valueIsAdjusting && issueList.isEnabled) openSelectedIssue(false)
        }
        issueList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.button != MouseEvent.BUTTON1 || !issueList.isEnabled) return
                val index = issueList.selectedIndex
                if (index >= 0 && issueList.getCellBounds(index, index).contains(event.point)) openSelectedIssue(false)
            }
        })
        issueList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openIssue")
        issueList.actionMap.put("openIssue", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                if (issueList.isEnabled) openSelectedIssue(true)
            }
        })
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
            searchInput.syncText(state.searchInput)
            searchInput.isEnabled = canNavigate
            search.isEnabled = canNavigate && state.searchInput.isNotBlank()
            issueList.isEnabled = canNavigate
            refresh.isEnabled = !state.writing
            newIssue.text = message(if (editor == null) "action.new" else "action.openDraft")
            newIssue.isEnabled = editor != null || (canNavigate && !state.needsSignIn)
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
                state.listError != null -> message("list.failed")
                renderedIssues.isEmpty() -> message(if (state.searchQuery == null) "list.empty" else "list.noMatches")
                else -> ""
            }
            listStatus.isVisible = listStatus.text.isNotEmpty()

            val error = state.listError ?: state.detailError?.takeIf { state.selected == null }
            feedback.syncText(error?.message.orEmpty())
            feedbackScroll.isVisible = error != null
        } finally {
            updating = false
        }
    }

    private fun openSelectedIssue(focus: Boolean) {
        val number = issueList.selectedValue?.number ?: return
        model.select(number)
        if (model.state.value.selected == number) showDetails(focus)
    }

    private fun submitSearch() {
        val state = model.state.value
        if (state.searchInput.isBlank() || state.writing || state.editor != null) return
        model.search()
        if (model.state.value.selected != null) showDetails(false)
    }

    private fun renderSignIn(state: IssuesState) {
        signInLink.text = message(if (state.needsSignIn) "action.signInAgain" else "action.signIn")
        signInLink.isVisible = contextSignIn || state.needsSignIn
        signInLink.isEnabled = !state.writing
    }

    private fun <T> renderer(display: (T?) -> String): ListCellRenderer<T> {
        val renderer = DefaultListCellRenderer().apply { putClientProperty("html.disable", true) }
        return ListCellRenderer { list, value, index, selected, focus ->
            renderer.getListCellRendererComponent(list, display(value), index, selected, focus)
        }
    }
}

internal class IssueDetailsPanel(
    private val model: IssuesModel,
    openOnGitHub: (Long) -> Unit,
    parent: Disposable,
) {
    private val detailCards = CardLayout()
    private val details = JPanel(detailCards)
    private val detailPlaceholder = JBLabel()
    private val detailStatus = JBLabel()
    private val issueText = JBHtmlPane(
        JBHtmlPaneStyleConfiguration(),
        JBHtmlPaneConfiguration {
            customStyleSheetProvider { issueDetailsStyleSheet(JBColor.isBright()) }
            extensions(ExtendableHTMLViewFactory.Extensions.WORD_WRAP)
            iconResolver { name ->
                when (name) {
                    "label-event" -> AllIcons.Nodes.Tag
                    "link-event" -> AllIcons.Actions.OpenNewTab
                    "pull-request-event" -> AllIcons.Vcs.Branch
                    else -> null
                }
            }
        },
    ).apply {
        Disposer.register(parent, this)
        accessibleContext.accessibleName = message("detail.tab")
        addHyperlinkListener { event ->
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                event.description?.let(IssueMarkdown::browserUri)?.let(BrowserUtil::browse)
            }
        }
    }
    private val issueScroll = JBScrollPane(issueText).apply { border = JBUI.Borders.empty() }
    private val moreActivity = button("action.more", model::moreActivity)
    private val toggleStatus = button("action.close", model::toggleStatus)
    private val edit = button("action.edit", model::startEdit)
    private val editLabels = button("action.labels", model::startLabels)
    private val editAssignees = button("action.assignees", model::startAssignees)
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
    private val pickerHeading = JBLabel().apply { font = font.deriveFont(Font.BOLD) }
    private val pickerSearch = JBTextField()
    private val pickerList = CheckBoxList<String>()
    private val pickerStatus = textArea(2).apply {
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty()
    }
    private val assignMe = button("action.assignMe", model::assignMe)
    private val savePicker = button("action.saveLabels", model::savePicker)
    private val cancelPicker = button("action.cancel", model::cancelPicker)
    private val feedback = textArea(2).apply { isEditable = false }
    private val feedbackScroll = JBScrollPane(feedback).apply { isVisible = false }

    val panel = JPanel(BorderLayout(JBUI.scale(6), JBUI.scale(6))).apply { border = JBUI.Borders.empty(12) }
    val preferredFocus: JTextComponent
        get() {
            val state = model.state.value
            return when {
                state.editor != null -> title
                state.picker != null -> pickerSearch
                else -> issueText
            }
        }

    private var updating = false
    private var renderedContent: IssueDetailsContent? = null
    private var wasEditing = false
    private var shownPicker: PickerKind? = null
    private var renderedChoices = emptyList<String>()

    init {
        val commentHeading = labelFor("detail.addComment", comment).apply { font = font.deriveFont(Font.BOLD) }
        val composer = stack(row(moreActivity), row(commentHeading), JBScrollPane(comment), row(post)).apply {
            border = JBUI.Borders.empty(0, 20)
        }
        val detail = JPanel(BorderLayout(JBUI.scale(4), JBUI.scale(4))).apply {
            add(stack(row(toggleStatus, edit, editLabels, editAssignees, browser), detailStatus), BorderLayout.NORTH)
            add(issueScroll, BorderLayout.CENTER)
            add(composer, BorderLayout.SOUTH)
        }
        val form = JPanel(BorderLayout(JBUI.scale(4), JBUI.scale(4))).apply {
            add(
                stack(editorHeading, labelFor("editor.title", title), title, labelFor("editor.body", description)),
                BorderLayout.NORTH,
            )
            add(JBScrollPane(description), BorderLayout.CENTER)
            add(row(save, cancel), BorderLayout.SOUTH)
        }
        details.add(detailPlaceholder, "empty")
        details.add(detail, "issue")
        val picker = JPanel(BorderLayout(JBUI.scale(4), JBUI.scale(4))).apply {
            add(stack(pickerHeading, pickerSearch, pickerStatus, row(assignMe)), BorderLayout.NORTH)
            add(JBScrollPane(pickerList), BorderLayout.CENTER)
            add(row(savePicker, cancelPicker), BorderLayout.SOUTH)
        }
        details.add(form, "editor")
        details.add(picker, "picker")
        panel.add(details, BorderLayout.CENTER)
        panel.add(feedbackScroll, BorderLayout.SOUTH)

        ApplicationManager.getApplication().messageBus.connect(parent).subscribe(
            LafManagerListener.TOPIC,
            LafManagerListener { issueText.reloadCssStylesheets() },
        )
        comment.onEdit { if (!updating) model.editComment(comment.text) }
        title.onEdit { if (!updating) updateDraft() }
        description.onEdit { if (!updating) updateDraft() }
        pickerSearch.onEdit { if (!updating) model.searchPicker(pickerSearch.text) }
        pickerList.setCheckBoxListListener { index, _ ->
            if (!updating) pickerList.getItemAt(index)?.let(model::togglePicked)
        }
    }

    fun render(state: IssuesState) {
        updating = true
        try {
            val editor = state.editor
            when {
                editor != null -> {
                    val (draft, number) = editor
                    editorHeading.text = number?.let { message("editor.issue", it.toString()) } ?: message("action.new")
                    title.syncText(draft.title)
                    description.syncText(draft.body)
                    title.isEditable = !state.writing
                    description.isEditable = !state.writing
                    save.text = message(if (number == null) "action.create" else "action.save")
                    save.isEnabled = !state.writing && !state.needsSignIn && draft.title.isNotBlank()
                    cancel.isEnabled = !state.writing
                    detailCards.show(details, "editor")
                    if (!wasEditing && panel.isShowing) title.requestFocusInWindow()
                }

                state.picker != null && state.issue != null -> {
                    renderPicker(state, state.picker)
                    detailCards.show(details, "picker")
                    if (shownPicker != state.picker.kind && panel.isShowing) pickerSearch.requestFocusInWindow()
                }

                state.issue != null -> {
                    renderIssue(state, state.issue)
                    detailCards.show(details, "issue")
                }

                else -> {
                    renderedContent = null
                    detailPlaceholder.text = message(
                        when {
                            state.detailLoading -> "detail.loading"
                            state.detailError != null -> "detail.failed"
                            else -> "detail.empty"
                        }
                    )
                    detailCards.show(details, "empty")
                }
            }
            wasEditing = editor != null
            shownPicker = state.picker?.kind?.takeIf { editor == null && state.issue != null }

            val error = state.writeError ?: state.detailError
            val status = if (state.writing) message("feedback.writing")
            else listOfNotNull(state.feedback, error?.message).joinToString("\n")
            feedback.syncText(status)
            feedbackScroll.isVisible = status.isNotBlank()
        } finally {
            updating = false
        }
    }

    private fun renderIssue(state: IssuesState, issue: Issue) {
        val content = IssueDetailsContent(
            issue,
            state.activity.items,
            showEmptyActivity = state.activity.items.isEmpty() && state.activity.next == null &&
                !state.detailLoading && state.detailError == null,
        )
        if (renderedContent != content) {
            val sameIssue = renderedContent?.issue?.number == issue.number
            val position = if (sameIssue) issueText.caretPosition else 0
            val viewport = issueScroll.viewport
            val scrollPosition = if (sameIssue) viewport.viewPosition else Point()
            issueText.text = content.toHtml()
            issueText.caretPosition = position.coerceAtMost(issueText.document.length)
            viewport.viewPosition = scrollPosition
            renderedContent = content
        }
        detailStatus.text = message("detail.loading")
        detailStatus.isVisible = state.detailLoading
        moreActivity.isVisible = state.activity.next != null
        moreActivity.isEnabled = !state.detailLoading && !state.writing
        toggleStatus.text = message(if (issue.open) "action.close" else "action.reopen")
        toggleStatus.isEnabled = !state.writing && !state.needsSignIn
        edit.isEnabled = !state.writing && !state.detailLoading && !state.needsSignIn
        editLabels.isEnabled = !state.writing && !state.detailLoading && !state.needsSignIn
        editAssignees.isEnabled = editLabels.isEnabled
        comment.syncText(state.commentDraft)
        comment.isEditable = !state.writing
        post.isEnabled = !state.writing && !state.needsSignIn && state.commentDraft.isNotBlank()
    }

    private fun renderPicker(state: IssuesState, picker: SelectionPicker) {
        val prefix = if (picker.kind == PickerKind.LABELS) "labels" else "assignees"
        pickerHeading.text = message("$prefix.heading", picker.issueNumber.toString())
        pickerSearch.emptyText.text = message("$prefix.search")
        pickerSearch.accessibleContext.accessibleName = message("$prefix.search")
        pickerList.accessibleContext.accessibleName =
            message(if (picker.kind == PickerKind.LABELS) "detail.labels" else "detail.assignees")
        pickerSearch.syncText(picker.query)
        pickerSearch.isEditable = !state.writing
        val visible = picker.visible
        if (shownPicker != picker.kind || renderedChoices != visible) {
            renderedChoices = visible
            pickerList.setItems(visible) { it }
        }
        visible.forEach { pickerList.setItemSelected(it, it in picker.selected) }
        pickerList.isEnabled = !state.writing
        pickerList.repaint()
        val status = when {
            picker.loading -> message("$prefix.loading")
            picker.error != null -> listOfNotNull(message("$prefix.failed"), picker.error.message).joinToString("\n")
            picker.available.isNullOrEmpty() && picker.original.isEmpty() -> message("$prefix.empty")
            visible.isEmpty() -> message("$prefix.noMatches")
            else -> ""
        }
        pickerStatus.syncText(status)
        pickerStatus.isVisible = status.isNotEmpty()
        assignMe.isVisible = picker.kind == PickerKind.ASSIGNEES
        assignMe.isEnabled = !state.writing && !state.needsSignIn && state.assignableViewer != null
        savePicker.text =
            message(if (picker.kind == PickerKind.LABELS) "action.saveLabels" else "action.saveAssignees")
        savePicker.isEnabled = !state.writing && !state.needsSignIn && picker.changed
        cancelPicker.isEnabled = !state.writing
    }

    private fun updateDraft() = model.editDraft(IssueDraft(title.text, description.text))

    private fun labelFor(key: String, field: JTextComponent) = JBLabel(message(key)).apply { labelFor = field }
}

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
