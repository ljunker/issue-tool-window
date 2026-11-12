package dev.issuetoolwindow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IssueStatusBarTest {
    @Test
    fun `issue number is taken from the start of the branch name`() {
        assertEquals(42L, issueNumberFromBranch("42-fix-parser"))
        assertEquals(42L, issueNumberFromBranch("42"))
        assertEquals(9L, issueNumberFromBranch("9-lokalen-git-branch-aus-einem-issue-erstellen"))
        assertEquals(42L, issueNumberFromBranch("42-fix/parser"))
    }

    @Test
    fun `issue number may follow a prefix path`() {
        assertEquals(35L, issueNumberFromBranch("feature/35-issue-in-statusleiste"))
        assertEquals(7L, issueNumberFromBranch("users/lj/7"))
    }

    @Test
    fun `branches without a leading issue number have no issue`() {
        assertNull(issueNumberFromBranch("main"))
        assertNull(issueNumberFromBranch("release-42"))
        assertNull(issueNumberFromBranch("v2-cleanup"))
        assertNull(issueNumberFromBranch("42abc"))
        assertNull(issueNumberFromBranch("42_fix"))
        assertNull(issueNumberFromBranch("0-zero"))
        assertNull(issueNumberFromBranch("007-bond"))
        assertNull(issueNumberFromBranch("feature/"))
        assertNull(issueNumberFromBranch(""))
    }

    @Test
    fun `numbers too large for an issue are ignored`() {
        assertNull(issueNumberFromBranch("99999999999999999999-overflow"))
    }

    @Test
    fun `short titles stay unchanged apart from whitespace`() {
        assertEquals("Fix parser", shortIssueTitle("  Fix   parser "))
        assertEquals("a".repeat(30), shortIssueTitle("a".repeat(30)))
    }

    @Test
    fun `long titles are cut at a word boundary`() {
        assertEquals(
            "Issue des aktuellen Branches…",
            shortIssueTitle("Issue des aktuellen Branches in der Statusleiste anzeigen"),
        )
    }

    @Test
    fun `long words are cut hard`() {
        assertEquals("a".repeat(29) + "…", shortIssueTitle("a".repeat(40)))
        assertEquals("ab " + "c".repeat(26) + "…", shortIssueTitle("ab " + "c".repeat(40)))
    }

    @Test
    fun `repository prefers the connected one, then origin, then the first remote`() {
        val fork = Repository("me", "tool")
        val upstream = Repository("org", "tool")
        val remotes = listOf(RemoteRepository(fork, "fork"), RemoteRepository(upstream, "origin"))
        assertEquals(fork, issueRepository(remotes, Repository("ME", "Tool")))
        assertEquals(upstream, issueRepository(remotes, null))
        assertEquals(upstream, issueRepository(remotes, Repository("other", "repo")))
        assertEquals(fork, issueRepository(listOf(RemoteRepository(fork, "fork")), null))
        assertNull(issueRepository(emptyList(), null))
    }
}
