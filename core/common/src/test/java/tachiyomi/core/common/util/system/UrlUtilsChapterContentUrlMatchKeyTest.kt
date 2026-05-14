package tachiyomi.core.common.util.system

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UrlUtilsChapterContentUrlMatchKeyTest {

    @Test
    fun `leading slash chapter url matches absolute https on different host`() {
        val relative = UrlUtils.chapterContentUrlMatchKey("/photo/287058")
        val absolute = UrlUtils.chapterContentUrlMatchKey("https://example.test/photo/287058")
        assertEquals("/photo/287058", relative)
        assertEquals(relative, absolute)
    }

    @Test
    fun `matches generic absolute url style from roadmap`() {
        val a = UrlUtils.chapterContentUrlMatchKey("/photo/287058")
        val b = UrlUtils.chapterContentUrlMatchKey("https://cdn.example.org/photo/287058")
        assertEquals(a, b)
    }

    @Test
    fun `http and https same path produce same key`() {
        val http = UrlUtils.chapterContentUrlMatchKey("http://h/photo/287058")
        val https = UrlUtils.chapterContentUrlMatchKey("https://h/photo/287058")
        assertEquals(http, https)
    }

    @Test
    fun `protocol relative url matches https absolute`() {
        val rel = UrlUtils.chapterContentUrlMatchKey("//example.test/photo/287058")
        val abs = UrlUtils.chapterContentUrlMatchKey("https://example.test/photo/287058")
        assertEquals(rel, abs)
    }

    @Test
    fun `bare relative path without leading slash matches slash form`() {
        val bare = UrlUtils.chapterContentUrlMatchKey("photo/287058")
        val slash = UrlUtils.chapterContentUrlMatchKey("/photo/287058")
        assertEquals(slash, bare)
    }

    @Test
    fun `query and fragment are preserved in key`() {
        val a = UrlUtils.chapterContentUrlMatchKey("/photo/1?x=y#z")
        val b = UrlUtils.chapterContentUrlMatchKey("https://host/photo/1?x=y#z")
        assertEquals("/photo/1?x=y#z", a)
        assertEquals(a, b)
    }

    @Test
    fun `whitespace is trimmed`() {
        val k = UrlUtils.chapterContentUrlMatchKey("  /photo/287058  ")
        assertEquals("/photo/287058", k)
    }

    @Test
    fun `orphaned synthetic url returns null`() {
        assertNull(UrlUtils.chapterContentUrlMatchKey("orphaned://第1話_0e6dcf"))
    }

    @Test
    fun `blank returns null`() {
        assertNull(UrlUtils.chapterContentUrlMatchKey(null))
        assertNull(UrlUtils.chapterContentUrlMatchKey(""))
        assertNull(UrlUtils.chapterContentUrlMatchKey("   "))
    }

    @Test
    fun `unsupported scheme returns null`() {
        assertNull(UrlUtils.chapterContentUrlMatchKey("javascript:alert(1)"))
    }

    @Test
    fun `malformed url returns null`() {
        assertNull(UrlUtils.chapterContentUrlMatchKey("https://%ZZ"))
    }
}
