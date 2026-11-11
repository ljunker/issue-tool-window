package dev.issuetoolwindow

import com.intellij.dvcs.repo.Repository.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IssueBranchTest {
    @Test
    fun `suggested name starts with the issue number and uses the title as lowercase words`() {
        assertEquals(
            "9-lokalen-git-branch-aus-einem-issue-erstellen",
            suggestedBranchName(9, "Lokalen Git-Branch aus einem Issue erstellen"),
        )
        assertEquals("12-fix-npe-in-parser", suggestedBranchName(12, "  Fix NPE in `Parser`!  "))
    }

    @Test
    fun `suggested name transliterates umlauts and drops accents`() {
        assertEquals("3-groesse-aendern-fuer-uebersicht-cafe", suggestedBranchName(3, "Größe ändern für Übersicht (Café)"))
    }

    @Test
    fun `suggested name stays short at a word boundary`() {
        val name = suggestedBranchName(7, "one two three four five six seven eight nine ten eleven twelve thirteen")
        assertEquals("7-one-two-three-four-five-six-seven-eight-nine-ten", name)
        assertEquals("8-" + "a".repeat(50), suggestedBranchName(8, "a".repeat(80)))
    }

    @Test
    fun `suggested name falls back to the number when the title has no usable characters`() {
        assertEquals("issue-5", suggestedBranchName(5, "🚀 ???"))
        assertEquals("issue-6", suggestedBranchName(6, ""))
    }

    @Test
    fun `conflicts cover equal names and branches inside each other's path`() {
        val existing = listOf("main", "feature/login", "bugfix")
        assertEquals("main", conflictingBranch("main", existing, ignoreCase = false))
        assertEquals("feature/login", conflictingBranch("feature", existing, ignoreCase = false))
        assertEquals("bugfix", conflictingBranch("bugfix/9-crash", existing, ignoreCase = false))
        assertNull(conflictingBranch("feature-login", existing, ignoreCase = false))
        assertNull(conflictingBranch("9-new", existing, ignoreCase = false))
    }

    @Test
    fun `conflicts ignore case only on case-insensitive file systems`() {
        assertNull(conflictingBranch("Main", listOf("main"), ignoreCase = false))
        assertEquals("main", conflictingBranch("Main", listOf("main"), ignoreCase = true))
    }

    @Test
    fun `unfinished merge, rebase, cherry-pick and revert block the checkout`() {
        assertNull(unfinishedOperation(State.NORMAL))
        assertNull(unfinishedOperation(State.DETACHED))
        assertEquals("merge", unfinishedOperation(State.MERGING))
        assertEquals("rebase", unfinishedOperation(State.REBASING))
        assertEquals("cherry-pick", unfinishedOperation(State.GRAFTING))
        assertEquals("revert", unfinishedOperation(State.REVERTING))
    }
}
