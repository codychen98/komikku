package eu.kanade.tachiyomi.extension.all.cloudlibrary.cloudstorage

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object CloudPathUtil {
    fun normalize(path: String): String {
        return path.trim('/').split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/")
    }

    fun join(vararg parts: String): String {
        return parts
            .flatMap { it.split('/') }
            .filter { it.isNotEmpty() }
            .joinToString("/")
    }

    fun encodeSegment(segment: String): String {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8)
            .replace("+", "%20")
    }

    fun encodePath(path: String): String {
        return normalize(path).split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { encodeSegment(it) }
    }

    fun decode(value: String): String {
        return try {
            URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            value
        }
    }

    fun parent(path: String): String {
        val normalized = normalize(path)
        val idx = normalized.lastIndexOf('/')
        return if (idx <= 0) "" else normalized.substring(0, idx)
    }

    fun name(path: String): String {
        val normalized = normalize(path)
        val idx = normalized.lastIndexOf('/')
        return if (idx < 0) normalized else normalized.substring(idx + 1)
    }
}
