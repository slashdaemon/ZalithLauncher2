package com.movtery.zalithlauncher.tbs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.movtery.zalithlauncher.upgrade.RemoteData
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.network.downloadFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

private const val TAG = "TbsApkInstaller"

/**
 * Downloads a launcher update APK from our GitHub release, checks it against the release's sha256,
 * and hands it to the system installer. Android shows its own confirmation (and, the first time,
 * asks the player to allow installs from the launcher). Same package + same TBS signing key, so the
 * update installs in place and keeps the player's game data.
 */
object ApkInstaller {
    /** Download progress 0..1 while an update downloads; null otherwise. */
    var progress by mutableStateOf<Float?>(null)
        private set

    fun start(context: Context, scope: CoroutineScope, file: RemoteData.RemoteFile) {
        if (progress != null) return
        val app = context.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(app, "Allow TBS Launcher to install updates, then tap Download again.", Toast.LENGTH_LONG).show()
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        progress = 0f
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val out = File(File(app.cacheDir, "updates").apply { mkdirs() }, file.fileName)
                    out.delete()
                    var got = 0L
                    downloadFile(url = file.uri, outputFile = out) { delta ->
                        got += delta
                        if (file.size > 0) progress = (got.toFloat() / file.size).coerceIn(0f, 1f)
                    }
                    ApkDigests.get(file.uri)?.let { expected ->
                        val actual = sha256(out)
                        check(actual == expected) { "Update download is corrupt (sha256 $actual, expected $expected)" }
                    }
                    out
                }
            }
            progress = null
            result.onSuccess { apk ->
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.provider", apk)
                context.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { th ->
                Logger.error(TAG, "Update download failed", th)
                Toast.makeText(app, "Update download failed: ${th.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Shown while an update downloads; rendered from MainActivity. */
@Composable
fun ApkDownloadDialog() {
    val p = ApkInstaller.progress ?: return
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text("Downloading update") },
        text = { LinearProgressIndicator(progress = { p }) }
    )
}
