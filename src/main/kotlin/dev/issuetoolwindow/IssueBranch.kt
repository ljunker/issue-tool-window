package dev.issuetoolwindow

import com.intellij.dvcs.repo.Repository as VcsRepository
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import dev.issuetoolwindow.IssuesBundle.message
import git4idea.repo.GitRepository
import git4idea.validators.GitRefNameValidator
import java.text.Normalizer
import javax.swing.JComponent

private const val MAX_SLUG_LENGTH = 50

/** Suggests a branch name like GitHub does: the issue number followed by the title as lowercase words. */
internal fun suggestedBranchName(number: Long, title: String): String {
    val transliterated = title.lowercase()
        .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
    val ascii = Normalizer.normalize(transliterated, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    val words = ascii.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    val slug = StringBuilder()
    for (word in words) {
        val next = if (slug.isEmpty()) word else "-$word"
        if (slug.length + next.length > MAX_SLUG_LENGTH) {
            if (slug.isEmpty()) slug.append(word.take(MAX_SLUG_LENGTH))
            break
        }
        slug.append(next)
    }
    return if (slug.isEmpty()) "issue-$number" else "$number-$slug"
}

/**
 * Returns the existing local branch that blocks creating [name]: the same name, or a name that is a path prefix of the
 * other one (`feature` and `feature/x` cannot exist side by side in Git).
 */
internal fun conflictingBranch(name: String, existing: Collection<String>, ignoreCase: Boolean): String? =
    existing.firstOrNull { branch ->
        branch.equals(name, ignoreCase) ||
            branch.startsWith("$name/", ignoreCase) ||
            name.startsWith("$branch/", ignoreCase)
    }

/** Names the unfinished Git operation that has to end before switching branches, or `null` if there is none. */
internal fun unfinishedOperation(state: VcsRepository.State): String? = when (state) {
    VcsRepository.State.NORMAL, VcsRepository.State.DETACHED -> null
    VcsRepository.State.MERGING -> "merge"
    VcsRepository.State.REBASING -> "rebase"
    VcsRepository.State.GRAFTING -> "cherry-pick"
    VcsRepository.State.REVERTING -> "revert"
}

/** Asks for the branch name and, when several local roots belong to the repository, for the root. */
internal class CreateIssueBranchDialog(
    project: Project,
    issue: Issue,
    private val roots: List<GitRepository>,
) : DialogWrapper(project) {
    private val nameField = JBTextField(suggestedBranchName(issue.number, issue.title), 40).apply {
        accessibleContext.accessibleName = message("branch.name")
    }
    private val rootBox = ComboBox(CollectionComboBoxModel(roots, roots.first())).apply {
        renderer = textListCellRenderer { it?.root?.presentableUrl }
    }

    val branchName: String get() = nameField.text.trim()
    val root: GitRepository get() = rootBox.item ?: roots.first()

    init {
        title = message("branch.title", issue.number.toString())
        setOKButtonText(message("branch.create"))
        init()
        initValidation()
    }

    override fun createCenterPanel(): JComponent = panel {
        if (roots.size > 1) {
            row(message("branch.root")) { cell(rootBox).align(AlignX.FILL) }
        }
        row(message("branch.name")) { cell(nameField).align(AlignX.FILL) }
        row { comment(message("branch.hint")) }
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? {
        val repository = root
        val name = branchName
        unfinishedOperation(repository.state)?.let { operation ->
            val target = if (roots.size > 1) rootBox else nameField
            return ValidationInfo(message("branch.blocked", operation), target)
        }
        if (name.isEmpty()) return ValidationInfo(message("branch.empty"), nameField)
        if (!GitRefNameValidator.getInstance().checkInput(name)) return ValidationInfo(message("branch.invalid"), nameField)
        val existing = repository.branches.localBranches.map { it.name }
        conflictingBranch(name, existing, ignoreCase = !SystemInfo.isFileSystemCaseSensitive)?.let { branch ->
            return ValidationInfo(
                if (branch.equals(name, ignoreCase = true)) message("branch.exists", branch)
                else message("branch.pathConflict", branch),
                nameField,
            )
        }
        return null
    }
}
