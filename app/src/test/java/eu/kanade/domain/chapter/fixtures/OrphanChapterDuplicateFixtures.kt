package eu.kanade.domain.chapter.fixtures

import java.io.InputStream

/**
 * Test resources and in-memory models for ComicInfo-based orphan chapter duplicate work.
 * XML lives under `app/src/test/resources/orphan_chapter_duplicates/`.
 */
object OrphanChapterDuplicateFixtures {

    const val COMIC_INFO_RELATIVE_PATH: String = "orphan_chapter_duplicates/ComicInfo.xml"

    /**
     * Minimal catalog chapter row shape for URL matching tests (not a domain entity).
     */
    data class CatalogChapterRef(
        val id: Long,
        val name: String,
        val url: String,
    )

    /**
     * Sample list aligned with [COMIC_INFO_RELATIVE_PATH] `<Web>` for normalization tests.
     */
    val sampleCatalogChaptersForComicInfoFixture: List<CatalogChapterRef> = listOf(
        CatalogChapterRef(
            id = 944L,
            name = "Chapter 1",
            url = "/photo/287058",
        ),
    )

    fun openComicInfoXmlStream(): InputStream =
        requireNotNull(
            OrphanChapterDuplicateFixtures::class.java.classLoader?.getResourceAsStream(
                COMIC_INFO_RELATIVE_PATH,
            ),
        ) { "Missing test resource: $COMIC_INFO_RELATIVE_PATH" }
}
