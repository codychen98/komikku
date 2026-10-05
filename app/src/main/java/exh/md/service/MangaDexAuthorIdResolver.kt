package exh.md.service

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import exh.md.utils.MdApi
import exh.md.utils.MdUtil
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

@Serializable
private data class PersonListDto(
    val data: List<PersonDto> = emptyList(),
)

@Serializable
private data class PersonDto(
    val id: String,
    val attributes: PersonAttributesDto? = null,
)

@Serializable
private data class PersonAttributesDto(
    val name: String? = null,
)

/**
 * Resolves a display name to a MangaDex author uuid.
 * Only an exact name match is kept, matching the website `author=<uuid>` filter.
 */
class MangaDexAuthorIdResolver(
    private val client: OkHttpClient,
) {
    suspend fun resolve(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val key = normalize(trimmed)
        cache[key]?.let { return it.id }
        val lock = locks.getOrPut(key) { Mutex() }
        return lock.withLock {
            cache[key]?.let { return@withLock it.id }
            val id = fetch(trimmed)
            cache[key] = CacheEntry(id)
            id
        }
    }

    private suspend fun fetch(name: String): String? {
        val url = MdApi.author.toHttpUrl().newBuilder()
            .addQueryParameter("name", name)
            .addQueryParameter("limit", "100")
            .build()
        val people = with(MdUtil.jsonParser) {
            client.newCall(GET(url, cache = CacheControl.FORCE_NETWORK))
                .awaitSuccess()
                .parseAs<PersonListDto>()
        }
        val target = normalize(name)
        return people.data.firstOrNull { person ->
            person.attributes?.name?.let(::normalize) == target
        }?.id
    }

    private data class CacheEntry(val id: String?)

    companion object {
        private val whitespace = Regex("\\s+")
        private val cache = ConcurrentHashMap<String, CacheEntry>()
        private val locks = ConcurrentHashMap<String, Mutex>()

        private fun normalize(name: String): String {
            return name.trim().replace(whitespace, " ").lowercase()
        }
    }
}
