package eu.kanade.tachiyomi.data.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

// KMK -->
/**
 * Broadcast receiver that creates a backup when it receives the [ACTION_CREATE_BACKUP] intent.
 *
 * Usage:
 * ```
 * adb shell am broadcast -a app.komikku.CREATE_BACKUP
 * adb shell am broadcast -a app.komikku.CREATE_BACKUP --es export_path /sdcard/Download/backups
 * ```
 */
class BackupBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CREATE_BACKUP) return

        val exportPath = intent.getStringExtra(EXTRA_EXPORT_PATH) ?: DEFAULT_EXPORT_PATH

        try {
            val file = BackupExportFile.prepare(File(exportPath), BACKUP_FILENAME)

            BackupCreateJob.startNow(
                context = context,
                uri = file.toUri(),
                options = BackupOptions(privateSettings = true),
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to start backup from broadcast intent" }
        }
    }

    companion object {
        const val ACTION_CREATE_BACKUP = "app.komikku.CREATE_BACKUP"
        const val EXTRA_EXPORT_PATH = "export_path"
        const val DEFAULT_EXPORT_PATH = "/storage/emulated/0/Download"
        const val BACKUP_FILENAME = "komikku.tachibk"
    }
}
// KMK <--
