package dev.issuetoolwindow

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.vcs.log.VcsLogDataKeys
import dev.issuetoolwindow.IssuesBundle.message
import git4idea.repo.GitRepositoryManager

/** Issues referenced in the message of a commit, together with the GitHub repository they belong to. */
private data class CommitIssues(val repository: Repository, val numbers: List<Long>)

/**
 * Opens the issues referenced in the message of the commit selected in the Git log, like `#42`. One reference shows as
 * a single entry, several as a submenu. Hidden without a reference, with several selected commits or without a
 * GitHub.com remote.
 */
class OpenCommitIssueActionGroup : ActionGroup(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val issues = commitIssues(e)
        e.presentation.isEnabledAndVisible = issues != null
        e.presentation.isPopupGroup = issues != null && issues.numbers.size > 1
        e.presentation.text = message("commitIssue.group")
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val issues = e?.let(::commitIssues) ?: return EMPTY_ARRAY
        val single = issues.numbers.size == 1
        return issues.numbers.map { number ->
            val text = if (single) message("commitIssue.open", number.toString()) else "#$number"
            OpenIssueAction(issues.repository, number, text)
        }.toTypedArray()
    }

    private fun commitIssues(e: AnActionEvent): CommitIssues? {
        val project = e.project ?: return null
        val selection = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION) ?: return null
        val commit = selection.commits.singleOrNull() ?: return null
        // Details still loading have an empty message and show no entry until the menu is opened again.
        val message = selection.cachedMetadata.singleOrNull()?.fullMessage ?: return null
        val numbers = referencedIssueNumbers(message).ifEmpty { return null }
        val git = GitRepositoryManager.getInstance(project).getRepositoryForRootQuick(commit.root) ?: return null
        val repository = referencedRepository(project, git) ?: return null
        return CommitIssues(repository, numbers)
    }
}

private class OpenIssueAction(
    private val repository: Repository,
    private val number: Long,
    text: String,
) : AnAction(text, message("commitIssue.description", number.toString(), repository.fullName), null), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<IssueNavigator>()?.open(repository, number)
    }
}
