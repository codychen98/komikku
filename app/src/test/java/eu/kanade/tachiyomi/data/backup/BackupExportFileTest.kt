package eu.kanade.tachiyomi.data.backup

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupExportFileTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `prepare creates backup file when missing`() {
        val prepared = BackupExportFile.prepare(tempDir, BackupBroadcastReceiver.BACKUP_FILENAME)

        assertTrue(prepared.isFile)
        assertEquals(BackupBroadcastReceiver.BACKUP_FILENAME, prepared.name)
        assertEquals(0L, prepared.length())
        assertEquals(tempDir.absolutePath, prepared.parent)
    }

    @Test
    fun `prepare replaces existing backup file`() {
        val existing = File(tempDir, BackupBroadcastReceiver.BACKUP_FILENAME)
        existing.writeText("old-backup")

        val prepared = BackupExportFile.prepare(tempDir, BackupBroadcastReceiver.BACKUP_FILENAME)

        assertEquals(existing.absolutePath, prepared.absolutePath)
        assertTrue(prepared.isFile)
        assertEquals(0L, prepared.length())
    }

    @Test
    fun `prepare replaces read-only existing backup file`() {
        val existing = File(tempDir, BackupBroadcastReceiver.BACKUP_FILENAME)
        existing.writeText("old-backup")
        existing.setWritable(false)

        val prepared = BackupExportFile.prepare(tempDir, BackupBroadcastReceiver.BACKUP_FILENAME)

        assertEquals(existing.absolutePath, prepared.absolutePath)
        assertTrue(prepared.isFile)
        assertEquals(0L, prepared.length())
        assertTrue(prepared.canWrite())
    }

    @Test
    fun `prepare creates missing export directory`() {
        val dir = File(tempDir, "nested/backup")

        val prepared = BackupExportFile.prepare(dir, BackupBroadcastReceiver.BACKUP_FILENAME)

        assertTrue(dir.isDirectory)
        assertTrue(prepared.isFile)
        assertEquals(dir.absolutePath, prepared.parent)
    }
}
