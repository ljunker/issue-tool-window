// The classic Search Everywhere API is deprecated, but not scheduled for removal; its successor is still experimental and
// built for a frontend/backend split. The classic API keeps working and passes the plugin verifier.
@file:Suppress("DEPRECATION")

package dev.issuetoolwindow

import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.Processor
import dev.issuetoolwindow.IssuesBundle.message
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import org.jetbrains.plugins.github.GithubIcons
import org.jetbrains.plugins.github.authentication.GHAccountsUtil
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import org.jetbrains.plugins.github.util.GHHostedRepositoriesManager
import javax.swing.JList
import javax.swing.ListCellRenderer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/** Free text shorter than this is not searched, to stay within the rate limit of the GitHub search. */
internal const val MIN_SEARCH_TEXT_LENGTH = 3

/** What Search Everywhere asks GitHub for. */
internal sealed interface IssueSearchQuery {
    data class Number(val number: Long) : IssueSearchQuery
    data class Text(val text: String) : IssueSearchQuery
}

/**
 * `#42` asks for issue 42; other input with at least [MIN_SEARCH_TEXT_LENGTH] characters searches title and
 * description. Blank and shorter input, `#` alone and numbers that cannot be an issue ask for nothing.
 */
internal fun issueSearchQuery(pattern: String): IssueSearchQuery? {
    val input = pattern.trim()
    val digits = input.removePrefix("#")
    if (input.startsWith('#') && digits.isNotEmpty() && digits.all { it in '0'..'9' }) {
        return digits.toLongOrNull()?.takeIf { it > 0 }?.let(IssueSearchQuery::Number)
    }
    return if (input.length >= MIN_SEARCH_TEXT_LENGTH) IssueSearchQuery.Text(input) else null
}

/**
 * The repository Search Everywhere searches: the one the tool window shows, then the one chosen there last time while it
 * is still a remote of the project, then the repository of the `origin` remote or the first remote.
 */
internal fun searchRepository(connected: Repository?, saved: String?, remotes: List<RemoteRepository>): Repository? =
    connected
        ?: remotes.firstOrNull { it.repository.fullName.equals(saved, ignoreCase = true) }?.repository
        ?: issueRepository(remotes, null)

/** Puts open issues in front of closed ones and keeps the order within each group. */
internal fun openIssuesFirst(issues: List<Issue>): List<Issue> = issues.sortedBy { !it.open }

internal data class IssueSearchHit(val repository: Repository, val issue: Issue)

class IssueSearchEverywhereContributorFactory : SearchEverywhereContributorFactory<Any> {
    @Suppress("UNCHECKED_CAST")
    override fun createContributor(initEvent: AnActionEvent): SearchEverywhereContributor<Any> =
        IssueSearchEverywhereContributor(requireNotNull(initEvent.project))
            as SearchEverywhereContributor<Any>
}

/**
 * Finds issues of the selected repository in Search Everywhere. Without a GitHub.com remote, an account or a connection
 * it finds nothing and reports no errors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class IssueSearchEverywhereContributor(private val project: Project) :
    SearchEverywhereContributor<IssueSearchHit> {

    override fun getSearchProviderId() = ID
    override fun getGroupName() = message("searchEverywhere.group")
    override fun getSortWeight() = SORT_WEIGHT
    override fun showInFindResults() = false
    override fun isShownInSeparateTab() = true
    override fun isDumbAware() = true
    override fun getElementsRenderer(): ListCellRenderer<in IssueSearchHit> = IssueSearchHitRenderer()
    override fun getItemDescription(element: IssueSearchHit) = "#${element.issue.number} ${element.issue.title}"

    override fun processSelectedItem(selected: IssueSearchHit, modifiers: Int, searchText: String): Boolean {
        project.service<IssueNavigator>().open(selected.repository, selected.issue.number)
        return true
    }

    override fun fetchElements(
        pattern: String,
        progressIndicator: ProgressIndicator,
        consumer: Processor<in IssueSearchHit>,
    ) {
        val query = issueSearchQuery(pattern) ?: return
        val (repository, account) = target() ?: return
        var issues = emptyList<Issue>()
        try {
            ProgressManager.getInstance().executeProcessUnderProgress({
                issues = runBlockingCancellable { find(GithubIssuesGateway(repository, account), query) }
            }, progressIndicator)
        } catch (error: ProcessCanceledException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Missing issues, sign-in problems, rate limits and connection errors simply find nothing.
            return
        }
        for (issue in issues) {
            if (!consumer.process(IssueSearchHit(repository, issue))) return
        }
    }

    private suspend fun find(gateway: IssuesGateway, query: IssueSearchQuery): List<Issue> = when (query) {
        is IssueSearchQuery.Number -> listOf(gateway.issue(query.number))
        is IssueSearchQuery.Text -> {
            // Search Everywhere cancels this call when the input changes, so fast typing sends no request per key.
            delay(TYPING_DELAY)
            val page = gateway.search(query.text, IssueFilter.ALL, IssueSort.RECENTLY_UPDATED, IssueCriteria(), null)
            openIssuesFirst(page.items)
        }
    }

    private fun target(): Pair<Repository, GithubAccount>? {
        val connected = project.service<IssueNavigator>().connected.value
        val remotes = project.service<GHHostedRepositoriesManager>().knownRepositoriesState.value
            .filter { it.repository.serverPath.isGithubDotCom }
            .map {
                val path = it.repository.repositoryPath
                RemoteRepository(Repository(path.owner, path.repository), it.remote.remote.name)
            }
        val properties = PropertiesComponent.getInstance(project)
        val repository = searchRepository(connected?.first, properties.getValue(REPOSITORY_KEY), remotes) ?: return null
        val account = preferredAccount(
            GHAccountsUtil.accountsState.value.filter { it.server.isGithubDotCom },
            connected?.second,
            properties.getValue(ACCOUNT_KEY),
            GHAccountsUtil.getDefaultAccount(project),
        ) ?: return null
        return repository to account
    }

    override fun dispose() = Unit

    private companion object {
        const val ID = "dev.issuetoolwindow.IssueSearchEverywhere"
        const val SORT_WEIGHT = 1000
        val TYPING_DELAY = 300.milliseconds
    }
}

/** `#42 Title` with the state icon and the repository in gray. */
private class IssueSearchHitRenderer : ColoredListCellRenderer<IssueSearchHit>() {
    override fun customizeCellRenderer(
        list: JList<out IssueSearchHit>,
        value: IssueSearchHit?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        val hit = value ?: return
        icon = if (hit.issue.open) GithubIcons.IssueOpened else GithubIcons.IssueClosed
        append("#${hit.issue.number} ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        append(hit.issue.title)
        append("  " + hit.repository.fullName, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        toolTipText = hit.issue.title
    }
}
