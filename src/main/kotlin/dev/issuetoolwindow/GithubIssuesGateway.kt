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

    override suspend fun issues(filter: IssueFilter, next: String?): Page<Issue> =
        firstPageWithIssues(next ?: "$issuesUrl?state=${filter.apiValue}&per_page=30") { url ->
            val page = request(GithubApiRequest.Get.JsonPage<RestIssue>(pageUrl(url), RestIssue::class.java, ACCEPT))
            Page(page.items, page.nextLink)
        }

    override suspend fun search(query: String, filter: IssueFilter, next: String?): Page<Issue> =
        firstPageWithIssues(next ?: issueSearchUrl(repository, query, filter)) { url ->
            val page = request(GithubApiRequest.Get.JsonSearchPage(pageUrl(url), RestIssue::class.java, ACCEPT))
            Page(page.items, page.nextLink)
        }

    override suspend fun issue(number: Long): Issue =
        request(GithubApiRequest.Get.Optional.Json("$issuesUrl/$number", RestIssue::class.java, ACCEPT))
            .toOpenedIssue(number)

    override suspend fun comments(number: Long, next: String?): Page<Comment> {
        val url = pageUrl(next ?: "$issuesUrl/$number/comments?per_page=30")
        val page = request(GithubApiRequest.Get.JsonPage(url, RestComment::class.java, ACCEPT))
        return Page(page.items.map(RestComment::toComment), page.nextLink)
    }

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

    override suspend fun setOpen(number: Long, open: Boolean): Issue =
        request(
            GithubApiRequest.Patch.Json(
                "$issuesUrl/$number", mapOf("state" to if (open) "open" else "closed"), RestIssue::class.java,
            )
        ).toIssue()

    private suspend fun <T> request(request: GithubApiRequest<T>): T = try {
        executor.execute(request)
    } catch (_: GithubMissingTokenException) {
        throw IssuesFailure(message("error.signIn"), offerSignIn = true)
    } catch (error: GithubRateLimitExceededException) {
        throw IssuesFailure(message("error.rateLimit"))
    } catch (error: GithubAuthenticationException) {
        // The bundled executor combines 401 and 403 and only preserves GitHub's response text.
        val details = error.message.orEmpty()
        val invalidCredentials = listOf("bad credentials", "requires authentication", "token has expired")
            .any { details.contains(it, ignoreCase = true) }
        throw IssuesFailure(message("error.access", details), offerSignIn = invalidCredentials)
    } catch (error: GithubStatusCodeException) {
        val details = error.error?.presentableError
        throw IssuesFailure(
            when {
                details != null -> message("error.github", error.statusCode, details)
                error.statusCode == 410 -> message("error.issuesDisabled")
                else -> message("error.http", error.statusCode)
            }
        )
    } catch (_: IOException) {
        throw IssuesFailure(message("error.connection"))
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

internal fun issueSearchUrl(repository: Repository, query: String, filter: IssueFilter): String {
    val terms = buildString {
        append("repo:${repository.fullName} is:issue in:title,body")
        if (filter != IssueFilter.ALL) append(" state:${filter.apiValue}")
        // Keep user text inside the phrase so it cannot change the repository or status qualifiers.
        append(" \"").append(query.replace('"', ' ').replace('\\', ' ')).append('"')
    }
    return "https://api.github.com/search/issues?q=${URLEncoder.encode(terms, Charsets.UTF_8)}&per_page=30"
}

internal fun RestIssue?.toOpenedIssue(number: Long): Issue {
    if (this == null) throw IssuesFailure(message("error.issueNotFound", number.toString()))
    if (pullRequest != null) throw IssuesFailure(message("error.pullRequest", number.toString()))
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
) {
    fun toIssue(): Issue = Issue(
        number, title, state == "open", user?.login, body.orEmpty(), labels.map { it.name }, assignees.map { it.login },
    )
}

internal data class RestUser(val login: String)
internal data class RestLabel(val name: String)
internal data class RestComment(val id: Long, val user: RestUser?, val body: String?, val createdAt: String?) {
    fun toComment(): Comment = Comment(id, user?.login, body.orEmpty(), createdAt)
}
