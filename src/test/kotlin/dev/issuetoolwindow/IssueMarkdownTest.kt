package dev.issuetoolwindow

import java.awt.Color
import java.net.URI
import javax.swing.JEditorPane
import javax.swing.SwingUtilities
import javax.swing.text.StyleConstants
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IssueMarkdownTest {
    @Test
    fun `description and comments render independently with selectable code`() {
        val issue = Issue(
            4, "Preview <draft>", true, "owner", """
                ## Steps

                1. Run `foo < bar`
                2. Check the output

                - [x] Done
                - [ ] Pending

                ```text
                first
                  second
                ```
            """.trimIndent(), emptyList(), emptyList(),
        )
        val comments = listOf(
            Comment("1", "reviewer", "**Reviewed** with `foo`", "2026-11-11T09:00:00Z"),
            Comment("2", "maintainer", "Follow-up", null),
        )
        val html = IssueDetailsContent(issue, comments, showEmptyActivity = false).toHtml()

        assertContains(html, "<h1 class='issue-title'>Preview &lt;draft&gt; <span class='issue-number'>#4</span></h1>")
        assertContains(html, "<div class='issue-description'><p class='issue-card-header'><strong>owner</strong></p>")
        assertContains(html, "<div class='issue-comment'><p class='issue-card-header'><strong>reviewer</strong> <span class='issue-time'>")
        assertContains(html, "<div class='issue-comment'><p class='issue-card-header'><strong>maintainer</strong></p>")
        assertContains(html, "<h2>Steps</h2>")
        assertContains(html, "<ol>")
        assertContains(html, "<li>Run <code>foo &lt; bar</code></li>")
        assertContains(html, "<ul>")
        assertContains(html, "☑ Done")
        assertContains(html, "☐ Pending")
        assertContains(html, "<strong>Reviewed</strong> with <code>foo</code>")

        SwingUtilities.invokeAndWait {
            val pane = JEditorPane("text/html", html).apply { isEditable = false }
            val displayed = pane.document.getText(0, pane.document.length)
            val code = "first\n  second"
            val offset = displayed.indexOf(code)
            check(offset >= 0) { displayed }
            pane.select(offset, offset + code.length)
            assertEquals(code, pane.selectedText)
            val end = displayed.indexOf("Follow-up") + "Follow-up".length
            pane.select(offset, end)
            assertContains(pane.selectedText, "Reviewed with foo")
            assertContains(pane.selectedText, "Follow-up")
        }
    }

    @Test
    fun `closed issue shows escaped metadata and an empty description without an invalid date`() {
        val issue = Issue(7, "Empty", false, null, " ", listOf("bug <ui>"), listOf("reviewer"), "invalid")
        val html = IssueDetailsContent(issue, emptyList(), showEmptyActivity = true).toHtml()

        assertContains(html, "class='issue-closed'")
        assertContains(html, "class='issue-label'>&nbsp;bug &lt;ui&gt;&nbsp;")
        assertContains(html, "class='issue-assignee'>&nbsp;reviewer&nbsp;")
        assertContains(html, "<strong>Unknown</strong></p>")
        assertContains(html, "No description.")
        assertContains(html, "No activity yet.")
        assertFalse(html.contains("issue-time"))
    }

    @Test
    fun `activity shows safe colored labels and clickable closing pull requests between comments`() {
        val issue = Issue(4, "Issue", true, null, "Description", emptyList(), emptyList())
        val activity = listOf(
            LabelActivity("label", "alice", null, "bug <ui>", "a2eeef", true),
            Comment("comment", "bob", "A comment", null),
            PullRequestActivity("pr", "alice", null, 14, "Fix <ui>", "https://github.com/owner/project/pull/14",
                PullRequestActivity.Relation.CLOSES),
            LabelActivity("bad-color", null, null, "bug", "red; background: url(example.png)", false),
            PullRequestActivity("bad-url", null, null, 15, "Related change", "javascript:alert(1)",
                PullRequestActivity.Relation.REFERENCES),
        )
        val html = IssueDetailsContent(issue, activity, showEmptyActivity = false).toHtml()

        assertContains(html, "background-color: #a2eeef; color: #1f2328")
        assertContains(html, "bug &lt;ui&gt;")
        assertContains(html, "linked a pull request that will close this issue")
        assertContains(html, "<a href='https://github.com/owner/project/pull/14'>Fix &lt;ui&gt; <span class='issue-number'>#14</span></a>")
        assertContains(html, "Related change <span class='issue-number'>#15</span>")
        assertFalse(html.contains("javascript:"))
        assertFalse(html.contains("example.png"))
        assertTrue(html.indexOf("bug &lt;ui&gt;") < html.indexOf("A comment"))
        assertTrue(html.indexOf("A comment") < html.indexOf("Fix &lt;ui&gt;"))
    }

    @Test
    fun `description and status styles use separate palettes for light and dark themes`() {
        val light = issueDetailsStyleSheet(light = true)
        val dark = issueDetailsStyleSheet(light = false)
        val description = "div.issue-description p.issue-card-header"
        assertEquals(Color(0xddf4ff), StyleConstants.getBackground(light.getRule(description)))
        assertEquals(Color(0x12283e), StyleConstants.getBackground(dark.getRule(description)))
        assertEquals(Color.WHITE, StyleConstants.getForeground(dark.getRule("span.issue-closed")))
    }

    @Test
    fun `raw HTML and images produce text without resource tags`() {
        val html = IssueMarkdown.render(
            """
            <img src="https://example.com/raw.png">

            Inline <b>HTML</b> and ![diagram](https://example.com/markdown.png).
            """.trimIndent(),
        )

        assertContains(html, "&lt;img src=&quot;https://example.com/raw.png&quot;&gt;")
        assertContains(html, "&lt;b&gt;HTML&lt;/b&gt; and diagram.")
        assertFalse(html.contains("<img"))
        assertFalse(html.contains("markdown.png"))
    }

    @Test
    fun `only absolute HTTP and HTTPS destinations become browser links`() {
        val html = IssueMarkdown.render(
            """
            [docs](https://example.com/?a=1&b=2) and http://example.org/notes
            [local file](file:///tmp/notes) and [relative](/issues/4)
            """.trimIndent(),
        )

        assertContains(html, "<a href=\"https://example.com/?a=1&amp;b=2\">docs</a>")
        assertContains(html, "<a href=\"http://example.org/notes\">http://example.org/notes</a>")
        assertContains(html, "local file and relative")
        assertEquals(2, Regex("<a href=").findAll(html).count())
        assertEquals(URI("HTTPS://example.com/notes"), IssueMarkdown.browserUri("HTTPS://example.com/notes"))
        assertNull(IssueMarkdown.browserUri("https:notes"))
        assertNull(IssueMarkdown.browserUri("https://example.com/bad path"))
        assertNull(IssueMarkdown.browserUri("file:///tmp/notes"))
    }
}
