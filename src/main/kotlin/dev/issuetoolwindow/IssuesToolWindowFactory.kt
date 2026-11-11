package dev.issuetoolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorOpenRequest
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.util.IconUtil
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.JBUI
import dev.issuetoolwindow.IssuesBundle.message
import git4idea.branch.GitBrancher
import git4idea.repo.GitRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.github.api.GithubApiRequestExecutor
import org.jetbrains.plugins.github.api.GithubApiRequests
import org.jetbrains.plugins.github.api.GithubServerPath
import org.jetbrains.plugins.github.authentication.GHAccountsUtil
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import org.jetbrains.plugins.github.util.GHHostedRepositoriesManager
import java.awt.CardLayout
import java.net.URI
import javax.swing.Icon
import javax.swing.JPanel

class IssuesToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val window = IssuesToolWindow(project)
        val content = ContentFactory.getInstance().createContent(window.panel, "", false)
        content.setDisposer(window)
        toolWindow.contentManager.addContent(content)
        window.attach(toolWindow, content)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class IssuesToolWindow(private val project: Project) : Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    private val model = IssuesModel(scope)
    private val editorManager = FileEditorManager.getInstance(project)
    private val properties = PropertiesComponent.getInstance(project)
    private val detailsFile = IssueDetailsFile(model, ::openOnGitHub, ::createBranch)
    private val selector = RepositorySelectorPanel(::chooseRepository, ::chooseAccount, ::viewIssues, ::signIn)
    private val list = IssuesPanel(model, scope, ::signIn, ::showDetails)
    private val cards = CardLayout()
    val panel = JPanel(cards).apply {
        add(selector.panel, SELECT_CARD)
        add(list.panel, ISSUES_CARD)
    }

    private var content: Content? = null
    private var openDetailsJob: Job? = null
    private var repositories = emptyList<Repository>()
    private var remoteNames = emptyMap<String, String>()
    /** Local Git roots with a remote of each repository, keyed by the lowercase full name. */
    private var gitRoots = emptyMap<String, List<GitRepository>>()
    private var accounts = emptyList<GithubAccount>()
    private var repository: Repository? = null
    private var account: GithubAccount? = null
    private var contextLoaded = false
    /** Whether the issue list is shown instead of the start screen. */
    private var viewing = false
    private var connectedTo: Pair<Repository, GithubAccount>? = null
    private val avatars = mutableMapOf<String, Icon?>()
    private var savedSettings: IssueListSettings? = null

    init {
        selector.showLoading()
        scope.launch {
            val remotes = project.service<GHHostedRepositoriesManager>().knownRepositoriesState
            combine(remotes, GHAccountsUtil.accountsState) { mappings, savedAccounts ->
                val names = mutableMapOf<String, String>()
                val roots = mutableMapOf<String, MutableSet<GitRepository>>()
                val candidates = mappings.mapNotNull { mapping ->
                    val remote = mapping.repository
                    if (!remote.serverPath.isGithubDotCom) return@mapNotNull null
                    val repo = Repository(remote.repositoryPath.owner, remote.repositoryPath.repository)
                    val key = repo.fullName.lowercase()
                    val remoteName = mapping.remote.remote.name
                    if (names[key] == null || remoteName == "origin") names[key] = remoteName
                    roots.getOrPut(key) { linkedSetOf() } += mapping.gitRepository
                    repo
                }
                RemoteContext(
                    repositoriesForPicker(candidates),
                    names.toMap(),
                    roots.mapValues { (_, found) -> found.sortedBy { it.root.path } },
                    savedAccounts.filter { it.server.isGithubDotCom }.sortedBy { it.name.lowercase() },
                )
            }.collect { (repos, names, roots, savedAccounts) ->
                repositories = repos
                remoteNames = names
                gitRoots = roots
                accounts = savedAccounts
                if (!contextLoaded) restoreSelection()
                contextLoaded = true
                repository = repositoryForSelection(repos, repository)
                account = account?.takeIf { it in accounts }
                    ?: GHAccountsUtil.getDefaultAccount(project)?.takeIf { it in accounts }
                            ?: accounts.singleOrNull()
                updateContext()
            }
        }
        scope.launch {
            model.state.collect { state ->
                list.render(state)
                if (state.connected) saveListSettings(state.settings)
                if (state.selected == null && state.editor == null) closeDetails()
                ActivityTracker.getInstance().inc()
            }
        }
    }

    fun attach(toolWindow: ToolWindow, content: Content) {
        this.content = content
        toolWindow.setTitleActions(listOf(NewIssueAction(), RefreshAction()))
        toolWindow.setAdditionalGearActions(DefaultActionGroup(ChangeRepositoryAction()))
        updateTitle()
    }

    /** Opens the list right away when the repository and account chosen last time are still available. */
    private fun restoreSelection() {
        val savedRepository = properties.getValue(REPOSITORY_KEY) ?: return
        val savedAccount = properties.getValue(ACCOUNT_KEY) ?: return
        val repo = repositories.firstOrNull { it.fullName.equals(savedRepository, ignoreCase = true) } ?: return
        val selected = accounts.firstOrNull { it.id == savedAccount } ?: return
        repository = repo
        account = selected
        viewing = true
    }

    private fun showDetails(focus: Boolean) {
        if (!focus && (editorManager.currentFile == detailsFile || openDetailsJob?.isActive == true)) return
        openDetailsJob?.cancel()
        openDetailsJob = scope.launch {
            val request = FileEditorOpenRequest.withFocus(focus).withReuseOpen(true)
            editorManager.openFile(detailsFile, request)
        }
    }

    private fun closeDetails() {
        openDetailsJob?.cancel()
        if (editorManager.isFileOpen(detailsFile)) editorManager.closeFile(detailsFile)
    }

    private fun chooseRepository(repo: Repository) {
        repository = repo
        updateContext()
    }

    private fun chooseAccount(selected: GithubAccount) {
        account = selected
        updateContext()
    }

    private fun viewIssues() {
        val repo = repository ?: return
        val selected = account ?: return
        properties.setValue(REPOSITORY_KEY, repo.fullName)
        properties.setValue(ACCOUNT_KEY, selected.id)
        viewing = true
        updateContext()
    }

    private fun changeRepository() {
        if (model.state.value.writing) return
        viewing = false
        properties.unsetValue(REPOSITORY_KEY)
        properties.unsetValue(ACCOUNT_KEY)
        updateContext()
    }

    private fun updateContext() {
        val repo = repository
        val selected = account
        if (repo == null || selected == null) viewing = false
        val connection = if (viewing && repo != null && selected != null) repo to selected else null
        if (connectedTo != connection) {
            connectedTo = connection
            model.connect(
                connection?.let { (repo, account) -> GithubIssuesGateway(repo, account) },
                selected?.name,
                readListSettings(properties::getValue),
            )
        }
        if (contextLoaded) {
            selector.show(repositories, remoteNames, repo, accounts, selected, ::avatar)
        }
        cards.show(panel, if (connection != null) ISSUES_CARD else SELECT_CARD)
        updateTitle()
        ActivityTracker.getInstance().inc()
    }

    /** Keeps status, order, label and assignee of the list per project; the search text is not saved. */
    private fun saveListSettings(settings: IssueListSettings) {
        if (settings == savedSettings) return
        savedSettings = settings
        writeListSettings(settings) { key, value ->
            if (value == null) properties.unsetValue(key) else properties.setValue(key, value)
        }
    }

    private fun updateTitle() {
        content?.displayName = connectedTo?.first?.name.orEmpty()
    }

    /** Cached avatar of [account]; starts loading it on first use and returns `null` until it is there. */
    private fun avatar(account: GithubAccount): Icon? {
        if (account.id in avatars) return avatars[account.id]
        avatars[account.id] = null
        scope.launch {
            val icon = try {
                withContext(Dispatchers.IO) {
                    val executor = GithubApiRequestExecutor.Factory.getInstance().create(account)
                    val user = executor.execute(GithubApiRequests.CurrentUser.get(account.server))
                    val image = user.avatarUrl?.let { executor.execute(GithubApiRequests.CurrentUser.getAvatar(it)) }
                    image?.let {
                        val size = JBUI.scale(AVATAR_SIZE)
                        IconUtil.createImageIcon(ImageUtil.scaleImage(ImageUtil.createCircleImage(it), size, size))
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Without a token or connection the initials stay in place.
                null
            }
            avatars[account.id] = icon
            if (icon != null) selector.refreshAvatar()
        }
        return null
    }

    private fun signIn() {
        val selected = account
        val previous = GHAccountsUtil.accounts
        if (selected != null && model.state.value.needsSignIn && selected in previous) {
            if (GHAccountsUtil.reLogIn(selected, project, panel)) {
                avatars.remove(selected.id)
                model.refresh()
            }
        } else {
            val newAccount = GHAccountsUtil.logIn(
                server = GithubServerPath.DEFAULT_SERVER,
                project = project,
                parentComponent = panel
            )
            if (newAccount != null) {
                accounts = GHAccountsUtil.accounts.filter { it.server.isGithubDotCom }.sortedBy { it.name.lowercase() }
                account = newAccount
                avatars.remove(newAccount.id)
                updateContext()
            }
        }
    }

    private fun openOnGitHub(number: Long) {
        val repo = repository ?: return
        BrowserUtil.browse(URI("https", "github.com", "/${repo.fullName}/issues/$number", null).toASCIIString())
    }

    /** Creates a local branch for [issue] from the current HEAD and checks it out; never pushes. */
    private fun createBranch(issue: Issue) {
        val repo = connectedTo?.first ?: return
        val roots = gitRoots[repo.fullName.lowercase()].orEmpty()
        if (roots.isEmpty()) {
            Messages.showErrorDialog(project, message("branch.noRoot", repo.fullName), message("action.branch"))
            return
        }
        val dialog = CreateIssueBranchDialog(project, issue, roots)
        if (!dialog.showAndGet()) return
        // Like "git checkout -b": starts at HEAD, keeps local changes and reports failures as IDE notifications.
        GitBrancher.getInstance(project).checkoutNewBranch(dialog.branchName, listOf(dialog.root))
    }

    override fun dispose() {
        scope.cancel()
        if (!project.isDisposed) closeDetails()
    }

    private abstract inner class ToolWindowAction(text: String, icon: Icon? = null) :
        DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class NewIssueAction : ToolWindowAction(message("action.new"), AllIcons.General.Add) {
        override fun update(e: AnActionEvent) {
            val state = model.state.value
            val editor = state.editor
            e.presentation.isVisible = connectedTo != null
            e.presentation.text = message(if (editor == null) "action.new" else "action.openDraft")
            e.presentation.isEnabled = editor != null || (!state.writing && !state.needsSignIn)
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (model.state.value.editor == null) model.startCreate()
            if (model.state.value.editor != null) showDetails(true)
        }
    }

    private inner class RefreshAction : ToolWindowAction(message("action.refresh"), AllIcons.Actions.Refresh) {
        override fun update(e: AnActionEvent) {
            e.presentation.isVisible = connectedTo != null
            e.presentation.isEnabled = !model.state.value.writing
        }

        override fun actionPerformed(e: AnActionEvent) = model.refresh()
    }

    private inner class ChangeRepositoryAction : ToolWindowAction(message("action.changeRepository")) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = connectedTo != null
            e.presentation.isEnabled = connectedTo != null && !model.state.value.writing
        }

        override fun actionPerformed(e: AnActionEvent) = changeRepository()
    }

    private data class RemoteContext(
        val repositories: List<Repository>,
        val remoteNames: Map<String, String>,
        val gitRoots: Map<String, List<GitRepository>>,
        val accounts: List<GithubAccount>,
    )

    private companion object {
        const val SELECT_CARD = "select"
        const val ISSUES_CARD = "issues"
        const val REPOSITORY_KEY = "dev.issuetoolwindow.repository"
        const val ACCOUNT_KEY = "dev.issuetoolwindow.account"
        const val AVATAR_SIZE = 24
    }
}
