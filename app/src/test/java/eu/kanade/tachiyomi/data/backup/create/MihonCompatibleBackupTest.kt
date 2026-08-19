package eu.kanade.tachiyomi.data.backup.create

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MihonCompatibleBackupTest {

    @Test
    fun `siblingFileName replaces komikku with mihon`() {
        assertEquals("mihon.tachibk", MihonCompatibleBackup.siblingFileName("komikku.tachibk"))
        assertEquals(
            "app.mihon_2026-08-19_19-14.tachibk",
            MihonCompatibleBackup.siblingFileName("app.komikku_2026-08-19_19-14.tachibk"),
        )
        assertEquals(
            "app.mihon.dev_2026-08-19_19-14.tachibk",
            MihonCompatibleBackup.siblingFileName("app.komikku.dev_2026-08-19_19-14.tachibk"),
        )
    }

    @Test
    fun `siblingFileName appends -mihon when komikku is not in the name`() {
        assertEquals("library-mihon.tachibk", MihonCompatibleBackup.siblingFileName("library.tachibk"))
    }

    @Test
    fun `protobufBytes drops chapter fields 13 and 14 only`() {
        val chapter = bytes(
            lengthDelimited(1, "https://example.com/ch/1".toByteArray()),
            lengthDelimited(2, "Ch. 1".toByteArray()),
            varintField(13, 7),
            varintField(14, 1),
        )
        val manga = bytes(
            varintField(1, 99),
            lengthDelimited(2, "https://example.com/manga".toByteArray()),
            varintField(13, 12345),
            lengthDelimited(16, chapter),
        )
        val backup = bytes(
            lengthDelimited(1, manga),
            lengthDelimited(610, "feed".toByteArray()),
        )

        val compatible = MihonCompatibleBackup.protobufBytes(backup)
        val rewrittenManga = fields(compatible).single { it.field == 1 }.payload
        val rewrittenChapter = fields(rewrittenManga).single { it.field == 16 }.payload
        val chapterFields = fields(rewrittenChapter).map { it.field }.toSet()
        val mangaFields = fields(rewrittenManga).map { it.field }.toSet()
        val backupFields = fields(compatible).map { it.field }.toSet()

        assertEquals(setOf(1, 2), chapterFields)
        assertFalse(13 in chapterFields)
        assertFalse(14 in chapterFields)
        assertTrue(13 in mangaFields)
        assertEquals(setOf(1, 610), backupFields)
    }

    private data class ProtoField(val field: Int, val wire: Int, val payload: ByteArray)

    private fun fields(message: ByteArray): List<ProtoField> {
        val result = mutableListOf<ProtoField>()
        var i = 0
        while (i < message.size) {
            val key = readVarint(message, i)
            i = key.next
            val field = (key.value ushr 3).toInt()
            val wire = (key.value and 7L).toInt()
            when (wire) {
                0 -> {
                    val value = readVarint(message, i)
                    result += ProtoField(field, wire, varintBytes(value.value))
                    i = value.next
                }
                2 -> {
                    val len = readVarint(message, i)
                    val start = len.next
                    val end = start + len.value.toInt()
                    result += ProtoField(field, wire, message.copyOfRange(start, end))
                    i = end
                }
                else -> error("unexpected wire $wire")
            }
        }
        return result
    }

    private fun bytes(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        chunks.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun varintField(field: Int, value: Long): ByteArray {
        return bytes(varintBytes((field.toLong() shl 3)), varintBytes(value))
    }

    private fun lengthDelimited(field: Int, payload: ByteArray): ByteArray {
        return bytes(
            varintBytes((field.toLong() shl 3) or 2),
            varintBytes(payload.size.toLong()),
            payload,
        )
    }

    private data class Varint(val value: Long, val next: Int)

    private fun readVarint(bytes: ByteArray, start: Int): Varint {
        var result = 0L
        var shift = 0
        var i = start
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            i++
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return Varint(result, i)
            shift += 7
        }
        error("truncated varint")
    }

    private fun varintBytes(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var remaining = value
        while (remaining ushr 7 != 0L) {
            out.write(((remaining and 0x7F) or 0x80).toInt())
            remaining = remaining ushr 7
        }
        out.write((remaining and 0x7F).toInt())
        return out.toByteArray()
    }
}
