package eu.kanade.domain.chapter.fixtures

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class OrphanChapterDuplicateFixturesTest {

    @Test
    fun `ComicInfo fixture is on classpath and contains Web`() {
        val text = OrphanChapterDuplicateFixtures.openComicInfoXmlStream().bufferedReader().readText()
        assertTrue(text.contains("<Web>"))
        assertTrue(text.contains("photo/287058"))
    }
}
