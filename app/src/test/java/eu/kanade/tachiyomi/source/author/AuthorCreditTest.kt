package eu.kanade.tachiyomi.source.author

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class AuthorCreditTest {

    @Test
    fun `splits a combined credit into each person`() {
        assertEquals(
            listOf("author1", "author 2"),
            authorCreditNames("author1, author 2"),
        )
    }

    @Test
    fun `keeps a single name that contains spaces`() {
        assertEquals(listOf("TSUKIYO Rui"), authorCreditNames(" TSUKIYO Rui "))
    }

    @Test
    fun `drops empty pieces and repeated names`() {
        assertEquals(
            listOf("author1", "author 2"),
            authorCreditNames("author1, , author 2, Author1"),
        )
    }

    @Test
    fun `uses the shared author and artist field for either credit`() {
        val filters = FilterList(NameFilter("Author / Artist"), NameFilter("Release year (From)"))
        assertEquals("Author / Artist", findCreditFilter(filters, CatalogueCreditRole.Author)?.name)
        assertEquals("Author / Artist", findCreditFilter(filters, CatalogueCreditRole.Artist)?.name)
    }

    @Test
    fun `picks the matching field when author and artist are separate`() {
        val author = NameFilter("Author")
        val artist = NameFilter("Artist")
        val filters = FilterList(author, artist, NameFilter("Authorization"))
        assertSame(author, findCreditFilter(filters, CatalogueCreditRole.Author))
        assertSame(artist, findCreditFilter(filters, CatalogueCreditRole.Artist))
        assertNull(findCreditFilter(FilterList(NameFilter("Authorization")), CatalogueCreditRole.Author))
    }

    @Test
    fun `merges each person's page and drops duplicate urls`() {
        val merged = mergeAuthorPages(
            listOf(
                MangasPage(listOf(manga("a", "One"), manga("b", "Two")), hasNextPage = true),
                MangasPage(listOf(manga("b", "Two again"), manga("c", "Three")), hasNextPage = false),
            ),
        )
        assertEquals(listOf("a", "b", "c"), merged.mangas.map { it.url })
        assertEquals(true, merged.hasNextPage)
    }

    private fun manga(url: String, title: String): SManga = SManga(url = url, title = title)

    private class NameFilter(name: String) : Filter.Text(name)
}
