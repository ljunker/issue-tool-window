package dev.issuetoolwindow

import dev.issuetoolwindow.IssuesBundle.message
import org.jetbrains.plugins.github.api.GithubApiRequest
import org.jetbrains.plugins.github.api.GithubApiRequestExecutor
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import org.jetbrains.plugins.github.exceptions.GithubAuthenticationException
import org.jetbrains.plugins.github.exceptions.GithubMissingTokenException
import org.jetbrains.plugins.github.exceptions.GithubRateLimitExceededException
import org.jetbrains.plugins.github.exceptions.GithubStatusCodeException
import java.io.IOException
import java.net.URI
import java.net.URLEncoder

internal class GithubIssuesGateway(private val repository: Repository, account: GithubAccount) : IssuesGateway {
    private val executor = GithubApiRequestExecutor.Factory.getInstance().create(account)
    private val issuesUrl = URI("https", "api.github.com", "/repos/${repository.fullName}/issues", null).toASCIIString()
    private val labelsUrl =
        URI("https", "api.github.com", "/repos/${repository.fullName}/labels", "per_page=100", null).toASCIIString()
    private val assigneesUrl =
        URI("https", "api.github.com", "/repos/${repository.fullName}/assignees", "per_page=100", null).toASCIIString()

    override suspend fun issues(
        filter: IssueFilter,
        sort: IssueSort,
        criteria: IssueCriteria,
        next: String?,
    ): Page<Issue> {
        // The issues endpoint separates labels by commas, so only the search can filter by such a label.
        if (criteria.label?.contains(',') == true) {
            return searchPages(next ?: issueSearchUrl(repository, null, filter, sort, criteria))
        }
        return firstPageWithIssues(next ?: issueListUrl(issuesUrl, filter, sort, criteria)) { url ->
            val page = request(GithubApiRequest.Get.JsonPage<RestIssue>(pageUrl(url), RestIssue::class.java, ACCEPT))
            Page(page.items, page.nextLink)
        }
    }

    override suspend fun search(
        query: String,
        filter: IssueFilter,
        sort: IssueSort,
        criteria: IssueCriteria,
        next: String?,
    ): Page<Issue> = searchPages(next ?: issueSearchUrl(repository, query, filter, sort, criteria))

    private suspend fun searchPages(start: String): Page<Issue> =
        firstPageWithIssues(start) { url ->
            val page = request(GithubApiRequest.Get.JsonSearchPage(pageUrl(url), RestIssue::class.java, ACCEPT))
            Page(page.items, page.nextLink)
        }

    override suspend fun issue(number: Long): Issue =
        request(GithubApiRequest.Get.Optional.Json("$issuesUrl/$number", RestIssue::class.java, ACCEPT))
            .toOpenedIssue(number)

    override suspend fun activity(number: Long, next: String?): Page<IssueActivity> =
        request(issueActivityRequest(repository, number, next)).toPage(next)

    override suspend fun create(title: String, body: String?): Issue {
        val fields = if (body == null) mapOf("title" to title) else mapOf("title" to title, "body" to body)
        return request(GithubApiRequest.Post.Json(issuesUrl, fields, RestIssue::class.java, ACCEPT)).toIssue()
    }

    override suspend fun updateIssue(number: Long, title: String, body: String): Issue =
        request(
            GithubApiRequest.Patch.Json(
                "$issuesUrl/$number", mapOf("title" to title, "body" to body), RestIssue::class.java,
            )
        ).toIssue()

    override suspend fun addComment(number: Long, body: String): Comment =
        request(
            GithubApiRequest.Post.Json(
                "$issuesUrl/$number/comments",
                mapOf("body" to body),
                RestComment::class.java,
                ACCEPT
            )
        ).toComment()

    override suspend fun updateComment(id: String, body: String): Comment =
        request(updateCommentRequest(id, body)).toComment()

    override suspend fun deleteComment(id: String) = request(deleteCommentRequest(id)).confirm()

    override suspend fun close(number: Long, reason: CloseReason): Issue =
        changeState(number, mapOf("state" to "closed", "state_reason" to reason.apiValue))

    override suspend fun reopen(number: Long): Issue = changeState(number, mapOf("state" to "open"))

    private suspend fun changeState(number: Long, fields: Map<String, String>): Issue =
        request(GithubApiRequest.Patch.Json("$issuesUrl/$number", fields, RestIssue::class.java)).toIssue()

    override suspend fun labels(): List<String> =
        allPages(labelsUrl) { url ->
            val page = request(GithubApiRequest.Get.JsonPage<RestLabel>(pageUrl(url), RestLabel::class.java, ACCEPT))
            Page(page.items.map(RestLabel::name), page.nextLink)
        }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

    override suspend fun changeLabels(number: Long, add: Collection<String>, remove: Collection<String>): Issue {
        // Single additions and removals leave labels that someone else changed in the meantime untouched.
        if (add.isNotEmpty()) {
            request(
                GithubApiRequest.Post.Json(
                    "$issuesUrl/$number/labels", mapOf("labels" to add.toList()), Array<RestLabel>::class.java, ACCEPT,
                )
            )
        }
        for (label in remove) {
            try {
                executor.execute(GithubApiRequest.Delete.json<Unit>(issueLabelUrl(repository, number, label)))
            } catch (error: GithubStatusCodeException) {
                // GitHub answers 404 when the label was already removed from the issue.
                if (error.statusCode != 404) throw failure(error)
            } catch (error: Exception) {
                throw failure(error)
            }
        }
        return issue(number)
    }

    override suspend fun assignees(): List<String> =
        allPages(assigneesUrl) { url ->
            val page = request(GithubApiRequest.Get.JsonPage<RestUser>(pageUrl(url), RestUser::class.java, ACCEPT))
            Page(page.items.map(RestUser::login), page.nextLink)
        }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

    override suspend fun changeAssignees(
        number: Long,
        add: Collection<String>,
        remove: Collection<String>,
    ): AssigneeChange {
        val url = "$issuesUrl/$number/assignees"
        val failed = linkedMapOf<String, IssuesFailure>()
        // One request per person, so a single failure neither blocks nor hides the other changes.
        for (login in add) {
            attempt(login, failed) {
                request(GithubApiRequest.Post.Json(url, assigneesBody(login), RestIssue::class.java, ACCEPT))
            }
        }
        for (login in remove) {
            attempt(login, failed) {
                request(GithubApiRequest.Delete.Json(url, assigneesBody(login), RestIssue::class.java))
            }
        }
        return AssigneeChange(issue(number), failed)
    }

    private inline fun attempt(login: String, failed: MutableMap<String, IssuesFailure>, change: () -> Unit) {
        try {
            change()
        } catch (error: IssuesFailure) {
            // Without valid credentials every further request fails the same way.
            if (error.offerSignIn) throw error
            failed[login] = error
        }
    }

    private suspend fun <T> request(request: GithubApiRequest<T>): T = try {
        executor.execute(request)
    } catch (error: Exception) {
        throw failure(error)
    }

    private fun failure(error: Exception): Exception = when (error) {
        is GithubMissingTokenException -> IssuesFailure(message("error.signIn"), offerSignIn = true)
        is GithubRateLimitExceededException -> IssuesFailure(message("error.rateLimit"))
        is GithubAuthenticationException -> {
            // The bundled executor combines 401 and 403 and only preserves GitHub's response text.
            val details = error.message.orEmpty()
            val invalidCredentials = listOf("bad credentials", "requires authentication", "token has expired")
                .any { details.contains(it, ignoreCase = true) }
            IssuesFailure(message("error.access", details), offerSignIn = invalidCredentials)
        }
        is GithubStatusCodeException -> {
            val details = error.error?.presentableError
            IssuesFailure(
                when {
                    details != null -> message("error.github", error.statusCode, details)
                    error.statusCode == 410 -> message("error.issuesDisabled")
                    else -> message("error.http", error.statusCode)
                }
            )
        }
        is IOException -> IssuesFailure(message("error.connection"))
        else -> error
    }

    private fun pageUrl(url: String): String {
        val uri = runCatching { URI.create(url) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host != "api.github.com" || uri.port != -1 || uri.userInfo != null) {
            throw IssuesFailure(message("error.pagination"))
        }
        return url
    }

    private companion object {
        const val ACCEPT = "application/vnd.github+json"
    }
}

internal fun issueListUrl(
    issuesUrl: String,
    filter: IssueFilter,
    sort: IssueSort,
    criteria: IssueCriteria = IssueCriteria(),
): String = buildString {
    append("$issuesUrl?state=${filter.apiValue}&sort=${sort.apiSort}&direction=${sort.apiDirection}")
    criteria.label?.let { append("&labels=").append(URLEncoder.encode(it, Charsets.UTF_8)) }
    criteria.assignee?.let { append("&assignee=").append(URLEncoder.encode(it, Charsets.UTF_8)) }
    append("&per_page=30")
}

/** Searches the issues of [repository]; without a [query] only the status, label and assignee qualifiers apply. */
internal fun issueSearchUrl(
    repository: Repository,
    query: String?,
    filter: IssueFilter,
    sort: IssueSort = IssueSort.NEWEST,
    criteria: IssueCriteria = IssueCriteria(),
): String {
    val terms = buildString {
        append("repo:${repository.fullName} is:issue")
        if (query != null) append(" in:title,body")
        if (filter != IssueFilter.ALL) append(" state:${filter.apiValue}")
        // Quoted values cannot add qualifiers that change the repository or status.
        criteria.label?.let { append(" label:").append(quoted(it)) }
        criteria.assignee?.let { append(" assignee:").append(quoted(it)) }
        if (query != null) append(' ').append(quoted(query))
    }
    return "https://api.github.com/search/issues?q=${URLEncoder.encode(terms, Charsets.UTF_8)}" +
        "&sort=${sort.apiSort}&order=${sort.apiDirection}&per_page=30"
}

private fun quoted(value: String): String = "\"" + value.replace('"', ' ').replace('\\', ' ') + "\""

internal fun issueLabelUrl(repository: Repository, number: Long, label: String): String {
    // A label name is one path segment, so slashes and spaces must be encoded as well.
    val name = URLEncoder.encode(label, Charsets.UTF_8).replace("+", "%20")
    return URI("https", "api.github.com", "/repos/${repository.fullName}/issues/$number/labels", null)
        .toASCIIString() + "/" + name
}

private fun assigneesBody(login: String): Map<String, List<String>> = mapOf("assignees" to listOf(login))

internal suspend fun <T> allPages(start: String, load: suspend (String) -> Page<T>): List<T> {
    val items = mutableListOf<T>()
    val visited = mutableSetOf<String>()
    var url: String? = start
    while (url != null) {
        if (!visited.add(url)) throw IssuesFailure(message("error.pagination"))
        val page = load(url)
        items += page.items
        url = page.next
    }
    return items
}

internal fun RestIssue?.toOpenedIssue(number: Long): Issue {
    if (this == null) throw IssuesFailure(message("error.issueNotFound", number.toString()), missing = true)
    if (pullRequest != null) throw IssuesFailure(message("error.pullRequest", number.toString()), missing = true)
    return toIssue()
}

internal suspend fun firstPageWithIssues(start: String, load: suspend (String) -> Page<RestIssue>): Page<Issue> {
    var url = start
    val visited = mutableSetOf<String>()
    while (visited.add(url)) {
        val page = load(url)
        val issues = page.items.filter { it.pullRequest == null }.map(RestIssue::toIssue)
        val next = page.next
        if (issues.isNotEmpty() || next == null) return Page(issues, next)
        url = next
    }
    throw IssuesFailure(message("error.pagination"))
}

internal data class RestIssue(
    val number: Long,
    val title: String,
    val state: String,
    val user: RestUser? = null,
    val body: String? = null,
    val labels: List<RestLabel> = emptyList(),
    val assignees: List<RestUser> = emptyList(),
    val pullRequest: Map<String, Any?>? = null,
    val createdAt: String? = null,
    val comments: Int = 0,
    val stateReason: String? = null,
) {
    fun toIssue(): Issue {
        val open = state == "open"
        return Issue(
            number, title, open, user?.login, body.orEmpty(), labels.map { it.name }, assignees.map { it.login },
            createdAt, comments, labels.mapNotNull { label -> label.color?.let { label.name to it } }.toMap(),
            closeReason = if (open) null else CloseReason.fromApi(stateReason),
        )
    }
}

internal data class RestUser(val login: String)
internal data class RestLabel(val name: String, val color: String? = null)
internal data class RestComment(val nodeId: String, val user: RestUser?, val body: String?, val createdAt: String?) {
    fun toComment(): Comment = Comment(nodeId, user?.login, body.orEmpty(), createdAt)
}
