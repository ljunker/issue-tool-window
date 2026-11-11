package dev.issuetoolwindow

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.jetbrains.plugins.github.api.GithubServerPath
import org.jetbrains.plugins.github.authentication.GHAccountsUtil
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import org.jetbrains.plugins.github.util.GHHostedRepositoriesManager
import java.net.URI

class IssuesToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val window = IssuesToolWindow(project)
        val content = ContentFactory.getInstance().createContent(window.panel, "", false)
        content.setDisposer(window)
        toolWindow.contentManager.addContent(content)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class IssuesToolWindow(private val project: Project) : Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    private val model = IssuesModel(scope)
    private val view = IssuesPanel(model, ::chooseRepository, ::chooseAccount, ::signIn, ::openOnGitHub)
    val panel = view.panel

    private var repositories = emptyList<Repository>()
    private var accounts = emptyList<GithubAccount>()
    private var repository: Repository? = null
    private var account: GithubAccount? = null
    private var connectedTo: Pair<Repository, GithubAccount>? = null

    init {
        scope.launch {
            val remotes = project.service<GHHostedRepositoriesManager>().knownRepositoriesState
            combine(remotes, GHAccountsUtil.accountsState) { mappings, savedAccounts ->
                val candidates = mappings.mapNotNull { mapping ->
                    val remote = mapping.repository
                    if (!remote.serverPath.isGithubDotCom) null
                    else Repository(remote.repositoryPath.owner, remote.repositoryPath.repository)
                }
                repositoriesForPicker(candidates) to savedAccounts.filter { it.server.isGithubDotCom }
                    .sortedBy { it.name.lowercase() }
            }.collect { (repos, savedAccounts) ->
                repositories = repos
                accounts = savedAccounts
                repository = repositoryForSelection(repos, repository)
                account = account?.takeIf { it in accounts }
                    ?: GHAccountsUtil.getDefaultAccount(project)?.takeIf { it in accounts }
                            ?: accounts.singleOrNull()
                updateContext()
            }
        }
        scope.launch { model.state.collect(view::render) }
    }

    private fun chooseRepository(repo: Repository) {
        repository = repo
        updateContext()
    }

    private fun chooseAccount(selected: GithubAccount) {
        account = selected
        updateContext()
    }

    private fun updateContext() {
        val repo = repository
        val selected = account
        val connection = if (repo != null && selected != null) repo to selected else null
        if (connectedTo != connection) {
            connectedTo = connection
            model.connect(connection?.let { (repo, account) -> GithubIssuesGateway(repo, account) })
        }
        view.showContext(repositories, repo, accounts, selected)
    }

    private fun signIn() {
        val selected = account
        val previous = GHAccountsUtil.accounts
        if (selected != null && model.state.value.needsSignIn && selected in previous) {
            if (GHAccountsUtil.reLogIn(selected, project, panel)) model.refresh()
        } else {
            val newAccount = GHAccountsUtil.logIn(
                server = GithubServerPath.DEFAULT_SERVER,
                project = project,
                parentComponent = panel
            )
            if (newAccount != null) {
                accounts = GHAccountsUtil.accounts.filter { it.server.isGithubDotCom }.sortedBy { it.name.lowercase() }
                account = newAccount
                updateContext()
            }
        }
    }

    private fun openOnGitHub(number: Long) {
        val repo = repository ?: return
        BrowserUtil.browse(URI("https", "github.com", "/${repo.fullName}/issues/$number", null).toASCIIString())
    }

    override fun dispose() {
        scope.cancel()
    }
}
