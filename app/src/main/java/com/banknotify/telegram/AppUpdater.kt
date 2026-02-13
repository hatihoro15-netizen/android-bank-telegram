package com.banknotify.telegram

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class AppUpdater(private val context: Context) {

    companion object {
        private const val TAG = "AppUpdater"
        private const val GITHUB_API = "https://api.github.com/repos/hatihoro15-netizen/android-bank-telegram/releases/latest"
    }

    fun checkAndUpdate() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val release = fetchLatestRelease() ?: return@launch
                val remoteVersion = release.first
                val downloadUrl = release.second

                val currentVersion = getCurrentVersionCode()
                Log.d(TAG, "Current: $currentVersion, Remote: $remoteVersion")

                if (remoteVersion > currentVersion) {
                    withContext(Dispatchers.Main) {
                        showUpdateDialog(remoteVersion, downloadUrl)
                    }
                } else {
                    Log.d(TAG, "App is up to date")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
            }
        }
    }

    private fun getCurrentVersionCode(): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun fetchLatestRelease(): Pair<Int, String>? {
        val url = URL(GITHUB_API)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        return try {
            if (conn.responseCode != 200) {
                Log.e(TAG, "GitHub API returned ${conn.responseCode}")
                return null
            }

            val response = conn.inputStream.bufferedReader().readText()
            val json = org.json.JSONObject(response)

            // tag_name에서 버전코드 추출 (예: "v2" → 2, "v1.1-build3" → 3)
            val tagName = json.getString("tag_name")
            val versionCode = extractVersionCode(tagName)

            // APK 다운로드 URL 찾기
            val assets = json.getJSONArray("assets")
            var apkUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }

            if (apkUrl.isBlank() || versionCode <= 0) {
                Log.e(TAG, "No APK found or invalid version: tag=$tagName, url=$apkUrl")
                return null
            }

            Pair(versionCode, apkUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse release", e)
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun extractVersionCode(tag: String): Int {
        // "v2" → 2, "v1.1-build5" → 5, "build-3" → 3
        val buildMatch = Regex("""build[.-]?(\d+)""").find(tag)
        if (buildMatch != null) return buildMatch.groupValues[1].toIntOrNull() ?: 0

        // "v2" → 2, "v10" → 10
        val vMatch = Regex("""v(\d+)""").find(tag)
        if (vMatch != null) return vMatch.groupValues[1].toIntOrNull() ?: 0

        // 숫자만 있는 경우
        return tag.filter { it.isDigit() }.toIntOrNull() ?: 0
    }

    private fun showUpdateDialog(remoteVersion: Int, downloadUrl: String) {
        if (context is android.app.Activity && !context.isFinishing) {
            AlertDialog.Builder(context)
                .setTitle("새 업데이트가 있습니다")
                .setMessage("새 버전(빌드 $remoteVersion)이 있습니다.\n지금 업데이트하시겠습니까?")
                .setPositiveButton("업데이트") { _, _ ->
                    downloadAndInstall(downloadUrl)
                }
                .setNegativeButton("나중에", null)
                .setCancelable(false)
                .show()
        }
    }

    private fun downloadAndInstall(downloadUrl: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "업데이트 다운로드 중...", android.widget.Toast.LENGTH_LONG).show()
                }

                val dir = File(context.getExternalFilesDir(null), "updates")
                if (!dir.exists()) dir.mkdirs()
                val apkFile = File(dir, "update.apk")

                val url = URL(downloadUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 30000
                conn.readTimeout = 60000

                conn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        input.copyTo(output)
                    }
                }
                conn.disconnect()

                Log.d(TAG, "APK downloaded: ${apkFile.length()} bytes")

                withContext(Dispatchers.Main) {
                    installApk(apkFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "다운로드 실패: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun installApk(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
