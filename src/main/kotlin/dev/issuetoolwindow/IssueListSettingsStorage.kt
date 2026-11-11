package dev.issuetoolwindow

private const val FILTER_KEY = "dev.issuetoolwindow.filter"
private const val SORT_KEY = "dev.issuetoolwindow.sort"
private const val LABEL_KEY = "dev.issuetoolwindow.label"
private const val ASSIGNEE_KEY = "dev.issuetoolwindow.assignee"

/** Reads the saved list settings; missing or unknown values fall back to the defaults. */
internal fun readListSettings(value: (key: String) -> String?): IssueListSettings {
    val defaults = IssueListSettings()
    return IssueListSettings(
        filter = IssueFilter.entries.firstOrNull { it.name == value(FILTER_KEY) } ?: defaults.filter,
        sort = IssueSort.entries.firstOrNull { it.name == value(SORT_KEY) } ?: defaults.sort,
        criteria = IssueCriteria(
            label = value(LABEL_KEY)?.takeIf(String::isNotBlank),
            assignee = value(ASSIGNEE_KEY)?.takeIf(String::isNotBlank),
        ),
    )
}

/** Saves [settings]; `null` removes a key, so default values leave nothing behind. */
internal fun writeListSettings(settings: IssueListSettings, store: (key: String, value: String?) -> Unit) {
    val defaults = IssueListSettings()
    store(FILTER_KEY, settings.filter.takeIf { it != defaults.filter }?.name)
    store(SORT_KEY, settings.sort.takeIf { it != defaults.sort }?.name)
    store(LABEL_KEY, settings.criteria.label)
    store(ASSIGNEE_KEY, settings.criteria.assignee)
}
