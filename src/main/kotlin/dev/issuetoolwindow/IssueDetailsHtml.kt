package dev.issuetoolwindow

import com.intellij.openapi.util.text.StringUtil.escapeXmlEntities
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.StyleSheetUtil
import dev.issuetoolwindow.IssuesBundle.message
import dev.issuetoolwindow.PullRequestActivity.Relation
import java.awt.Color
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import javax.swing.text.html.StyleSheet

internal data class IssueDetailsContent(
    val issue: Issue,
    val activity: List<IssueActivity>,
    val showEmptyActivity: Boolean,
) {
    fun toHtml(): String = buildString {
        val status = message("status.${issue.stateSuffix}")
        val statusClass = when {
            issue.open -> "issue-open"
            issue.closeReason == CloseReason.COMPLETED || issue.closeReason == null -> "issue-closed"
            else -> "issue-not-planned"
        }
        append("<h1 class='issue-title'>${escapeXmlEntities(issue.title)} ")
        append("<span class='issue-number'>#${issue.number}</span></h1>")
        append("<p class='issue-state'><span class='$statusClass'>&nbsp;&nbsp;${escapeXmlEntities(status)}&nbsp;&nbsp;</span></p>")
        append("<p class='issue-metadata'>")
        appendMetadata(message("detail.labels"), issue.labels, "issue-label")
        append(" &nbsp;·&nbsp; ")
        appendMetadata(message("detail.assignees"), issue.assignees, "issue-assignee")
        append("</p>")

        appendCard("issue-description", issue.author, issue.createdAt, issue.body)
        if (activity.isNotEmpty() || showEmptyActivity) {
            append("<h2 class='issue-activity'>${escapeXmlEntities(message("detail.activity"))}</h2>")
        }
        if (showEmptyActivity) {
            append("<p class='issue-muted'>${escapeXmlEntities(message("detail.noActivity"))}</p>")
        }
        for (entry in activity) {
            when (entry) {
                is Comment -> appendCard("issue-comment", entry.author, entry.createdAt, entry.body) {
                    appendCommentActions(entry)
                }
                is LabelActivity -> appendLabelEvent(entry)
                is PullRequestActivity -> appendPullRequestEvent(entry)
            }
        }
    }

    private fun StringBuilder.appendLabelEvent(entry: LabelActivity) {
        appendEventHeader(entry, "label-event")
        val action = message(if (entry.added) "activity.label.added" else "activity.label.removed")
        append(" ${escapeXmlEntities(action)} <span class='issue-label'")
        val hex = entry.color?.takeIf(labelColorPattern::matches)
        if (hex != null) {
            val textColor = if (ColorUtil.isDark(Color(hex.toInt(16)))) "#ffffff" else "#1f2328"
            append(" style='background-color: #$hex; color: $textColor'")
        }
        append(">&nbsp;${escapeXmlEntities(entry.name)}&nbsp;</span>")
        appendTimestamp(entry.createdAt)
        append("</p></div>")
    }

    private fun StringBuilder.appendPullRequestEvent(entry: PullRequestActivity) {
        appendEventHeader(entry, "link-event")
        val action = when (entry.relation) {
            Relation.REFERENCES -> message("activity.pr.referenced")
            Relation.CLOSES -> message("activity.pr.closes")
            Relation.LINKS -> message("activity.pr.linked")
            Relation.UNLINKS -> message("activity.pr.unlinked")
        }
        append(" ${escapeXmlEntities(action)}")
        appendTimestamp(entry.createdAt)
        append("</p><p class='issue-event-target'><icon src='pull-request-event'>&nbsp; ")
        val title = "${escapeXmlEntities(entry.title)} <span class='issue-number'>#${entry.number}</span>"
        val uri = IssueMarkdown.browserUri(entry.url)
        if (uri != null) append("<a href='${escapeXmlEntities(uri.toASCIIString())}'>$title</a>")
        else append(title)
        append("</p></div>")
    }

    private fun StringBuilder.appendEventHeader(entry: IssueActivity, icon: String) {
        append("<div class='issue-event'><p class='issue-event-summary'>")
        append("<span class='issue-event-icon'>&nbsp;<icon src='$icon'>&nbsp;</span> ")
        append("<strong>${escapeXmlEntities(entry.author ?: message("detail.unknown"))}</strong>")
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

    private fun StringBuilder.appendCard(
        cardClass: String,
        author: String?,
        createdAt: String?,
        body: String,
        appendActions: StringBuilder.() -> Unit = {},
    ) {
        append("<div class='$cardClass'><p class='issue-card-header'>")
        append("<strong>${escapeXmlEntities(author ?: message("detail.unknown"))}</strong>")
        appendTimestamp(createdAt)
        appendActions()
        append("</p><div class='issue-markdown'>")
        if (body.isBlank() && cardClass == "issue-description") {
            append("<p class='issue-muted'>${escapeXmlEntities(message("detail.noDescription"))}</p>")
        } else {
            append(IssueMarkdown.render(body))
        }
        append("</div></div>")
    }

    private fun StringBuilder.appendCommentActions(comment: Comment) {
        val links = listOfNotNull(
            CommentAction.EDIT.takeIf { comment.canEdit },
            CommentAction.DELETE.takeIf { comment.canDelete },
        ).map { action ->
            "<a href='${escapeXmlEntities(action.link(comment.id))}'>${escapeXmlEntities(message(action.messageKey))}</a>"
        }
        if (links.isNotEmpty()) {
            append(" <span class='issue-comment-actions'>&nbsp;&nbsp;${links.joinToString(" &nbsp;·&nbsp; ")}</span>")
        }
    }

    private fun StringBuilder.appendTimestamp(createdAt: String?) {
        formattedTimestamp(createdAt)?.let { time ->
            append(" <span class='issue-time'>· ${escapeXmlEntities(time)}</span>")
        }
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

    private companion object {
        val labelColorPattern = Regex("[0-9a-fA-F]{6}")
    }
}

/**
 * Actions in the header of a comment. Their links use a scheme of their own, which rendered Markdown cannot produce
 * because it only keeps web links.
 */
internal enum class CommentAction(val messageKey: String) {
    EDIT("action.editComment"),
    DELETE("action.deleteComment");

    fun link(commentId: String): String = "$SCHEME:${name.lowercase()}:${URLEncoder.encode(commentId, Charsets.UTF_8)}"

    companion object {
        private const val SCHEME = "issue-comment"

        /** The action and comment ID of a link created by [link], or `null` for any other link. */
        fun parse(link: String?): Pair<CommentAction, String>? {
            val parts = link?.split(':', limit = 3)?.takeIf { it.size == 3 && it[0] == SCHEME } ?: return null
            val action = entries.firstOrNull { it.name.lowercase() == parts[1] } ?: return null
            val id = runCatching { URLDecoder.decode(parts[2], Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: return null
            return action to id
        }
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
    val notPlanned = color("#59636e", "#656c76")

    return StyleSheetUtil.loadStyleSheet(
        """
        body { color: $foreground; margin: 8px 20px 20px 20px; }
        h1.issue-title { color: $foreground; font-size: 200%; font-weight: normal; margin: 0 0 12px 0; }
        span.issue-number { color: $muted; }
        p.issue-state { margin: 0 0 12px 0; }
        span.issue-open, span.issue-closed, span.issue-not-planned { color: #ffffff; font-weight: bold; border-radius: 10px; }
        span.issue-open { background-color: $open; }
        span.issue-closed { background-color: $closed; }
        span.issue-not-planned { background-color: $notPlanned; }
        p.issue-metadata { margin: 0 0 6px 0; }
        .issue-muted, span.issue-time { color: $muted; }
        span.issue-label { color: $link; background-color: $labelBackground; border-radius: 6px; }
        span.issue-assignee { background-color: $surface; border-radius: 6px; }
        div.issue-description, div.issue-comment { border: 1px solid $border; border-radius: 6px; margin: 0 0 12px 0; }
        div.issue-description { border-color: $descriptionBorder; margin-top: 14px; }
        p.issue-card-header { color: $foreground; background-color: $surface; border-bottom: 1px solid $border; margin: 0; padding: 8px 14px; }
        div.issue-description p.issue-card-header { background-color: $descriptionHeader; border-bottom-color: $descriptionBorder; }
        span.issue-comment-actions a { color: $link; }
        div.issue-markdown { padding: 12px 14px; }
        div.issue-markdown h1, div.issue-markdown h2 { border-bottom: 1px solid $border; padding-bottom: 5px; }
        div.issue-markdown h1 { font-size: 160%; }
        div.issue-markdown h2 { font-size: 140%; }
        div.issue-markdown ul, div.issue-markdown ol { margin: 6px 0 10px 0; padding-left: 24px; }
        div.issue-markdown li { margin: 2px 0; }
        div.issue-markdown pre { color: $foreground; background-color: $surface; border-radius: 6px; padding: 10px; }
        div.issue-markdown a { color: $link; }
        h2.issue-activity { color: $foreground; font-size: 120%; margin: 16px 0 8px 0; }
        div.issue-event { border-left: 2px solid $border; margin: 0 0 0 12px; padding: 4px 0 6px 12px; }
        p.issue-event-summary { color: $muted; margin: 0; }
        p.issue-event-summary strong { color: $foreground; }
        span.issue-event-icon { background-color: $surface; border-radius: 10px; }
        p.issue-event-target { margin: 0 0 0 26px; }
        p.issue-event-target a { color: $link; }
        """.trimIndent(),
    )
}
