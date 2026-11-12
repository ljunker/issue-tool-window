package dev.issuetoolwindow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IssueSearchEverywhereTest {
    private val origin = RemoteRepository(Repository("octo", "app"), "origin")
    private val fork = RemoteRepository(Repository("me", "app"), "fork")

    @Test
    fun `hash and number ask for that issue`() {
        assertEquals(IssueSearchQuery.Number(42), issueSearchQuery("#42"))
        assertEquals(IssueSearchQuery.Number(42), issueSearchQuery("  #42 "))
        assertEquals(IssueSearchQuery.Number(7), issueSearchQuery("#007"))
        assertEquals(IssueSearchQuery.Number(1), issueSearchQuery("#1"))
    }

    @Test
    fun `numbers that cannot be an issue ask for nothing`() {
        assertNull(issueSearchQuery("#0"))
        assertNull(issueSearchQuery("#99999999999999999999"))
    }

    @Test
    fun `other input with at least three characters searches the text`() {
        assertEquals(IssueSearchQuery.Text("parser"), issueSearchQuery(" parser "))
        assertEquals(IssueSearchQuery.Text("fix parser"), issueSearchQuery("fix parser"))
        assertEquals(IssueSearchQuery.Text("#abc"), issueSearchQuery("#abc"))
        assertEquals(IssueSearchQuery.Text("#4a"), issueSearchQuery("#4a"))
        assertEquals(IssueSearchQuery.Text("123"), issueSearchQuery("123"))
    }

    @Test
    fun `blank and short input asks for nothing`() {
        assertNull(issueSearchQuery(""))
        assertNull(issueSearchQuery("   "))
        assertNull(issueSearchQuery("#"))
        assertNull(issueSearchQuery("ab"))
        assertNull(issueSearchQuery(" ab "))
        assertNull(issueSearchQuery("#a"))
    }

    @Test
    fun `repository of the tool window wins`() {
        val shown = Repository("other", "repo")
        assertEquals(shown, searchRepository(shown, "me/app", listOf(origin, fork)))
    }

    @Test
    fun `repository chosen last time is used while it is still a remote`() {
        assertEquals(fork.repository, searchRepository(null, "ME/App", listOf(origin, fork)))
    }

    @Test
    fun `origin remote is used without a usable saved repository`() {
        assertEquals(origin.repository, searchRepository(null, null, listOf(fork, origin)))
        assertEquals(origin.repository, searchRepository(null, "gone/repo", listOf(fork, origin)))
        assertEquals(fork.repository, searchRepository(null, null, listOf(fork)))
    }

    @Test
    fun `projects without a GitHub remote have no repository`() {
        assertNull(searchRepository(null, "me/app", emptyList()))
    }

    @Test
    fun `open issues come first and keep their order`() {
        val issues = listOf(issue(1, open = false), issue(2), issue(3, open = false), issue(4))
        assertEquals(listOf(2L, 4L, 1L, 3L), openIssuesFirst(issues).map { it.number })
        assertEquals(emptyList(), openIssuesFirst(emptyList()))
    }

    private fun issue(number: Long, open: Boolean = true) =
        Issue(number, "Issue $number", open, null, "", emptyList(), emptyList())
}
