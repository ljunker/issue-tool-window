package dev.issuetoolwindow

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.commonmark.renderer.html.HtmlRenderer
import java.net.URI
import java.net.URISyntaxException

internal object IssueMarkdown {
    private val parser = Parser.builder()
        .extensions(listOf(AutolinkExtension.create(), TaskListItemsExtension.create()))
        .maxOpenBlockParsers(64)
        .maxInlineNesting(64)
        .build()

    private val renderer = HtmlRenderer.builder()
        .escapeHtml(true)
        .softbreak("<br>")
        .nodeRendererFactory(::IssueMarkdownNodes)
        .build()

    fun render(markdown: String): String = renderer.render(parser.parse(markdown))

    fun browserUri(destination: String): URI? = try {
        URI(destination).takeIf { uri ->
            uri.host != null && (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true))
        }
    } catch (_: URISyntaxException) {
        null
    }
}

private class IssueMarkdownNodes(private val context: HtmlNodeRendererContext) : NodeRenderer {
    override fun getNodeTypes(): Set<Class<out Node>> =
        setOf(Image::class.java, Link::class.java, TaskListItemMarker::class.java)

    override fun render(node: Node) {
        when (node) {
            is Image -> renderChildren(node)
            is Link -> {
                val uri = IssueMarkdown.browserUri(node.destination)
                if (uri == null) {
                    renderChildren(node)
                    return
                }
                val attributes = buildMap {
                    put("href", uri.toASCIIString())
                    node.title?.let { put("title", it) }
                }
                context.writer.tag("a", attributes)
                renderChildren(node)
                context.writer.tag("/a")
            }
            is TaskListItemMarker -> context.writer.text(if (node.isChecked) "☑ " else "☐ ")
        }
    }

    private fun renderChildren(node: Node) {
        var child = node.firstChild
        while (child != null) {
            context.render(child)
            child = child.next
        }
    }
}
