package com.hoard.hoard

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service owning the download queue: max 2 concurrent, one notification per download.
 * Queue is in-memory (v1); if the process dies, pending items are lost.
 *
 * Notification flow per download: a progress notification is posted at once (pops up as a
 * heads-up), then updated in place without re-alerting so it sits in the shade, and finally
 * replaced by a fresh completion notification that pops up again.
 */
class DownloadService : Service() {

    private val pool = Executors.newFixedThreadPool(MAX_CONCURRENT)
    private val main = Handler(Looper.getMainLooper())
    private val pending = AtomicInteger(0)   // queued + running
    private val running = AtomicInteger(0)

    /** What yt-dlp told us about the item, filled in while the download runs. */
    private class Meta {
        @Volatile var name: String? = null
        @Volatile var uploader: String? = null
        @Volatile var thumb: Bitmap? = null
        @Volatile var infoDone = false
        @Volatile var thumbDone = false
        @Volatile var streams = 1
    }

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground quickly after startForegroundService.
        val note = Notifier.summary(this, "Downloading…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(SUMMARY_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(SUMMARY_ID, note)
        }

        val url = intent?.getStringExtra(EXTRA_URL)
        if (intent?.action == ACTION_ENQUEUE && url != null) enqueue(url)
        else if (pending.get() == 0) stopSelfClean()
        return START_NOT_STICKY
    }

    private fun enqueue(url: String) {
        val ahead = pending.getAndIncrement()
        if (ahead >= MAX_CONCURRENT) {
            toast("Queued (${ahead - MAX_CONCURRENT + 1} ahead)")
        }
        val id = ids.getAndIncrement()
        pool.execute { runDownload(id, url) }
    }

    private fun runDownload(id: Int, url: String) {
        val nm = NotificationManagerCompat.from(this)
        running.incrementAndGet()
        val dir = File(cacheDir, "dl/$id").apply { mkdirs() }
        // Thumbnail + info json go in a sub-folder so they are never mistaken for the output file.
        val metaDir = File(dir, "meta").apply { mkdirs() }
        // Outside [dir] for the same reason.
        val cookieFile = File(cacheDir, "dl/$id.cookies.txt")
        val platform = SocialPlatform.fromUrl(url)
        val meta = Meta()
        try {
            // First post: HIGH-importance channel, so this is the one that pops up.
            notify(nm, id, Notifier.progress(this, null, null, 0, true, null))

            if (!HoardApp.awaitReady()) {
                fail(nm, id, "yt-dlp failed to start. Try reopening Hoard.", null)
                return
            }

            val req = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("-f", "bv*+ba/b")
                addOption("--merge-output-format", "mp4")
                addOption("-o", "${dir.absolutePath}/%(id)s.%(ext)s")
                addOption("--write-thumbnail")
                addOption("--write-info-json")
                // Typed output templates (the options map holds one value per key, so raw args).
                addCommands(
                    listOf(
                        "-o", "thumbnail:${metaDir.absolutePath}/%(id)s.%(ext)s",
                        "-o", "infojson:${metaDir.absolutePath}/%(id)s.%(ext)s",
                    )
                )
                // Optional login: decrypt this platform's cookies for the duration of the run.
                if (platform != null &&
                    CookieStore.writeTemp(this@DownloadService, platform, cookieFile)
                ) {
                    addOption("--cookies", cookieFile.absolutePath)
                    addOption("--user-agent", CookieStore.USER_AGENT)
                }
            }

            // yt-dlp often fetches video and audio as two separate streams (each counts 0-100%),
            // then merges. Fold that into ONE overall percentage so it never appears to restart.
            var last = -1
            var streamIdx = -1
            var streamPct = 0f
            var finishing = false
            var postedFinishing = false
            YoutubeDL.getInstance().execute(req, "hoard-$id") { _, _, line ->
                if (line.contains("[download] Destination:")) {
                    streamIdx++
                    streamPct = 0f
                } else {
                    PERCENT.find(line)?.groupValues?.get(1)?.toFloatOrNull()?.let { streamPct = it }
                }
                if (line.contains("[Merger]") || line.contains("[VideoRemuxer]") ||
                    line.contains("[Fixup")
                ) finishing = true

                val gotMeta = pollMeta(metaDir, meta)
                val total = meta.streams.coerceAtLeast(1)
                val overall = (((streamIdx.coerceAtLeast(0) + streamPct / 100f) / total) * 100f)
                    .toInt().coerceIn(0, 99)
                if (overall != last || gotMeta || finishing != postedFinishing) {
                    last = overall
                    postedFinishing = finishing
                    // Updates never re-alert (setOnlyAlertOnce), they just fill in name/thumb/percent.
                    notify(
                        nm, id,
                        Notifier.progress(
                            this, meta.name, meta.uploader, overall,
                            overall == 0 && !finishing, meta.thumb,
                            if (finishing) "Finishing…" else null
                        )
                    )
                }
            }

            val out = dir.listFiles()?.filter { it.isFile }?.maxByOrNull { it.length() }
                ?: error("No output file produced")
            val saved = MediaStoreSaver.save(this, out)
            pollMeta(metaDir, meta)
            val thumb = meta.thumb ?: loadThumb(saved.uri)

            // Remove the progress notification and post a brand-new one so it pops up again.
            nm.cancel(id)
            notify(
                nm, id,
                Notifier.complete(this, id, saved.uri, saved.mime, out.name, meta.uploader, thumb)
            )
        } catch (e: Exception) {
            Log.e(TAG, "download failed: $url", e)
            val msg = e.message.orEmpty()
            val unsupportedWhy = unsupportedReason(msg)
            if (unsupportedWhy != null) {
                // Checked first: yt-dlp's "Unsupported URL: https://x/login" would otherwise
                // be mistaken for a login problem.
                nm.cancel(id)
                notify(nm, id, Notifier.unsupported(this, hostOf(url), unsupportedWhy))
                return
            }
            val login = msg.contains("login", true) || msg.contains("cookies", true) ||
                msg.contains("sign in", true)
            val reason = when {
                login && platform != null && CookieStore.has(this, platform) ->
                    "Your ${platform.label} session expired. Log in again."
                login -> "Login required. Log in to continue."
                msg.contains("rate", true) && msg.contains("limit", true) -> "Rate limited. Try again in a bit."
                else -> msg.lineSequence().firstOrNull { it.isNotBlank() }?.take(140) ?: "Unknown error"
            }
            fail(nm, id, reason, if (login) platform else null)
        } finally {
            dir.deleteRecursively()
            cookieFile.delete()
            running.decrementAndGet()
            if (pending.decrementAndGet() == 0) main.post { stopSelfClean() }
        }
    }

    /** Non-null when yt-dlp's error means "this link or site can't be downloaded", not a glitch. */
    private fun unsupportedReason(msg: String): String? = when {
        msg.contains("Unsupported URL", true) ->
            "yt-dlp doesn't support this site or this kind of page."
        msg.contains("is not a valid URL", true) -> "That isn't a valid link."
        msg.contains("No video could be found", true) ||
            msg.contains("No video formats found", true) ||
            msg.contains("There is no video", true) ->
            "There is no downloadable video at this link."
        msg.contains("DRM", false) -> "This content is DRM protected."
        else -> null
    }

    private fun hostOf(url: String): String? =
        runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull()

    private fun fail(
        nm: NotificationManagerCompat, id: Int, reason: String, loginPlatform: SocialPlatform?,
    ) {
        nm.cancel(id)
        notify(nm, id, Notifier.failure(this, reason, loginPlatform))
    }

    /**
     * yt-dlp writes the info json and the thumbnail right after extraction, before the bytes
     * start flowing. Called on every output line; returns true when something new was picked up.
     */
    private fun pollMeta(metaDir: File, meta: Meta): Boolean {
        if (meta.infoDone && meta.thumbDone) return false
        val files = metaDir.listFiles() ?: return false
        var changed = false

        if (!meta.infoDone) {
            files.firstOrNull { it.name.endsWith(".info.json") }?.let { f ->
                try {
                    val j = JSONObject(f.readText())
                    val id = j.optString("id")
                    val ext = j.optString("ext")
                    if (id.isNotBlank() && ext.isNotBlank() && ext != "null") meta.name = "$id.$ext"
                    meta.uploader = listOf("uploader", "channel", "uploader_id")
                        .map { j.optString(it) }
                        .firstOrNull { it.isNotBlank() && it != "null" }
                    meta.infoDone = true
                    j.optJSONArray("requested_formats")?.length()?.takeIf { it > 0 }?.let {
                        meta.streams = it
                    }
                    changed = true
                } catch (_: Exception) {
                    // Still being written; try again on the next line.
                }
            }
        }

        if (!meta.thumbDone) {
            files.firstOrNull { it.extension.lowercase() in THUMB_EXT }?.let { f ->
                decodeThumb(f)?.let {
                    meta.thumb = it
                    meta.thumbDone = true
                    changed = true
                }
            }
        }
        return changed
    }

    private fun decodeThumb(f: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 768 || bounds.outHeight / sample > 768) sample *= 2
        BitmapFactory.decodeFile(
            f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }
        )
    } catch (_: Exception) {
        null
    }

    private fun loadThumb(uri: Uri): Bitmap? = try {
        contentResolver.loadThumbnail(uri, Size(512, 512), null)
    } catch (_: Exception) {
        null
    }

    private fun notify(nm: NotificationManagerCompat, id: Int, n: android.app.Notification) {
        try {
            nm.notify(id, n)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied: downloads still run, just silently.
        }
    }

    private fun toast(text: String) = main.post {
        Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
    }

    private fun stopSelfClean() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        pool.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DownloadService"
        const val ACTION_ENQUEUE = "com.hoard.hoard.ENQUEUE"
        const val EXTRA_URL = "url"
        private const val MAX_CONCURRENT = 2
        private const val SUMMARY_ID = 1
        private val THUMB_EXT = setOf("jpg", "jpeg", "png", "webp")
        private val PERCENT = Regex("""\[download\]\s+(\d+(?:\.\d+)?)%""")
        // Process-wide so a restarted service never reuses IDs of earlier completion notifications.
        private val ids = AtomicInteger(100)
    }
}
