package ru.wbparser.domain.model

/**
 * Preset sort order configuration.
 */
data class Preset(
    val id: Long,
    val name: String,
    val sortField: String,
    val sortDirection: SortDirection = SortDirection.ASC,
    val flags: Set<PresetFlag> = emptySet(),
)

enum class SortDirection {
    ASC, DESC
}

enum class PresetFlag {
    TopDeals,
    Popular,
    New,
    Recommendation
}

fun Preset.toWbSortParam(): String {
    val direction = if (sortDirection == SortDirection.DESC) "desc" else "asc"
    return "${sortField}=$direction"
}
