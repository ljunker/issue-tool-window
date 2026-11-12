package dev.issuetoolwindow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IssueTemplatesTest {
    @Test
    fun `front matter fills name, description, title, labels and assignees`() {
        val template = parseIssueTemplate(
            "bug_report.md",
            """
            ---
            name: Bug report
            about: Create a report to help us improve
            title: "[BUG] "
            labels: bug, needs triage
            assignees: 'octocat'
            ---

            **Describe the bug**
            A clear description.
            """.trimIndent(),
        )
        assertEquals(
            IssueTemplate(
                "Bug report",
                "Create a report to help us improve",
                IssueDraft("[BUG] ", "**Describe the bug**\nA clear description.", listOf("bug", "needs triage"), listOf("octocat")),
            ),
            template,
        )
    }

    @Test
    fun `labels and assignees may be flow lists, block lists or quoted strings`() {
        val flow = parseIssueTemplate(
            "a.md",
            "---\nname: A\nlabels: [\"bug\", 'good, first', ui]\nassignees: []\n---\n",
        )
        assertEquals(listOf("bug", "good, first", "ui"), flow.draft.labels)
        assertEquals(emptyList(), flow.draft.assignees)

        val block = parseIssueTemplate(
            "b.md",
            "---\nname: B\nlabels:\n  - bug # comment\n  - \"help wanted\"\n\nassignees:\n- alice\n- BOB\n- alice\ntitle: x\n---\nBody",
        )
        assertEquals(listOf("bug", "help wanted"), block.draft.labels)
        assertEquals(listOf("alice", "BOB"), block.draft.assignees)
        assertEquals("x", block.draft.title)

        val quoted = parseIssueTemplate("c.md", "---\nname: C\nlabels: \"bug, docs\"\nassignees: ''\n---\n")
        assertEquals(listOf("bug", "docs"), quoted.draft.labels)
        assertEquals(emptyList(), quoted.draft.assignees)
    }

    @Test
    fun `quotes, comments and unknown fields are handled like YAML`() {
        val template = parseIssueTemplate(
            "d.md",
            "---\n# leading comment\nname: 'It''s a #1 bug' # trailing\nabout: \"Say \\\"hi\\\"\"\ntitle: Issue #42 # comment\nlabels: C#\n" +
                "projects: [\"octo/1\"]\ntype: Bug\nbody:\n  nested: ignored\n---\nText",
        )
        assertEquals("It's a #1 bug", template.name)
        assertEquals("Say \"hi\"", template.about)
        assertEquals("Issue", template.draft.title)
        assertEquals("Text", template.draft.body)
        assertEquals(listOf("C#"), template.draft.labels)
    }

    @Test
    fun `Windows line endings and a byte order mark are accepted`() {
        val template = parseIssueTemplate("e.md", "\uFEFF---\r\nname: E\r\nlabels: bug\r\n---\r\nLine 1\r\nLine 2")
        assertEquals("E", template.name)
        assertEquals(listOf("bug"), template.draft.labels)
        assertEquals("Line 1\nLine 2", template.draft.body)
    }

    @Test
    fun `files without front matter or name are offered under their file name`() {
        assertEquals(
            IssueTemplate("plain", null, IssueDraft(body = "Just text\n")),
            parseIssueTemplate("plain.md", "Just text\n"),
        )
        assertEquals(
            IssueTemplate("open", null, IssueDraft(body = "---\nname: Open\nNo end")),
            parseIssueTemplate("open.md", "---\nname: Open\nNo end"),
        )
        val unnamed = parseIssueTemplate("feature.MD", "---\nabout: ''\ntitle: Feature\n---\nIdea")
        assertEquals("feature", unnamed.name)
        assertNull(unnamed.about)
        assertEquals("Feature", unnamed.draft.title)
    }

    @Test
    fun `only Markdown files are templates and they are ordered by file name`() {
        assertTrue(isMarkdownTemplate("bug.md"))
        assertTrue(isMarkdownTemplate("Feature.MD"))
        assertFalse(isMarkdownTemplate("config.yml"))
        assertFalse(isMarkdownTemplate("form.yaml"))
        assertFalse(isMarkdownTemplate("readme.txt"))

        val templates = linkedMapOf(
            "z.md" to IssueTemplate("Z", null, IssueDraft()),
            "B.md" to IssueTemplate("B", null, IssueDraft()),
            "a.md" to IssueTemplate("A", null, IssueDraft()),
        )
        assertEquals(listOf("A", "B", "Z"), templatesForChooser(templates).map(IssueTemplate::name))
    }

    @Test
    fun `typed names are split at commas without empty entries or duplicates`() {
        assertEquals(listOf("bug", "help wanted"), splitNames(" bug, help wanted ,, BUG, "))
        assertEquals(emptyList(), splitNames(""))
    }
}
