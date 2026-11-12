package dev.issuetoolwindow

import com.intellij.util.ThrowableConvertor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.jetbrains.plugins.github.api.GithubApiRequest
import org.jetbrains.plugins.github.api.GithubApiResponse
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.net.URI
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
                """[{"number":2,"title":"Issue","state":"open","user":{"login":"author"},"body":"Description","labels":[{"name":"bug"}],"assignees":[{"login":"developer"}],"created_at":"2026-11-11T09:00:00Z"}]""",
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
            listOf(Issue(2, "Issue", true, "author", "Description", listOf("bug"), listOf("developer"), "2026-11-11T09:00:00Z")),
            page.items
        )
        assertEquals("page3", page.next)
    }

    @Test
    fun `GitHub search scopes the query and reads the next link from a search response`() {
        val repo = Repository("owner", "project")
        val url = issueSearchUrl(repo, "UI & café \"state:open", IssueFilter.CLOSED)
        val uri = URI(url)
        assertEquals("/search/issues", uri.path)
        assertEquals(
            "q=repo:owner/project is:issue in:title,body state:closed \"UI & café  state:open\"&sort=created&order=desc&per_page=30",
            URLDecoder.decode(uri.rawQuery, Charsets.UTF_8),
        )
        assertFalse(URI(issueSearchUrl(repo, "text", IssueFilter.ALL)).query.contains("state:"))
        assertTrue(issueSearchUrl(repo, "text", IssueFilter.ALL, IssueSort.OLDEST).endsWith("&sort=created&order=asc&per_page=30"))

        val next = "$url&page=2"
        val page = GithubApiRequest.Get.JsonSearchPage(url, RestIssue::class.java).extractResult(
            response(
                """{"total_count":2,"incomplete_results":false,"items":[{"number":42,"title":"Issue","state":"closed"}]}""",
                next
            )
        )
        assertEquals(listOf(42L), page.items.map(RestIssue::number))
        assertEquals(next, page.nextLink)
    }

    @Test
    fun `issue list URL carries status and sort order`() {
        val base = "https://api.github.com/repos/owner/project/issues"
        assertEquals(
            "$base?state=closed&sort=comments&direction=desc&per_page=30",
            issueListUrl(base, IssueFilter.CLOSED, IssueSort.MOST_COMMENTED),
        )
        assertEquals(
            "$base?state=open&sort=updated&direction=desc&per_page=30",
            issueListUrl(base, IssueFilter.OPEN, IssueSort.RECENTLY_UPDATED),
        )
    }

    @Test
    fun `GitHub issue JSON maps comment count and label colors`() {
        val request = GithubApiRequest.Get.JsonPage("https://api.github.com/example", RestIssue::class.java)
        val issue = request.extractResult(
            response(
                """[{"number":7,"title":"Colored","state":"open","comments":3,"labels":[{"name":"bug","color":"d73a4a"},{"name":"plain"}]}]"""
            )
        ).items.single().toIssue()
        assertEquals(3, issue.commentCount)
        assertEquals(listOf("bug", "plain"), issue.labels)
        assertEquals(mapOf("bug" to "d73a4a"), issue.labelColors)
    }

    @Test
    fun `issue list and search URLs combine status, label and assignee`() {
        val base = "https://api.github.com/repos/owner/project/issues"
        assertEquals(
            "$base?state=open&sort=created&direction=desc&labels=good+first+issue%2Fui&assignee=alice&per_page=30",
            issueListUrl(base, IssueFilter.OPEN, IssueSort.NEWEST, IssueCriteria("good first issue/ui", "alice")),
        )
        val repo = Repository("owner", "project")
        val search = issueSearchUrl(repo, "crash", IssueFilter.CLOSED, IssueSort.NEWEST, IssueCriteria("bug \"x", "bob"))
        assertEquals(
            "repo:owner/project is:issue in:title,body state:closed label:\"bug  x\" assignee:\"bob\" \"crash\"",
            URLDecoder.decode(URI(search).rawQuery.substringAfter("q=").substringBefore('&'), Charsets.UTF_8),
        )
        assertEquals(
            "repo:owner/project is:issue label:\"a,b\"",
            URLDecoder.decode(
                URI(issueSearchUrl(repo, null, IssueFilter.ALL, IssueSort.NEWEST, IssueCriteria("a,b")))
                    .rawQuery.substringAfter("q=").substringBefore('&'),
                Charsets.UTF_8,
            ),
        )
    }

    @Test
    fun `label and assignee filters combine with status and search and survive loading more pages`() = runBlocking {
        val first = Issue(1, "First", true, null, "", listOf("bug"), listOf("alice"))
        val second = first.copy(number = 2, title = "Second")
        val listCalls = mutableListOf<Pair<IssueCriteria, String?>>()
        val searchCalls = mutableListOf<Pair<IssueCriteria, IssueFilter>>()
        val model = IssuesModel(this)
        model.connect(object : StubGateway(first) {
            override suspend fun issues(
                filter: IssueFilter,
                sort: IssueSort,
                criteria: IssueCriteria,
                next: String?,
            ): Page<Issue> {
                listCalls += criteria to next
                return if (next == null) Page(listOf(first), "page2") else Page(listOf(second), null)
            }

            override suspend fun search(
                query: String,
                filter: IssueFilter,
                sort: IssueSort,
                criteria: IssueCriteria,
                next: String?,
            ): Page<Issue> {
                searchCalls += criteria to filter
                return Page(emptyList(), null)
            }
        }, viewer = "alice")
        await { !model.state.value.listLoading }
        model.filterLabel("bug")
        await { !model.state.value.listLoading }
        model.filterAssignee("alice")
        await { !model.state.value.listLoading }
        model.moreIssues()
        await { !model.state.value.listLoading }
        val both = IssueCriteria("bug", "alice")
        assertEquals(
            listOf(IssueCriteria() to null, IssueCriteria("bug") to null, both to null, both to "page2"),
            listCalls,
        )
        assertEquals(listOf(first, second), model.state.value.issues.items)

        model.filter(IssueFilter.CLOSED)
        await { !model.state.value.listLoading }
        model.editSearch("crash")
        model.search()
        await { !model.state.value.listLoading }
        assertEquals(both to IssueFilter.CLOSED, searchCalls.single())

        model.filterLabel(null)
        await { !model.state.value.listLoading }
        assertEquals(IssueCriteria(assignee = "alice"), model.state.value.criteria)
        assertEquals(IssueCriteria(assignee = "alice") to IssueFilter.CLOSED, searchCalls.last())

        model.startCreate()
        model.filterAssignee(null)
        assertEquals(IssueCriteria(assignee = "alice"), model.state.value.criteria)
    }

    @Test
    fun `saved list settings load the first page with their status, order and filters`() = runBlocking {
        val issue = Issue(1, "Issue", false, null, "", listOf("Bug"), listOf("bob"))
        val calls = mutableListOf<Triple<IssueFilter, IssueSort, IssueCriteria>>()
        val model = IssuesModel(this)
        model.connect(object : StubGateway(issue) {
            override suspend fun issues(filter: IssueFilter, sort: IssueSort, criteria: IssueCriteria, next: String?): Page<Issue> {
                calls += Triple(filter, sort, criteria)
                return Page(listOf(issue), null)
            }

            override suspend fun labels() = listOf("Bug", "docs")
            override suspend fun assignees() = listOf("bob")
        }, viewer = "alice", settings = IssueListSettings(IssueFilter.CLOSED, IssueSort.OLDEST, IssueCriteria("bug", "BOB")))
        await { !model.state.value.listLoading }
        val restored = IssueListSettings(IssueFilter.CLOSED, IssueSort.OLDEST, IssueCriteria("Bug", "bob"))
        assertEquals(listOf(Triple(restored.filter, restored.sort, restored.criteria)), calls)
        assertEquals(restored, model.state.value.settings)
        assertEquals("", model.state.value.searchInput)
    }

    @Test
    fun `a saved label or assignee the repository no longer offers is dropped silently`() = runBlocking {
        val issue = Issue(1, "Issue", true, null, "", emptyList(), emptyList())
        val calls = mutableListOf<IssueCriteria>()
        val gateway = object : StubGateway(issue) {
            var failOptions = false

            override suspend fun issues(filter: IssueFilter, sort: IssueSort, criteria: IssueCriteria, next: String?): Page<Issue> {
                calls += criteria
                return Page(emptyList(), null)
            }

            override suspend fun labels(): List<String> =
                if (failOptions) throw IssuesFailure("offline") else listOf("docs")

            override suspend fun assignees(): List<String> =
                if (failOptions) throw IssuesFailure("offline") else listOf("carol")
        }
        val model = IssuesModel(this)

        model.connect(gateway, viewer = "alice", settings = IssueListSettings(criteria = IssueCriteria("deleted", "gone")))
        await { !model.state.value.listLoading }
        assertEquals(IssueCriteria(), model.state.value.criteria)
        assertNull(model.state.value.listError)

        model.connect(gateway, viewer = "alice", settings = IssueListSettings(criteria = IssueCriteria("docs", "Alice")))
        await { !model.state.value.listLoading }
        assertEquals(IssueCriteria("docs", "alice"), model.state.value.criteria)

        gateway.failOptions = true
        model.connect(gateway, viewer = "alice", settings = IssueListSettings(criteria = IssueCriteria("bug", "bob")))
        await { !model.state.value.listLoading }
        assertEquals(IssueCriteria("bug", "bob"), model.state.value.criteria)
        assertEquals(listOf(IssueCriteria(), IssueCriteria("docs", "alice"), IssueCriteria("bug", "bob")), calls)
    }

    @Test
    fun `list settings are saved without defaults and read back with defaults for unknown values`() {
        val store = mutableMapOf<String, String>()
        val write: (String, String?) -> Unit = { key, value ->
            if (value == null) store.remove(key) else store[key] = value
        }
        val settings = IssueListSettings(IssueFilter.ALL, IssueSort.MOST_COMMENTED, IssueCriteria("bug", "alice"))
        writeListSettings(settings, write)
        assertEquals(settings, readListSettings(store::get))

        writeListSettings(IssueListSettings(), write)
        assertTrue(store.isEmpty())
        assertEquals(IssueListSettings(), readListSettings(store::get))

        val unknown = mapOf("dev.issuetoolwindow.filter" to "SOMETIMES", "dev.issuetoolwindow.sort" to "RANDOM")
        assertEquals(IssueListSettings(), readListSettings(unknown::get))
    }

    @Test
    fun `filter options come from the connected repository and offer the active account first`() = runBlocking {
        val issue = Issue(1, "Issue", true, null, "", emptyList(), emptyList())
        var labelLoads = 0
        val repoA = object : StubGateway(issue) {
            override suspend fun labels(): List<String> = listOf("bug").also { labelLoads++ }
            override suspend fun assignees() = listOf("bob", "Alice")
        }
        val repoB = object : StubGateway(issue) {
            override suspend fun labels() = listOf("docs")
            override suspend fun assignees() = listOf("carol")
        }
        val model = IssuesModel(this)
        model.connect(repoA, viewer = "alice")
        await { !model.state.value.listLoading }
        model.filterLabel("bug")
        await { !model.state.value.listLoading }
        assertEquals(listOf("bug"), model.labelFilterOptions())
        assertEquals(listOf("bug"), model.labelFilterOptions())
        assertEquals(1, labelLoads)
        assertEquals(listOf("alice", "bob"), model.assigneeFilterOptions())

        model.connect(repoB, viewer = "alice")
        await { !model.state.value.listLoading }
        assertEquals(IssueCriteria(), model.state.value.criteria)
        assertEquals(listOf("docs"), model.labelFilterOptions())
        assertEquals(listOf("alice", "carol"), model.assigneeFilterOptions())
    }

    @Test
    fun `an issue that no longer matches the label filter leaves the list after saving labels`() = runBlocking {
        val initial = Issue(4, "Issue", true, null, "", listOf("bug"), emptyList())
        val gateway = object : StubGateway(initial) {
            override suspend fun labels() = listOf("bug", "docs")

            override suspend fun changeLabels(number: Long, add: Collection<String>, remove: Collection<String>): Issue {
                issue = issue.copy(labels = issue.labels - remove.toSet() + add)
                return issue
            }
        }
        val model = IssuesModel(this)
        model.connect(gateway)
        model.filterLabel("BUG")
        await { !model.state.value.listLoading }
        assertEquals(listOf(initial), model.state.value.issues.items)
        model.select(initial.number)
        await { !model.state.value.detailLoading }
        model.startLabels()
        await { model.state.value.picker?.loading == false }
        model.togglePicked("bug")
        model.savePicker()
        await { !model.state.value.writing && !model.state.value.detailLoading && !model.state.value.listLoading }
        assertTrue(model.state.value.issues.items.isEmpty())
        assertEquals(emptyList(), model.state.value.issue?.labels)
    }

    @Test
    fun `GitHub JSON maps the milestone of issues and orders milestones open first`() {
        val issues = GithubApiRequest.Get.JsonPage("https://api.github.com/example", RestIssue::class.java)
        val parsed = issues.extractResult(
            response(
                """[{"number":7,"title":"Planned","state":"open","milestone":{"number":3,"title":"v1.0","state":"open"}},""" +
                    """{"number":8,"title":"Unplanned","state":"open","milestone":null}]"""
            )
        ).items.map(RestIssue::toIssue)
        assertEquals(listOf(Milestone(3, "v1.0"), null), parsed.map(Issue::milestone))

        val milestones = GithubApiRequest.Get.JsonPage("https://api.github.com/example", RestMilestone::class.java)
            .extractResult(
                response(
                    """[{"number":1,"title":"v0.9","state":"closed"},{"number":3,"title":"b","state":"open"},""" +
                        """{"number":2,"title":"A","state":"open"},{"number":2,"title":"A","state":"open"}]"""
                )
            ).items.map(RestMilestone::toMilestone)
        assertEquals(
            listOf(Milestone(2, "A"), Milestone(3, "b"), Milestone(1, "v0.9", open = false)),
            milestonesForPicker(milestones),
        )
    }

    @Test
    fun `issue list and search URLs filter by milestone number, title or no milestone`() {
        val base = "https://api.github.com/repos/owner/project/issues"
        val release = MilestoneFilter.Of(Milestone(12, "Release \"2\""))
        assertEquals(
            "$base?state=open&sort=created&direction=desc&labels=bug&milestone=12&per_page=30",
            issueListUrl(base, IssueFilter.OPEN, IssueSort.NEWEST, IssueCriteria("bug", milestone = release)),
        )
        assertEquals(
            "$base?state=all&sort=created&direction=desc&milestone=none&per_page=30",
            issueListUrl(base, IssueFilter.ALL, IssueSort.NEWEST, IssueCriteria(milestone = MilestoneFilter.None)),
        )
        val repo = Repository("owner", "project")
        fun terms(criteria: IssueCriteria, query: String?) = URLDecoder.decode(
            URI(issueSearchUrl(repo, query, IssueFilter.OPEN, IssueSort.NEWEST, criteria))
                .rawQuery.substringAfter("q=").substringBefore('&'),
            Charsets.UTF_8,
        )
        assertEquals(
            "repo:owner/project is:issue in:title,body state:open assignee:\"bob\" milestone:\"Release  2 \" \"crash\"",
            terms(IssueCriteria(assignee = "bob", milestone = release), "crash"),
        )
        assertEquals(
            "repo:owner/project is:issue state:open no:milestone",
            terms(IssueCriteria(milestone = MilestoneFilter.None), null),
        )
    }

    @Test
    fun `setting a milestone sends its number and removing it sends an explicit null`() {
        val url = "https://api.github.com/repos/owner/project/issues/4"
        assertEquals("""{"milestone":12}""", setMilestoneRequest(url, 12).body)
        assertEquals("""{"milestone":null}""", setMilestoneRequest(url, null).body)
        assertEquals(url, setMilestoneRequest(url, null).url)
    }

    @Test
    fun `milestone criteria match issues of one milestone or without milestone`() {
        val planned = Issue(1, "Planned", true, null, "", listOf("bug"), emptyList(), milestone = Milestone(3, "v1.0"))
        val unplanned = planned.copy(number = 2, milestone = null)
        val v1 = MilestoneFilter.Of(Milestone(3, "renamed", open = false))
        assertTrue(IssueCriteria(milestone = v1).includes(planned))
        assertFalse(IssueCriteria(milestone = v1).includes(unplanned))
        assertFalse(IssueCriteria(milestone = MilestoneFilter.None).includes(planned))
        assertTrue(IssueCriteria(milestone = MilestoneFilter.None).includes(unplanned))
        assertFalse(IssueCriteria("docs", milestone = v1).includes(planned))
    }

    @Test
    fun `milestone filters are saved with number and title and read back`() {
        val store = mutableMapOf<String, String>()
        val write: (String, String?) -> Unit = { key, value ->
            if (value == null) store.remove(key) else store[key] = value
        }
        val release = IssueListSettings(criteria = IssueCriteria(milestone = MilestoneFilter.Of(Milestone(12, "v2.0"))))
        writeListSettings(release, write)
        assertEquals(release, readListSettings(store::get))

        val none = IssueListSettings(criteria = IssueCriteria(milestone = MilestoneFilter.None))
        writeListSettings(none, write)
        assertEquals(mapOf("dev.issuetoolwindow.milestone" to "none"), store)
        assertEquals(none, readListSettings(store::get))

        writeListSettings(IssueListSettings(), write)
        assertTrue(store.isEmpty())
        val broken = mapOf("dev.issuetoolwindow.milestone" to "x", "dev.issuetoolwindow.milestone.title" to "v2.0")
        assertEquals(IssueListSettings(), readListSettings(broken::get))
        val untitled = mapOf("dev.issuetoolwindow.milestone" to "12")
        assertEquals(IssueListSettings(), readListSettings(untitled::get))
    }

    @Test
    fun `milestone filter combines with label and search and a saved milestone is checked by number`() = runBlocking {
        val issue = Issue(1, "Issue", true, null, "", listOf("bug"), emptyList())
        val listCalls = mutableListOf<IssueCriteria>()
        val searchCalls = mutableListOf<IssueCriteria>()
        val gateway = object : StubGateway(issue) {
            var failOptions = false
            var loads = 0

            override suspend fun issues(filter: IssueFilter, sort: IssueSort, criteria: IssueCriteria, next: String?): Page<Issue> {
                listCalls += criteria
                return Page(emptyList(), null)
            }

            override suspend fun search(
                query: String,
                filter: IssueFilter,
                sort: IssueSort,
                criteria: IssueCriteria,
                next: String?,
            ): Page<Issue> {
                searchCalls += criteria
                return Page(emptyList(), null)
            }

            override suspend fun milestones(): List<Milestone> {
                loads++
                if (failOptions) throw IssuesFailure("offline")
                return listOf(Milestone(3, "v1.0 renamed"), Milestone(1, "v0.9", open = false))
            }
        }
        val model = IssuesModel(this)
        model.connect(gateway)
        await { !model.state.value.listLoading }
        model.filterLabel("bug")
        await { !model.state.value.listLoading }
        val v1 = MilestoneFilter.Of(Milestone(3, "v1.0 renamed"))
        model.filterMilestone(v1)
        await { !model.state.value.listLoading }
        model.editSearch("crash")
        model.search()
        await { !model.state.value.listLoading }
        model.filterMilestone(MilestoneFilter.None)
        await { !model.state.value.listLoading }
        assertEquals(listOf(IssueCriteria("bug", milestone = v1), IssueCriteria("bug", milestone = MilestoneFilter.None)), searchCalls)
        model.filterMilestone(null)
        await { !model.state.value.listLoading }
        assertEquals(IssueCriteria("bug"), model.state.value.criteria)
        assertEquals(listOf(Milestone(3, "v1.0 renamed"), Milestone(1, "v0.9", open = false)), model.milestoneFilterOptions())
        assertEquals(listOf(Milestone(3, "v1.0 renamed"), Milestone(1, "v0.9", open = false)), model.milestoneFilterOptions())
        assertEquals(1, gateway.loads)

        listCalls.clear()
        val saved = { number: Long -> IssueListSettings(criteria = IssueCriteria(milestone = MilestoneFilter.Of(Milestone(number, "v1.0")))) }
        model.connect(gateway, settings = saved(3))
        await { !model.state.value.listLoading }
        model.connect(gateway, settings = saved(99))
        await { !model.state.value.listLoading }
        model.connect(gateway, settings = IssueListSettings(criteria = IssueCriteria(milestone = MilestoneFilter.None)))
        await { !model.state.value.listLoading }
        gateway.failOptions = true
        model.connect(gateway, settings = saved(99))
        await { !model.state.value.listLoading }
        assertEquals(
            listOf(
                IssueCriteria(milestone = v1),
                IssueCriteria(),
                IssueCriteria(milestone = MilestoneFilter.None),
                saved(99).criteria,
            ),
            listCalls,
        )
    }

    @Test
    fun `setting and removing a milestone shows the stored issue and leaves a filtered list`() = runBlocking {
        val v1 = Milestone(3, "v1.0")
        val initial = Issue(4, "Issue", true, null, "", emptyList(), emptyList(), milestone = v1)
        val changes = mutableListOf<Long?>()
        val gateway = object : StubGateway(initial) {
            override suspend fun milestones() = listOf(Milestone(5, "v2.0"), v1, Milestone(1, "old", open = false))

            override suspend fun setMilestone(number: Long, milestone: Long?): Issue {
                changes += milestone
                issue = issue.copy(milestone = milestones().firstOrNull { it.number == milestone })
                return issue
            }
        }
        val model = IssuesModel(this)
        model.connect(gateway)
        model.filterMilestone(MilestoneFilter.Of(v1))
        await { !model.state.value.listLoading }
        model.select(initial.number)
        await { !model.state.value.detailLoading }
        assertEquals(listOf(Milestone(5, "v2.0"), v1), model.openMilestones())

        model.setMilestone(3)
        assertTrue(changes.isEmpty())

        model.setMilestone(5)
        await { !model.state.value.writing && !model.state.value.detailLoading && !model.state.value.listLoading }
        assertEquals("Set milestone v2.0 on issue #4.", model.state.value.feedback)
        assertEquals(Milestone(5, "v2.0"), model.state.value.issue?.milestone)
        assertTrue(model.state.value.issues.items.isEmpty())

        model.setMilestone(null)
        await { !model.state.value.writing && !model.state.value.detailLoading && !model.state.value.listLoading }
        assertEquals("Removed the milestone from issue #4.", model.state.value.feedback)
        assertNull(model.state.value.issue?.milestone)
        assertEquals(listOf(5L, null), changes)

        model.startEdit()
        assertFalse(model.canChangeMilestone)
        model.setMilestone(5)
        assertEquals(listOf(5L, null), changes)
    }

    @Test
    fun `list rows and issue details show the milestone`() {
        val issue = Issue(16, "Title", true, "ljunker", "", emptyList(), emptyList(), milestone = Milestone(3, "v1.0 <b>"))
        assertEquals("#16 · by ljunker · Milestone v1.0 <b>", issueMetaLine(issue) { "Today" })
        assertEquals("#16 · Milestone v1.0 <b>", issueMetaLine(issue.copy(author = null)) { "Today" })

        val html = IssueDetailsContent(issue, emptyList(), false).toHtml()
        assertTrue("<strong>Milestone</strong> <span class='issue-milestone'>&nbsp;v1.0 &lt;b&gt;&nbsp;</span>" in html, html)
        val without = IssueDetailsContent(issue.copy(milestone = null), emptyList(), false).toHtml()
        assertTrue("<strong>Milestone</strong> <span class='issue-muted'>None</span>" in without, without)
    }

    @Test
    fun `list rows describe number, creation date and author`() {
        val issue = Issue(16, "Title", true, "ljunker", "", emptyList(), emptyList(), "2026-11-11T09:00:00Z")
        assertEquals("#16 · created Today, by ljunker", issueMetaLine(issue) { "Today" })
        assertEquals("#16 · by ljunker", issueMetaLine(issue.copy(createdAt = "not a date")) { "Today" })
        assertEquals("#16", issueMetaLine(issue.copy(author = null, createdAt = null)) { "Today" })
    }

    @Test
    fun `changing the sort reloads the list and is blocked while editing`() = runBlocking {
        val issue = Issue(1, "Issue", true, null, "", emptyList(), emptyList())
        val sorts = mutableListOf<IssueSort>()
        val model = IssuesModel(this)
        model.connect(object : StubGateway(issue) {
            override suspend fun issues(filter: IssueFilter, sort: IssueSort, criteria: IssueCriteria, next: String?): Page<Issue> {
                sorts += sort
                return Page(listOf(issue), null)
            }
        })
        await { !model.state.value.listLoading }
        model.select(issue.number)
        await { !model.state.value.detailLoading }
        model.sort(IssueSort.OLDEST)
        await { !model.state.value.listLoading }
        assertEquals(IssueSort.OLDEST, model.state.value.sort)
        assertNull(model.state.value.selected)
        model.sort(IssueSort.OLDEST)
        model.startCreate()
        model.sort(IssueSort.MOST_COMMENTED)
        assertEquals(IssueSort.OLDEST, model.state.value.sort)
        assertEquals(listOf(IssueSort.NEWEST, IssueSort.OLDEST), sorts)
    }

    @Test
    fun `search paginates with the active status and clears back to the regular list`() = runBlocking {
        val regular = Issue(1, "Regular", false, null, "", emptyList(), emptyList())
        val first = regular.copy(number = 2, title = "First match", open = true)
        val second = first.copy(number = 3, title = "Second match")
        val calls = mutableListOf<Triple<String, IssueFilter, String?>>()
        val gateway = object : StubGateway(regular) {
            override suspend fun search(
                query: String,
                filter: IssueFilter,
                sort: IssueSort,
                criteria: IssueCriteria,
                next: String?,
            ): Page<Issue> {
                calls += Triple(query, filter, next)
                if (query == "failed") throw IssuesFailure("Search failed")
                if (filter == IssueFilter.CLOSED) return Page(emptyList(), null)
                return if (next == null) Page(listOf(first), "page2") else Page(listOf(second), null)
            }
        }
        val model = IssuesModel(this)
        model.connect(gateway)
        model.editSearch("  match  ")
        model.search()
        await { !model.state.value.listLoading }
        assertEquals(Page(listOf(first), "page2"), model.state.value.issues)
        model.moreIssues()
        await { !model.state.value.listLoading }
        assertEquals(Page(listOf(first, second), null), model.state.value.issues)

        model.filter(IssueFilter.CLOSED)
        await { !model.state.value.listLoading }
        assertTrue(model.state.value.issues.items.isEmpty())
        assertNull(model.state.value.listError)
        assertEquals(
            listOf(
                Triple("match", IssueFilter.OPEN, null),
                Triple("match", IssueFilter.OPEN, "page2"),
                Triple("match", IssueFilter.CLOSED, null),
            ), calls
        )

        model.editSearch("failed")
        model.search()
        await { !model.state.value.listLoading }
        assertEquals("Search failed", model.state.value.listError?.message)
        model.editSearch("")
        await { !model.state.value.listLoading }
        assertEquals(IssueFilter.CLOSED, model.state.value.filter)
        assertEquals(Page(listOf(regular), null), model.state.value.issues)
        assertNull(model.state.value.searchQuery)
        assertNull(model.state.value.listError)
    }

    @Test
    fun `a delayed previous search cannot overwrite the current results`() = runBlocking {
        val old = Issue(1, "Old match", true, null, "", emptyList(), emptyList())
        val current = old.copy(number = 2, title = "Current match")
        val started = CompletableDeferred<Unit>()
        val oldPage = CompletableDeferred<Page<Issue>>()
        val model = IssuesModel(this)
        model.connect(object : StubGateway(old) {
            override suspend fun search(
                query: String,
                filter: IssueFilter,
                sort: IssueSort,
                criteria: IssueCriteria,
                next: String?,
            ): Page<Issue> =
                if (query == "old") withContext(NonCancellable) {
                    started.complete(Unit)
                    withTimeout(3_000) { oldPage.await() }
                } else Page(listOf(current), null)
        })
        model.editSearch("old")
        model.search()
        withTimeout(3_000) { started.await() }
        model.editSearch("current")
        model.search()
        await { !model.state.value.listLoading }
        oldPage.complete(Page(listOf(old), "stale"))
        yield()
        assertEquals(Page(listOf(current), null), model.state.value.issues)
        assertNull(model.state.value.listError)
    }

    @Test
    fun `direct lookup opens a closed issue and reports pull requests and missing issues`() = runBlocking {
        val closed = RestIssue(42, "Closed issue", "closed")
        val pullRequest = GithubApiRequest.Get.Optional.Json("https://api.github.com/example", RestIssue::class.java)
            .extractResult(response("""{"number":43,"title":"PR","state":"open","pull_request":{}}"""))
        val activityRequests = mutableListOf<Long>()
        val model = IssuesModel(this)
        model.connect(object : StubGateway(closed.toIssue()) {
            override suspend fun issue(number: Long): Issue = when (number) {
                42L -> closed
                43L -> pullRequest
                else -> null
            }.toOpenedIssue(number)

            override suspend fun activity(number: Long, next: String?): Page<IssueActivity> {
                activityRequests += number
                return super.activity(number, next)
            }
        })
        await { !model.state.value.listLoading }
        assertTrue(model.state.value.issues.items.isEmpty())
        model.editSearch(" #42 ")
        model.search()
        await { !model.state.value.detailLoading }
        assertEquals(closed.toIssue(), model.state.value.issue)
        assertEquals(IssueFilter.OPEN, model.state.value.filter)

        model.editSearch("#43")
        model.search()
        await { !model.state.value.detailLoading }
        assertNull(model.state.value.issue)
        assertTrue(model.state.value.detailError?.message.orEmpty().contains("#43 is a pull request"))

        model.editSearch("#44")
        model.search()
        await { !model.state.value.detailLoading }
        assertNull(model.state.value.issue)
        assertTrue(model.state.value.detailError?.message.orEmpty().contains("Issue #44 was not found"))
        assertEquals(listOf(42L), activityRequests)
    }

    @Test
    fun `GraphQL activity keeps label changes and distinguishes closing pull requests from references`() {
        val page = issueActivityRequest(Repository("owner", "project"), 4, null).extractResult(response("""
            {"data":{"repository":{"issue":{"timeline":{
              "page_info":{"has_next_page":true,"end_cursor":"next"},
              "nodes":[
                {"kind":"LabeledEvent","id":"label","actor":{"login":"alice"},"created_at":"2026-11-11T09:00:00Z","label":{"name":"bug","color":"a2eeef"}},
                {"kind":"IssueComment","id":"comment","body":"Looks good"},
                {"kind":"CrossReferencedEvent","id":"closing","will_close_target":true,"source":{"number":14,"title":"Fix issue","url":"https://github.com/owner/project/pull/14"}},
                {"kind":"CrossReferencedEvent","id":"mention","will_close_target":false,"source":{"number":15,"title":"Related change","url":"https://github.com/owner/project/pull/15"}},
                {"kind":"CrossReferencedEvent","id":"issue-reference","source":{}},
                null,
                {"kind":"UnlabeledEvent","id":"removed","label":{"name":"bug","color":"a2eeef"}}
              ]
            }}}}}
        """.trimIndent())).toPage(null)

        assertEquals("next", page.next)
        assertEquals(listOf("label", "comment", "closing", "mention", "removed"), page.items.map(IssueActivity::id))
        assertEquals(LabelActivity("label", "alice", "2026-11-11T09:00:00Z", "bug", "a2eeef", true), page.items[0])
        assertEquals(Comment("comment", null, "Looks good", null), page.items[1])
        assertEquals(PullRequestActivity.Relation.CLOSES, (page.items[2] as PullRequestActivity).relation)
        assertEquals(PullRequestActivity.Relation.REFERENCES, (page.items[3] as PullRequestActivity).relation)
        assertFalse((page.items[4] as LabelActivity).added)
    }

    @Test
    fun `loading more activity keeps the timeline order and skips repeated nodes`() = runBlocking {
        val issue = Issue(4, "Issue", true, null, "", emptyList(), emptyList())
        val label = LabelActivity("label", "alice", null, "bug", null, true)
        val comment = Comment("comment", "bob", "A comment", null)
        val pr = PullRequestActivity("pr", "alice", null, 14, "Fix", "https://github.com/owner/project/pull/14",
            PullRequestActivity.Relation.CLOSES)
        val model = IssuesModel(this)
        model.connect(object : StubGateway(issue) {
            override suspend fun activity(number: Long, next: String?): Page<IssueActivity> = when (next) {
                null -> Page(listOf(label, comment), "next")
                "next" -> Page(listOf(comment, pr), null)
                else -> error("Unexpected cursor $next")
            }
        })
        model.select(issue.number)
        await { !model.state.value.detailLoading }
        assertEquals(Page(listOf(label, comment), "next"), model.state.value.activity)

        model.moreActivity()
        await { !model.state.value.detailLoading }
        assertEquals(Page(listOf(label, comment, pr), null), model.state.value.activity)
    }

    @Test
    fun `polling shows comments and changes made elsewhere without a loading indicator`() = runBlocking {
        val issue = Issue(4, "Issue", true, null, "", emptyList(), emptyList())
        val first = Comment("first", "alice", "First", null)
        val second = Comment("second", "bob", "Second", null)
        val foreign = Comment("foreign", "carol", "From GitHub", null)
        var comments = listOf(first, second)
        var activityRequests = 0
        val gateway = object : StubGateway(issue) {
            override suspend fun activity(number: Long, next: String?): Page<IssueActivity> {
                activityRequests++
                return when (next) {
                    null -> Page(comments.take(1), "next")
                    "next" -> Page(comments.drop(1), null)
                    else -> error("Unexpected cursor $next")
                }
            }
        }
        val model = IssuesModel(this)
        model.connect(gateway)
        await { !model.state.value.listLoading }
        model.select(issue.number)
        await { !model.state.value.detailLoading }
        model.moreActivity()
        await { !model.state.value.detailLoading }
        assertEquals(Page(listOf<IssueActivity>(first, second), null), model.state.value.activity)

        gateway.issue = issue.copy(title = "Renamed elsewhere")
        comments = listOf(first, second, foreign)
        val states = mutableListOf<IssuesState>()
        val watcher = launch { model.state.collect { states += it } }
        yield()
        model.pollDetail()
        await { model.state.value.activity.items.size == 3 }
        watcher.cancel()
        assertEquals(Page(listOf<IssueActivity>(first, second, foreign), null), model.state.value.activity)
        assertEquals("Renamed elsewhere", model.state.value.issue?.title)
        assertEquals("Renamed elsewhere", model.state.value.issues.items.single().title)
        assertTrue(states.none { it.detailLoading })

        val requests = activityRequests
        model.select(null)
        model.pollDetail()
        yield()
        assertEquals(requests, activityRequests)
    }

    @Test
    fun `refreshing the detail reloads issue and activity, updates the list and keeps the comment draft`() =
        runBlocking {
            val issue = Issue(4, "Issue", true, null, "", emptyList(), emptyList())
            val first = Comment("first", "alice", "First", null)
            val foreign = Comment("foreign", "carol", "From GitHub", null)
            var comments = listOf(first)
            var listRequests = 0
            var fail = false
            val gateway = object : StubGateway(issue) {
                override suspend fun issues(
                    filter: IssueFilter,
                    sort: IssueSort,
                    criteria: IssueCriteria,
                    next: String?,
                ): Page<Issue> {
                    listRequests++
                    return super.issues(filter, sort, criteria, next)
                }

                override suspend fun issue(number: Long): Issue =
                    if (fail) throw IssuesFailure("Offline") else super.issue(number)

                override suspend fun activity(number: Long, next: String?) = Page<IssueActivity>(comments, null)
            }
            val model = IssuesModel(this)
            model.refreshDetail()
            model.connect(gateway)
            await { !model.state.value.listLoading }
            model.refreshDetail()
            yield()
            assertFalse(model.state.value.detailLoading)

            model.select(issue.number)
            await { !model.state.value.detailLoading }
            model.editComment("Draft comment")
            val lists = listRequests

            gateway.issue = issue.copy(title = "Renamed elsewhere")
            comments = listOf(first, foreign)
            model.refreshDetail()
            assertTrue(model.state.value.detailLoading)
            await { !model.state.value.detailLoading }
            assertEquals(Page(listOf<IssueActivity>(first, foreign), null), model.state.value.activity)
            assertEquals("Renamed elsewhere", model.state.value.issue?.title)
            assertEquals("Renamed elsewhere", model.state.value.issues.items.single().title)
            assertEquals("Draft comment", model.state.value.commentDraft)
            assertEquals(lists, listRequests)

            fail = true
            model.refreshDetail()
            await { !model.state.value.detailLoading }
            assertEquals("Offline", model.state.value.detailError?.message)
            assertEquals("Renamed elsewhere", model.state.value.issue?.title)
        }

    @Test
    fun `refreshing the detail waits until a write has finished`() = runBlocking {
        val issue = Issue(4, "Issue", true, null, "", emptyList(), emptyList())
        val posted = CompletableDeferred<Comment>()
        var issueRequests = 0
        val gateway = object : StubGateway(issue) {
            override suspend fun issue(number: Long): Issue {
                issueRequests++
                return super.issue(number)
            }

            override suspend fun addComment(number: Long, body: String): Comment = posted.await()
        }
        val model = IssuesModel(this)
        model.connect(gateway)
        await { !model.state.value.listLoading }
        model.select(issue.number)
        await { !model.state.value.detailLoading }
        model.editComment("Comment")
        model.comment()
        await { model.state.value.writing }
        val requests = issueRequests

        model.refreshDetail()
        repeat(5) { yield() }
        assertEquals(requests, issueRequests)
        assertFalse(model.state.value.detailLoading)
        posted.complete(Comment("new", "alice", "Comment", null))
        await { !model.state.value.writing && !model.state.value.detailLoading }
    }

    @Test
    fun `a failed poll keeps the shown issue and does not report an error`() = runBlocking {
        val issue = Issue(4, "Issue", true, null, "", emptyList(), emptyList())
        val comment = Comment("comment", "alice", "Shown", null)
        var fail = false
        val model = IssuesModel(this)
        model.connect(object : StubGateway(issue) {
            override suspend fun issue(number: Long): Issue =
                if (fail) throw IssuesFailure("Offline") else issue

            override suspend fun activity(number: Long, next: String?) = Page<IssueActivity>(listOf(comment), null)
        })
        await { !model.state.value.listLoading }
        model.select(issue.number)
        await { !model.state.value.detailLoading }

        fail = true
        model.pollDetail()
        repeat(5) { yield() }
        assertEquals(issue, model.state.value.issue)
        assertEquals(Page(listOf<IssueActivity>(comment), null), model.state.value.activity)
        assertNull(model.state.value.detailError)
    }

    @Test
    fun `late replies from the previous repo cannot replace the current list or detail`() = runBlocking {
        val oldList = CompletableDeferred<Page<Issue>>()
        val oldDetail = CompletableDeferred<Issue>()
        val repoA = object : StubGateway(Issue(1, "Repo A", true, null, "", emptyList(), emptyList())) {
            override suspend fun search(
                query: String,
                filter: IssueFilter,
                sort: IssueSort,
                criteria: IssueCriteria,
                next: String?,
            ): Page<Issue> =
                withContext(NonCancellable) { withTimeout(3_000) { oldList.await() } }

            override suspend fun issue(number: Long): Issue = withContext(NonCancellable) { oldDetail.await() }
        }
        val repoB = StubGateway(Issue(2, "Repo B", true, null, "", emptyList(), emptyList()))
        val model = IssuesModel(this)
        model.connect(repoA)
        model.editSearch("Repo A")
        model.search()
        model.select(1)
        yield()
        model.connect(repoB)
        await { !model.state.value.listLoading }
        model.select(2)
        await { !model.state.value.detailLoading }
        oldList.complete(Page(listOf(repoA.issue), null))
        oldDetail.complete(repoA.issue)
        yield()
        assertEquals("", model.state.value.searchInput)
        assertNull(model.state.value.searchQuery)
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
        model.editDraft(draft)
        model.saveEditor()
        await { !model.state.value.writing }
        assertEquals(draft, model.state.value.editor?.draft)
        assertEquals("Failed", model.state.value.writeError?.message)
        assertEquals(1, creates)

        model.cancelEditor()
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
        val reasons = mutableListOf<CloseReason>()
        val gateway = object : StubGateway(initial) {
            override suspend fun create(title: String, body: String?): Issue {
                issue = Issue(9, title, true, "author", body.orEmpty(), emptyList(), emptyList())
                return issue
            }

            override suspend fun close(number: Long, reason: CloseReason): Issue {
                reasons += reason
                issue = issue.copy(open = false, closeReason = reason)
                return issue
            }

            override suspend fun reopen(number: Long): Issue {
                issue = issue.copy(open = true, closeReason = null)
                return issue
            }
        }
        val model = IssuesModel(this)
        model.connect(gateway)
        model.filter(IssueFilter.CLOSED)
        model.startCreate()
        model.editDraft(IssueDraft("New issue", "Description"))
        model.saveEditor()
        await { !model.state.value.writing && !model.state.value.detailLoading && !model.state.value.listLoading }
        assertEquals(IssueFilter.OPEN, model.state.value.filter)
        assertEquals(listOf(gateway.issue), model.state.value.issues.items)
        assertEquals(9L, model.state.value.selected)

        model.reopen()
        assertFalse(model.state.value.writing)
        model.close(CloseReason.NOT_PLANNED)
        await { !model.state.value.writing && !model.state.value.detailLoading && !model.state.value.listLoading }
        assertFalse(model.state.value.issue!!.open)
        assertEquals(CloseReason.NOT_PLANNED, model.state.value.issue!!.closeReason)
        assertEquals("Closed issue #9 as not planned.", model.state.value.feedback)
        assertTrue(model.state.value.issues.items.isEmpty())
        model.close(CloseReason.COMPLETED)
        assertFalse(model.state.value.writing)
        model.reopen()
        await { !model.state.value.writing && !model.state.value.detailLoading && !model.state.value.listLoading }
        assertTrue(model.state.value.issue!!.open)
        assertNull(model.state.value.issue!!.closeReason)
        assertEquals("Reopened issue #9.", model.state.value.feedback)
        assertEquals(listOf(gateway.issue), model.state.value.issues.items)
        model.close(CloseReason.COMPLETED)
        await { !model.state.value.writing && !model.state.value.detailLoading && !model.state.value.listLoading }
        assertEquals("Closed issue #9 as completed.", model.state.value.feedback)
        assertEquals(listOf(CloseReason.NOT_PLANNED, CloseReason.COMPLETED), reasons)
    }

    @Test
    fun `GitHub issue JSON maps the close reason of closed issues only`() {
        val request = GithubApiRequest.Get.JsonPage("https://api.github.com/example", RestIssue::class.java)
        val issues = request.extractResult(
            response(
                """[
                {"number":1,"title":"Done","state":"closed","state_reason":"completed"},
                {"number":2,"title":"Dropped","state":"closed","state_reason":"not_planned"},
                {"number":3,"title":"Twin","state":"closed","state_reason":"duplicate"},
                {"number":4,"title":"Old","state":"closed","state_reason":null},
                {"number":5,"title":"Back","state":"open","state_reason":"reopened"}
                ]"""
            )
        ).items.map(RestIssue::toIssue)
        assertEquals(
            listOf(CloseReason.COMPLETED, CloseReason.NOT_PLANNED, CloseReason.DUPLICATE, CloseReason.COMPLETED, null),
            issues.map(Issue::closeReason),
        )
        assertEquals(
            listOf("CLOSED", "NOT PLANNED", "DUPLICATE", "CLOSED", "OPEN"),
            issues.map { IssuesBundle.message("state.${it.stateSuffix}") },
        )
    }

    @Test
    fun `issue details name the close reason in the state badge`() {
        val issue = Issue(8, "Existing issue", true, null, "", emptyList(), emptyList())
        fun badge(issue: Issue) = Regex("<p class='issue-state'><span class='([^']+)'>&nbsp;&nbsp;([^&]+)&nbsp;")
            .find(IssueDetailsContent(issue, emptyList(), false).toHtml())!!.groupValues.drop(1)

        assertEquals(listOf("issue-open", "Open"), badge(issue))
        assertEquals(listOf("issue-closed", "Closed as completed"), badge(issue.copy(open = false, closeReason = CloseReason.COMPLETED)))
        assertEquals(
            listOf("issue-not-planned", "Closed as not planned"),
            badge(issue.copy(open = false, closeReason = CloseReason.NOT_PLANNED)),
        )
        assertEquals(
            listOf("issue-not-planned", "Closed as duplicate"),
            badge(issue.copy(open = false, closeReason = CloseReason.DUPLICATE)),
        )
    }

    @Test
    fun `editing prefills markdown and rejects a blank title without saving`() = runBlocking {
        val issue = Issue(8, "Existing issue", true, null, "## Heading\n\n- [ ] Task", emptyList(), emptyList())
        val model = IssuesModel(this)
        model.connect(StubGateway(issue))
        await { !model.state.value.listLoading }
        model.select(issue.number)
        await { !model.state.value.detailLoading }

        model.startEdit()
        assertEquals(IssueDraft(issue.title, issue.body), model.state.value.editor?.draft)
        model.editDraft(IssueDraft("   ", "Unsaved description"))
        model.saveEditor()
        assertFalse(model.state.value.writing)
        model.cancelEditor()
        assertNull(model.state.value.editor)
        assertEquals(issue, model.state.value.issue)
        assertEquals(listOf(issue), model.state.value.issues.items)
    }

    @Test
    fun `failed edit keeps draft and retry can clear the description of an issue on a later page`() = runBlocking {
        val initial = Issue(8, "Existing issue", true, null, "Description", listOf("bug"), emptyList())
        val firstIssue = initial.copy(number = 9, title = "Newer issue")
        var attempts = 0
        val gateway = object : StubGateway(initial) {
            override suspend fun issues(filter: IssueFilter, sort: IssueSort, criteria: IssueCriteria, next: String?): Page<Issue> =
                if (next == null) Page(listOf(firstIssue), "page2") else Page(listOf(issue), "page3")

            override suspend fun updateIssue(number: Long, title: String, body: String): Issue {
                assertEquals(initial.number, number)
                assertEquals("Updated title", title)
                assertEquals("", body)
                if (++attempts == 1) throw IssuesFailure("Could not complete the GitHub request.")
                issue = issue.copy(title = title, body = body)
                return issue
            }
        }
        val model = IssuesModel(this)
        model.connect(gateway)
        await { !model.state.value.listLoading }
        model.moreIssues()
        await { !model.state.value.listLoading }
        model.select(initial.number)
        await { !model.state.value.detailLoading }

        val draft = IssueDraft("  Updated title  ", "")
        model.startEdit()
        model.editDraft(draft)
        model.select(firstIssue.number)
        model.saveEditor()
        await { !model.state.value.writing }
        assertEquals("Could not complete the GitHub request.", model.state.value.writeError?.message)
        assertEquals(draft, model.state.value.editor?.draft)
        assertEquals(initial, model.state.value.issue)

        model.saveEditor()
        model.saveEditor()
        await { !model.state.value.writing }
        assertEquals(2, attempts)
        assertNull(model.state.value.editor)
        assertNull(model.state.value.writeError)
        assertEquals(gateway.issue, model.state.value.issue)
        assertEquals(Page(listOf(firstIssue, gateway.issue), "page3"), model.state.value.issues)
    }

    @Test
    fun `switching repositories discards edits and ignores an in-flight save response`() = runBlocking {
        val initial = Issue(8, "Repo A", true, null, "Original description", emptyList(), emptyList())
        val saved = CompletableDeferred<Issue>()
        val saving = CompletableDeferred<Unit>()
        val repoA = object : StubGateway(initial) {
            override suspend fun updateIssue(number: Long, title: String, body: String): Issue =
                withContext(NonCancellable) {
                    saving.complete(Unit)
                    withTimeout(3_000) { saved.await() }
                }
        }
        val repoB = StubGateway(initial.copy(title = "Repo B"))
        val model = IssuesModel(this)
        model.connect(repoA)
        await { !model.state.value.listLoading }
        model.select(initial.number)
        await { !model.state.value.detailLoading }
        model.startEdit()
        model.editDraft(IssueDraft("Changed title", "Changed body"))
        model.saveEditor()
        withTimeout(3_000) { saving.await() }

        model.connect(repoB)
        await { !model.state.value.listLoading }
        model.select(repoB.issue.number)
        await { !model.state.value.detailLoading }
        model.saveEditor()
        saved.complete(initial.copy(title = "Changed title", body = "Changed body"))
        yield()
        assertNull(model.state.value.editor)
        assertFalse(model.state.value.writing)
        assertEquals(repoB.issue, model.state.value.issue)
        assertEquals(listOf(repoB.issue), model.state.value.issues.items)
    }

    @Test
    fun `label pages are combined, loops are rejected and label names stay one path segment`() = runBlocking {
        val calls = mutableListOf<String>()
        val labels = allPages("page1") { url ->
            calls += url
            if (url == "page1") Page(listOf("bug"), "page2") else Page(listOf("docs"), null)
        }
        assertEquals(listOf("bug", "docs"), labels)
        assertEquals(listOf("page1", "page2"), calls)
        val loop = runCatching { allPages("page1") { Page(listOf("bug"), "page1") } }.exceptionOrNull()
        assertTrue(loop is IssuesFailure)

        assertEquals(
            "https://api.github.com/repos/owner/project/issues/4/labels/good%20first%20issue%2Fui%C3%A4",
            issueLabelUrl(Repository("owner", "project"), 4, "good first issue/uiä"),
        )
        val page = GithubApiRequest.Get.JsonPage("https://api.github.com/example", RestLabel::class.java)
            .extractResult(response("""[{"name":"bug","color":"d73a4a"}]""", "page2"))
        assertEquals(listOf("bug"), page.items.map(RestLabel::name))
        assertEquals("page2", page.nextLink)
    }

    @Test
    fun `label picker filters repository labels and keeps labels missing from the repository`() {
        val picker = SelectionPicker(PickerKind.LABELS, 4, setOf("legacy", "bug"), setOf("legacy", "bug"), listOf("bug", "Docs", "ui"))
        assertEquals(listOf("bug", "Docs", "ui", "legacy"), picker.visible)
        assertEquals(listOf("Docs"), picker.copy(query = " doc ").visible)
        val changed = picker.copy(selected = setOf("bug", "ui"))
        assertEquals(listOf("ui"), changed.added)
        assertEquals(listOf("legacy"), changed.removed)
        assertTrue(changed.changed)
        assertFalse(picker.changed)
    }

    @Test
    fun `saving labels sends only the selected changes and shows the labels stored on GitHub`() = runBlocking {
        val initial = Issue(4, "Issue", true, null, "", listOf("bug", "triage"), emptyList())
        val changes = mutableListOf<Pair<Collection<String>, Collection<String>>>()
        val gateway = object : StubGateway(initial) {
            override suspend fun labels() = listOf("bug", "docs", "triage", "ui")

            override suspend fun changeLabels(number: Long, add: Collection<String>, remove: Collection<String>): Issue {
                assertEquals(initial.number, number)
                changes += add to remove
                // Someone else added "external" on GitHub meanwhile; it must stay.
                issue = issue.copy(labels = (issue.labels - remove.toSet() + add + "external").distinct())
                return issue
            }
        }
        val model = IssuesModel(this)
        model.connect(gateway)
        await { !model.state.value.listLoading }
        model.select(initial.number)
        await { !model.state.value.detailLoading }

        model.startLabels()
        await { model.state.value.picker?.loading == false }
        assertEquals(listOf("bug", "docs", "triage", "ui"), model.state.value.picker?.available)
        model.togglePicked("docs")
        model.togglePicked("ui")
        model.togglePicked("triage")
        model.togglePicked("ui")
        model.savePicker()
        await { !model.state.value.writing && !model.state.value.detailLoading && !model.state.value.listLoading }

        assertEquals(listOf<Pair<Collection<String>, Collection<String>>>(listOf("docs") to listOf("triage")), changes)
        assertNull(model.state.value.picker)
        assertNull(model.state.value.writeError)
        assertEquals(listOf("bug", "docs", "external"), model.state.value.issue?.labels)
        assertEquals(listOf(gateway.issue), model.state.value.issues.items)
        assertEquals("Updated labels of issue #4.", model.state.value.feedback)
    }

    @Test
    fun `a partly failed label save closes the picker and shows the labels stored on GitHub`() = runBlocking {
        val initial = Issue(4, "Issue", true, null, "", listOf("bug", "docs"), emptyList())
        val gateway = object : StubGateway(initial) {
            override suspend fun labels(): List<String> = throw IssuesFailure("No labels")

            override suspend fun changeLabels(number: Long, add: Collection<String>, remove: Collection<String>): Issue {
                // The first removal reached GitHub before the second one failed.
                issue = issue.copy(labels = listOf("docs"))
                throw IssuesFailure("Save failed")
            }
        }
        val model = IssuesModel(this)
        model.connect(gateway)
        await { !model.state.value.listLoading }
        model.select(initial.number)
        await { !model.state.value.detailLoading }

        model.startLabels()
        await { model.state.value.picker?.loading == false }
        assertEquals("No labels", model.state.value.picker?.error?.message)
        assertEquals(listOf("bug", "docs"), model.state.value.picker?.visible)
        model.togglePicked("bug")
        model.togglePicked("docs")
        model.savePicker()
        await { !model.state.value.writing && !model.state.value.detailLoading }
        assertEquals("Save failed", model.state.value.writeError?.message)
        assertNull(model.state.value.picker)
        assertEquals(listOf("docs"), model.state.value.issue?.labels)
    }

    @Test
    fun `label changes that GitHub ignores are reported`() = runBlocking {
        val initial = Issue(4, "Issue", true, null, "", listOf("bug"), emptyList())
        val model = IssuesModel(this)
        model.connect(object : StubGateway(initial) {
            override suspend fun labels() = listOf("bug", "docs")
            override suspend fun changeLabels(number: Long, add: Collection<String>, remove: Collection<String>) = issue
        })
        await { !model.state.value.listLoading }
        model.select(initial.number)
        await { !model.state.value.detailLoading }
        model.startLabels()
        await { model.state.value.picker?.loading == false }
        model.togglePicked("bug")
        model.togglePicked("docs")
        model.savePicker()
        await { !model.state.value.writing && !model.state.value.detailLoading }
        assertNull(model.state.value.feedback)
        assertTrue(model.state.value.writeError?.message.orEmpty().contains("(docs, bug)"))
        assertEquals(initial, model.state.value.issue)
    }

    @Test
    fun `assignable people are read from every page of the GitHub response`() = runBlocking {
        val page = GithubApiRequest.Get.JsonPage("https://api.github.com/example", RestUser::class.java)
            .extractResult(response("""[{"login":"alice","id":1},{"login":"bob","id":2}]""", "page2"))
        assertEquals(listOf("alice", "bob"), page.items.map(RestUser::login))
        assertEquals("page2", page.nextLink)
    }

    @Test
    fun `assign me is offered only to an assignable account that is not assigned yet`() = runBlocking {
        val initial = Issue(4, "Issue", true, null, "", emptyList(), listOf("bob"))
        val changes = mutableListOf<Pair<Collection<String>, Collection<String>>>()
        val gateway = object : StubGateway(initial) {
            override suspend fun assignees() = listOf("Alice", "bob")

            override suspend fun changeAssignees(number: Long, add: Collection<String>, remove: Collection<String>):
                AssigneeChange {
                changes += add to remove
                issue = issue.copy(assignees = issue.assignees + add)
                return AssigneeChange(issue)
            }
        }
        val model = IssuesModel(this)
        model.connect(gateway, viewer = "alice")
        await { !model.state.value.listLoading }
        model.select(initial.number)
        await { !model.state.value.detailLoading }

        model.startAssignees()
        assertNull(model.state.value.assignableViewer)
        await { model.state.value.picker?.loading == false }
        assertEquals("Alice", model.state.value.assignableViewer)
        model.assignMe()
        await { !model.state.value.writing && !model.state.value.detailLoading && !model.state.value.listLoading }
        assertEquals(listOf<Pair<Collection<String>, Collection<String>>>(listOf("Alice") to emptyList()), changes)
        assertEquals(listOf("bob", "Alice"), model.state.value.issue?.assignees)
        assertEquals("Updated assignees of issue #4.", model.state.value.feedback)

        model.startAssignees()
        await { model.state.value.picker?.loading == false }
        assertNull(model.state.value.assignableViewer)
        model.cancelPicker()

        val stranger = IssuesModel(this)
        stranger.connect(gateway, viewer = "mallory")
        await { !stranger.state.value.listLoading }
        stranger.select(initial.number)
        await { !stranger.state.value.detailLoading }
        stranger.startAssignees()
        await { stranger.state.value.picker?.loading == false }
        assertNull(stranger.state.value.assignableViewer)
        stranger.assignMe()
        assertFalse(stranger.state.value.writing)
        assertEquals(1, changes.size)
    }

    @Test
    fun `saving assignees keeps concurrent changes and reports failed and ignored people separately`() = runBlocking {
        val initial = Issue(4, "Issue", true, null, "", listOf("bug"), listOf("alice", "bob"))
        val gateway = object : StubGateway(initial) {
            override suspend fun assignees() = listOf("alice", "bob", "carol", "dave")

            override suspend fun changeAssignees(number: Long, add: Collection<String>, remove: Collection<String>):
                AssigneeChange {
                assertEquals(listOf("carol", "dave"), add.toList())
                assertEquals(listOf("bob"), remove.toList())
                // GitHub ignores "dave" silently, refuses removing "bob" and someone else added "erin" meanwhile.
                issue = issue.copy(assignees = listOf("alice", "bob", "carol", "erin"))
                return AssigneeChange(issue, mapOf("bob" to IssuesFailure("GitHub returned 403.")))
            }
        }
        val model = IssuesModel(this)
        model.connect(gateway, viewer = "alice")
        await { !model.state.value.listLoading }
        model.select(initial.number)
        await { !model.state.value.detailLoading }

        model.startAssignees()
        await { model.state.value.picker?.loading == false }
        assertEquals(PickerKind.ASSIGNEES, model.state.value.picker?.kind)
        model.togglePicked("carol")
        model.togglePicked("dave")
        model.togglePicked("bob")
        model.savePicker()
        await { !model.state.value.writing && !model.state.value.detailLoading && !model.state.value.listLoading }

        assertNull(model.state.value.picker)
        assertNull(model.state.value.feedback)
        assertEquals(listOf("alice", "bob", "carol", "erin"), model.state.value.issue?.assignees)
        assertEquals(listOf("bug"), model.state.value.issue?.labels)
        val error = model.state.value.writeError?.message.orEmpty()
        assertTrue(error.contains("Could not change bob: GitHub returned 403."), error)
        assertTrue(error.contains("assignee changes (dave)"), error)
    }

    @Test
    fun `a failed assignee save closes the picker and reloads the issue from GitHub`() = runBlocking {
        val initial = Issue(4, "Issue", true, null, "", emptyList(), listOf("alice"))
        val gateway = object : StubGateway(initial) {
            override suspend fun assignees(): List<String> = throw IssuesFailure("No assignees")

            override suspend fun changeAssignees(number: Long, add: Collection<String>, remove: Collection<String>):
                AssigneeChange {
                issue = issue.copy(assignees = emptyList())
                throw IssuesFailure("Reload failed")
            }
        }
        val model = IssuesModel(this)
        model.connect(gateway)
        await { !model.state.value.listLoading }
        model.select(initial.number)
        await { !model.state.value.detailLoading }
        model.startAssignees()
        await { model.state.value.picker?.loading == false }
        assertEquals("No assignees", model.state.value.picker?.error?.message)
        assertEquals(listOf("alice"), model.state.value.picker?.visible)
        model.togglePicked("alice")
        model.savePicker()
        await { !model.state.value.writing && !model.state.value.detailLoading }
        assertEquals("Reload failed", model.state.value.writeError?.message)
        assertNull(model.state.value.picker)
        assertEquals(emptyList(), model.state.value.issue?.assignees)
    }

    @Test
    fun `GraphQL activity reports whether the active account may edit and delete comments`() {
        val page = issueActivityRequest(Repository("owner", "project"), 4, null).extractResult(response("""
            {"data":{"repository":{"issue":{"timeline":{
              "page_info":{"has_next_page":false,"end_cursor":null},
              "nodes":[
                {"kind":"IssueComment","id":"own","body":"Mine","viewer_can_update":true,"viewer_can_delete":true},
                {"kind":"IssueComment","id":"foreign","body":"Theirs","viewer_can_update":false,"viewer_can_delete":false}
              ]
            }}}}}
        """.trimIndent())).toPage(null)

        assertEquals(
            listOf(
                Comment("own", null, "Mine", null, canEdit = true, canDelete = true),
                Comment("foreign", null, "Theirs", null),
            ),
            page.items,
        )
    }

    @Test
    fun `comment mutations return the stored comment and explain missing comments and permissions`() {
        val updated = updateCommentRequest("own", "Fixed").extractResult(response("""
            {"data":{"update_issue_comment":{"issue_comment":{
              "kind":"IssueComment","id":"own","actor":{"login":"alice"},"created_at":"2026-11-11T09:00:00Z",
              "body":"Fixed **typo**","viewer_can_update":true,"viewer_can_delete":true
            }}}}
        """.trimIndent())).toComment()
        assertEquals(Comment("own", "alice", "Fixed **typo**", "2026-11-11T09:00:00Z", true, true), updated)

        val missing = assertFailsWith<IssuesFailure> {
            updateCommentRequest("gone", "Fixed").extractResult(response("""
                {"data":{"update_issue_comment":null},"errors":[{"type":"NOT_FOUND","message":"Could not resolve to a node"}]}
            """.trimIndent())).toComment()
        }
        assertTrue(missing.missing)
        assertEquals("This comment no longer exists. It may have been deleted on GitHub.", missing.message)

        val forbidden = assertFailsWith<IssuesFailure> {
            deleteCommentRequest("foreign").extractResult(response("""
                {"data":{"delete_issue_comment":null},"errors":[{"type":"FORBIDDEN","message":"Resource not accessible"}]}
            """.trimIndent())).confirm()
        }
        assertFalse(forbidden.missing)
        assertEquals("Your GitHub account is not allowed to change this comment.", forbidden.message)

        val other = assertFailsWith<IssuesFailure> {
            deleteCommentRequest("own").extractResult(response("""{"errors":[{"message":"Something broke"}]}""")).confirm()
        }
        assertEquals("Could not change the comment. Something broke", other.message)

        deleteCommentRequest("own").extractResult(
            response("""{"data":{"delete_issue_comment":{"client_mutation_id":null}}}"""),
        ).confirm()
    }

    @Test
    fun `editing a comment saves its Markdown and shows the stored comment`() = runBlocking {
        val issue = Issue(4, "Issue", true, null, "", emptyList(), emptyList())
        val own = Comment("own", "alice", "Typo", null, canEdit = true, canDelete = true)
        val foreign = Comment("foreign", "bob", "Theirs", null)
        val updates = mutableListOf<Pair<String, String>>()
        val model = IssuesModel(this)
        model.connect(object : StubGateway(issue) {
            override suspend fun activity(number: Long, next: String?) = Page<IssueActivity>(listOf(own, foreign), null)
            override suspend fun updateComment(id: String, body: String): Comment {
                updates += id to body
                return own.copy(body = "$body (stored)")
            }
        })
        model.select(issue.number)
        await { !model.state.value.detailLoading }

        model.startEditComment("foreign")
        assertNull(model.state.value.commentEditor)

        model.startEditComment("own")
        assertEquals(CommentEditor("own", 4, "Typo", "Typo"), model.state.value.commentEditor)
        model.saveCommentEditor()
        assertNull(model.state.value.commentEditor)
        assertTrue(updates.isEmpty())

        model.startEditComment("own")
        model.editCommentEditor("   ")
        model.saveCommentEditor()
        assertTrue(updates.isEmpty())
        assertEquals("   ", model.state.value.commentEditor?.body)

        model.editCommentEditor("Fixed")
        model.saveCommentEditor()
        await { !model.state.value.writing }
        assertEquals(listOf("own" to "Fixed"), updates)
        assertNull(model.state.value.commentEditor)
        assertEquals("Comment updated.", model.state.value.feedback)
        assertEquals(listOf<IssueActivity>(own.copy(body = "Fixed (stored)"), foreign), model.state.value.activity.items)
    }

    @Test
    fun `cancelling and switching issues discard the comment editor`() = runBlocking {
        val issue = Issue(4, "Issue", true, null, "", emptyList(), emptyList())
        val own = Comment("own", "alice", "Typo", null, canEdit = true)
        val model = IssuesModel(this)
        model.connect(object : StubGateway(issue) {
            override suspend fun activity(number: Long, next: String?) = Page<IssueActivity>(listOf(own), null)
        })
        model.select(issue.number)
        await { !model.state.value.detailLoading }
        model.startEditComment("own")
        model.editCommentEditor("Draft")
        model.startLabels()
        assertNull(model.state.value.picker)
        model.cancelCommentEditor()
        assertNull(model.state.value.commentEditor)
        assertEquals(listOf<IssueActivity>(own), model.state.value.activity.items)

        model.startEditComment("own")
        model.select(null)
        assertNull(model.state.value.commentEditor)
    }

    @Test
    fun `deleting a comment removes it and reloads the list`() = runBlocking {
        val issue = Issue(4, "Issue", true, null, "", emptyList(), emptyList(), commentCount = 2)
        val own = Comment("own", "alice", "Mine", null, canEdit = true, canDelete = true)
        val foreign = Comment("foreign", "bob", "Theirs", null)
        var comments = listOf<IssueActivity>(own, foreign)
        val deleted = mutableListOf<String>()
        var listRequests = 0
        val gateway = object : StubGateway(issue) {
            override suspend fun issues(filter: IssueFilter, sort: IssueSort, criteria: IssueCriteria, next: String?):
                Page<Issue> {
                listRequests++
                return super.issues(filter, sort, criteria, next)
            }

            override suspend fun activity(number: Long, next: String?) = Page(comments, null)
            override suspend fun deleteComment(id: String) {
                deleted += id
                comments = comments.filterNot { it.id == id }
                this.issue = issue.copy(commentCount = 1)
            }
        }
        val model = IssuesModel(this)
        model.connect(gateway)
        await { !model.state.value.listLoading }
        model.select(issue.number)
        await { !model.state.value.detailLoading }

        model.deleteComment("foreign")
        assertFalse(model.state.value.writing)
        val lists = listRequests

        model.deleteComment("own")
        await { !model.state.value.writing && !model.state.value.detailLoading && !model.state.value.listLoading }
        assertEquals(listOf("own"), deleted)
        assertEquals("Comment deleted.", model.state.value.feedback)
        assertEquals(listOf<IssueActivity>(foreign), model.state.value.activity.items)
        assertEquals(1, model.state.value.issues.items.single().commentCount)
        assertTrue(listRequests > lists)
    }

    @Test
    fun `a comment deleted elsewhere disappears while missing permissions keep the edited text`() = runBlocking {
        val issue = Issue(4, "Issue", true, null, "", emptyList(), emptyList())
        val own = Comment("own", "alice", "Typo", null, canEdit = true, canDelete = true)
        var comments = listOf<IssueActivity>(own)
        var failure = IssuesFailure("Your GitHub account is not allowed to change this comment.")
        val model = IssuesModel(this)
        model.connect(object : StubGateway(issue) {
            override suspend fun activity(number: Long, next: String?) = Page(comments, null)
            override suspend fun updateComment(id: String, body: String): Comment = throw failure
            override suspend fun deleteComment(id: String) = throw failure
        })
        model.select(issue.number)
        await { !model.state.value.detailLoading }

        model.startEditComment("own")
        model.editCommentEditor("Fixed")
        model.saveCommentEditor()
        await { !model.state.value.writing }
        assertEquals("Your GitHub account is not allowed to change this comment.", model.state.value.writeError?.message)
        assertEquals("Fixed", model.state.value.commentEditor?.body)

        failure = IssuesFailure("This comment no longer exists.", missing = true)
        comments = emptyList()
        model.saveCommentEditor()
        await { !model.state.value.writing && !model.state.value.detailLoading }
        assertEquals("This comment no longer exists.", model.state.value.writeError?.message)
        assertNull(model.state.value.commentEditor)
        assertEquals(emptyList(), model.state.value.activity.items)

        comments = listOf(own)
        model.refreshDetail()
        await { !model.state.value.detailLoading }
        comments = emptyList()
        model.deleteComment("own")
        await { !model.state.value.writing && !model.state.value.detailLoading }
        assertEquals("This comment no longer exists.", model.state.value.writeError?.message)
        assertEquals(emptyList(), model.state.value.activity.items)
    }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(3_000) { while (!condition()) yield() }
    }

    private open class StubGateway(var issue: Issue) : IssuesGateway {
        override suspend fun issues(filter: IssueFilter, sort: IssueSort, criteria: IssueCriteria, next: String?) =
            Page(listOf(issue).filter { filter.includes(it) && criteria.includes(it) }, null)

        override suspend fun search(
            query: String,
            filter: IssueFilter,
            sort: IssueSort,
            criteria: IssueCriteria,
            next: String?,
        ): Page<Issue> = error("Unexpected search")

        override suspend fun issue(number: Long) = issue
        override suspend fun activity(number: Long, next: String?) = Page<IssueActivity>(emptyList(), null)
        override suspend fun create(title: String, body: String?): Issue = error("Unexpected create")
        override suspend fun updateIssue(number: Long, title: String, body: String): Issue = error("Unexpected edit")
        override suspend fun addComment(number: Long, body: String): Comment = error("Unexpected comment")
        override suspend fun updateComment(id: String, body: String): Comment = error("Unexpected comment edit")
        override suspend fun deleteComment(id: String): Unit = error("Unexpected comment deletion")
        override suspend fun close(number: Long, reason: CloseReason): Issue = error("Unexpected close")
        override suspend fun reopen(number: Long): Issue = error("Unexpected reopen")
        override suspend fun labels(): List<String> = error("Unexpected label list")
        override suspend fun changeLabels(number: Long, add: Collection<String>, remove: Collection<String>): Issue =
            error("Unexpected label change")

        override suspend fun assignees(): List<String> = error("Unexpected assignee list")
        override suspend fun changeAssignees(number: Long, add: Collection<String>, remove: Collection<String>):
            AssigneeChange = error("Unexpected assignee change")

        override suspend fun milestones(): List<Milestone> = error("Unexpected milestone list")
        override suspend fun setMilestone(number: Long, milestone: Long?): Issue = error("Unexpected milestone change")
    }

    private fun response(json: String, next: String? = null): GithubApiResponse = object : GithubApiResponse {
        override fun findHeader(headerName: String): String? =
            next?.takeIf { headerName.equals("link", ignoreCase = true) }?.let { "<$it>; rel=\"next\"" }

        override fun <T> readBody(converter: ThrowableConvertor<Reader, T, IOException>): T =
            json.reader().use { converter.convert(it) }

        override fun <T> handleBody(converter: ThrowableConvertor<InputStream, T, IOException>): T =
            json.byteInputStream().use { converter.convert(it) }
    }
}
