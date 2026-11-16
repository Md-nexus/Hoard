package com.hoard.hoard

import android.app.Application
import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Initialises yt-dlp + ffmpeg once, off the main thread. Downloads wait on [awaitReady].
 * The first init extracts Python and is slow, so it must never run on the share path.
 * yt-dlp is also self-updated here (at most once a day) because extractors break often.
 */
class HoardApp : Application() {

    override fun onCreate() {
        super.onCreate()
        thread(name = "hoard-init") {
            try {
                YoutubeDL.getInstance().init(this)
                FFmpeg.getInstance().init(this)
                initOk = true
                // Update BEFORE releasing the latch so no download is running on files being replaced.
                updateIfStale(this)
            } catch (e: Exception) {
                Log.e(TAG, "yt-dlp init failed", e)
            } finally {
                ready.countDown()
            }
        }
    }

    companion object {
        private const val TAG = "HoardApp"
        private const val PREFS = "hoard_prefs"
        private const val KEY_LAST_UPDATE = "ytdlp_last_update"
        private const val UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000
        private val ready = CountDownLatch(1)

        @Volatile
        var initOk: Boolean = false
            private set

        /** Blocks until init finished. Returns true if yt-dlp is usable. */
        fun awaitReady(): Boolean {
            ready.await()
            return initOk
        }

        private fun updateIfStale(ctx: Context) {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val last = prefs.getLong(KEY_LAST_UPDATE, 0L)
            if (System.currentTimeMillis() - last < UPDATE_INTERVAL_MS) return
            updateNow(ctx)
        }

        /** Blocking. Returns a short human-readable status. Call off the main thread. */
        fun updateNow(ctx: Context): String = try {
            val status = YoutubeDL.getInstance()
                .updateYoutubeDL(ctx, YoutubeDL.UpdateChannel._STABLE)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
            when (status) {
                YoutubeDL.UpdateStatus.DONE -> "Updated"
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Already up to date"
                else -> "Done"
            }
        } catch (e: Exception) {
            // Offline or GitHub unreachable: keep the bundled yt-dlp and try again next launch.
            Log.w(TAG, "yt-dlp update failed", e)
            "Update failed: ${e.message?.take(80) ?: "unknown error"}"
        }
    }
}
