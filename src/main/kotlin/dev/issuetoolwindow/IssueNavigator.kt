package dev.issuetoolwindow

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount

internal const val TOOL_WINDOW_ID = "GitHub Issues"
internal const val REPOSITORY_KEY = "dev.issuetoolwindow.repository"
internal const val ACCOUNT_KEY = "dev.issuetoolwindow.account"

/**
 * The account to use for a repository: the [current] one while it is still saved in the IDE, then the account saved for
 * the project, the IDE's default account and finally the only available account.
 */
internal fun preferredAccount(
    accounts: List<GithubAccount>,
    current: GithubAccount?,
    savedId: String?,
    default: GithubAccount?,
): GithubAccount? =
    current?.takeIf { it in accounts }
        ?: accounts.firstOrNull { it.id == savedId }
        ?: default?.takeIf { it in accounts }
        ?: accounts.singleOrNull()

/** A GitHub repository together with the name of the Git remote that points to it. */
internal data class RemoteRepository(val repository: Repository, val remoteName: String)

/** Picks the repository of a Git root: the one the tool window shows, then the `origin` remote, then the first one. */
internal fun issueRepository(remotes: List<RemoteRepository>, connected: Repository?): Repository? =
    remotes.firstOrNull { it.repository.fullName.equals(connected?.fullName, ignoreCase = true) }?.repository
        ?: remotes.firstOrNull { it.remoteName == "origin" }?.repository
        ?: remotes.firstOrNull()?.repository

internal interface IssueOpener {
    fun openIssue(target: Repository, number: Long)
}

/** Connects the tool window with parts of the IDE that open issues in it, such as the status bar widget. */
@Service(Service.Level.PROJECT)
internal class IssueNavigator(private val project: Project) {
    private val connectedState = MutableStateFlow<Pair<Repository, GithubAccount>?>(null)
    private var opener: IssueOpener? = null
    private var pending: Pair<Repository, Long>? = null

    /** Repository and account the tool window currently shows, or `null` while it shows none. */
    val connected: StateFlow<Pair<Repository, GithubAccount>?> = connectedState.asStateFlow()

    /** Registers the tool window and returns an open request that arrived before it existed. */
    fun register(opener: IssueOpener): Pair<Repository, Long>? {
        this.opener = opener
        return pending.also { pending = null }
    }

    fun unregister(opener: IssueOpener) {
        if (this.opener !== opener) return
        this.opener = null
        connectedState.value = null
    }

    fun connectionChanged(connection: Pair<Repository, GithubAccount>?) {
        connectedState.value = connection
    }

    /** Opens issue [number] of [repository] in the issue tab, creating the tool window content when needed. */
    fun open(repository: Repository, number: Long) {
        val current = opener
        if (current != null) {
            current.openIssue(repository, number)
            return
        }
        pending = repository to number
        // Asking for the content manager creates the content without showing the tool window; it then registers and
        // takes over the pending request.
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.contentManager
    }
}
