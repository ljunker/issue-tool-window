package dev.issuetoolwindow

import java.net.URI
import javax.swing.JEditorPane
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

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
        val comment = Comment(1, "reviewer", "**Reviewed** with `foo`", null)
        val html = IssueDetailsContent(issue, listOf(comment), showEmptyComments = false).toHtml()

        assertContains(html, "Preview &lt;draft&gt;</h1>")
        assertContains(html, "<h2>Steps</h2>")
        assertContains(html, "<ol>")
        assertContains(html, "<li>Run <code>foo &lt; bar</code></li>")
        assertContains(html, "<ul>")
        assertContains(html, "☑ Done")
        assertContains(html, "☐ Pending")
        assertContains(html, "<h3>reviewer</h3>")
        assertContains(html, "<strong>Reviewed</strong> with <code>foo</code>")

        SwingUtilities.invokeAndWait {
            val pane = JEditorPane("text/html", html).apply { isEditable = false }
            val displayed = pane.document.getText(0, pane.document.length)
            val code = "first\n  second"
            val offset = displayed.indexOf(code)
            check(offset >= 0) { displayed }
            pane.select(offset, offset + code.length)
            assertEquals(code, pane.selectedText)
        }
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
