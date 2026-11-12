package dev.issuetoolwindow

import dev.issuetoolwindow.IssuesBundle.message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Labels and assignees are only sent when an issue is created; editing an issue changes them with the pickers. */
internal data class IssueDraft(
    val title: String = "",
    val body: String = "",
    val labels: List<String> = emptyList(),
    val assignees: List<String> = emptyList(),
)

internal data class IssueEditor(val draft: IssueDraft, val issueNumber: Long? = null)

/** Markdown of an existing comment of issue [issueNumber] while it is edited. */
internal data class CommentEditor(val commentId: String, val issueNumber: Long, val original: String, val body: String) {
    val changed: Boolean get() = body != original
}

internal enum class PickerKind { LABELS, ASSIGNEES }

internal fun statusFeedback(issue: Issue): String =
    if (issue.open) message("feedback.reopened", issue.number)
    else message("feedback.closed.${issue.stateSuffix}", issue.number)

/** Checkbox selection of labels or assignees for one issue. */
internal data class SelectionPicker(
    val kind: PickerKind,
    val issueNumber: Long,
    val original: Set<String>,
    val selected: Set<String>,
    /** Values offered by the repository; `null` while they load or after loading failed. */
    val available: List<String>? = null,
    val query: String = "",
    val loading: Boolean = true,
    val error: IssuesFailure? = null,
) {
    /** Repository values plus values of the issue that the repository no longer offers, filtered by [query]. */
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

/** Status, order and filters of the issue list that are kept per project; the search text is not part of them. */
internal data class IssueListSettings(
    val filter: IssueFilter = IssueFilter.OPEN,
    val sort: IssueSort = IssueSort.NEWEST,
    val criteria: IssueCriteria = IssueCriteria(),
)

internal data class IssuesState(
    val connected: Boolean = false,
    val filter: IssueFilter = IssueFilter.OPEN,
    val sort: IssueSort = IssueSort.NEWEST,
    val criteria: IssueCriteria = IssueCriteria(),
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
    val commentEditor: CommentEditor? = null,
    val picker: SelectionPicker? = null,
    /** Login of the active GitHub account. */
    val viewer: String? = null,
) {
    val settings: IssueListSettings get() = IssueListSettings(filter, sort, criteria)

    /** Whether [issue] belongs in the list with the active status, label and assignee filters. */
    fun shows(issue: Issue): Boolean = filter.includes(issue) && criteria.includes(issue)

    val needsSignIn: Boolean
        get() = listOf(listError, detailError, writeError, picker?.error).any { it?.offerSignIn == true }

    /** The active account in the open assignee picker, if it is assignable and not yet selected. */
    val assignableViewer: String?
        get() {
            val picker = picker?.takeIf { it.kind == PickerKind.ASSIGNEES } ?: return null
            val viewer = viewer ?: return null
            if (picker.selected.any { it.equals(viewer, ignoreCase = true) }) return null
            return picker.available.orEmpty().firstOrNull { it.equals(viewer, ignoreCase = true) }
        }
}

/** All model calls and state changes run on the tool window's UI scope. */
internal class IssuesModel(private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(IssuesState())
    val state = mutableState.asStateFlow()
    private val current get() = mutableState.value

    private var gateway: IssuesGateway? = null
    var connectionVersion: Long = 0
        private set
    private var listJob: Job? = null
    private var detailJob: Job? = null
    private var writeJob: Job? = null
    private var pickerJob: Job? = null
    private var assignedJob: Job? = null
    private val commentDrafts = mutableMapOf<Long, String>()
    private var labelOptions: List<String>? = null
    private var assigneeOptions: List<String>? = null
    private var markdownIssuePage: Pair<TimeMark, List<Issue>>? = null
    private var milestoneOptions: List<Milestone>? = null
    private var templateOptions: List<IssueTemplate>? = null
    /** Whether the criteria restored on [connect] still need to be checked against the repository. */
    private var criteriaUnchecked = false
    /** Background updates that failed in a row since the last success, connection or manual refresh. */
    private var pollFailures = 0
    /** Set when a background update found the credentials missing or invalid; only a refresh or connection clears it. */
    private var pollNeedsSignIn = false
    /** Open issues known to be assigned to the active account; `null` until the first check after connecting. */
    private var knownAssigned: Set<Long>? = null

    /**
     * Time until the next background update: [POLL_INTERVAL], doubled with every background failure in a row up to
     * [MAX_POLL_INTERVAL].
     */
    val pollInterval: Duration
        get() = if (pollFailures == 0) POLL_INTERVAL
        else (POLL_INTERVAL * (1 shl pollFailures.coerceAtMost(MAX_BACKOFF_STEPS))).coerceAtMost(MAX_POLL_INTERVAL)

    /**
     * Connects to [gateway] and shows its list with [settings]. A saved label, assignee or milestone that the repository
     * no longer offers is dropped silently before the first page loads; when the options cannot be loaded, the values
     * stay.
     */
    fun connect(gateway: IssuesGateway?, viewer: String? = null, settings: IssueListSettings = IssueListSettings()) {
        listJob?.cancel()
        detailJob?.cancel()
        writeJob?.cancel()
        pickerJob?.cancel()
        assignedJob?.cancel()
        commentDrafts.clear()
        resetPolling()
        knownAssigned = null
        labelOptions = null
        assigneeOptions = null
        markdownIssuePage = null
        milestoneOptions = null
        templateOptions = null
        this.gateway = gateway
        connectionVersion++
        criteriaUnchecked = gateway != null && settings.criteria != IssueCriteria()
        mutableState.value = if (gateway == null) IssuesState() else IssuesState(
            connected = true,
            filter = settings.filter,
            sort = settings.sort,
            criteria = settings.criteria,
            viewer = viewer,
        )
        if (gateway != null) loadList()
    }

    /**
     * [criteria] without a label, assignee or milestone that the connected repository does not offer (any more). A
     * milestone is matched by number and takes the title GitHub reports now.
     */
    private suspend fun validCriteria(criteria: IssueCriteria): IssueCriteria {
        suspend fun valid(value: String?, options: suspend () -> List<String>): String? {
            if (value == null) return null
            val offered = try {
                options()
            } catch (_: IssuesFailure) {
                return value
            }
            return offered.firstOrNull { it.equals(value, ignoreCase = true) }
        }
        suspend fun validMilestone(filter: MilestoneFilter?): MilestoneFilter? {
            if (filter !is MilestoneFilter.Of) return filter
            val offered = try {
                milestoneFilterOptions()
            } catch (_: IssuesFailure) {
                return filter
            }
            return offered.firstOrNull { it.number == filter.milestone.number }?.let(MilestoneFilter::Of)
        }
        return IssueCriteria(
            label = valid(criteria.label, ::labelFilterOptions),
            assignee = valid(criteria.assignee, ::assigneeFilterOptions),
            milestone = validMilestone(criteria.milestone),
        )
    }

    fun refresh() {
        if (current.writing) return
        update { copy(writeError = null, feedback = null) }
        templateOptions = null
        markdownIssuePage = null
        resetPolling()
        loadList()
        if (current.selected != null) loadDetail()
    }

    /** Reloads only the shown issue and its activity, e.g. from the issue tab while the tool window is hidden. */
    fun refreshDetail() {
        if (current.writing || current.selected == null) return
        update { copy(writeError = null, feedback = null) }
        loadDetail()
    }

    fun filter(filter: IssueFilter) {
        if (current.writing || current.editor != null || filter == current.filter) return
        update { copy(filter = filter) }
        showList(current.searchQuery)
    }

    fun filterLabel(label: String?) = criteria(current.criteria.copy(label = label))

    fun filterAssignee(login: String?) = criteria(current.criteria.copy(assignee = login))

    fun filterMilestone(milestone: MilestoneFilter?) = criteria(current.criteria.copy(milestone = milestone))

    private fun criteria(criteria: IssueCriteria) {
        if (!current.connected || current.writing || current.editor != null || criteria == current.criteria) return
        update { copy(criteria = criteria) }
        showList(current.searchQuery)
    }

    /** Labels of the connected repository to filter by, loaded once per connection. */
    suspend fun labelFilterOptions(): List<String> {
        val source = gateway ?: return emptyList()
        return labelOptions ?: source.labels().also { if (gateway === source) labelOptions = it }
    }

    /**
     * People of the connected repository to filter by, loaded once per connection. The active account comes first,
     * so it can be offered as "Assigned to me" even when the repository does not list it.
     */
    suspend fun assigneeFilterOptions(): List<String> {
        val source = gateway ?: return emptyList()
        val people = assignablePeople(source)
        val viewer = current.viewer ?: return people
        return listOf(viewer) + people.filterNot { it.equals(viewer, ignoreCase = true) }
    }

    private suspend fun assignablePeople(source: IssuesGateway): List<String> {
        assigneeOptions?.let { return it }
        val connection = connectionVersion
        return source.assignees().also { if (connectionVersion == connection) assigneeOptions = it }
    }

    suspend fun markdownSuggestions(reference: MarkdownReference): List<MarkdownSuggestion> {
        val source = gateway ?: return emptyList()
        if (current.needsSignIn) return emptyList()
        val connection = connectionVersion
        val suggestions = try {
            if (reference.marker == '@') {
                assignablePeople(source).asSequence()
                    .filter { it.startsWith(reference.prefix, ignoreCase = true) }
                    .take(10)
                    .map { MarkdownSuggestion("@$it") }
                    .toList()
            } else {
                markdownIssueSuggestions(source, connection, reference.prefix)
            }
        } catch (_: IssuesFailure) {
            return emptyList()
        }
        return suggestions.takeIf { connectionVersion == connection && !current.needsSignIn }.orEmpty()
    }

    private suspend fun markdownIssueSuggestions(
        source: IssuesGateway,
        connection: Long,
        prefix: String,
    ): List<MarkdownSuggestion> {
        val recent = markdownIssuePage?.takeIf { it.first.elapsedNow() < 30.seconds }?.second
            ?: source.issues(IssueFilter.ALL, IssueSort.RECENTLY_UPDATED, IssueCriteria(), null).items.also {
                if (connectionVersion == connection) markdownIssuePage = TimeSource.Monotonic.markNow() to it
            }
        if (connectionVersion != connection) return emptyList()
        val matches = recent.filter { it.number.toString().startsWith(prefix) }
        val number = prefix.toLongOrNull()?.takeIf { it > 0 }
        val exact = matches.firstOrNull { it.number == number } ?: number?.let {
            try {
                source.issue(it)
            } catch (error: IssuesFailure) {
                if (!error.missing) throw error
                null
            }
        }
        return (listOfNotNull(exact) + matches.filterNot { it.number == exact?.number }).take(10).map {
            MarkdownSuggestion("#${it.number}", "#${it.number} ${it.title}")
        }
    }

    /** Milestones of the connected repository, open and closed, loaded once per connection. */
    suspend fun milestoneFilterOptions(): List<Milestone> {
        val source = gateway ?: return emptyList()
        return milestoneOptions ?: source.milestones().also { if (gateway === source) milestoneOptions = it }
    }

    /** Open milestones of the connected repository to set on an issue; loaded freshly, as they change more often. */
    suspend fun openMilestones(): List<Milestone> {
        val source = gateway ?: return emptyList()
        val milestones = source.milestones()
        if (gateway === source) milestoneOptions = milestones
        return milestones.filter(Milestone::open)
    }

    fun sort(sort: IssueSort) {
        if (current.writing || current.editor != null || sort == current.sort) return
        update { copy(sort = sort) }
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
        val sort = current.sort
        var criteria = current.criteria
        val query = current.searchQuery
        listJob?.cancel()
        update { copy(listLoading = true, listError = null) }
        listJob = scope.launch {
            try {
                if (criteriaUnchecked) {
                    criteria = validCriteria(criteria)
                    ensureActive()
                    criteriaUnchecked = false
                    update { copy(criteria = criteria) }
                }
                val page = if (query == null) source.issues(filter, sort, criteria, next)
                else source.search(query, filter, sort, criteria, next)
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
        pickerJob?.cancel()
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
                commentEditor = null,
                picker = null,
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
                    update {
                        copy(
                            issue = issue,
                            issues = issues.copy(items = issues.items.map { if (it.number == number) issue else it }),
                        )
                    }
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

    /**
     * Reloads the shown issue in the background, so changes made elsewhere appear without a loading indicator. Reloads
     * at least as much activity as is shown; when all of it was loaded, new entries at the end appear as well. Skips
     * while anything else is loading or written and keeps the shown state when the request fails.
     */
    fun pollDetail() {
        val source = gateway ?: return
        val number = current.selected ?: return
        val state = current
        if (state.issue == null || state.writing || state.detailLoading || state.detailError != null) return
        if (detailJob?.isActive == true) return
        val shown = state.activity.items.size
        val complete = state.activity.next == null
        detailJob = scope.launch {
            try {
                val issue = source.issue(number)
                var page = source.activity(number, null)
                val items = page.items.toMutableList()
                while (page.next != null && (complete || items.size < shown)) {
                    page = source.activity(number, page.next)
                    items += page.items
                }
                ensureActive()
                update {
                    copy(
                        issue = issue,
                        issues = issues.copy(items = issues.items.map { if (it.number == number) issue else it }),
                        activity = Page(items.distinctBy(IssueActivity::id), page.next),
                    )
                }
            } catch (_: IssuesFailure) {
                // The next poll or a manual refresh tries again; a background failure must not hide the shown issue.
            }
        }
    }

    /**
     * Reloads the list in the background with the active status, order, filters and search, so changes made elsewhere
     * appear without a loading indicator. Reloads at least as many issues as are shown, so the selection, scroll
     * position and loaded pages stay. Skips while anything else is loaded, written or edited and while the list shows
     * an error. A failure keeps the shown list and makes [pollInterval] longer instead of showing an error.
     */
    fun pollList() {
        val source = gateway ?: return
        val state = current
        if (pollNeedsSignIn || !state.connected || criteriaUnchecked) return
        if (state.writing || state.editor != null || state.listLoading || state.listError != null) return
        if (listJob?.isActive == true) return
        val filter = state.filter
        val sort = state.sort
        val criteria = state.criteria
        val query = state.searchQuery
        val shown = state.issues.items.size
        listJob = scope.launch {
            try {
                suspend fun load(next: String?) = if (query == null) source.issues(filter, sort, criteria, next)
                else source.search(query, filter, sort, criteria, next)
                var page = load(null)
                val items = page.items.toMutableList()
                while (page.next != null && items.size < shown) {
                    page = load(page.next)
                    items += page.items
                }
                ensureActive()
                pollSucceeded()
                val now = current
                if (now.filter != filter || now.sort != sort || now.criteria != criteria || now.searchQuery != query) {
                    return@launch
                }
                if (now.writing || now.listLoading) return@launch
                update { copy(issues = Page(items.distinctBy(Issue::number), page.next)) }
            } catch (error: IssuesFailure) {
                ensureActive()
                pollFailed(error)
            }
        }
    }

    /**
     * Checks in the background which open issues are assigned to the active account and passes those that were not
     * assigned at the previous check to [assigned]. The first check after connecting only notes the current ones. Only
     * the most recently updated issues are checked, as assigning an issue updates it.
     */
    fun pollAssigned(assigned: (List<Issue>) -> Unit) {
        val source = gateway ?: return
        val viewer = current.viewer ?: return
        if (pollNeedsSignIn || !current.connected || assignedJob?.isActive == true) return
        assignedJob = scope.launch {
            try {
                val page = source.issues(IssueFilter.OPEN, IssueSort.RECENTLY_UPDATED, IssueCriteria(assignee = viewer), null)
                ensureActive()
                pollSucceeded()
                val known = knownAssigned
                // Issues stay known once seen, so one that drops off the first page does not count as new later.
                knownAssigned = known.orEmpty() + page.items.map(Issue::number)
                if (known == null) return@launch
                val fresh = page.items.filter { it.number !in known }
                if (fresh.isNotEmpty()) assigned(fresh)
            } catch (error: IssuesFailure) {
                ensureActive()
                pollFailed(error)
            }
        }
    }

    /** Forgets the assigned issues, e.g. while notifications are off, so the next check only notes them again. */
    fun forgetAssigned() {
        assignedJob?.cancel()
        knownAssigned = null
    }

    /** Issues the active account assigns to itself in the tool window are no news for it. */
    private fun noteAssigned(issue: Issue) {
        val viewer = current.viewer ?: return
        val known = knownAssigned ?: return
        if (issue.open && issue.assignees.any { it.equals(viewer, ignoreCase = true) }) knownAssigned = known + issue.number
    }

    private fun pollSucceeded() {
        pollFailures = 0
    }

    private fun pollFailed(error: IssuesFailure) {
        if (error.offerSignIn) pollNeedsSignIn = true
        pollFailures++
    }

    private fun resetPolling() {
        pollFailures = 0
        pollNeedsSignIn = false
    }

    /**
     * Issue templates of the connected repository, loaded once per connection or [refresh]. A failure counts as no
     * templates, so creating an issue works as without them.
     */
    suspend fun issueTemplates(): List<IssueTemplate> {
        val source = gateway ?: return emptyList()
        templateOptions?.let { return it }
        val templates = try {
            source.issueTemplates()
        } catch (_: IssuesFailure) {
            return emptyList()
        }
        if (gateway === source) templateOptions = templates
        return templates
    }

    /** Opens the editor for a new issue, empty or filled in from a template. */
    fun startCreate(draft: IssueDraft = IssueDraft()) {
        openEditor(IssueEditor(draft))
    }

    fun startEdit() {
        val issue = current.issue ?: return
        openEditor(IssueEditor(IssueDraft(issue.title, issue.body), issue.number))
    }

    private fun openEditor(editor: IssueEditor) {
        if (gateway == null || current.writing || current.editor != null) return
        pickerJob?.cancel()
        update { copy(editor = editor, picker = null, writeError = null, feedback = null) }
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
        if (number == null) {
            val body = draft.body.takeUnless(String::isBlank)
            createIssue(title, body, cleanedNames(draft.labels), cleanedNames(draft.assignees))
        } else {
            saveChanges(number, title, draft.body)
        }
    }

    private fun createIssue(title: String, body: String?, labels: List<String>, assignees: List<String>) {
        write({ create(title, body, labels, assignees) }) { created ->
            noteAssigned(created)
            detailJob?.cancel()
            update {
                copy(
                    filter = IssueFilter.OPEN,
                    criteria = IssueCriteria(),
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
                    issues.copy(items = issues.items.map { if (it.number == number) changed else it }.filter(::shows))
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

    /** Opens the Markdown of the shown comment [id] for editing, if GitHub lets the active account edit it. */
    fun startEditComment(id: String) {
        val state = current
        if (gateway == null || state.writing || state.editor != null || state.picker != null) return
        if (state.commentEditor != null) return
        val number = state.selected ?: return
        val comment = state.activity.items.firstOrNull { it.id == id } as? Comment ?: return
        if (!comment.canEdit) return
        update {
            copy(commentEditor = CommentEditor(id, number, comment.body, comment.body), writeError = null, feedback = null)
        }
    }

    fun editCommentEditor(body: String) {
        val editor = current.commentEditor ?: return
        if (!current.writing) update { copy(commentEditor = editor.copy(body = body)) }
    }

    fun cancelCommentEditor() {
        if (!current.writing) update { copy(commentEditor = null, writeError = null) }
    }

    /** Saves the edited comment and shows it as GitHub stores it afterwards. */
    fun saveCommentEditor() {
        val editor = current.commentEditor ?: return
        if (editor.body.isBlank()) return
        if (!editor.changed) return cancelCommentEditor()
        val failed = { commentFailed(editor.issueNumber) }
        write({ updateComment(editor.commentId, editor.body) }, refreshAfter = false, failed = failed) { saved ->
            // A read started before this write can still contain the old text.
            detailJob?.cancel()
            update {
                copy(
                    activity = activity.copy(items = activity.items.map { if (it.id == saved.id) saved else it }),
                    detailLoading = false,
                    commentEditor = null,
                    feedback = message("feedback.commentUpdated"),
                )
            }
        }
    }

    /** Deletes the shown comment [id]; the caller asks for confirmation first. */
    fun deleteComment(id: String) {
        val state = current
        if (state.editor != null || state.picker != null || state.commentEditor != null) return
        val number = state.selected ?: return
        val comment = state.activity.items.firstOrNull { it.id == id } as? Comment ?: return
        if (!comment.canDelete) return
        write({ deleteComment(id) }, failed = { commentFailed(number) }) {
            update {
                copy(
                    activity = activity.copy(items = activity.items.filterNot { it.id == id }),
                    feedback = message("feedback.commentDeleted"),
                )
            }
        }
    }

    /** A comment deleted in the meantime disappears together with its editor; other failures keep the edited text. */
    private fun commentFailed(number: Long) {
        if (current.writeError?.missing != true || current.selected != number) return
        update { copy(commentEditor = null) }
        loadDetail()
    }

    fun close(reason: CloseReason) {
        val issue = current.issue?.takeIf { it.open } ?: return
        changeStatus { close(issue.number, reason) }
    }

    fun reopen() {
        val issue = current.issue?.takeIf { !it.open } ?: return
        changeStatus { reopen(issue.number) }
    }

    private fun changeStatus(change: suspend IssuesGateway.() -> Issue) {
        write(change) { changed ->
            update {
                val items =
                    issues.items.map { if (it.number == changed.number) changed else it }.filter(::shows)
                copy(
                    issue = changed,
                    issues = issues.copy(items = items),
                    feedback = statusFeedback(changed),
                )
            }
        }
    }

    /** Whether the milestone of the shown issue can be changed now. */
    val canChangeMilestone: Boolean
        get() {
            val state = current
            return gateway != null && state.issue != null && !state.writing && state.editor == null &&
                state.picker == null && state.commentEditor == null
        }

    /** Sets the milestone [milestone] (a number) on the shown issue or removes it with `null`. */
    fun setMilestone(milestone: Long?) {
        val issue = current.issue ?: return
        if (!canChangeMilestone || issue.milestone?.number == milestone) return
        write({ setMilestone(issue.number, milestone) }) { changed ->
            update {
                copy(
                    issue = changed,
                    issues = issues.copy(
                        items = issues.items.map { if (it.number == changed.number) changed else it }.filter(::shows),
                    ),
                    feedback = changed.milestone?.let { message("feedback.milestone", changed.number, it.title) }
                        ?: message("feedback.milestoneRemoved", changed.number),
                )
            }
        }
    }

    fun startLabels() = startPicker(PickerKind.LABELS, Issue::labels, IssuesGateway::labels)

    fun startAssignees() = startPicker(PickerKind.ASSIGNEES, Issue::assignees, IssuesGateway::assignees)

    private fun startPicker(
        kind: PickerKind,
        values: (Issue) -> List<String>,
        load: suspend IssuesGateway.() -> List<String>,
    ) {
        val source = gateway ?: return
        val issue = current.issue ?: return
        if (current.writing || current.editor != null || current.picker != null || current.commentEditor != null) return
        val original = values(issue).toSet()
        update { copy(picker = SelectionPicker(kind, issue.number, original, original), writeError = null, feedback = null) }
        pickerJob?.cancel()
        pickerJob = scope.launch {
            try {
                val available = source.load()
                ensureActive()
                updatePicker { copy(available = available, loading = false) }
            } catch (error: IssuesFailure) {
                ensureActive()
                updatePicker { copy(loading = false, error = error) }
            }
        }
    }

    fun searchPicker(query: String) {
        updatePicker { copy(query = query) }
    }

    fun togglePicked(name: String) {
        if (current.writing) return
        updatePicker { copy(selected = if (name in selected) selected - name else selected + name) }
    }

    /** Adds the active account to the open assignee selection and saves it right away. */
    fun assignMe() {
        if (current.writing) return
        val login = current.assignableViewer ?: return
        updatePicker { copy(selected = selected + login) }
        savePicker()
    }

    fun cancelPicker() {
        if (current.writing) return
        pickerJob?.cancel()
        update { copy(picker = null) }
    }

    fun savePicker() {
        val picker = current.picker ?: return
        if (!picker.changed) return cancelPicker()
        val number = picker.issueNumber
        val added = picker.added
        val removed = picker.removed
        // Some changes may have been saved before the failure, so show what GitHub stores now.
        val reload = {
            pickerJob?.cancel()
            update { copy(picker = null) }
            loadDetail()
        }
        when (picker.kind) {
            PickerKind.LABELS -> write({ changeLabels(number, added, removed) }, failed = reload) { changed ->
                showSaved(changed, changed.labels, added, removed, emptyMap(), "feedback.labels", "error.labelsIgnored")
            }

            PickerKind.ASSIGNEES -> write({ changeAssignees(number, added, removed) }, failed = reload) { change ->
                val issue = change.issue
                showSaved(
                    issue, issue.assignees, added, removed, change.failed, "feedback.assignees", "error.assigneesIgnored",
                )
            }
        }
    }

    private fun showSaved(
        changed: Issue,
        stored: List<String>,
        added: List<String>,
        removed: List<String>,
        failed: Map<String, IssuesFailure>,
        savedKey: String,
        ignoredKey: String,
    ) {
        pickerJob?.cancel()
        noteAssigned(changed)
        val ignored = (added.filter { it !in stored } + removed.filter { it in stored }).filter { it !in failed }
        val problems = failed.map { (name, error) -> message("error.changeFailed", name, error.message.orEmpty()) } +
            listOfNotNull(ignored.takeIf { it.isNotEmpty() }?.let { message(ignoredKey, it.joinToString(", ")) })
        update {
            copy(
                issue = changed,
                issues = issues.copy(
                    items = issues.items.map { if (it.number == changed.number) changed else it }.filter(::shows),
                ),
                picker = null,
                feedback = if (problems.isEmpty()) message(savedKey, changed.number) else null,
                writeError = problems.takeIf { it.isNotEmpty() }?.let { IssuesFailure(it.joinToString("\n")) },
            )
        }
    }

    private inline fun updatePicker(transform: SelectionPicker.() -> SelectionPicker) {
        val picker = current.picker ?: return
        update { copy(picker = picker.transform()) }
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

    companion object {
        val POLL_INTERVAL = 60.seconds
        val MAX_POLL_INTERVAL = 15.minutes
        private const val MAX_BACKOFF_STEPS = 4
    }
}
