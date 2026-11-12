package dev.issuetoolwindow

/** A Markdown issue template of the repository; [draft] holds what it fills in when creating an issue. */
internal data class IssueTemplate(val name: String, val about: String?, val draft: IssueDraft)

/** Directory of the repository whose Markdown files GitHub offers as issue templates. */
internal const val ISSUE_TEMPLATE_DIRECTORY = ".github/ISSUE_TEMPLATE"

/** Only Markdown files are templates; issue forms (`.yml`, `.yaml`) and `config.yml` are left out. */
internal fun isMarkdownTemplate(fileName: String): Boolean = fileName.endsWith(".md", ignoreCase = true)

/** Templates ordered by file name, as GitHub lists them. */
internal fun templatesForChooser(templates: Map<String, IssueTemplate>): List<IssueTemplate> =
    templates.entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key }).map { it.value }

/**
 * Reads the YAML front matter of a Markdown issue template: `name`, `about`, `title`, `labels` and `assignees`. Lists
 * may be written as `[a, b]`, as `- a` lines or comma-separated. A file without front matter is offered under its file
 * name with its whole content as description.
 */
internal fun parseIssueTemplate(fileName: String, content: String): IssueTemplate {
    val text = content.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    val lines = text.split('\n')
    val end = if (lines.firstOrNull()?.trimEnd() == "---") {
        lines.drop(1).indexOfFirst { it.trimEnd() == "---" }.takeIf { it >= 0 }?.plus(1)
    } else {
        null
    }
    val fallbackName = fileName.substringBeforeLast('.')
    if (end == null) return IssueTemplate(fallbackName, null, IssueDraft(body = text))

    val fields = frontMatter(lines.subList(1, end))
    val body = lines.drop(end + 1).joinToString("\n").trimStart('\n')
    return IssueTemplate(
        name = fields["name"]?.scalar()?.takeIf(String::isNotBlank) ?: fallbackName,
        about = fields["about"]?.scalar()?.takeIf(String::isNotBlank),
        draft = IssueDraft(
            title = fields["title"]?.scalar().orEmpty(),
            body = body,
            labels = fields["labels"]?.list().orEmpty(),
            assignees = fields["assignees"]?.list().orEmpty(),
        ),
    )
}

/** Trimmed, non-empty entries without duplicates (ignoring case), in their original order. */
internal fun cleanedNames(names: Iterable<String>): List<String> =
    names.map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase)

/** Splits comma-separated names as typed into the editor or written in front matter. */
internal fun splitNames(text: String): List<String> = cleanedNames(text.split(','))

/** A front matter value: the text after the key, or the entries of a following block list. */
private sealed interface FrontMatterValue {
    fun scalar(): String

    fun list(): List<String>

    data class Text(val raw: String) : FrontMatterValue {
        override fun scalar(): String = unquoted(raw)

        override fun list(): List<String> {
            val value = raw.trim()
            val entries = if (value.startsWith("[") && value.endsWith("]")) {
                splitOutsideQuotes(value.substring(1, value.length - 1))
            } else if (isQuoted(value)) {
                unquoted(value).split(',')
            } else {
                value.split(',')
            }
            return cleanedNames(entries.map(::unquoted))
        }
    }

    data class Items(val items: List<String>) : FrontMatterValue {
        override fun scalar(): String = items.joinToString(", ")

        override fun list(): List<String> = cleanedNames(items.map(::unquoted))
    }
}

private fun frontMatter(lines: List<String>): Map<String, FrontMatterValue> {
    val fields = linkedMapOf<String, FrontMatterValue>()
    var key: String? = null
    var items: MutableList<String>? = null
    fun finishList() {
        val listKey = key
        val listItems = items
        if (listKey != null && listItems != null) fields[listKey] = FrontMatterValue.Items(listItems)
        key = null
        items = null
    }
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
        val listItems = items
        if (listItems != null && (trimmed == "-" || trimmed.startsWith("- "))) {
            listItems += withoutComment(trimmed.removePrefix("-"))
            continue
        }
        finishList()
        // Only keys at the start of the line belong to the template; nested values are ignored.
        if (line.first().isWhitespace()) continue
        val colon = line.indexOf(':').takeIf { it > 0 } ?: continue
        val name = line.substring(0, colon).trim().lowercase()
        val value = withoutComment(line.substring(colon + 1))
        if (value.isEmpty()) {
            key = name
            items = mutableListOf()
        } else {
            fields[name] = FrontMatterValue.Text(value)
        }
    }
    finishList()
    return fields
}

/** Removes a YAML comment (` #…`) outside quotes and surrounding whitespace. */
private fun withoutComment(value: String): String {
    var quote: Char? = null
    for (index in value.indices) {
        val char = value[index]
        when {
            quote != null -> if (char == quote) quote = null
            char == '"' || char == '\'' -> quote = char
            char == '#' && (index == 0 || value[index - 1].isWhitespace()) -> return value.substring(0, index).trim()
        }
    }
    return value.trim()
}

private fun splitOutsideQuotes(value: String): List<String> {
    val parts = mutableListOf<String>()
    val part = StringBuilder()
    var quote: Char? = null
    for (char in value) {
        when {
            quote != null -> {
                if (char == quote) quote = null
                part.append(char)
            }
            char == '"' || char == '\'' -> {
                quote = char
                part.append(char)
            }
            char == ',' -> {
                parts += part.toString()
                part.clear()
            }
            else -> part.append(char)
        }
    }
    parts += part.toString()
    return parts
}

private fun isQuoted(value: String): Boolean =
    value.length >= 2 && (value.first() == '"' || value.first() == '\'') && value.last() == value.first()

private fun unquoted(raw: String): String {
    val value = raw.trim()
    if (!isQuoted(value)) return value
    val inner = value.substring(1, value.length - 1)
    return if (value.first() == '\'') inner.replace("''", "'") else inner.replace("\\\"", "\"").replace("\\\\", "\\")
}
