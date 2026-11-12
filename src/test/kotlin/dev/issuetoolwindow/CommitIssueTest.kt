package dev.issuetoolwindow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommitIssueTest {
    @Test
    fun `commit issue prefix is taken from the token before the caret`() {
        assertEquals("#", commitIssuePrefix("#", 1))
        assertEquals("#42", commitIssuePrefix("Refs #42\nDetails", 8))
        assertEquals("#parser", commitIssuePrefix("Fix #parser", 11))
        assertEquals("#Über", commitIssuePrefix("#Über", 5))
        assertNull(commitIssuePrefix("Use C#42", 8))
        assertNull(commitIssuePrefix("Text &#42", 9))
        assertNull(commitIssuePrefix("Refs #420", 8))
        assertNull(commitIssuePrefix("#42 ", 4))
    }

    @Test
    fun `only an empty enabled message on a numbered branch receives a reference`() {
        assertEquals("Refs #42", branchCommitMessage("", true, "42-fix-parser"))
        assertNull(branchCommitMessage("Existing draft", true, "42-fix-parser"))
        assertNull(branchCommitMessage(" ", true, "42-fix-parser"))
        assertNull(branchCommitMessage("", false, "42-fix-parser"))
        assertNull(branchCommitMessage("", true, "develop"))
        assertNull(branchCommitMessage("", true, null))
    }

    @Test
    fun `the included changes take precedence over the project repositories`() {
        val repositories = listOf("origin", "other")
        assertEquals("other", commitRepository(listOf("other", "other"), repositories))
        assertNull(commitRepository(listOf("origin", "other"), repositories))
        assertNull(commitRepository(listOf("origin", null), repositories))
        assertNull(commitRepository(emptyList(), repositories))
        assertEquals("origin", commitRepository(emptyList(), listOf("origin")))
    }
}
