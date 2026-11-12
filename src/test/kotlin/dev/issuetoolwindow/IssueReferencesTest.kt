package dev.issuetoolwindow

import kotlin.test.Test
import kotlin.test.assertEquals

class IssueReferencesTest {
    @Test
    fun `finds a single reference with its range`() {
        assertEquals(listOf(IssueReference(4..6, 42)), issueReferences("see #42 for details"))
    }

    @Test
    fun `finds every reference in order`() {
        assertEquals(
            listOf(IssueReference(4..5, 1), IssueReference(12..15, 200), IssueReference(21..22, 1)),
            issueReferences("Fix #1, see #200 and #1"),
        )
    }

    @Test
    fun `references may stand at the start and end of the text and next to punctuation`() {
        assertEquals(listOf(42L), numbers("#42"))
        assertEquals(listOf(42L), numbers("// #42"))
        assertEquals(listOf(42L), numbers("(#42)"))
        assertEquals(listOf(42L), numbers("Closes #42."))
        assertEquals(listOf(42L, 7L), numbers("#42,#7"))
        assertEquals(listOf(42L), numbers("fixed in #42\nnext line"))
        assertEquals(listOf(42L), numbers("/* #42 */"))
        assertEquals(listOf(42L), numbers("-#42-"))
    }

    @Test
    fun `numbers glued to words are no references`() {
        assertEquals(emptyList(), numbers("a#1"))
        assertEquals(emptyList(), numbers("x_#1"))
        assertEquals(emptyList(), numbers("ä#1"))
        assertEquals(emptyList(), numbers("#12abc"))
        assertEquals(emptyList(), numbers("#12_"))
        assertEquals(emptyList(), numbers("#12ü"))
        assertEquals(emptyList(), numbers("1#2"))
    }

    @Test
    fun `invalid numbers and character references are no references`() {
        assertEquals(emptyList(), numbers("#"))
        assertEquals(emptyList(), numbers("# 42"))
        assertEquals(emptyList(), numbers("#0"))
        assertEquals(emptyList(), numbers("#042"))
        assertEquals(emptyList(), numbers("&#123;"))
        assertEquals(emptyList(), numbers("##"))
        assertEquals(emptyList(), numbers("#99999999999999999999"))
    }

    @Test
    fun `referenced issue numbers are distinct and keep their first order`() {
        assertEquals(listOf(7L, 3L), referencedIssueNumbers("Refs #7, #3 and #7 again"))
        assertEquals(emptyList(), referencedIssueNumbers("No issue here"))
    }

    private fun numbers(text: String) = issueReferences(text).map { it.number }
}
