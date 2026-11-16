package com.hoard.hoard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.app.NotificationCompat

/** Channels and notification builders. */
object Notifier {
    /** Quiet channel for the mandatory foreground-service notification. */
    const val CH_SERVICE = "hoard_service"

    /**
     * Per-download progress. HIGH importance so the first post pops up as a heads-up, but with
     * setOnlyAlertOnce on every update it then settles into the shade and stays there.
     * (v2: a channel's importance cannot be raised after creation, so the old silent channel
     * "hoard_progress" is deleted and replaced.)
     */
    const val CH_PROGRESS = "hoard_progress_v2"
    const val CH_DONE = "hoard_done"

    private const val OLD_CH_PROGRESS = "hoard_progress"
    private const val ACCENT = 0xFF7C4DFF.toInt()

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(OLD_CH_PROGRESS)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Background service", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "Pops up when a download starts, then stays in the notification bar."
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Download complete", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** Ongoing notification required while the service is alive. Quiet, never pops. */
    fun summary(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_hoard)
            .setColor(ACCENT)
            .setContentTitle("Hoard")
            .setContentText(text)
            .setContentIntent(openApp(ctx))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    /**
     * One per download. [name] / [uploader] / [thumb] are null until yt-dlp has extracted them, so
     * the first post (the heads-up) is generic and later updates fill in the details in place.
     */
    fun progress(
        ctx: Context,
        name: String?,
        uploader: String?,
        percent: Int,
        indeterminate: Boolean,
        thumb: Bitmap?,
        status: String? = null,
    ): Notification {
        val b = NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_hoard)
            .setColor(ACCENT)
            .setContentTitle(name ?: "Preparing download…")
            .setContentText(
                status ?: if (indeterminate) "Starting…" else "Downloading · $percent%"
            )
            .setProgress(100, percent, indeterminate)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(ctx))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        if (!uploader.isNullOrBlank()) b.setSubText(uploader)
        if (thumb != null) b.setLargeIcon(thumb)
        return b.build()
    }

    fun complete(
        ctx: Context,
        id: Int,
        uri: Uri,
        mime: String,
        name: String,
        uploader: String?,
        thumb: Bitmap?,
    ): Notification {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val send = Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Share"
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val viewPi = PendingIntent.getActivity(ctx, id * 2, view, flags)
        val sharePi = PendingIntent.getActivity(ctx, id * 2 + 1, send, flags)
        val b = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_hoard)
            .setColor(ACCENT)
            .setContentTitle(name)
            .setContentText("Saved to Hoard · tap to open")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(viewPi)
            .addAction(0, "Open", viewPi)
            .addAction(0, "Share", sharePi)
            .setAutoCancel(true)
        if (!uploader.isNullOrBlank()) b.setSubText(uploader)
        if (thumb != null) {
            b.setLargeIcon(thumb)
            b.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(thumb)
                    .bigLargeIcon(null as Bitmap?)
            )
        }
        return b.build()
    }

    /** Pops up when yt-dlp cannot handle a link or a whole platform. */
    fun unsupported(ctx: Context, host: String?, detail: String): Notification {
        val where = host?.takeIf { it.isNotBlank() } ?: "this link"
        val text = "Hoard can't download from $where"
        return NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_hoard)
            .setColor(ACCENT)
            .setContentTitle("Unsupported link")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text. $detail"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
    }

    /** [loginPlatform] non-null adds a "Log in again" action that opens that platform's login. */
    fun failure(ctx: Context, reason: String, loginPlatform: SocialPlatform?): Notification {
        val b = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_hoard)
            .setColor(ACCENT)
            .setContentTitle("Hoard couldn't save that")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        if (loginPlatform != null) {
            val login = Intent(ctx, LoginActivity::class.java)
                .putExtra(LoginActivity.EXTRA_PLATFORM, loginPlatform.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(
                ctx, 700 + loginPlatform.ordinal, login,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(0, "Log in again", pi)
        }
        return b.build()
    }
}
