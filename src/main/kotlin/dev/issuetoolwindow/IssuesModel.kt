package dev.issuetoolwindow

import dev.issuetoolwindow.IssuesBundle.message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class IssueDraft(val title: String = "", val body: String = "")

internal data class IssuesState(
    val connected: Boolean = false,
    val filter: IssueFilter = IssueFilter.OPEN,
    val issues: Page<Issue> = Page(emptyList(), null),
    val selected: Long? = null,
    val issue: Issue? = null,
    val comments: Page<Comment> = Page(emptyList(), null),
    val listLoading: Boolean = false,
    val detailLoading: Boolean = false,
    val writing: Boolean = false,
    val listError: IssuesFailure? = null,
    val detailError: IssuesFailure? = null,
    val writeError: IssuesFailure? = null,
    val feedback: String? = null,
    val newIssue: IssueDraft? = null,
    val commentDraft: String = "",
) {
    val needsSignIn: Boolean
        get() = listError?.offerSignIn == true || detailError?.offerSignIn == true || writeError?.offerSignIn == true
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
    private val commentDrafts = mutableMapOf<Long, String>()

    fun connect(gateway: IssuesGateway?) {
        listJob?.cancel()
        detailJob?.cancel()
        writeJob?.cancel()
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
        if (current.writing || filter == current.filter) return
        update { copy(filter = filter, issues = Page(emptyList(), null), feedback = null) }
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
        listJob?.cancel()
        update { copy(listLoading = true, listError = null) }
        listJob = scope.launch {
            try {
                val page = source.issues(filter, next)
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
        if (current.writing || current.selected == number) return
        detailJob?.cancel()
        update {
            copy(
                selected = number,
                issue = issues.items.firstOrNull { it.number == number },
                comments = Page(emptyList(), null),
                detailLoading = false,
                detailError = null,
                writeError = null,
                feedback = null,
                commentDraft = commentDrafts[number].orEmpty(),
            )
        }
        if (number != null) loadDetail()
    }

    fun moreComments() {
        val next = current.comments.next ?: return
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
                val page = source.comments(number, next)
                ensureActive()
                update {
                    val items = if (next == null) page.items else (comments.items + page.items).distinctBy(Comment::id)
                    copy(comments = Page(items, page.next), detailLoading = false)
                }
            } catch (error: IssuesFailure) {
                ensureActive()
                update { copy(detailError = error, detailLoading = false) }
            }
        }
    }

    fun startCreate() {
        if (gateway == null || current.writing || current.newIssue != null) return
        update { copy(newIssue = IssueDraft(), writeError = null, feedback = null) }
    }

    fun editIssue(draft: IssueDraft) {
        if (!current.writing && current.newIssue != null) update { copy(newIssue = draft) }
    }

    fun cancelCreate() {
        if (!current.writing) update { copy(newIssue = null, writeError = null) }
    }

    fun create() {
        val draft = current.newIssue ?: return
        val title = draft.title.trim()
        if (title.isEmpty()) return
        write({ create(title, draft.body.takeUnless(String::isBlank)) }) { created ->
            detailJob?.cancel()
            update {
                copy(
                    filter = IssueFilter.OPEN,
                    issues = Page((listOf(created) + issues.items.filter { it.open }).distinctBy(Issue::number), null),
                    selected = created.number,
                    issue = created,
                    comments = Page(emptyList(), null),
                    detailError = null,
                    newIssue = null,
                    commentDraft = "",
                    feedback = message("feedback.created", created.number),
                )
            }
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

    private fun <T> write(request: suspend IssuesGateway.() -> T, applied: (T) -> Unit) {
        val source = gateway ?: return
        if (current.writing) return
        update { copy(writing = true, writeError = null, feedback = null) }
        writeJob = scope.launch {
            try {
                val result = source.request()
                ensureActive()
                applied(result)
                update { copy(writing = false) }
                loadList()
                loadDetail()
            } catch (error: IssuesFailure) {
                ensureActive()
                update { copy(writing = false, writeError = error) }
            }
        }
    }

    private inline fun update(transform: IssuesState.() -> IssuesState) {
        mutableState.value = current.transform()
    }
}
