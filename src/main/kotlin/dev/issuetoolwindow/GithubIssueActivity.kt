package dev.issuetoolwindow

import dev.issuetoolwindow.IssuesBundle.message
import dev.issuetoolwindow.PullRequestActivity.Relation
import org.jetbrains.plugins.github.api.GithubApiRequest

// Match the field names used by the bundled client's REST JSON mapper.
private val activityQuery = """
    query IssueActivity(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!, ${'$'}cursor: String) {
      repository(owner: ${'$'}owner, name: ${'$'}name) {
        issue(number: ${'$'}number) {
          timeline: timelineItems(first: 30, after: ${'$'}cursor, itemTypes: [
            ISSUE_COMMENT, LABELED_EVENT, UNLABELED_EVENT, CROSS_REFERENCED_EVENT, CONNECTED_EVENT, DISCONNECTED_EVENT
          ]) {
            page_info: pageInfo { has_next_page: hasNextPage end_cursor: endCursor }
            nodes {
              kind: __typename
              ... on IssueComment { id actor: author { login } created_at: createdAt body }
              ... on LabeledEvent { id actor { login } created_at: createdAt label { name color } }
              ... on UnlabeledEvent { id actor { login } created_at: createdAt label { name color } }
              ... on CrossReferencedEvent {
                id actor { login } created_at: createdAt will_close_target: willCloseTarget
                source { ... on PullRequest { number title url } }
              }
              ... on ConnectedEvent { id actor { login } created_at: createdAt source: subject { ... on PullRequest { number title url } } }
              ... on DisconnectedEvent { id actor { login } created_at: createdAt source: subject { ... on PullRequest { number title url } } }
            }
          }
        }
      }
    }
""".trimIndent()

internal fun issueActivityRequest(repository: Repository, number: Long, cursor: String?) = GithubApiRequest.Post.Json(
    "https://api.github.com/graphql",
    mapOf(
        "query" to activityQuery,
        "variables" to mapOf("owner" to repository.owner, "name" to repository.name, "number" to number, "cursor" to cursor),
    ),
    ActivityResponse::class.java,
)

internal data class ActivityResponse(val data: ActivityData?, val errors: List<ActivityError>?) {
    fun toPage(cursor: String?): Page<IssueActivity> {
        val timeline = data?.repository?.issue?.timeline ?: throw IssuesFailure(
            message("error.activity", errors?.firstOrNull()?.message.orEmpty()),
        )
        val next = if (timeline.pageInfo.hasNextPage) {
            timeline.pageInfo.endCursor?.takeIf { it.isNotBlank() && it != cursor }
                ?: throw IssuesFailure(message("error.pagination"))
        } else null
        return Page(timeline.nodes.filterNotNull().mapNotNull(ActivityNode::toActivity), next)
    }
}

internal data class ActivityError(val message: String?)
internal data class ActivityData(val repository: ActivityRepository?)
internal data class ActivityRepository(val issue: ActivityIssue?)
internal data class ActivityIssue(val timeline: ActivityTimeline?)
internal data class ActivityTimeline(val pageInfo: ActivityPageInfo, val nodes: List<ActivityNode?>)
internal data class ActivityPageInfo(val hasNextPage: Boolean, val endCursor: String?)
internal data class ActivityLabel(val name: String, val color: String?)
internal data class ActivityPullRequest(val number: Long?, val title: String?, val url: String?)

internal data class ActivityNode(
    val kind: String,
    val id: String?,
    val actor: RestUser?,
    val createdAt: String?,
    val body: String?,
    val label: ActivityLabel?,
    val source: ActivityPullRequest?,
    val willCloseTarget: Boolean?,
) {
    fun toActivity(): IssueActivity? {
        val id = id ?: return null
        val author = actor?.login
        return when (kind) {
            "IssueComment" -> Comment(id, author, body.orEmpty(), createdAt)
            "LabeledEvent", "UnlabeledEvent" -> label?.let {
                LabelActivity(id, author, createdAt, it.name, it.color, kind == "LabeledEvent")
            }
            "CrossReferencedEvent", "ConnectedEvent", "DisconnectedEvent" -> {
                val number = source?.number ?: return null
                val title = source.title ?: return null
                val url = source.url ?: return null
                val relation = when (kind) {
                    "ConnectedEvent" -> Relation.LINKS
                    "DisconnectedEvent" -> Relation.UNLINKS
                    else -> if (willCloseTarget == true) Relation.CLOSES else Relation.REFERENCES
                }
                PullRequestActivity(id, author, createdAt, number, title, url, relation)
            }
            else -> null
        }
    }
}
