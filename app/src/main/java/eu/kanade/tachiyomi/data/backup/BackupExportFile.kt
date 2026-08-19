package eu.kanade.tachiyomi.data.backup

import java.io.File
import java.io.IOException

internal object BackupExportFile {

    fun prepare(dir: File, filename: String): File {
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("Unable to create backup directory: ${dir.absolutePath}")
        }
        if (!dir.isDirectory) {
            throw IOException("Backup export path is not a directory: ${dir.absolutePath}")
        }

        val file = File(dir, filename)
        if (file.exists()) {
            file.setWritable(true)
            if (!file.delete() && file.exists()) {
                throw IOException("Unable to replace existing backup: ${file.absolutePath}")
            }
        }
        if (!file.createNewFile()) {
            throw IOException("Unable to create backup file: ${file.absolutePath}")
        }
        return file
    }
}
