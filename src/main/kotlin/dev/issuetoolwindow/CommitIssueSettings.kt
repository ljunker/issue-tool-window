package dev.issuetoolwindow

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import dev.issuetoolwindow.IssuesBundle.message

private const val PREFILL_KEY = "dev.issuetoolwindow.prefillCommitIssue"

internal fun commitIssuePrefillEnabled(project: Project): Boolean =
    PropertiesComponent.getInstance(project).getBoolean(PREFILL_KEY, true)

class CommitIssueConfigurable(private val project: Project) : BoundConfigurable(message("commitIssue.settings")) {
    override fun createPanel() = panel {
        row {
            checkBox(message("commitIssue.prefill")).bindSelected(
                getter = { commitIssuePrefillEnabled(project) },
                setter = { PropertiesComponent.getInstance(project).setValue(PREFILL_KEY, it, true) },
            )
        }
    }
}
