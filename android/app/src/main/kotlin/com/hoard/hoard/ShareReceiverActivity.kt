package com.hoard.hoard

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Share-sheet and "open with" target. Transparent, does no UI, hands the link to
 * [DownloadService] and finishes so the user stays in whatever app they were in.
 * Handles both ACTION_SEND (share sheet) and ACTION_VIEW (tapping a link in another app).
 */
class ShareReceiverActivity : android.app.Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
        overridePendingTransition(0, 0)
    }

    private fun handle(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        val url = URL_REGEX.find(text)?.value
        if (url == null) {
            Toast.makeText(this, "Hoard: no link found", Toast.LENGTH_SHORT).show()
            return
        }
        val svc = Intent(this, DownloadService::class.java)
            .setAction(DownloadService.ACTION_ENQUEUE)
            .putExtra(DownloadService.EXTRA_URL, url)
        ContextCompat.startForegroundService(this, svc)
    }

    companion object {
        private val URL_REGEX = Regex("""https?://[^\s]+""")
    }
}
