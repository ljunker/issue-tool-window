package dev.issuetoolwindow

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBHtmlPaneConfiguration
import com.intellij.ui.components.JBHtmlPaneStyleConfiguration
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.ExtendableHTMLViewFactory
import com.intellij.util.ui.JBUI
import dev.issuetoolwindow.IssuesBundle.message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.Point
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.ButtonGroup
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.HyperlinkEvent
import javax.swing.text.JTextComponent
import kotlin.time.Duration.Companion.milliseconds

internal data class MarkdownReference(val marker: Char, val prefix: String, val start: Int, val end: Int) {
    companion object {
        fun at(text: String, caret: Int): MarkdownReference? {
            val start = (caret - 1 downTo 0).firstOrNull { !text[it].isLoginCharacter() } ?: return null
            val marker = text[start]
            if (marker != '@' && marker != '#') return null
            val before = text.getOrNull(start - 1)
            if (before != null && (before.isLetterOrDigit() || before in "_-@#\\/")) return null
            val end = (caret until text.length).firstOrNull { !text[it].isLoginCharacter() } ?: text.length
            if (marker == '#' && (start + 1 until end).any { text[it] !in '0'..'9' }) return null
            return MarkdownReference(marker, text.substring(start + 1, caret), start, end)
        }

        private fun Char.isLoginCharacter() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '-'
    }
}

internal data class MarkdownSuggestion(val reference: String, val label: String = reference)

/** Keeps the Swing document as the editable source while the other card shows its rendered Markdown. */
internal class MarkdownEditor(
    rows: Int,
    private val scope: CoroutineScope,
    parent: Disposable,
    private val model: IssuesModel,
    onEdit: (String) -> Unit,
) : JPanel(BorderLayout(JBUI.scale(4), JBUI.scale(4))) {
    val input = JBTextArea(rows, 1).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    val text: String get() = input.text
    val preferredFocus: JTextComponent get() = if (previewButton.isSelected) preview else input

    private val editButton = JToggleButton(message("editor.markdown.edit"))
    private val previewButton = JToggleButton(message("editor.markdown.preview"))
    private val preview = JBHtmlPane(
        JBHtmlPaneStyleConfiguration(),
        JBHtmlPaneConfiguration {
            customStyleSheetProvider { issueDetailsStyleSheet(JBColor.isBright()) }
            extensions(ExtendableHTMLViewFactory.Extensions.WORD_WRAP)
        },
    ).apply {
        Disposer.register(parent, this)
        accessibleContext.accessibleName = message("editor.markdown.preview")
        addHyperlinkListener { event ->
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                event.description?.let(IssueMarkdown::browserUri)?.let(BrowserUtil::browse)
            }
        }
    }
    private val editScroll = JBScrollPane(input)
    private val cardLayout = CardLayout()
    private val cards = object : JPanel(cardLayout) {
        // A long preview must not increase the height of the comment composer.
        override fun getPreferredSize() = editScroll.preferredSize
    }
    private var syncing = false
    private var inserting = false
    private var request: Job? = null
    private var completion: Completion? = null

    init {
        Disposer.register(parent) { dismissCompletion() }
        ButtonGroup().apply {
            add(editButton)
            add(previewButton)
        }
        editButton.isSelected = true
        editButton.addActionListener {
            showPreview(false)
            input.requestFocusInWindow()
        }
        previewButton.addActionListener { showPreview(true) }
        add(JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply {
            add(editButton)
            add(previewButton)
        }, BorderLayout.NORTH)
        cards.add(editScroll, "edit")
        cards.add(JBScrollPane(preview), "preview")
        add(cards, BorderLayout.CENTER)

        input.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (syncing) return
                onEdit(text)
                if (!inserting) requestCompletion()
            }
        })
        input.addCaretListener {
            if (completion?.reference?.let { it != currentReference() } == true) dismissCompletion()
        }
        input.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = dismissCompletion()
        })
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val shown = completion?.takeIf { it.popup.isVisible }
                if (e.keyCode == KeyEvent.VK_ESCAPE && (shown != null || request?.isActive == true)) {
                    dismissCompletion()
                } else if (shown != null && e.modifiersEx == 0) {
                    when (e.keyCode) {
                        KeyEvent.VK_UP -> shown.moveSelection(-1)
                        KeyEvent.VK_DOWN -> shown.moveSelection(1)
                        KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> accept(shown, shown.list.selectedIndex)
                        else -> return
                    }
                } else {
                    return
                }
                e.consume()
            }
        })
    }

    fun syncDraft(body: String, editable: Boolean, resetView: Boolean = false) {
        if (resetView) showPreview(false)
        input.isEditable = editable
        if (!editable) dismissCompletion()
        if (body == text) {
            if (resetView) input.caretPosition = 0
            return
        }
        dismissCompletion()
        val caret = input.caretPosition
        syncing = true
        try {
            input.text = body
            input.caretPosition = if (resetView) 0 else caret.coerceAtMost(input.document.length)
        } finally {
            syncing = false
        }
        if (previewButton.isSelected) renderPreview()
    }

    fun reloadCssStylesheets() = preview.reloadCssStylesheets()

    private fun showPreview(show: Boolean) {
        dismissCompletion()
        if (show) {
            previewButton.isSelected = true
            renderPreview()
        } else {
            editButton.isSelected = true
        }
        cardLayout.show(cards, if (show) "preview" else "edit")
    }

    private fun renderPreview() {
        preview.text = "<div class='issue-markdown'>${IssueMarkdown.render(text)}</div>"
        preview.caretPosition = 0
    }

    private fun currentReference(): MarkdownReference? {
        if (!input.isShowing || !input.isFocusOwner || !input.isEditable || input.selectionStart != input.selectionEnd) {
            return null
        }
        return MarkdownReference.at(text, input.caretPosition)
    }

    private fun requestCompletion() {
        dismissCompletion()
        val connection = model.connectionVersion
        request = scope.launch {
            // Document listeners can run before Swing advances the caret to the inserted character.
            delay(175.milliseconds)
            val reference = currentReference() ?: return@launch
            val suggestions = model.markdownSuggestions(reference)
            if (suggestions.isNotEmpty() && model.connectionVersion == connection && currentReference() == reference) {
                showCompletion(connection, reference, suggestions)
            }
        }
    }

    private fun showCompletion(connection: Long, reference: MarkdownReference, suggestions: List<MarkdownSuggestion>) {
        val caret = input.modelToView2D(input.caretPosition) ?: return
        val list = JBList(suggestions.map { it.label }).apply {
            isFocusable = false
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = suggestions.size.coerceAtMost(7)
            selectedIndex = 0
            accessibleContext.accessibleName = message("editor.markdown.suggestions")
        }
        val scroll = JBScrollPane(list).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = preferredSize.apply { width = width.coerceIn(JBUI.scale(160), JBUI.scale(420)) }
        }
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(scroll, null)
            .setRequestFocus(false)
            .setFocusable(false)
            .createPopup()
        val shown = Completion(connection, reference, suggestions, list, popup)
        completion = shown
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = list.locationToIndex(e.point)
                if (list.getCellBounds(index, index)?.contains(e.point) == true) accept(shown, index)
            }
        })
        popup.show(RelativePoint(input, Point(caret.x.toInt(), caret.maxY.toInt())))
    }

    private fun accept(shown: Completion, index: Int) {
        val suggestion = shown.suggestions.getOrNull(index) ?: return
        val reference = shown.reference
        val valid = model.connectionVersion == shown.connection && currentReference() == reference
        dismissCompletion()
        if (!valid) return
        inserting = true
        try {
            input.select(reference.start, reference.end)
            input.replaceSelection(suggestion.reference)
        } finally {
            inserting = false
        }
    }

    private fun dismissCompletion() {
        request?.cancel()
        request = null
        val shown = completion
        completion = null
        if (shown?.popup?.isVisible == true) shown.popup.cancel()
    }

    private data class Completion(
        val connection: Long,
        val reference: MarkdownReference,
        val suggestions: List<MarkdownSuggestion>,
        val list: JBList<String>,
        val popup: JBPopup,
    ) {
        fun moveSelection(offset: Int) {
            val index = (list.selectedIndex + offset).coerceIn(suggestions.indices)
            list.selectedIndex = index
            list.ensureIndexIsVisible(index)
        }
    }
}
