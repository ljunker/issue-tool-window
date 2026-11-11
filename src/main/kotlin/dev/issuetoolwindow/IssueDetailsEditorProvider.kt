package dev.issuetoolwindow

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileWithoutContent
import com.intellij.testFramework.LightVirtualFile
import dev.issuetoolwindow.IssuesBundle.message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import kotlin.time.Duration.Companion.seconds

internal class IssueDetailsFile(
    val model: IssuesModel,
    val openOnGitHub: (Long) -> Unit,
    val createBranch: (Issue) -> Unit,
) : LightVirtualFile(message("detail.tab")), VirtualFileWithoutContent

class IssueDetailsEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is IssueDetailsFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        IssueDetailsEditor(file as IssueDetailsFile)

    override fun getEditorTypeId() = "github-issue-details"

    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

@OptIn(ExperimentalCoroutinesApi::class)
private class IssueDetailsEditor(private val issueFile: IssueDetailsFile) : UserDataHolderBase(), FileEditor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    private val view = IssueDetailsPanel(issueFile.model, issueFile.openOnGitHub, issueFile.createBranch, this)

    init {
        view.render(issueFile.model.state.value)
        scope.launch { issueFile.model.state.collect(view::render) }
        // Comments and changes made elsewhere appear while the tab is visible, without reopening the issue.
        scope.launch {
            while (true) {
                delay(POLL_INTERVAL)
                if (view.panel.isShowing) issueFile.model.pollDetail()
            }
        }
    }

    private companion object {
        val POLL_INTERVAL = 30.seconds
    }

    override fun getComponent(): JComponent = view.panel
    override fun getPreferredFocusedComponent(): JComponent = view.preferredFocus
    override fun getName() = issueFile.name
    override fun getFile(): VirtualFile = issueFile
    override fun isModified() = false
    override fun isValid() = issueFile.isValid
    override fun setState(state: FileEditorState) = Unit
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() = scope.cancel()
}
