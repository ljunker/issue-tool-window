package dev.issuetoolwindow

private const val FILTER_KEY = "dev.issuetoolwindow.filter"
private const val SORT_KEY = "dev.issuetoolwindow.sort"
private const val LABEL_KEY = "dev.issuetoolwindow.label"
private const val ASSIGNEE_KEY = "dev.issuetoolwindow.assignee"
private const val MILESTONE_KEY = "dev.issuetoolwindow.milestone"
private const val MILESTONE_TITLE_KEY = "dev.issuetoolwindow.milestone.title"
private const val NO_MILESTONE = "none"

/** Reads the saved list settings; missing or unknown values fall back to the defaults. */
internal fun readListSettings(value: (key: String) -> String?): IssueListSettings {
    val defaults = IssueListSettings()
    return IssueListSettings(
        filter = IssueFilter.entries.firstOrNull { it.name == value(FILTER_KEY) } ?: defaults.filter,
        sort = IssueSort.entries.firstOrNull { it.name == value(SORT_KEY) } ?: defaults.sort,
        criteria = IssueCriteria(
            label = value(LABEL_KEY)?.takeIf(String::isNotBlank),
            assignee = value(ASSIGNEE_KEY)?.takeIf(String::isNotBlank),
            milestone = readMilestoneFilter(value(MILESTONE_KEY), value(MILESTONE_TITLE_KEY)),
        ),
    )
}

/** `none` stands for "no milestone"; a milestone needs its number for the list and its title for the search. */
private fun readMilestoneFilter(number: String?, title: String?): MilestoneFilter? {
    if (number == NO_MILESTONE) return MilestoneFilter.None
    val parsed = number?.toLongOrNull()?.takeIf { it > 0 } ?: return null
    return title?.takeIf(String::isNotBlank)?.let { MilestoneFilter.Of(Milestone(parsed, it)) }
}

/** Saves [settings]; `null` removes a key, so default values leave nothing behind. */
internal fun writeListSettings(settings: IssueListSettings, store: (key: String, value: String?) -> Unit) {
    val defaults = IssueListSettings()
    store(FILTER_KEY, settings.filter.takeIf { it != defaults.filter }?.name)
    store(SORT_KEY, settings.sort.takeIf { it != defaults.sort }?.name)
    store(LABEL_KEY, settings.criteria.label)
    store(ASSIGNEE_KEY, settings.criteria.assignee)
    val milestone = settings.criteria.milestone
    store(
        MILESTONE_KEY,
        when (milestone) {
            null -> null
            MilestoneFilter.None -> NO_MILESTONE
            is MilestoneFilter.Of -> milestone.milestone.number.toString()
        },
    )
    store(MILESTONE_TITLE_KEY, (milestone as? MilestoneFilter.Of)?.milestone?.title)
}
