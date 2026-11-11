package dev.issuetoolwindow

import com.intellij.openapi.util.text.StringUtil.escapeXmlEntities
import com.intellij.util.ui.StyleSheetUtil
import dev.issuetoolwindow.IssuesBundle.message
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import javax.swing.text.html.StyleSheet

internal data class IssueDetailsContent(
    val issue: Issue,
    val comments: List<Comment>,
    val showEmptyComments: Boolean,
) {
    fun toHtml(): String = buildString {
        val status = message(if (issue.open) "filter.open" else "filter.closed")
        val statusClass = if (issue.open) "issue-open" else "issue-closed"
        append("<h1 class='issue-title'>${escapeXmlEntities(issue.title)} ")
        append("<span class='issue-number'>#${issue.number}</span></h1>")
        append("<p class='issue-state'><span class='$statusClass'>&nbsp;&nbsp;${escapeXmlEntities(status)}&nbsp;&nbsp;</span></p>")
        append("<p class='issue-metadata'>")
        appendMetadata(message("detail.labels"), issue.labels, "issue-label")
        append(" &nbsp;·&nbsp; ")
        appendMetadata(message("detail.assignees"), issue.assignees, "issue-assignee")
        append("</p>")

        appendCard("issue-description", issue.author, issue.createdAt, issue.body)
        if (comments.isNotEmpty() || showEmptyComments) {
            append("<h2 class='issue-comments'>${escapeXmlEntities(message("detail.comments"))}</h2>")
        }
        if (showEmptyComments) {
            append("<p class='issue-muted'>${escapeXmlEntities(message("detail.noComments"))}</p>")
        }
        for (comment in comments) {
            appendCard("issue-comment", comment.author, comment.createdAt, comment.body)
        }
    }

    private fun StringBuilder.appendMetadata(heading: String, values: List<String>, valueClass: String) {
        append("<strong>${escapeXmlEntities(heading)}</strong> ")
        if (values.isEmpty()) {
            append("<span class='issue-muted'>${escapeXmlEntities(message("detail.none"))}</span>")
        } else {
            values.joinTo(this, separator = " ") { value ->
                "<span class='$valueClass'>&nbsp;${escapeXmlEntities(value)}&nbsp;</span>"
            }
        }
    }

    private fun StringBuilder.appendCard(cardClass: String, author: String?, createdAt: String?, body: String) {
        append("<div class='$cardClass'><p class='issue-card-header'>")
        append("<strong>${escapeXmlEntities(author ?: message("detail.unknown"))}</strong>")
        formattedTimestamp(createdAt)?.let { time ->
            append(" <span class='issue-time'>· ${escapeXmlEntities(time)}</span>")
        }
        append("</p><div class='issue-markdown'>")
        if (body.isBlank() && cardClass == "issue-description") {
            append("<p class='issue-muted'>${escapeXmlEntities(message("detail.noDescription"))}</p>")
        } else {
            append(IssueMarkdown.render(body))
        }
        append("</div></div>")
    }

    private fun formattedTimestamp(value: String?): String? {
        if (value == null) return null
        val instant = try {
            Instant.parse(value)
        } catch (_: DateTimeParseException) {
            return null
        }
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault()).format(instant)
    }
}

internal fun issueDetailsStyleSheet(light: Boolean): StyleSheet {
    fun color(onLight: String, onDark: String) = if (light) onLight else onDark

    val foreground = color("#1f2328", "#e6edf3")
    val muted = color("#59636e", "#9198a1")
    val border = color("#d1d9e0", "#3d444d")
    val surface = color("#f6f8fa", "#151b23")
    val descriptionHeader = color("#ddf4ff", "#12283e")
    val descriptionBorder = color("#b6ddf9", "#2f567d")
    val link = color("#0969da", "#58a6ff")
    val labelBackground = color("#ddf4ff", "#132c48")
    val open = color("#1a7f37", "#23773a")
    val closed = color("#8250df", "#7546c8")

    return StyleSheetUtil.loadStyleSheet(
        """
        body { color: $foreground; margin: 8px 20px 20px 20px; }
        h1.issue-title { color: $foreground; font-size: 200%; font-weight: normal; margin: 0 0 12px 0; }
        span.issue-number { color: $muted; }
        p.issue-state { margin: 0 0 12px 0; }
        span.issue-open, span.issue-closed { color: #ffffff; font-weight: bold; border-radius: 10px; }
        span.issue-open { background-color: $open; }
        span.issue-closed { background-color: $closed; }
        p.issue-metadata { margin: 0 0 6px 0; }
        .issue-muted, span.issue-time { color: $muted; }
        span.issue-label { color: $link; background-color: $labelBackground; border-radius: 6px; }
        span.issue-assignee { background-color: $surface; border-radius: 6px; }
        div.issue-description, div.issue-comment { border: 1px solid $border; border-radius: 6px; margin: 0 0 12px 0; }
        div.issue-description { border-color: $descriptionBorder; margin-top: 14px; }
        p.issue-card-header { color: $foreground; background-color: $surface; border-bottom: 1px solid $border; margin: 0; padding: 8px 14px; }
        div.issue-description p.issue-card-header { background-color: $descriptionHeader; border-bottom-color: $descriptionBorder; }
        div.issue-markdown { padding: 12px 14px; }
        div.issue-markdown h1, div.issue-markdown h2 { border-bottom: 1px solid $border; padding-bottom: 5px; }
        div.issue-markdown h1 { font-size: 160%; }
        div.issue-markdown h2 { font-size: 140%; }
        div.issue-markdown ul, div.issue-markdown ol { margin: 6px 0 10px 0; padding-left: 24px; }
        div.issue-markdown li { margin: 2px 0; }
        div.issue-markdown pre { color: $foreground; background-color: $surface; border-radius: 6px; padding: 10px; }
        div.issue-markdown a { color: $link; }
        h2.issue-comments { color: $foreground; font-size: 120%; margin: 16px 0 8px 0; }
        """.trimIndent(),
    )
}
