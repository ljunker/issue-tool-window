package dev.issuetoolwindow

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

internal object IssuesBundle {
    private const val PATH = "messages.IssuesBundle"
    private val bundle = DynamicBundle(IssuesBundle::class.java, PATH)

    fun message(@PropertyKey(resourceBundle = PATH) key: String, vararg args: Any): String =
        bundle.getMessage(key, *args)
}

internal data class Repository(val owner: String, val name: String) {
    val fullName: String get() = "$owner/$name"
}

internal fun repositoriesForPicker(repositories: Iterable<Repository>): List<Repository> =
    repositories.distinctBy { it.fullName.lowercase() }.sortedBy { it.fullName.lowercase() }

internal fun repositoryForSelection(repositories: List<Repository>, selected: Repository?): Repository? =
    repositories.firstOrNull { it.fullName.equals(selected?.fullName, ignoreCase = true) }
        ?: repositories.singleOrNull()

internal enum class IssueFilter(val apiValue: String, val messageKey: String) {
    OPEN("open", "filter.open"),
    CLOSED("closed", "filter.closed"),
    ALL("all", "filter.all");

    fun includes(issue: Issue): Boolean = this == ALL || (this == OPEN) == issue.open
}

/** Order of the issue list; [apiSort] is the value both the issues and the search endpoint accept. */
internal enum class IssueSort(val messageKey: String, val apiSort: String, val ascending: Boolean) {
    NEWEST("sort.newest", "created", false),
    OLDEST("sort.oldest", "created", true),
    MOST_COMMENTED("sort.comments", "comments", false),
    RECENTLY_UPDATED("sort.updated", "updated", false);

    val apiDirection: String get() = if (ascending) "asc" else "desc"
}

internal data class Issue(
    val number: Long,
    val title: String,
    val open: Boolean,
    val author: String?,
    val body: String,
    val labels: List<String>,
    val assignees: List<String>,
    val createdAt: String? = null,
    val commentCount: Int = 0,
    /** Hex colors without `#` by label name, as far as GitHub reported them. */
    val labelColors: Map<String, String> = emptyMap(),
)

internal sealed interface IssueActivity {
    val id: String
    val author: String?
    val createdAt: String?
}

internal data class Comment(
    override val id: String,
    override val author: String?,
    val body: String,
    override val createdAt: String?,
) : IssueActivity

internal data class LabelActivity(
    override val id: String,
    override val author: String?,
    override val createdAt: String?,
    val name: String,
    val color: String?,
    val added: Boolean,
) : IssueActivity

internal data class PullRequestActivity(
    override val id: String,
    override val author: String?,
    override val createdAt: String?,
    val number: Long,
    val title: String,
    val url: String,
    val relation: Relation,
) : IssueActivity {
    enum class Relation { REFERENCES, CLOSES, LINKS, UNLINKS }
}

internal data class Page<T>(val items: List<T>, val next: String?)

internal interface IssuesGateway {
    suspend fun issues(filter: IssueFilter, sort: IssueSort, next: String?): Page<Issue>
    suspend fun search(query: String, filter: IssueFilter, sort: IssueSort, next: String?): Page<Issue>
    suspend fun issue(number: Long): Issue
    suspend fun activity(number: Long, next: String?): Page<IssueActivity>
    suspend fun create(title: String, body: String?): Issue
    suspend fun updateIssue(number: Long, title: String, body: String): Issue
    suspend fun addComment(number: Long, body: String): Comment
    suspend fun setOpen(number: Long, open: Boolean): Issue

    /** All label names of the repository, across every result page. */
    suspend fun labels(): List<String>

    /** Adds and removes only the given labels and returns the issue as GitHub stores it afterwards. */
    suspend fun changeLabels(number: Long, add: Collection<String>, remove: Collection<String>): Issue

    /** Logins of everyone who can be assigned to issues of the repository, across every result page. */
    suspend fun assignees(): List<String>

    /**
     * Adds and removes each given person separately, so assignees changed elsewhere stay untouched, and returns the
     * issue as GitHub stores it afterwards together with the changes that failed.
     */
    suspend fun changeAssignees(number: Long, add: Collection<String>, remove: Collection<String>): AssigneeChange
}

internal data class AssigneeChange(val issue: Issue, val failed: Map<String, IssuesFailure> = emptyMap())

internal class IssuesFailure(message: String, val offerSignIn: Boolean = false) : Exception(message)
