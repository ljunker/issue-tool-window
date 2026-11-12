package dev.issuetoolwindow

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.jetbrains.plugins.github.util.GHGitRepositoryMapping
import org.jetbrains.plugins.github.util.GHHostedRepositoriesManager

/** An issue reference like `#42` in a text: the [range] covers `#` and the number. */
internal data class IssueReference(val range: IntRange, val number: Long)

private val issueReference = Regex("""(?<![\p{L}\p{N}_&])#([1-9][0-9]*)(?![\p{L}\p{N}_])""")

/**
 * The issue references in [text] in their order. A reference is `#` followed by a number without leading zero; it must
 * not be glued to a word on either side (`a#1`, `#12abc`) and must not be an HTML character reference (`&#123;`).
 */
internal fun issueReferences(text: CharSequence): List<IssueReference> =
    issueReference.findAll(text).mapNotNull { match ->
        match.groupValues[1].toLongOrNull()?.let { IssueReference(match.range, it) }
    }.toList()

/** The issue numbers referenced in [text], each once, in the order of their first appearance. */
internal fun referencedIssueNumbers(text: CharSequence): List<Long> = issueReferences(text).map { it.number }.distinct()

/** The GitHub.com remotes of [git] among the repository [mappings] the GitHub plugin knows. */
internal fun githubRemotes(mappings: Set<GHGitRepositoryMapping>, git: GitRepository): List<RemoteRepository> =
    mappings
        .filter { it.gitRepository == git && it.repository.serverPath.isGithubDotCom }
        .map {
            val path = it.repository.repositoryPath
            RemoteRepository(Repository(path.owner, path.repository), it.remote.remote.name)
        }

/**
 * The GitHub.com repository whose issues `#42` in files and commits of [git] refers to, see [issueRepository]; `null`
 * without a GitHub.com remote.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun referencedRepository(project: Project, git: GitRepository): Repository? = issueRepository(
    githubRemotes(project.service<GHHostedRepositoriesManager>().knownRepositoriesState.value, git),
    project.service<IssueNavigator>().connected.value?.first,
)
