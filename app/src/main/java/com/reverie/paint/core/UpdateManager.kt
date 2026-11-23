/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.reverie.paint.BuildConfig
import com.reverie.paint.model.AbiMatcher
import com.reverie.paint.model.DownloadStatus
import com.reverie.paint.model.MarkdownParser
import com.reverie.paint.model.ReleaseAsset
import com.reverie.paint.model.ReleaseInfo
import com.reverie.paint.model.ReleaseNotesAggregator
import com.reverie.paint.model.UpdateCheckResult
import com.reverie.paint.model.VersionComparator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Xml
import androidx.core.text.HtmlCompat
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * GitHub Releases API 版本检测与应用内下载更新管理器
 */
object UpdateManager {

    private const val GITHUB_RELEASES_LIST_URL =
        "https://api.github.com/repos/LanRhyme/ReveriePaint/releases?per_page=20"

    private const val GITHUB_LATEST_RELEASE_URL =
        "https://api.github.com/repos/LanRhyme/ReveriePaint/releases/latest"

    private const val GITHUB_RELEASES_ATOM_URL =
        "https://github.com/LanRhyme/ReveriePaint/releases.atom"

    const val MIRRORCHYAN_PROJECT_URL =
        "https://mirrorchyan.com/zh/projects?rid=ReveriePaint&os=android"

    private const val MIRRORCHYAN_LATEST_API =
        "https://mirrorchyan.com/api/resources/ReveriePaint/latest?os=android"

    private const val PREFS_KEY_AUTO_CHECK = "auto_check_updates"

    /**
     * 测试版 (`.beta` 包名或 `-test` 版本后缀) 不接入稳定版更新通道,
     * 避免测试构建被稳定版更新弹窗打断.
     */
    val isUpdatePromptDisabled: Boolean
        get() = BuildConfig.APPLICATION_ID.endsWith(".beta") ||
            BuildConfig.VERSION_NAME.contains("-test")

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var activeDownloadCall: Call? = null
    private var downloadJob: Job? = null

    // Compose 观察状态
    var isChecking by mutableStateOf(false)
        private set

    var availableUpdate by mutableStateOf<ReleaseInfo?>(null)
        private set

    var showUpdateDialog by mutableStateOf(false)

    var downloadStatus by mutableStateOf(DownloadStatus.IDLE)
        private set

    var downloadProgress by mutableFloatStateOf(0f)
        private set

    var downloadedBytes by mutableLongStateOf(0L)
        private set

    var totalBytes by mutableLongStateOf(0L)
        private set

    var downloadError by mutableStateOf<String?>(null)
        private set

    var downloadedApkFile by mutableStateOf<File?>(null)
        private set

    fun isAutoCheckEnabled(context: Context): Boolean {
        return context.getSharedPreferences("paint_prefs", Context.MODE_PRIVATE)
            .getBoolean(PREFS_KEY_AUTO_CHECK, true)
    }

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("paint_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREFS_KEY_AUTO_CHECK, enabled)
            .apply()
    }

    fun dismissDialog() {
        showUpdateDialog = false
    }

    /**
     * 检查新版本
     * @param context 上下文
     * @param isManual 是否手动触发（如果是手动触发，未检测到或失败时会弹 Toast 提示）
     */
    /**
     * 检查新版本
     * 优先尝试 GitHub REST API；若因 IP 频控（HTTP 403 Rate Limit）或网络问题失败，
     * 自动无缝降级至官方 Releases Atom 订阅源，彻底避免 60次/小时 速率限制导致的检查失败。
     */
    fun checkForUpdates(
        context: Context,
        isManual: Boolean,
        onResult: ((UpdateCheckResult) -> Unit)? = null,
    ) {
        if (isUpdatePromptDisabled) {
            if (isManual) {
                Toast.makeText(
                    context,
                    context.getString(com.reverie.paint.R.string.settings_update_disabled_test),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            onResult?.invoke(UpdateCheckResult.AlreadyLatest)
            return
        }
        if (isChecking) return
        isChecking = true

        if (isManual) {
            Toast.makeText(context, "正在检查更新...", Toast.LENGTH_SHORT).show()
        }

        scope.launch(Dispatchers.IO) {
            try {
                val currentVersion = BuildConfig.VERSION_NAME
                val release = fetchReleaseFromApi(currentVersion)
                    ?: fetchReleaseFromAtomFeed(currentVersion)
                    ?: fetchReleaseFromMirrorChyan()

                if (release == null) {
                    withContext(Dispatchers.Main) {
                        isChecking = false
                        val errorMsg = "获取版本信息失败，请检查网络设置"
                        onResult?.invoke(UpdateCheckResult.Error(errorMsg))
                        if (isManual) {
                            Toast.makeText(context, "检查更新失败: $errorMsg", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@launch
                }

                val hasNew = VersionComparator.isNewerVersion(release.tagName, currentVersion)

                withContext(Dispatchers.Main) {
                    isChecking = false
                    if (hasNew) {
                        availableUpdate = release
                        showUpdateDialog = true
                        onResult?.invoke(UpdateCheckResult.NewVersion(release))
                    } else {
                        onResult?.invoke(UpdateCheckResult.AlreadyLatest)
                        if (isManual) {
                            Toast.makeText(context, "当前已是最新版本 (v$currentVersion)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isChecking = false
                    val errorMsg = e.localizedMessage ?: "网络连接异常"
                    onResult?.invoke(UpdateCheckResult.Error(errorMsg))
                    if (isManual) {
                        Toast.makeText(context, "检查更新失败: $errorMsg", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun fetchReleaseFromApi(currentVersion: String): ReleaseInfo? {
        return fetchReleaseListFromApi(currentVersion) ?: fetchSingleLatestReleaseFromApi()
    }

    private fun parseReleaseObject(json: JSONObject): ReleaseInfo? {
        val tagName = json.optString("tag_name", "")
        if (tagName.isBlank()) return null
        val name = json.optString("name", tagName)
        val body = json.optString("body", "")
        val htmlUrl = json.optString("html_url", "https://github.com/LanRhyme/ReveriePaint/releases")
        val publishedAt = json.optString("published_at", "")

        val assetsJson = json.optJSONArray("assets")
        val candidateAssets = mutableListOf<ReleaseAsset>()
        if (assetsJson != null) {
            for (i in 0 until assetsJson.length()) {
                val assetObj = assetsJson.optJSONObject(i) ?: continue
                val assetName = assetObj.optString("name", "")
                val downloadUrl = assetObj.optString("browser_download_url", "")
                val size = assetObj.optLong("size", 0L)
                val contentType = assetObj.optString("content_type", "")

                if (assetName.endsWith(".apk", ignoreCase = true)) {
                    candidateAssets.add(
                        ReleaseAsset(
                            name = assetName,
                            downloadUrl = downloadUrl,
                            size = size,
                            contentType = contentType,
                        )
                    )
                }
            }
        }

        val apkAsset = AbiMatcher.selectBestApk(candidateAssets, Build.SUPPORTED_ABIS)

        return ReleaseInfo(
            tagName = tagName,
            name = name,
            body = body,
            htmlUrl = htmlUrl,
            publishedAt = publishedAt,
            apkAsset = apkAsset,
        )
    }

    private fun fetchReleaseListFromApi(currentVersion: String): ReleaseInfo? {
        try {
            val request = Request.Builder()
                .url(GITHUB_RELEASES_LIST_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "ReveriePaint-Android")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return null

            val bodyStr = response.body?.string() ?: return null
            val array = JSONArray(bodyStr)
            var latestRelease: ReleaseInfo? = null
            val notesPairs = mutableListOf<Pair<String, String>>()

            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                if (obj.optBoolean("draft", false) || obj.optBoolean("prerelease", false)) continue
                val parsed = parseReleaseObject(obj) ?: continue
                if (latestRelease == null) {
                    latestRelease = parsed
                }
                notesPairs.add(parsed.tagName to parsed.body)
            }

            val latest = latestRelease ?: return null
            val mergedNotes = ReleaseNotesAggregator.mergeNewerReleaseNotes(notesPairs, currentVersion)
            return if (mergedNotes.isNotBlank()) latest.copy(body = mergedNotes) else latest
        } catch (_: Exception) {
            return null
        }
    }

    private fun fetchSingleLatestReleaseFromApi(): ReleaseInfo? {
        try {
            val request = Request.Builder()
                .url(GITHUB_LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "ReveriePaint-Android")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return null

            val bodyStr = response.body?.string() ?: return null
            return parseReleaseObject(JSONObject(bodyStr))
        } catch (_: Exception) {
            return null
        }
    }

    private fun fetchReleaseFromAtomFeed(currentVersion: String): ReleaseInfo? {
        try {
            val request = Request.Builder()
                .url(GITHUB_RELEASES_ATOM_URL)
                .header("User-Agent", "ReveriePaint-Android")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            val xmlStr = response.body?.string() ?: return null

            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xmlStr))
            var eventType = parser.eventType
            var inEntry = false
            var title = ""
            var updated = ""
            var contentHtml = ""
            var htmlUrl = ""

            data class AtomEntry(
                val tag: String,
                val updated: String,
                val body: String,
                val htmlUrl: String,
            )

            val entries = mutableListOf<AtomEntry>()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val name = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (name == "entry") {
                            inEntry = true
                            title = ""
                            updated = ""
                            contentHtml = ""
                            htmlUrl = ""
                        } else if (inEntry) {
                            when (name) {
                                "title" -> if (title.isEmpty()) title = parser.nextText()
                                "updated" -> if (updated.isEmpty()) updated = parser.nextText()
                                "content" -> if (contentHtml.isEmpty()) contentHtml = parser.nextText()
                                "link" -> {
                                    if (htmlUrl.isEmpty()) {
                                        val rel = parser.getAttributeValue(null, "rel")
                                        if (rel == "alternate" || rel == null) {
                                            htmlUrl = parser.getAttributeValue(null, "href") ?: ""
                                        }
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (name == "entry" && inEntry) {
                            inEntry = false
                            val tag = title.trim()
                            if (tag.isNotBlank()) {
                                val convertedBody = MarkdownParser.htmlToMarkdown(contentHtml)
                                val finalBody = if (convertedBody.isNotBlank()) {
                                    convertedBody
                                } else {
                                    HtmlCompat.fromHtml(contentHtml, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
                                }
                                entries.add(AtomEntry(tag, updated, finalBody, htmlUrl))
                            }
                        }
                    }
                }
                eventType = parser.next()
            }

            val first = entries.firstOrNull() ?: return null
            val mergedNotes = ReleaseNotesAggregator.mergeNewerReleaseNotes(
                entries.map { it.tag to it.body },
                currentVersion,
            )
            val apkAsset = AbiMatcher.buildFallbackApkAsset(first.tag, Build.SUPPORTED_ABIS)

            return ReleaseInfo(
                tagName = first.tag,
                name = first.tag,
                body = mergedNotes.ifBlank { first.body },
                htmlUrl = first.htmlUrl.ifBlank { "https://github.com/LanRhyme/ReveriePaint/releases/tag/${first.tag}" },
                publishedAt = first.updated,
                apkAsset = apkAsset,
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun fetchReleaseFromMirrorChyan(): ReleaseInfo? {
        try {
            val request = Request.Builder()
                .url(MIRRORCHYAN_LATEST_API)
                .header("User-Agent", "ReveriePaint-Android")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return null

            val bodyStr = response.body?.string() ?: return null
            val json = JSONObject(bodyStr)
            if (json.optInt("code", -1) != 0) return null

            val data = json.optJSONObject("data") ?: return null
            val versionName = data.optString("version_name", "")
            if (versionName.isBlank()) return null
            val releaseNote = data.optString("release_note", "")

            return ReleaseInfo(
                tagName = versionName,
                name = versionName,
                body = releaseNote,
                htmlUrl = MIRRORCHYAN_PROJECT_URL,
                publishedAt = "",
                apkAsset = null,
            )
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * 开始应用内下载 APK
     */
    fun startDownload(context: Context, release: ReleaseInfo) {
        val asset = release.apkAsset ?: run {
            Toast.makeText(context, "未找到有效的 APK 安装包资源", Toast.LENGTH_SHORT).show()
            return
        }

        if (downloadStatus == DownloadStatus.DOWNLOADING) return

        downloadStatus = DownloadStatus.DOWNLOADING
        downloadProgress = 0f
        downloadedBytes = 0L
        totalBytes = if (asset.size > 0L) asset.size else 1L
        downloadError = null
        downloadedApkFile = null

        val updatesDir = File(context.cacheDir, "updates")
        if (!updatesDir.exists()) {
            updatesDir.mkdirs()
        }

        val apkFileName = asset.name.ifBlank { "ReveriePaint-update.apk" }
        val targetFile = File(updatesDir, apkFileName)
        val tempFile = File(updatesDir, "$apkFileName.part")

        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                if (tempFile.exists()) tempFile.delete()
                if (targetFile.exists()) targetFile.delete()

                val request = Request.Builder()
                    .url(asset.downloadUrl)
                    .header("User-Agent", "ReveriePaint-Android")
                    .build()

                val call = okHttpClient.newCall(request)
                activeDownloadCall = call

                val response = call.execute()
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}")
                }

                val body = response.body ?: throw IOException("下载内容为空")
                val contentLength = body.contentLength()
                if (contentLength > 0L) {
                    totalBytes = contentLength
                }

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var curBytes = 0L
                        var lastProgressUpdate = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            curBytes += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdate > 100 || curBytes == totalBytes) {
                                lastProgressUpdate = now
                                withContext(Dispatchers.Main) {
                                    downloadedBytes = curBytes
                                    downloadProgress = (curBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                                }
                            }
                        }
                        output.flush()
                    }
                }

                if (tempFile.renameTo(targetFile)) {
                    withContext(Dispatchers.Main) {
                        downloadedApkFile = targetFile
                        downloadStatus = DownloadStatus.COMPLETED
                        downloadProgress = 1f
                        // 下载成功自动触发安装
                        installApk(context, targetFile)
                    }
                } else {
                    throw IOException("文件命名失败")
                }
            } catch (e: Exception) {
                if (tempFile.exists()) tempFile.delete()
                withContext(Dispatchers.Main) {
                    if (callCancelled) {
                        downloadStatus = DownloadStatus.CANCELED
                    } else {
                        downloadStatus = DownloadStatus.FAILED
                        downloadError = e.localizedMessage ?: "下载失败"
                    }
                }
            } finally {
                activeDownloadCall = null
                callCancelled = false
            }
        }
    }

    private var callCancelled = false

    /**
     * 取消当前下载
     */
    fun cancelDownload() {
        callCancelled = true
        activeDownloadCall?.cancel()
        downloadJob?.cancel()
        downloadStatus = DownloadStatus.CANCELED
    }

    /**
     * 重置下载状态
     */
    fun resetDownload() {
        downloadStatus = DownloadStatus.IDLE
        downloadProgress = 0f
        downloadedBytes = 0L
        downloadError = null
    }

    /**
     * 拉起系统安装器安装 APK
     */
    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) {
            Toast.makeText(context, "安装包不存在", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, apkFile)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // 检查是否有未知来源安装权限 (Android 8.0+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                    Toast.makeText(context, "请授予安装未知应用权限后返回安装", Toast.LENGTH_LONG).show()
                    return
                }
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法拉起安装器: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
}
