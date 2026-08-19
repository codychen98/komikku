package eu.kanade.tachiyomi.data.backup.create

import java.io.ByteArrayOutputStream

internal object MihonCompatibleBackup {

    private const val WIRE_VARINT = 0
    private const val WIRE_64 = 1
    private const val WIRE_LEN = 2
    private const val WIRE_32 = 5

    private const val BACKUP_MANGA_FIELD = 1
    private const val MANGA_CHAPTERS_FIELD = 16
    private const val CHAPTER_CUSTOM_SORT_FIELD = 13
    private const val CHAPTER_EXCLUDED_FIELD = 14

    fun siblingFileName(komikkuFileName: String): String {
        if (komikkuFileName.contains("komikku", ignoreCase = true)) {
            return komikkuFileName.replace("komikku", "mihon", ignoreCase = true)
        }
        val suffix = if (komikkuFileName.endsWith(".tachibk")) ".tachibk" else ""
        val base = komikkuFileName.removeSuffix(".tachibk").ifEmpty { "backup" }
        return "$base-mihon$suffix"
    }

    fun protobufBytes(komikkuProtobuf: ByteArray): ByteArray {
        return rewrite(komikkuProtobuf, Message.Backup)
    }

    private enum class Message { Backup, Manga, Chapter }

    private fun nestedMessage(current: Message, field: Int, wire: Int): Message? {
        if (wire != WIRE_LEN) return null
        return when (current) {
            Message.Backup -> if (field == BACKUP_MANGA_FIELD) Message.Manga else null
            Message.Manga -> if (field == MANGA_CHAPTERS_FIELD) Message.Chapter else null
            Message.Chapter -> null
        }
    }

    private fun rewrite(bytes: ByteArray, message: Message): ByteArray {
        val out = ByteArrayOutputStream(bytes.size)
        var index = 0
        while (index < bytes.size) {
            val fieldStart = index
            val key = readVarint(bytes, index)
            index = key.next
            val field = (key.value ushr 3).toInt()
            val wire = (key.value and 7L).toInt()
            if (
                message == Message.Chapter &&
                (field == CHAPTER_CUSTOM_SORT_FIELD || field == CHAPTER_EXCLUDED_FIELD)
            ) {
                index = skipValue(bytes, index, wire)
                continue
            }
            val nested = nestedMessage(message, field, wire)
            if (nested != null) {
                val length = readVarint(bytes, index)
                val payloadStart = length.next
                val payloadEnd = payloadStart + length.value.toInt()
                require(payloadEnd <= bytes.size) { "Truncated protobuf payload" }
                val rewritten = rewrite(bytes.copyOfRange(payloadStart, payloadEnd), nested)
                writeVarint(out, key.value)
                writeVarint(out, rewritten.size.toLong())
                out.write(rewritten)
                index = payloadEnd
            } else {
                index = skipValue(bytes, index, wire)
                out.write(bytes, fieldStart, index - fieldStart)
            }
        }
        return out.toByteArray()
    }

    private data class Varint(val value: Long, val next: Int)

    private fun readVarint(bytes: ByteArray, start: Int): Varint {
        var result = 0L
        var shift = 0
        var index = start
        while (index < bytes.size) {
            val b = bytes[index].toInt() and 0xFF
            index++
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return Varint(result, index)
            shift += 7
            require(shift < 64) { "Varint too long" }
        }
        throw IllegalArgumentException("Truncated varint")
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var remaining = value
        while (remaining ushr 7 != 0L) {
            out.write(((remaining and 0x7F) or 0x80).toInt())
            remaining = remaining ushr 7
        }
        out.write((remaining and 0x7F).toInt())
    }

    private fun skipValue(bytes: ByteArray, index: Int, wire: Int): Int {
        return when (wire) {
            WIRE_VARINT -> readVarint(bytes, index).next
            WIRE_64 -> index + 8
            WIRE_LEN -> {
                val length = readVarint(bytes, index)
                val end = length.next + length.value.toInt()
                require(end <= bytes.size) { "Truncated protobuf payload" }
                end
            }
            WIRE_32 -> index + 4
            else -> throw IllegalArgumentException("Unsupported protobuf wire type $wire")
        }
    }
}
