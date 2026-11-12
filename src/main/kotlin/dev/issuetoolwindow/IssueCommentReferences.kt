package dev.issuetoolwindow

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.impl.FakePsiElement
import com.intellij.util.ProcessingContext
import dev.issuetoolwindow.IssuesBundle.message
import git4idea.repo.GitRepositoryManager
import org.jetbrains.plugins.github.GithubIcons
import javax.swing.Icon

/** Adds a reference to every `#42` in comments of files whose Git root has a GitHub.com remote. */
class IssueCommentReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(PsiComment::class.java), IssueCommentReferenceProvider())
    }
}

private class IssueCommentReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val text = element.text
        if ('#' !in text) return PsiReference.EMPTY_ARRAY
        val references = issueReferences(text)
        if (references.isEmpty()) return PsiReference.EMPTY_ARRAY
        val repository = repositoryOf(element) ?: return PsiReference.EMPTY_ARRAY
        return references
            .map { IssueCommentReference(element, TextRange(it.range.first, it.range.last + 1), repository, it.number) }
            .toTypedArray()
    }

    private fun repositoryOf(element: PsiElement): Repository? {
        val file = element.containingFile?.originalFile?.virtualFile ?: return null
        val project = element.project
        val git = GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file) ?: return null
        return referencedRepository(project, git)
    }
}

/** `#42` in a comment; navigating to it opens issue [number] of [repository] in the issue tab. */
internal class IssueCommentReference(
    element: PsiElement,
    range: TextRange,
    val repository: Repository,
    val number: Long,
) : PsiReferenceBase<PsiElement>(element, range, true) {
    override fun resolve(): PsiElement = IssueTarget(this)
}

/** The issue a reference points to. It has no source; navigating opens the issue tab. */
private class IssueTarget(private val reference: IssueCommentReference) : FakePsiElement() {
    override fun getParent(): PsiElement = reference.element
    override fun getName() = "#${reference.number}"
    override fun getPresentableText() = "#${reference.number}"
    override fun getLocationString() = reference.repository.fullName
    override fun getIcon(open: Boolean): Icon = GithubIcons.IssueOpened
    override fun getTextRange(): TextRange = reference.absoluteRange
    override fun canNavigate() = true
    override fun navigate(requestFocus: Boolean) =
        reference.element.project.service<IssueNavigator>().open(reference.repository, reference.number)
}

/** Shows issue references in comments as links, like the IDE does for URLs. */
class IssueCommentReferenceAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is PsiComment || holder.isBatchMode || '#' !in element.text) return
        for (reference in element.references) {
            if (reference !is IssueCommentReference) continue
            holder.newAnnotation(HighlightSeverity.INFORMATION, tooltip(reference))
                .range(reference.absoluteRange)
                .textAttributes(CodeInsightColors.INACTIVE_HYPERLINK_ATTRIBUTES)
                .create()
        }
    }

    private fun tooltip(reference: IssueCommentReference): String {
        val text = message("issueReference.tooltip", reference.number.toString(), reference.repository.fullName)
        val shortcuts = KeymapUtil.getShortcutsText(
            KeymapUtil.getActiveKeymapShortcuts(IdeActions.ACTION_GOTO_DECLARATION).shortcuts,
        )
        return if (shortcuts.isEmpty()) text else "$text ($shortcuts)"
    }
}
