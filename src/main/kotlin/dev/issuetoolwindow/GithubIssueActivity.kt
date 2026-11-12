package dev.issuetoolwindow

import dev.issuetoolwindow.IssuesBundle.message
import dev.issuetoolwindow.PullRequestActivity.Relation
import org.jetbrains.plugins.github.api.GithubApiRequest

// Match the field names used by the bundled client's REST JSON mapper.
private const val commentFields =
    "id actor: author { login } created_at: createdAt body " +
        "viewer_can_update: viewerCanUpdate viewer_can_delete: viewerCanDelete"

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
              ... on IssueComment { $commentFields }
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

internal data class ActivityError(val message: String?, val type: String? = null)
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
    val viewerCanUpdate: Boolean? = null,
    val viewerCanDelete: Boolean? = null,
) {
    fun toActivity(): IssueActivity? {
        val id = id ?: return null
        val author = actor?.login
        return when (kind) {
            "IssueComment" -> Comment(
                id, author, body.orEmpty(), createdAt, viewerCanUpdate == true, viewerCanDelete == true,
            )
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

private val updateCommentMutation = """
    mutation UpdateIssueComment(${'$'}id: ID!, ${'$'}body: String!) {
      update_issue_comment: updateIssueComment(input: { id: ${'$'}id, body: ${'$'}body }) {
        issue_comment: issueComment { kind: __typename $commentFields }
      }
    }
""".trimIndent()

private val deleteCommentMutation = """
    mutation DeleteIssueComment(${'$'}id: ID!) {
      delete_issue_comment: deleteIssueComment(input: { id: ${'$'}id }) { client_mutation_id: clientMutationId }
    }
""".trimIndent()

internal fun updateCommentRequest(id: String, body: String) = GithubApiRequest.Post.Json(
    "https://api.github.com/graphql",
    mapOf("query" to updateCommentMutation, "variables" to mapOf("id" to id, "body" to body)),
    UpdateCommentResponse::class.java,
)

internal fun deleteCommentRequest(id: String) = GithubApiRequest.Post.Json(
    "https://api.github.com/graphql",
    mapOf("query" to deleteCommentMutation, "variables" to mapOf("id" to id)),
    DeleteCommentResponse::class.java,
)

internal data class UpdateCommentResponse(val data: UpdateCommentData?, val errors: List<ActivityError>?) {
    fun toComment(): Comment = data?.updateIssueComment?.issueComment?.toActivity() as? Comment
        ?: throw commentFailure(errors)
}

internal data class UpdateCommentData(val updateIssueComment: UpdatedComment?)
internal data class UpdatedComment(val issueComment: ActivityNode?)

internal data class DeleteCommentResponse(val data: DeleteCommentData?, val errors: List<ActivityError>?) {
    fun confirm() {
        if (data?.deleteIssueComment == null || !errors.isNullOrEmpty()) throw commentFailure(errors)
    }
}

internal data class DeleteCommentData(val deleteIssueComment: Map<String, Any?>?)

/** GitHub reports a deleted comment as `NOT_FOUND` and missing permissions as `FORBIDDEN`. */
private fun commentFailure(errors: List<ActivityError>?): IssuesFailure {
    val error = errors?.firstOrNull()
    return when (error?.type) {
        "NOT_FOUND" -> IssuesFailure(message("error.commentMissing"), missing = true)
        "FORBIDDEN" -> IssuesFailure(message("error.commentForbidden"))
        else -> IssuesFailure(message("error.comment", error?.message.orEmpty()))
    }
}
