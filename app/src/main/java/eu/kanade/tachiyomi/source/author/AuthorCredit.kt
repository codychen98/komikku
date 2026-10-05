package eu.kanade.tachiyomi.source.author

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage

private val whitespace = Regex("\\s+")
private val authorWord = Regex("(?i)(?<![a-z])authors?(?![a-z])")
private val artistWord = Regex("(?i)(?<![a-z])artists?(?![a-z])")

/**
 * Splits a stored credit line into people.
 * Several people are joined with ", " when the entry is saved.
 */
fun authorCreditNames(credit: String): List<String> {
    return credit.split(',')
        .map { it.trim().replace(whitespace, " ") }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
}

/**
 * Text filter a source uses for a person credit, such as MangaFire's "Author / Artist".
 * "Authorization" and other words that merely contain those letters are skipped.
 */
fun findCreditFilter(filters: FilterList, role: CatalogueCreditRole): Filter.Text? {
    val texts = collectTextFilters(filters)
    val authors = texts.filter { authorWord.containsMatchIn(it.name) }
    val artists = texts.filter { artistWord.containsMatchIn(it.name) }
    return when (role) {
        CatalogueCreditRole.Author ->
            authors.firstOrNull { !artistWord.containsMatchIn(it.name) }
                ?: authors.firstOrNull()
        CatalogueCreditRole.Artist ->
            artists.firstOrNull { !authorWord.containsMatchIn(it.name) }
                ?: artists.firstOrNull()
                ?: authors.firstOrNull()
    }
}

fun mergeAuthorPages(pages: List<MangasPage>): MangasPage {
    return MangasPage(
        mangas = pages.flatMap { it.mangas }.distinctBy { it.url },
        hasNextPage = pages.any { it.hasNextPage },
    )
}

private fun collectTextFilters(filters: List<Filter<*>>): List<Filter.Text> {
    return filters.flatMap { filter ->
        when (filter) {
            is Filter.Text -> listOf(filter)
            is Filter.Group<*> -> collectTextFilters(filter.state.filterIsInstance<Filter<*>>())
            else -> emptyList()
        }
    }
}
