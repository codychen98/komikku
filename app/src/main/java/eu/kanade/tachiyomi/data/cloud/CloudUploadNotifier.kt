package eu.kanade.tachiyomi.data.cloud

import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.kmk.KMR

// CLOUD -->
class CloudUploadNotifier(private val context: Context) {

    private val progressBuilder = context.notificationBuilder(Notifications.CHANNEL_COMMON) {
        setSmallIcon(R.drawable.ic_komikku)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.komikku))
            .setColor(ContextCompat.getColor(context, R.color.ic_launcher))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
    }

    fun showProgress(title: String) {
        context.notificationManager.notify(
            NOTIFICATION_ID,
            progressBuilder
                .setContentTitle(context.stringResource(KMR.strings.cloud_upload_title))
                .setContentText(context.stringResource(KMR.strings.cloud_upload_progress, title))
                .build(),
        )
    }

    fun showError(message: String) {
        context.notificationManager.notify(
            NOTIFICATION_ID + 1,
            context.notificationBuilder(Notifications.CHANNEL_COMMON) {
                setSmallIcon(R.drawable.ic_warning_white_24dp)
                setContentTitle(context.stringResource(KMR.strings.cloud_upload_title))
                setContentText(context.stringResource(KMR.strings.cloud_upload_error, message))
                setAutoCancel(true)
            }.build(),
        )
    }

    fun showMigrateSkipped() {
        context.notificationManager.notify(
            NOTIFICATION_ID + 2,
            context.notificationBuilder(Notifications.CHANNEL_COMMON) {
                setSmallIcon(R.drawable.ic_komikku)
                setContentTitle(context.stringResource(KMR.strings.cloud_upload_title))
                setContentText(context.stringResource(KMR.strings.cloud_migrate_skipped))
                setAutoCancel(true)
            }.build(),
        )
    }

    fun showReconcileComplete(adopted: Int, unmatched: Int) {
        context.notificationManager.notify(
            NOTIFICATION_ID + 3,
            context.notificationBuilder(Notifications.CHANNEL_COMMON) {
                setSmallIcon(R.drawable.ic_komikku)
                setContentTitle(context.stringResource(KMR.strings.cloud_upload_title))
                setContentText(
                    context.stringResource(KMR.strings.cloud_reconcile_complete, adopted, unmatched),
                )
                setAutoCancel(true)
            }.build(),
        )
    }

    fun showReconcileError(message: String) {
        context.notificationManager.notify(
            NOTIFICATION_ID + 4,
            context.notificationBuilder(Notifications.CHANNEL_COMMON) {
                setSmallIcon(R.drawable.ic_warning_white_24dp)
                setContentTitle(context.stringResource(KMR.strings.cloud_upload_title))
                setContentText(context.stringResource(KMR.strings.cloud_reconcile_error, message))
                setAutoCancel(true)
            }.build(),
        )
    }

    fun dismissProgress() {
        context.notificationManager.cancel(NOTIFICATION_ID)
    }

    companion object {
        private const val NOTIFICATION_ID = 770174823
    }
}
// CLOUD <--
