package dev.issuetoolwindow

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.jetbrains.plugins.github.authentication.GHAccountsUtil
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount

private const val MAX_COMMIT_ISSUES = 10

internal data class CommitIssueTarget(val repository: Repository, val account: GithubAccount)

@OptIn(ExperimentalCoroutinesApi::class)
internal fun commitIssueTarget(project: Project, git: GitRepository): CommitIssueTarget? {
    val repository = referencedRepository(project, git) ?: return null
    val connectedAccount = project.service<IssueNavigator>().connected.value?.second
    val account = preferredAccount(
        GHAccountsUtil.accountsState.value.filter { it.server.isGithubDotCom },
        connectedAccount,
        PropertiesComponent.getInstance(project).getValue(ACCOUNT_KEY),
        GHAccountsUtil.getDefaultAccount(project),
    ) ?: return null
    return CommitIssueTarget(repository, account)
}

/** Offers an exact number first, even when it is older than the recent issues. */
internal suspend fun commitIssueSuggestions(
    prefix: String,
    gateway: IssuesGateway,
    searchTitles: suspend (String) -> List<Issue>,
): List<Issue> {
    val recent = gateway.issues(IssueFilter.ALL, IssueSort.RECENTLY_UPDATED, IssueCriteria(), null).items
    if (prefix.isEmpty()) return recent.take(MAX_COMMIT_ISSUES)

    if (prefix.all { it in '0'..'9' }) {
        val number = prefix.toLongOrNull()?.takeIf { it > 0 } ?: return emptyList()
        val matches = recent.filter { it.number.toString().startsWith(prefix) }
        val exact = matches.firstOrNull { it.number == number } ?: try {
            gateway.issue(number)
        } catch (error: IssuesFailure) {
            if (!error.missing) throw error
            null
        }
        return (listOfNotNull(exact) + matches.filterNot { it.number == exact?.number }).take(MAX_COMMIT_ISSUES)
    }

    val matches = recent.filter { it.title.contains(prefix, ignoreCase = true) }
    if (matches.size >= MAX_COMMIT_ISSUES || prefix.length < MIN_SEARCH_TEXT_LENGTH) {
        return matches.take(MAX_COMMIT_ISSUES)
    }
    return (matches + searchTitles(prefix)).distinctBy(Issue::number).take(MAX_COMMIT_ISSUES)
}

/** Prefix for the lookup, including the `#` it will replace when an issue is selected. */
internal fun commitIssuePrefix(text: CharSequence, caret: Int): String? {
    if (caret == 0 || text.getOrNull(caret)?.isIssueSearchCharacter() == true) return null
    val marker = (caret - 1 downTo 0).firstOrNull { !text[it].isIssueSearchCharacter() } ?: return null
    if (text[marker] != '#') return null
    val before = text.getOrNull(marker - 1)
    if (before != null && (before.isIssueSearchCharacter() || before in "#&/\\")) return null
    return text.subSequence(marker, caret).toString()
}

private fun Char.isIssueSearchCharacter(): Boolean = isLetterOrDigit() || this == '-' || this == '_'

internal fun branchCommitMessage(text: CharSequence, enabled: Boolean, branch: String?): String? =
    if (enabled && text.isEmpty()) branch?.let(::issueNumberFromBranch)?.let { "Refs #$it" } else null

/** A change without a Git root or changes spanning several roots leave the commit repository ambiguous. */
internal fun <T : Any> commitRepository(included: List<T?>, repositories: List<T>): T? =
    if (included.isEmpty()) repositories.singleOrNull() else included.distinct().singleOrNull()
