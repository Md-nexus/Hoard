package com.hoard.hoard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.CookieManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

/** Flutter host. Exposes a small command channel to the Dart UI. */
class MainActivity : FlutterActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private var pendingLogin: MethodChannel.Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Channels exist from first launch, so they show up in system settings before any download.
        Notifier.createChannels(this)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "notificationsGranted" -> result.success(notificationsGranted())
                    "requestNotifications" -> {
                        if (!notificationsGranted() && Build.VERSION.SDK_INT >= 33) {
                            ActivityCompat.requestPermissions(
                                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
                            )
                        }
                        result.success(notificationsGranted())
                    }
                    "openNotificationSettings" -> {
                        startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        )
                        result.success(null)
                    }
                    "openLinkSettings" -> {
                        // "Open by default" page for Hoard, or for another app when "package" is given
                        // (API 31+); app details page before that.
                        val target = call.argument<String>("package") ?: packageName
                        val pkg = Uri.parse("package:$target")
                        val intent = if (Build.VERSION.SDK_INT >= 31)
                            Intent("android.settings.APP_OPEN_BY_DEFAULT_SETTINGS", pkg)
                        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
                        try { startActivity(intent) } catch (e: Exception) {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
                        }
                        result.success(null)
                    }
                    "linkApps" -> {
                        // Official apps that grab these links before Hoard can (verified domains).
                        val pm = packageManager
                        val found = LINK_APPS.mapNotNull { (pkgName, fallback) ->
                            try {
                                val info = pm.getApplicationInfo(pkgName, 0)
                                mapOf(
                                    "package" to pkgName,
                                    "label" to (pm.getApplicationLabel(info)?.toString() ?: fallback),
                                )
                            } catch (e: Exception) {
                                null
                            }
                        }
                        result.success(found)
                    }
                    "enqueueUrl" -> {
                        val url = call.argument<String>("url")
                        if (url.isNullOrBlank()) {
                            result.error("bad_url", "No link", null)
                        } else {
                            ContextCompat.startForegroundService(
                                this,
                                Intent(this, DownloadService::class.java)
                                    .setAction(DownloadService.ACTION_ENQUEUE)
                                    .putExtra(DownloadService.EXTRA_URL, url)
                            )
                            result.success(true)
                        }
                    }
                    "updateYtDlp" -> Thread {
                        HoardApp.awaitReady()
                        val msg = HoardApp.updateNow(applicationContext)
                        runOnUiThread { result.success(msg) }
                    }.start()

                    // ---- Library (Movies/Hoard + Pictures/Hoard) ----
                    "listLibrary" -> io.execute {
                        val items = try { Library.list(applicationContext) } catch (e: Exception) {
                            emptyList()
                        }
                        runOnUiThread { result.success(items) }
                    }
                    "thumbnail" -> {
                        val uri = call.argument<String>("uri")
                        val size = call.argument<Int>("size") ?: 360
                        if (uri == null) result.success(null) else io.execute {
                            val bytes = Library.thumbnail(applicationContext, uri, size)
                            runOnUiThread { result.success(bytes) }
                        }
                    }
                    "deleteMedia" -> {
                        val uri = call.argument<String>("uri")
                        if (uri == null) result.success(false) else io.execute {
                            val ok = Library.delete(applicationContext, uri)
                            runOnUiThread { result.success(ok) }
                        }
                    }
                    "openMedia" -> {
                        val uri = call.argument<String>("uri")
                        val mime = call.argument<String>("mime") ?: "video/mp4"
                        if (uri != null) {
                            startActivity(
                                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            )
                        }
                        result.success(null)
                    }
                    "shareMedia" -> {
                        val uri = call.argument<String>("uri")
                        val mime = call.argument<String>("mime") ?: "video/mp4"
                        if (uri != null) {
                            startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).setType(mime)
                                        .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                    "Share"
                                )
                            )
                        }
                        result.success(null)
                    }

                    // ---- Accounts (optional per-platform login) ----
                    "loginStatus" -> result.success(
                        SocialPlatform.values().map {
                            mapOf(
                                "id" to it.id,
                                "label" to it.label,
                                "loggedIn" to CookieStore.has(applicationContext, it),
                            )
                        }
                    )
                    "startLogin" -> {
                        val platform = SocialPlatform.fromId(call.argument<String>("platform"))
                        if (platform == null) {
                            result.error("bad_platform", "Unknown platform", null)
                        } else {
                            pendingLogin?.success(false)
                            pendingLogin = result
                            @Suppress("DEPRECATION")
                            startActivityForResult(
                                Intent(this, LoginActivity::class.java)
                                    .putExtra(LoginActivity.EXTRA_PLATFORM, platform.id),
                                REQ_LOGIN
                            )
                        }
                    }
                    "logout" -> {
                        val platform = SocialPlatform.fromId(call.argument<String>("platform"))
                        if (platform != null) {
                            CookieStore.delete(applicationContext, platform)
                            // Drop the WebView's copy too, otherwise the next login screen would
                            // instantly "log in" again with the stale session.
                            CookieManager.getInstance().removeAllCookies(null)
                            CookieManager.getInstance().flush()
                        }
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LOGIN) {
            pendingLogin?.success(resultCode == RESULT_OK)
            pendingLogin = null
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        const val CHANNEL = "com.hoard/commands"
        private const val REQ_LOGIN = 2001
        private val LINK_APPS = listOf(
            "com.zhiliaoapp.musically" to "TikTok",
            "com.ss.android.ugc.trill" to "TikTok",
            "com.instagram.android" to "Instagram",
            "com.reddit.frontpage" to "Reddit",
            "com.google.android.youtube" to "YouTube",
            "com.twitter.android" to "X",
        )
    }
}
