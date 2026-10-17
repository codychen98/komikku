package eu.kanade.tachiyomi.extension.all.cloudlibrary

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class CloudLibraryFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        WebDavSource(),
        PCloudSource(),
    )
}
