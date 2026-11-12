package dev.issuetoolwindow

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CommitIssueLookupTest : BasePlatformTestCase() {
    fun testSelectingAnIssueReplacesTheTitlePrefix() {
        myFixture.configureByText(PlainTextFileType.INSTANCE, "Refs #parser<caret>\nDetails")
        val issue = Issue(42, "Fix parser", true, null, "", emptyList(), emptyList())
        val lookup = LookupManager.getInstance(project).showLookup(
            myFixture.editor, commitIssueLookupItems(listOf(issue), "#parser"), "#parser",
        )
        assertNotNull(lookup)
        assertEquals("#42", lookup?.currentItem?.lookupString)
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        myFixture.checkResult("Refs #42\nDetails")
    }
}
