package dev.issuetoolwindow

import com.intellij.util.ThrowableConvertor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.jetbrains.plugins.github.api.GithubApiRequest
import org.jetbrains.plugins.github.api.GithubApiResponse
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IssuesTest {
    @Test
    fun `same repo from multiple remotes is selectable once`() {
        val origin = Repository("owner", "project")
        val upstream = Repository("upstream", "project")
        val repositories = repositoriesForPicker(listOf(upstream, origin, Repository("Owner", "Project")))
        assertEquals(listOf(origin, upstream), repositories)
        assertNull(repositoryForSelection(repositories, null))
        assertEquals(origin, repositoryForSelection(repositories, Repository("OWNER", "project")))
        assertEquals(upstream, repositoryForSelection(listOf(upstream), origin))
    }

    @Test
    fun `GitHub JSON pagination skips a page full of pull requests`() = runBlocking {
        val request = GithubApiRequest.Get.JsonPage("https://api.github.com/example", RestIssue::class.java)
        val first = request.extractResult(
            response(
                """[{"number":1,"title":"Pull request","state":"open","pull_request":{"url":"example"}}]""",
                "page2"
            )
        )
        val second = request.extractResult(
            response(
                """[{"number":2,"title":"Issue","state":"open","user":{"login":"author"},"body":"Description","labels":[{"name":"bug"}],"assignees":[{"login":"developer"}]}]""",
                "page3",
            )
        )
        val calls = mutableListOf<String>()
        val page = firstPageWithIssues("page1") { url ->
            calls += url
            val result = if (url == "page1") first else second
            Page(result.items, result.nextLink)
        }
        assertEquals(listOf("page1", "page2"), calls)
        assertEquals(
            listOf(Issue(2, "Issue", true, "author", "Description", listOf("bug"), listOf("developer"))),
            page.items
        )
        assertEquals("page3", page.next)
    }

    @Test
    fun `late replies from the previous repo cannot replace the current list or detail`() = runBlocking {
        val oldList = CompletableDeferred<Page<Issue>>()
        val oldDetail = CompletableDeferred<Issue>()
        val repoA = object : StubGateway(Issue(1, "Repo A", true, null, "", emptyList(), emptyList())) {
            override suspend fun issues(filter: IssueFilter, next: String?): Page<Issue> =
                withContext(NonCancellable) { oldList.await() }

            override suspend fun issue(number: Long): Issue = withContext(NonCancellable) { oldDetail.await() }
        }
        val repoB = StubGateway(Issue(2, "Repo B", true, null, "", emptyList(), emptyList()))
        val model = IssuesModel(this)
        model.connect(repoA)
        model.select(1)
        yield()
        model.connect(repoB)
        await { !model.state.value.listLoading }
        model.select(2)
        await { !model.state.value.detailLoading }
        oldList.complete(Page(listOf(repoA.issue), null))
        oldDetail.complete(repoA.issue)
        yield()
        assertEquals(listOf(repoB.issue), model.state.value.issues.items)
        assertEquals(repoB.issue, model.state.value.issue)
    }

    @Test
    fun `failed writes keep both draft types and are only sent once`() = runBlocking {
        var creates = 0
        var comments = 0
        val gateway = object : StubGateway(Issue(8, "Existing issue", true, null, "", emptyList(), emptyList())) {
            override suspend fun create(title: String, body: String?): Issue {
                creates++
                throw IssuesFailure("Failed")
            }

            override suspend fun addComment(number: Long, body: String): Comment {
                comments++
                throw IssuesFailure("Failed")
            }
        }
        val model = IssuesModel(this)
        model.connect(gateway)
        await { !model.state.value.listLoading }

        model.startCreate()
        val draft = IssueDraft("Draft title", "Draft description")
        model.editIssue(draft)
        model.create()
        await { !model.state.value.writing }
        assertEquals(draft, model.state.value.newIssue)
        assertEquals("Failed", model.state.value.writeError?.message)
        assertEquals(1, creates)

        model.cancelCreate()
        model.select(gateway.issue.number)
        model.editComment("Draft comment")
        model.comment()
        await { !model.state.value.writing }
        assertEquals("Draft comment", model.state.value.commentDraft)
        assertEquals("Failed", model.state.value.writeError?.message)
        assertEquals(1, comments)
    }

    @Test
    fun `create returns to Open and close and reopen update the current issue`() = runBlocking {
        val initial = Issue(8, "Existing issue", false, null, "", emptyList(), emptyList())
        val gateway = object : StubGateway(initial) {
            override suspend fun create(title: String, body: String?): Issue {
                issue = Issue(9, title, true, "author", body.orEmpty(), emptyList(), emptyList())
                return issue
            }

            override suspend fun setOpen(number: Long, open: Boolean): Issue {
                issue = issue.copy(open = open)
                return issue
            }
        }
        val model = IssuesModel(this)
        model.connect(gateway)
        model.filter(IssueFilter.CLOSED)
        model.startCreate()
        model.editIssue(IssueDraft("New issue", "Description"))
        model.create()
        await { !model.state.value.writing && !model.state.value.detailLoading && !model.state.value.listLoading }
        assertEquals(IssueFilter.OPEN, model.state.value.filter)
        assertEquals(listOf(gateway.issue), model.state.value.issues.items)
        assertEquals(9L, model.state.value.selected)

        model.toggleStatus()
        await { !model.state.value.writing && !model.state.value.detailLoading && !model.state.value.listLoading }
        assertFalse(model.state.value.issue!!.open)
        assertTrue(model.state.value.issues.items.isEmpty())
        model.toggleStatus()
        await { !model.state.value.writing && !model.state.value.detailLoading && !model.state.value.listLoading }
        assertTrue(model.state.value.issue!!.open)
        assertEquals(listOf(gateway.issue), model.state.value.issues.items)
    }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(3_000) { while (!condition()) yield() }
    }

    private open class StubGateway(var issue: Issue) : IssuesGateway {
        override suspend fun issues(filter: IssueFilter, next: String?) =
            Page(listOf(issue).filter(filter::includes), null)

        override suspend fun issue(number: Long) = issue
        override suspend fun comments(number: Long, next: String?) = Page<Comment>(emptyList(), null)
        override suspend fun create(title: String, body: String?): Issue = error("Unexpected create")
        override suspend fun addComment(number: Long, body: String): Comment = error("Unexpected comment")
        override suspend fun setOpen(number: Long, open: Boolean): Issue = error("Unexpected state change")
    }

    private fun response(json: String, next: String): GithubApiResponse = object : GithubApiResponse {
        override fun findHeader(headerName: String): String? =
            if (headerName.equals("link", ignoreCase = true)) "<$next>; rel=\"next\"" else null

        override fun <T> readBody(converter: ThrowableConvertor<Reader, T, IOException>): T =
            json.reader().use { converter.convert(it) }

        override fun <T> handleBody(converter: ThrowableConvertor<InputStream, T, IOException>): T =
            json.byteInputStream().use { converter.convert(it) }
    }
}
