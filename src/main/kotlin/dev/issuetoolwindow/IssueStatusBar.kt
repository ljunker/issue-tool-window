package dev.issuetoolwindow

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import dev.issuetoolwindow.IssuesBundle.message
import git4idea.branch.GitBranchUtil
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.plugins.github.authentication.GHAccountsUtil
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import org.jetbrains.plugins.github.util.GHGitRepositoryMapping
import org.jetbrains.plugins.github.util.GHHostedRepositoriesManager
import java.awt.Component
import java.awt.event.MouseEvent

private const val WIDGET_ID = "dev.issuetoolwindow.BranchIssue"
private const val MAX_TITLE_LENGTH = 30
private val leadingIssueNumber = Regex("^([1-9][0-9]*)(?:-|$)")

/**
 * The issue number a branch name starts with, like `42` in `42-fix-parser`; a prefix path such as `feature/` in front of
 * the number is allowed. The number has to be followed by `-` or end the name.
 */
internal fun issueNumberFromBranch(branch: String): Long? =
    sequenceOf(branch, branch.substringAfterLast('/'))
        .firstNotNullOfOrNull { leadingIssueNumber.find(it)?.groupValues?.get(1)?.toLongOrNull() }

/** Shortens [title] to at most [max] characters, preferably at a word boundary, and marks the cut with `…`. */
internal fun shortIssueTitle(title: String, max: Int = MAX_TITLE_LENGTH): String {
    val text = title.trim().replace(Regex("\\s+"), " ")
    if (text.length <= max) return text
    val cut = text.take(max - 1)
    val space = cut.lastIndexOf(' ')
    return (if (space >= max / 2) cut.take(space) else cut).trimEnd() + "…"
}

class IssueStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = WIDGET_ID
    override fun getDisplayName() = message("statusBar.name")
    override fun createWidget(project: Project, scope: CoroutineScope): StatusBarWidget =
        IssueStatusBarWidget(project, scope)
}

/** Issue number taken from the current branch, together with the GitHub repository it belongs to. */
private data class BranchIssue(val repository: Repository, val number: Long)

/** What the widget shows; an empty [text] hides it. */
private data class Display(val text: String = "", val tooltip: String? = null, val target: BranchIssue? = null)

@OptIn(ExperimentalCoroutinesApi::class)
private class IssueStatusBarWidget(
    private val project: Project,
    private val scope: CoroutineScope,
) : StatusBarWidget, StatusBarWidget.TextPresentation {
    private val navigator = project.service<IssueNavigator>()
    private val changes = MutableStateFlow(0L)
    private var statusBar: StatusBar? = null
    private var display = Display()

    override fun ID() = WIDGET_ID
    override fun getPresentation() = this
    override fun getText() = display.text
    override fun getTooltipText() = display.tooltip
    override fun getAlignment() = Component.CENTER_ALIGNMENT
    override fun getClickConsumer() = Consumer<MouseEvent> {
        display.target?.let { navigator.open(it.repository, it.number) }
    }

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        val connection = project.messageBus.connect(this)
        connection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { changed() })
        // With several Git roots the branch follows the selected file, like the Git branch widget.
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = changed()
        })
        scope.launch(Dispatchers.EDT) {
            combine(
                changes,
                project.service<GHHostedRepositoriesManager>().knownRepositoriesState,
                GHAccountsUtil.accountsState,
                navigator.connected,
            ) { _, mappings, accounts, connected ->
                branchIssue(mappings, connected?.first) to account(accounts, connected?.second)
            }
                .distinctUntilChanged()
                .collectLatest { (issue, account) ->
                    show(Display())
                    if (issue != null) show(load(issue, account))
                }
        }
    }

    private fun changed() = changes.update { it + 1 }

    private fun branchIssue(mappings: Set<GHGitRepositoryMapping>, connected: Repository?): BranchIssue? {
        val selected = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        val git = GitBranchUtil.guessWidgetRepository(project, selected) ?: return null
        val number = git.currentBranch?.name?.let(::issueNumberFromBranch) ?: return null
        return issueRepository(githubRemotes(mappings, git), connected)?.let { BranchIssue(it, number) }
    }

    private fun account(accounts: Set<GithubAccount>, connected: GithubAccount?): GithubAccount? = preferredAccount(
        accounts.filter { it.server.isGithubDotCom },
        connected,
        PropertiesComponent.getInstance(project).getValue(ACCOUNT_KEY),
        GHAccountsUtil.getDefaultAccount(project),
    )

    private suspend fun load(target: BranchIssue, account: GithubAccount?): Display {
        val number = target.number.toString()
        val repository = target.repository.fullName
        if (account == null) return Display("#$number", message("statusBar.noAccount", number, repository), target)
        return try {
            val issue = GithubIssuesGateway(target.repository, account).issue(target.number)
            Display(
                "#$number ${shortIssueTitle(issue.title)}".trimEnd(),
                message("statusBar.tooltip", number, issue.title, repository),
                target,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: IssuesFailure) {
            // Numbers without an issue, like in `2026-release`, do not belong to an issue: hide the widget.
            if (error.missing) Display()
            else Display("#$number", message("statusBar.unavailable", number, repository, error.message.orEmpty()), target)
        } catch (error: Exception) {
            Display("#$number", message("statusBar.unavailable", number, repository, error.message.orEmpty()), target)
        }
    }

    private fun show(next: Display) {
        if (next == display) return
        display = next
        statusBar?.updateWidget(WIDGET_ID)
    }
}
