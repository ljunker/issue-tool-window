package dev.issuetoolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CheckBoxList
import com.intellij.collaboration.ui.codereview.list.search.ChooserPopupUtil
import com.intellij.collaboration.ui.codereview.list.search.DropDownComponentFactory
import com.intellij.collaboration.ui.util.popup.PopupItemPresentation
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBHtmlPaneConfiguration
import com.intellij.ui.components.JBHtmlPaneStyleConfiguration
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBOptionButton
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.ExtendableHTMLViewFactory
import com.intellij.util.ui.JBUI
import dev.issuetoolwindow.IssuesBundle.message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
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
import java.net.URI
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.HyperlinkEvent
import javax.swing.text.JTextComponent

internal class IssuesPanel(
    private val model: IssuesModel,
    scope: CoroutineScope,
    signIn: () -> Unit,
    private val showDetails: (focus: Boolean) -> Unit,
) {
    val panel = JPanel(BorderLayout())
    private val signInLink = ActionLink(message("action.signInAgain")) { signIn() }
    private val signInBanner = row(signInLink).apply {
        border = JBUI.Borders.empty(0, 8, 4, 8)
        isVisible = false
    }
    private val searchField = SearchTextField(SEARCH_HISTORY).apply {
        textEditor.emptyText.text = message("search.prompt")
        textEditor.accessibleContext.accessibleName = message("search.prompt")
    }
    private val stateChip = MutableStateFlow<IssueFilter?>(IssueFilter.OPEN)
    private val sortChip = MutableStateFlow<IssueSort?>(null)
    private val labelChip = MutableStateFlow<String?>(null)
    private val assigneeChip = MutableStateFlow<String?>(null)
    private val issueRows = DefaultListModel<Issue>()
    private val issueList = JBList(issueRows).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = IssueListCellRenderer()
        accessibleContext.accessibleName = message("list.name")
    }
    private val issueScroll = ScrollPaneFactory.createScrollPane(issueList, true)

    private val feedback = textArea(2).apply { isEditable = false }
    private val feedbackScroll = JBScrollPane(feedback).apply { isVisible = false }

    private var updating = false
    private var renderedIssues = emptyList<Issue>()

    init {
        val filters = JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(2), 0)).apply {
            add(JBLabel(AllIcons.General.Filter).apply { border = JBUI.Borders.emptyRight(4) })
            add(
                DropDownComponentFactory(stateChip).create(
                    scope,
                    filterName = message("filter.state"),
                    items = listOf(IssueFilter.OPEN, IssueFilter.CLOSED),
                    onSelect = {},
                    valuePresenter = { filter: IssueFilter -> message(filter.messageKey) },
                )
            )
            add(
                DropDownComponentFactory(labelChip).create(scope, message("filter.label"), { label: String -> label }) { point ->
                    choose(point, model::labelFilterOptions) { PopupItemPresentation.Simple(it) }
                }
            )
            add(
                DropDownComponentFactory(assigneeChip).create(scope, message("filter.assignee"), ::assigneeName) { point ->
                    choose(point, model::assigneeFilterOptions) { login ->
                        if (isViewer(login)) PopupItemPresentation.Simple(message("filter.assignee.me"), null, login)
                        else PopupItemPresentation.Simple(login)
                    }
                }
            )
            add(
                DropDownComponentFactory(sortChip).create(
                    scope,
                    filterName = message("filter.sort"),
                    items = IssueSort.entries,
                    onSelect = {},
                    valuePresenter = { sort: IssueSort -> message(sort.messageKey) },
                )
            )
        }
        val header = stack(
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(8, 8, 4, 8)
                add(searchField, BorderLayout.CENTER)
            },
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(0, 6, 6, 8)
                add(filters, BorderLayout.WEST)
            },
            signInBanner,
        )
        panel.add(header, BorderLayout.NORTH)
        panel.add(issueScroll, BorderLayout.CENTER)
        panel.add(feedbackScroll, BorderLayout.SOUTH)

        scope.launch {
            stateChip.collect { chosen ->
                model.filter(chosen ?: IssueFilter.ALL)
                // The model ignores the change while an issue is written or edited.
                stateChip.value = stateChipValue(model.state.value.filter)
            }
        }
        scope.launch {
            sortChip.collect { chosen ->
                model.sort(chosen ?: IssueSort.NEWEST)
                sortChip.value = sortChipValue(model.state.value.sort, chosen)
            }
        }
        scope.launch {
            labelChip.collect { chosen ->
                model.filterLabel(chosen)
                labelChip.value = model.state.value.criteria.label
            }
        }
        scope.launch {
            assigneeChip.collect { chosen ->
                model.filterAssignee(chosen)
                assigneeChip.value = model.state.value.criteria.assignee
            }
        }
        searchField.textEditor.addActionListener { submitSearch() }
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!updating) model.editSearch(searchField.text)
            }
        })
        issueScroll.verticalScrollBar.model.addChangeListener { loadMoreWhenAtEnd() }
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

    fun render(state: IssuesState) {
        updating = true
        try {
            signInBanner.isVisible = state.needsSignIn
            signInLink.isEnabled = !state.writing
            val canNavigate = !state.writing && state.editor == null
            stateChip.value = stateChipValue(state.filter)
            sortChip.value = sortChipValue(state.sort, sortChip.value)
            labelChip.value = state.criteria.label
            assigneeChip.value = state.criteria.assignee
            if (searchField.text != state.searchInput) searchField.text = state.searchInput
            searchField.isEnabled = canNavigate
            issueList.isEnabled = canNavigate

            if (renderedIssues != state.issues.items) {
                renderedIssues = state.issues.items
                issueRows.clear()
                issueRows.addAll(renderedIssues)
            }
            issueList.selectedIndex = renderedIssues.indexOfFirst { it.number == state.selected }
            issueList.emptyText.text = when {
                state.listLoading -> message("list.loading")
                state.listError != null -> message("list.failed")
                state.searchQuery != null -> message("list.noMatches")
                else -> message("list.empty")
            }

            val error = state.listError ?: state.detailError?.takeIf { state.selected == null }
            feedback.syncText(error?.message.orEmpty())
            feedbackScroll.isVisible = error != null
        } finally {
            updating = false
        }
        // A short first page does not fill the list, so nothing could be scrolled to load the next one.
        SwingUtilities.invokeLater(::loadMoreWhenAtEnd)
    }

    /** Shows the options of the connected repository, so a repository change never offers stale values. */
    private suspend fun choose(
        point: RelativePoint,
        options: suspend () -> List<String>,
        presentation: (String) -> PopupItemPresentation,
    ): String? {
        val loaded = flow {
            emit(
                try {
                    Result.success(options())
                } catch (error: IssuesFailure) {
                    Result.failure(error)
                }
            )
        }
        return ChooserPopupUtil.showAsyncChooserPopup(point, loaded, presentation)
    }

    private fun isViewer(login: String) = model.state.value.viewer?.equals(login, ignoreCase = true) == true

    private fun assigneeName(login: String) = if (isViewer(login)) message("filter.assignee.meShort") else login

    private fun loadMoreWhenAtEnd() {
        val state = model.state.value
        if (state.issues.next == null || state.listLoading || state.listError != null) return
        if (state.writing || state.editor != null || !panel.isShowing) return
        val bar = issueScroll.verticalScrollBar.model
        val threshold = JBUI.scale(LOAD_MORE_DISTANCE)
        if (bar.value + bar.extent >= bar.maximum - threshold) model.moreIssues()
    }

    private fun openSelectedIssue(focus: Boolean) {
        val number = issueList.selectedValue?.number ?: return
        model.select(number)
        if (model.state.value.selected == number) showDetails(focus)
    }

    private fun submitSearch() {
        val state = model.state.value
        if (state.searchInput.isBlank() || state.writing || state.editor != null) return
        searchField.addCurrentTextToHistory()
        model.search()
        if (model.state.value.selected != null) showDetails(false)
    }

    private companion object {
        const val SEARCH_HISTORY = "dev.issuetoolwindow.search.history"
        const val LOAD_MORE_DISTANCE = 120

        fun stateChipValue(filter: IssueFilter) = filter.takeIf { it != IssueFilter.ALL }

        /** The default order shows as an empty chip unless the user picked it explicitly. */
        fun sortChipValue(sort: IssueSort, shown: IssueSort?) =
            if (sort == IssueSort.NEWEST && shown == null) null else sort
    }
}

internal class IssueDetailsPanel(
    private val model: IssuesModel,
    openOnGitHub: (Long) -> Unit,
    openLink: (URI) -> Unit,
    createBranch: (Issue) -> Unit,
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
                event.description?.let(IssueMarkdown::browserUri)?.let(openLink)
            }
        }
    }
    private val issueScroll = JBScrollPane(issueText).apply { border = JBUI.Borders.empty() }
    private val moreActivity = button("action.more", model::moreActivity)
    private val close = action("action.close") { model.close(CloseReason.COMPLETED) }
    private val closeOptions = arrayOf<Action>(
        action("action.closeCompleted") { model.close(CloseReason.COMPLETED) },
        action("action.closeNotPlanned") { model.close(CloseReason.NOT_PLANNED) },
    )
    private val reopen = action("action.reopen", model::reopen)
    private val status = JBOptionButton(close, closeOptions)
    private val edit = button("action.edit", model::startEdit)
    private val editLabels = button("action.labels", model::startLabels)
    private val editAssignees = button("action.assignees", model::startAssignees)
    private val branch = button("action.branch") { model.state.value.issue?.let(createBranch) }
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
            add(stack(row(status, edit, editLabels, editAssignees, branch, browser), detailStatus), BorderLayout.NORTH)
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
        val canChangeStatus = !state.writing && !state.needsSignIn
        (closeOptions + close + reopen).forEach { it.isEnabled = canChangeStatus }
        // Closing offers a reason like GitHub; reopening has none.
        val statusAction = if (issue.open) close else reopen
        if (status.action !== statusAction) {
            status.action = statusAction
            status.options = if (issue.open) closeOptions else null
        }
        edit.isEnabled = !state.writing && !state.detailLoading && !state.needsSignIn
        editLabels.isEnabled = !state.writing && !state.detailLoading && !state.needsSignIn
        editAssignees.isEnabled = editLabels.isEnabled
        branch.isEnabled = !state.writing
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

private fun action(key: String, perform: () -> Unit): Action = object : AbstractAction(message(key)) {
    override fun actionPerformed(e: ActionEvent) = perform()
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
