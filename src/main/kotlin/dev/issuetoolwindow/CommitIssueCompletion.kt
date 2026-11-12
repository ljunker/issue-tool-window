package dev.issuetoolwindow

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupEvent
import com.intellij.codeInsight.lookup.LookupEx
import com.intellij.codeInsight.lookup.LookupListener
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.ui.CommitMessage
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener

private val CONTROLLER = Key.create<CommitIssueEditor>("dev.issuetoolwindow.commitIssueEditor")

class CommitIssueEditorListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        val message = editor.document.getUserData(CommitMessage.DATA_KEY) ?: return
        val service = project.service<CommitIssueEditors>()
        val controller = CommitIssueEditor(project, service.scope, editor, message)
        Disposer.register(service, controller)
        editor.putUserData(CONTROLLER, controller)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        event.editor.getUserData(CONTROLLER)?.let(Disposer::dispose)
    }
}

@Service(Service.Level.PROJECT)
internal class CommitIssueEditors(val scope: CoroutineScope) : Disposable {
    override fun dispose() = Unit
}

private class CommitIssueEditor(
    private val project: Project,
    private val scope: CoroutineScope,
    private val editor: Editor,
    private val message: CommitMessage,
) : Disposable {
    private val component = editor.contentComponent
    private var prefillChecked = false
    private var prefilling = false
    private var request: Job? = null
    private var lookup: LookupEx? = null

    private val focusListener = object : FocusAdapter() {
        override fun focusGained(e: FocusEvent) = schedulePrefill()

        override fun focusLost(e: FocusEvent) {
            request?.cancel()
            hideLookup()
        }
    }
    private val hierarchyListener = HierarchyListener {
        if (it.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && component.isShowing) {
            schedulePrefill()
        }
    }

    init {
        component.addFocusListener(focusListener)
        component.addHierarchyListener(hierarchyListener)
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!prefilling && component.isFocusOwner) requestSuggestions()
            }
        }, this)
        if (component.isShowing) schedulePrefill()
    }

    private fun schedulePrefill() {
        if (prefillChecked) return
        // The commit workflow restores its draft and installs its data provider while the editor opens.
        scope.launch(Dispatchers.EDT + ModalityState.stateForComponent(component).asContextElement()) {
            if (prefillChecked || editor.isDisposed || editor.isViewer || !component.isShowing) return@launch
            val context = commitContext() ?: return@launch
            if (context.amending) return@launch
            val branch = context.git.currentBranch?.name ?: return@launch
            prefillChecked = true
            val initialMessage = branchCommitMessage(
                editor.document.charsSequence, commitIssuePrefillEnabled(project), branch,
            ) ?: return@launch
            prefilling = true
            try {
                message.setCommitMessage(initialMessage)
            } finally {
                prefilling = false
            }
        }
    }

    private fun requestSuggestions() {
        request?.cancel()
        request = scope.launch(Dispatchers.EDT + ModalityState.stateForComponent(component).asContextElement()) {
            hideLookup()
            val input = currentInput() ?: return@launch
            delay(250)
            if (currentInput() != input) return@launch
            val target = currentTarget() ?: return@launch
            val gateway = GithubIssuesGateway(target.repository, target.account)
            val issues = try {
                commitIssueSuggestions(input.prefix.drop(1), gateway, gateway::searchTitles)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (_: Exception) {
                // Unavailable credentials, a failed request or a response error leaves the editor usable.
                return@launch
            }
            if (issues.isEmpty() || currentInput() != input || currentTarget() != target) return@launch
            if (LookupManager.getActiveLookup(editor) != null) return@launch

            val items = commitIssueLookupItems(issues, input.prefix)
            lookup = LookupManager.getInstance(project).showLookup(editor, items, input.prefix)?.also {
                it.addLookupListener(object : LookupListener {
                    override fun beforeItemSelected(event: LookupEvent): Boolean =
                        currentInput() == input && currentTarget() == target

                    override fun itemSelected(event: LookupEvent) {
                        lookup = null
                        request?.cancel()
                    }

                    override fun lookupCanceled(event: LookupEvent) {
                        lookup = null
                        if (event.isCanceledExplicitly) request?.cancel()
                    }
                })
            }
        }
    }

    private fun currentInput(): CompletionInput? {
        if (editor.isDisposed || editor.isViewer || !component.isFocusOwner ||
            editor.caretModel.caretCount != 1 || editor.selectionModel.hasSelection()
        ) return null
        val offset = editor.caretModel.offset
        val prefix = commitIssuePrefix(editor.document.charsSequence, offset) ?: return null
        return CompletionInput(prefix, offset, editor.document.modificationStamp)
    }

    private fun currentTarget(): CommitIssueTarget? = commitContext()?.let { commitIssueTarget(project, it.git) }

    private fun commitContext(): CommitContext? {
        if (message.editorField.editor !== editor) return null
        val changesSupplier = editor.document.getUserData(CommitMessage.CHANGES_SUPPLIER_KEY) ?: return null
        val data = DataManager.getInstance().getDataContext(component)
        val workflow = VcsDataKeys.COMMIT_WORKFLOW_UI.getData(data)?.takeIf { it.commitMessageUi === message }
        val paths = if (workflow == null) {
            ChangesUtil.iteratePaths(changesSupplier.get()).toList()
        } else {
            ChangesUtil.iteratePaths(workflow.getIncludedChanges()).toList() + workflow.getIncludedUnversionedFiles()
        }
        val manager = GitRepositoryManager.getInstance(project)
        val git = commitRepository(paths.map(manager::getRepositoryForFileQuick), manager.repositories) ?: return null
        val amending = VcsDataKeys.COMMIT_WORKFLOW_HANDLER.getData(data)?.amendCommitHandler?.isAmendCommitMode == true
        return CommitContext(git, amending)
    }

    private fun hideLookup() {
        val shown = lookup ?: return
        lookup = null
        if (LookupManager.getActiveLookup(editor) === shown) shown.hideLookup(false)
    }

    override fun dispose() {
        request?.cancel()
        component.removeFocusListener(focusListener)
        component.removeHierarchyListener(hierarchyListener)
        editor.putUserData(CONTROLLER, null)
    }

    private data class CommitContext(val git: GitRepository, val amending: Boolean)
    private data class CompletionInput(val prefix: String, val offset: Int, val stamp: Long)
}

internal fun commitIssueLookupItems(issues: List<Issue>, prefix: String): Array<LookupElement> =
    issues.map { issue ->
        LookupElementBuilder.create("#${issue.number}")
            .withTailText(" ${issue.title}", true)
            // Match the typed title or number, but insert only the issue reference.
            .withLookupString(prefix)
    }.toTypedArray()
