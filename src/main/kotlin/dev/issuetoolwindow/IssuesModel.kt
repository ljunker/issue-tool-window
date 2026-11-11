package dev.issuetoolwindow

import dev.issuetoolwindow.IssuesBundle.message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class IssueDraft(val title: String = "", val body: String = "")

internal data class IssueEditor(val draft: IssueDraft, val issueNumber: Long? = null)

internal data class LabelPicker(
    val issueNumber: Long,
    val original: Set<String>,
    val selected: Set<String>,
    /** Repository labels; `null` while they load or after loading failed. */
    val available: List<String>? = null,
    val query: String = "",
    val loading: Boolean = true,
    val error: IssuesFailure? = null,
) {
    /** Repository labels plus labels of the issue that no longer exist in the repository, filtered by [query]. */
    val visible: List<String>
        get() {
            val all = available.orEmpty()
            val missing = original.filter { it !in all }.sortedWith(String.CASE_INSENSITIVE_ORDER)
            val term = query.trim()
            return (all + missing).filter { term.isEmpty() || it.contains(term, ignoreCase = true) }
        }

    val added: List<String> get() = selected.filter { it !in original }
    val removed: List<String> get() = original.filter { it !in selected }
    val changed: Boolean get() = selected != original
}

internal data class IssuesState(
    val connected: Boolean = false,
    val filter: IssueFilter = IssueFilter.OPEN,
    val searchInput: String = "",
    val searchQuery: String? = null,
    val issues: Page<Issue> = Page(emptyList(), null),
    val selected: Long? = null,
    val issue: Issue? = null,
    val activity: Page<IssueActivity> = Page(emptyList(), null),
    val listLoading: Boolean = false,
    val detailLoading: Boolean = false,
    val writing: Boolean = false,
    val listError: IssuesFailure? = null,
    val detailError: IssuesFailure? = null,
    val writeError: IssuesFailure? = null,
    val feedback: String? = null,
    val editor: IssueEditor? = null,
    val commentDraft: String = "",
    val labelPicker: LabelPicker? = null,
) {
    val needsSignIn: Boolean
        get() = listOf(listError, detailError, writeError, labelPicker?.error).any { it?.offerSignIn == true }
}

/** All model calls and state changes run on the tool window's UI scope. */
internal class IssuesModel(private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(IssuesState())
    val state = mutableState.asStateFlow()
    private val current get() = mutableState.value

    private var gateway: IssuesGateway? = null
    private var listJob: Job? = null
    private var detailJob: Job? = null
    private var writeJob: Job? = null
    private var labelsJob: Job? = null
    private val commentDrafts = mutableMapOf<Long, String>()

    fun connect(gateway: IssuesGateway?) {
        listJob?.cancel()
        detailJob?.cancel()
        writeJob?.cancel()
        labelsJob?.cancel()
        commentDrafts.clear()
        this.gateway = gateway
        mutableState.value = IssuesState(connected = gateway != null)
        if (gateway != null) loadList()
    }

    fun refresh() {
        if (current.writing) return
        update { copy(writeError = null, feedback = null) }
        loadList()
        if (current.selected != null) loadDetail()
    }

    fun filter(filter: IssueFilter) {
        if (current.writing || current.editor != null || filter == current.filter) return
        update { copy(filter = filter) }
        showList(current.searchQuery)
    }

    fun editSearch(input: String) {
        if (!current.connected || current.writing || current.editor != null) return
        update { copy(searchInput = input) }
        if (input.isBlank() && current.searchQuery != null) showList(null)
    }

    fun search() {
        if (!current.connected || current.writing || current.editor != null) return
        val input = current.searchInput.trim()
        val digits = input.drop(1)
        if (input.startsWith('#') && digits.isNotEmpty() && digits.all { it in '0'..'9' }) {
            if (current.searchQuery != null) showList(null) else select(null)
            val number = digits.toLongOrNull()
            if (number == null || number == 0L) {
                update { copy(detailError = IssuesFailure(message("error.issueNotFound", digits))) }
            } else {
                select(number)
            }
        } else if (input.isNotEmpty() || current.searchQuery != null) {
            showList(input.ifEmpty { null })
        }
    }

    private fun showList(query: String?) {
        update { copy(searchQuery = query, issues = Page(emptyList(), null), detailError = null, feedback = null) }
        select(null)
        loadList()
    }

    fun moreIssues() {
        val next = current.issues.next ?: return
        if (!current.listLoading) loadList(next)
    }

    private fun loadList(next: String? = null) {
        val source = gateway ?: return
        val filter = current.filter
        val query = current.searchQuery
        listJob?.cancel()
        update { copy(listLoading = true, listError = null) }
        listJob = scope.launch {
            try {
                val page = if (query == null) source.issues(filter, next) else source.search(query, filter, next)
                ensureActive()
                update {
                    val items = if (next == null) page.items else (issues.items + page.items).distinctBy(Issue::number)
                    copy(issues = Page(items, page.next), listLoading = false)
                }
            } catch (error: IssuesFailure) {
                ensureActive()
                update { copy(listError = error, listLoading = false) }
            }
        }
    }

    fun select(number: Long?) {
        if (current.writing || current.editor != null || current.selected == number) return
        detailJob?.cancel()
        labelsJob?.cancel()
        update {
            copy(
                selected = number,
                issue = issues.items.firstOrNull { it.number == number },
                activity = Page(emptyList(), null),
                detailLoading = false,
                detailError = null,
                writeError = null,
                feedback = null,
                commentDraft = commentDrafts[number].orEmpty(),
                labelPicker = null,
            )
        }
        if (number != null) loadDetail()
    }

    fun moreActivity() {
        val next = current.activity.next ?: return
        if (!current.detailLoading) loadDetail(next)
    }

    private fun loadDetail(next: String? = null) {
        val source = gateway ?: return
        val number = current.selected ?: return
        detailJob?.cancel()
        update { copy(detailLoading = true, detailError = null) }
        detailJob = scope.launch {
            try {
                if (next == null) {
                    val issue = source.issue(number)
                    ensureActive()
                    update { copy(issue = issue) }
                }
                val page = source.activity(number, next)
                ensureActive()
                update {
                    val items = if (next == null) page.items else (activity.items + page.items).distinctBy(IssueActivity::id)
                    copy(activity = Page(items, page.next), detailLoading = false)
                }
            } catch (error: IssuesFailure) {
                ensureActive()
                update { copy(detailError = error, detailLoading = false) }
            }
        }
    }

    fun startCreate() {
        openEditor(IssueEditor(IssueDraft()))
    }

    fun startEdit() {
        val issue = current.issue ?: return
        openEditor(IssueEditor(IssueDraft(issue.title, issue.body), issue.number))
    }

    private fun openEditor(editor: IssueEditor) {
        if (gateway == null || current.writing || current.editor != null) return
        labelsJob?.cancel()
        update { copy(editor = editor, labelPicker = null, writeError = null, feedback = null) }
    }

    fun editDraft(draft: IssueDraft) {
        val editor = current.editor ?: return
        if (!current.writing) update { copy(editor = editor.copy(draft = draft)) }
    }

    fun cancelEditor() {
        if (!current.writing) update { copy(editor = null, writeError = null) }
    }

    fun saveEditor() {
        val (draft, number) = current.editor ?: return
        val title = draft.title.trim()
        if (title.isEmpty()) return
        if (number == null) createIssue(title, draft.body.takeUnless(String::isBlank))
        else saveChanges(number, title, draft.body)
    }

    private fun createIssue(title: String, body: String?) {
        write({ create(title, body) }) { created ->
            detailJob?.cancel()
            update {
                copy(
                    filter = IssueFilter.OPEN,
                    searchInput = "",
                    searchQuery = null,
                    issues = Page((listOf(created) + issues.items.filter { it.open }).distinctBy(Issue::number), null),
                    selected = created.number,
                    issue = created,
                    activity = Page(emptyList(), null),
                    detailError = null,
                    editor = null,
                    commentDraft = "",
                    feedback = message("feedback.created", created.number),
                )
            }
        }
    }

    private fun saveChanges(number: Long, title: String, body: String) {
        write({ updateIssue(number, title, body) }, refreshAfter = false) { changed ->
            // A read started before this write can still contain the old title or description.
            listJob?.cancel()
            detailJob?.cancel()
            update {
                val updatedList = if (searchQuery != null) Page(emptyList(), null) else {
                    issues.copy(items = issues.items.map { if (it.number == number) changed else it }
                        .filter(filter::includes))
                }
                copy(
                    issue = changed,
                    issues = updatedList,
                    listLoading = false,
                    detailLoading = false,
                    editor = null,
                    feedback = message("feedback.updated", number),
                )
            }
            if (current.searchQuery != null) loadList()
        }
    }

    fun editComment(body: String) {
        val number = current.selected ?: return
        if (current.writing) return
        if (body.isEmpty()) commentDrafts.remove(number) else commentDrafts[number] = body
        update { copy(commentDraft = body) }
    }

    fun comment() {
        val issue = current.issue ?: return
        val draft = current.commentDraft
        if (draft.isBlank()) return
        write({ addComment(issue.number, draft) }) {
            commentDrafts.remove(issue.number)
            update { copy(commentDraft = "", feedback = message("feedback.commented")) }
        }
    }

    fun toggleStatus() {
        val issue = current.issue ?: return
        write({ setOpen(issue.number, !issue.open) }) { changed ->
            update {
                val items =
                    issues.items.map { if (it.number == changed.number) changed else it }.filter(filter::includes)
                copy(
                    issue = changed,
                    issues = issues.copy(items = items),
                    feedback = message(if (changed.open) "feedback.reopened" else "feedback.closed", changed.number),
                )
            }
        }
    }

    fun startLabels() {
        val source = gateway ?: return
        val issue = current.issue ?: return
        if (current.writing || current.editor != null || current.labelPicker != null) return
        val labels = issue.labels.toSet()
        update { copy(labelPicker = LabelPicker(issue.number, labels, labels), writeError = null, feedback = null) }
        labelsJob?.cancel()
        labelsJob = scope.launch {
            try {
                val available = source.labels()
                ensureActive()
                updatePicker { copy(available = available, loading = false) }
            } catch (error: IssuesFailure) {
                ensureActive()
                updatePicker { copy(loading = false, error = error) }
            }
        }
    }

    fun searchLabels(query: String) {
        updatePicker { copy(query = query) }
    }

    fun toggleLabel(name: String) {
        if (current.writing) return
        updatePicker { copy(selected = if (name in selected) selected - name else selected + name) }
    }

    fun cancelLabels() {
        if (current.writing) return
        labelsJob?.cancel()
        update { copy(labelPicker = null) }
    }

    fun saveLabels() {
        val picker = current.labelPicker ?: return
        if (!picker.changed) return cancelLabels()
        val number = picker.issueNumber
        val added = picker.added
        val removed = picker.removed
        write(
            { changeLabels(number, added, removed) },
            // Some changes may have been saved before the failure, so show what GitHub stores now.
            failed = {
                labelsJob?.cancel()
                update { copy(labelPicker = null) }
                loadDetail()
            },
        ) { changed ->
            labelsJob?.cancel()
            val ignored = added.filter { it !in changed.labels } + removed.filter { it in changed.labels }
            update {
                copy(
                    issue = changed,
                    issues = issues.copy(items = issues.items.map { if (it.number == number) changed else it }),
                    labelPicker = null,
                    feedback = if (ignored.isEmpty()) message("feedback.labels", number) else null,
                    writeError = if (ignored.isEmpty()) null
                    else IssuesFailure(message("error.labelsIgnored", ignored.joinToString(", "))),
                )
            }
        }
    }

    private inline fun updatePicker(transform: LabelPicker.() -> LabelPicker) {
        val picker = current.labelPicker ?: return
        update { copy(labelPicker = picker.transform()) }
    }

    private fun <T> write(
        request: suspend IssuesGateway.() -> T,
        refreshAfter: Boolean = true,
        failed: () -> Unit = {},
        applied: (T) -> Unit,
    ) {
        val source = gateway ?: return
        if (current.writing) return
        update { copy(writing = true, writeError = null, feedback = null) }
        writeJob = scope.launch {
            try {
                val result = source.request()
                ensureActive()
                applied(result)
                update { copy(writing = false) }
                if (refreshAfter) {
                    loadList()
                    loadDetail()
                }
            } catch (error: IssuesFailure) {
                ensureActive()
                update { copy(writing = false, writeError = error) }
                failed()
            }
        }
    }

    private inline fun update(transform: IssuesState.() -> IssuesState) {
        mutableState.value = current.transform()
    }
}
