package dev.issuetoolwindow

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import javax.swing.JPanel
import javax.swing.JToggleButton
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class MarkdownEditorTest {
    @Test
    fun `reference at the caret spans the whole token but filters by the typed prefix`() {
        val text = "Ping @alice-dev and see (#123)."
        val mention = requireNotNull(MarkdownReference.at(text, text.indexOf("ice-dev")))
        assertEquals(MarkdownReference('@', "al", 5, 15), mention)
        assertEquals("Ping @alex and see (#123).", text.replaceRange(mention.start, mention.end, "@alex"))

        val issue = requireNotNull(MarkdownReference.at(text, text.indexOf("23")))
        assertEquals(MarkdownReference('#', "1", 25, 29), issue)
        assertEquals("Ping @alice-dev and see (#1).", text.replaceRange(issue.start, issue.end, "#1"))
        assertEquals(MarkdownReference('#', "", 4, 5), MarkdownReference.at("See #", 5))
    }

    @Test
    fun `ordinary Markdown and email addresses do not start suggestions`() {
        for (text in listOf("##", "# ", "dev@alice", "\\@alice", "/#42", "#blue", "text #42 then")) {
            assertNull(MarkdownReference.at(text, text.length), text)
        }
    }
}

class MarkdownEditorPreviewTest : BasePlatformTestCase() {
    fun testPreviewKeepsDraftAndSelection() {
        val scope = CoroutineScope(EmptyCoroutineContext)
        try {
            val edits = mutableListOf<String>()
            val editor = MarkdownEditor(3, scope, testRootDisposable, IssuesModel(scope), edits::add)
            val draft = "## Steps\n\nUse **bold** and `code`."
            editor.syncDraft(draft, editable = true)
            editor.input.select(3, 8)
            val buttons = (editor.getComponent(0) as JPanel).components.filterIsInstance<JToggleButton>()

            buttons[1].doClick(0)
            val preview = editor.preferredFocus
            assertContains(preview.text, "<h2>")
            assertContains(preview.text, "<strong>bold</strong>")
            assertEquals(draft, editor.text)
            buttons[0].doClick(0)
            assertSame(editor.input, editor.preferredFocus)
            assertEquals(draft, editor.text)
            assertEquals("Steps", editor.input.selectedText)
            assertEquals(emptyList<String>(), edits)

            buttons[1].doClick(0)
            editor.syncDraft("Another draft", editable = true, resetView = true)
            assertSame(editor.input, editor.preferredFocus)
            assertEquals("Another draft", editor.text)
            assertEquals(0, editor.input.caretPosition)
        } finally {
            scope.cancel()
        }
    }
}
