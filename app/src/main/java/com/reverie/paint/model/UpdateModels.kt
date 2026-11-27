/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import java.util.Locale

/**
 * GitHub Release 附件资源
 */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
    val contentType: String,
)

/**
 * 针对当前运行环境的 ABI 匹配与 APK 附件过滤策略
 */
object AbiMatcher {

    /**
     * 判断设备的主流架构（64 位 vs 32 位）
     */
    fun is64Bit(supportedAbis: Array<String>): Boolean {
        return supportedAbis.any { it.contains("64") }
    }

    /**
     * 判断某文件名是否明确属于 32 位 (armeabi-v7a 或 armv7)
     */
    fun isArm32Asset(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("armeabi-v7a") || lower.contains("armv7") || lower.contains("-v7a")
    }

    /**
     * 判断某文件名是否明确属于 64 位 (arm64-v8a 或 arm64)
     */
    fun isArm64Asset(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("arm64-v8a") || lower.contains("arm64") || lower.contains("-v8a")
    }

    /**
     * 从候选的 APK 附件列表中，根据系统支持的 ABI 列表挑选最匹配的 APK。
     *
     * 优先级规则:
     * 1. 如果当前设备为 64 位 (支持 arm64-v8a):
     *    a. 优先选用包含 "arm64-v8a" 或 "arm64" 的安装包
     *    b. 其次选用通用/无架构后缀的安装包 (如 ReveriePaint-v1.4.10.apk)
     *    c. 兜底选用 32 位安装包 (64 位 ARM 处理器通常兼容 32 位运行)
     * 2. 如果当前设备为 32 位 (不支持 64 位):
     *    a. 优先选用明确包含 "armeabi-v7a" 或 "armv7" 的安装包
     *    b. 其次选用通用/无架构后缀的安装包 (若存在 universal 构建)
     *    c. 绝不选择明确包含 arm64 的安装包 (安装时会报 INSTALL_FAILED_NO_MATCHING_ABIS)
     */
    fun selectBestApk(assets: List<ReleaseAsset>, supportedAbis: Array<String>): ReleaseAsset? {
        val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apkAssets.isEmpty()) return null

        val deviceIs64Bit = is64Bit(supportedAbis)

        if (deviceIs64Bit) {
            // 64 位设备: arm64 > universal/default > arm32
            return apkAssets.firstOrNull { isArm64Asset(it.name) }
                ?: apkAssets.firstOrNull { !isArm32Asset(it.name) }
                ?: apkAssets.firstOrNull()
        } else {
            // 32 位设备: arm32 > universal/default (排除 arm64)
            return apkAssets.firstOrNull { isArm32Asset(it.name) }
                ?: apkAssets.firstOrNull { !isArm64Asset(it.name) }
        }
    }

    /**
     * 为 Atom 订阅源等无附件列表的场景，构建回退的 APK 下载链接与文件名
     */
    fun buildFallbackApkAsset(tagName: String, supportedAbis: Array<String>): ReleaseAsset {
        val is64 = is64Bit(supportedAbis)
        // 64 位默认下载通用/arm64-v8a 包；32 位下载 -armeabi-v7a 包
        val apkName = if (is64) {
            "ReveriePaint-$tagName.apk"
        } else {
            "ReveriePaint-$tagName-armeabi-v7a.apk"
        }
        val downloadUrl = "https://github.com/LanRhyme/ReveriePaint/releases/download/$tagName/$apkName"
        return ReleaseAsset(
            name = apkName,
            downloadUrl = downloadUrl,
            size = 0L,
            contentType = "application/vnd.android.package-archive",
        )
    }
}

/**
 * GitHub Release 详情信息
 */
data class ReleaseInfo(
    val tagName: String,
    val name: String,
    val body: String,
    val htmlUrl: String,
    val publishedAt: String,
    val apkAsset: ReleaseAsset?,
)

/**
 * APK 下载状态
 */
enum class DownloadStatus {
    IDLE,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELED,
}

/**
 * 版本检查结果
 */
sealed class UpdateCheckResult {
    data class NewVersion(val release: ReleaseInfo) : UpdateCheckResult()
    data object AlreadyLatest : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

/**
 * 版本号解析与比较工具
 */
object VersionComparator {

    data class ParsedVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val isPrerelease: Boolean,
        val prereleaseType: String,
        val prereleaseNum: Int,
        val raw: String,
    ) : Comparable<ParsedVersion> {
        override fun compareTo(other: ParsedVersion): Int {
            if (major != other.major) return major.compareTo(other.major)
            if (minor != other.minor) return minor.compareTo(other.minor)
            if (patch != other.patch) return patch.compareTo(other.patch)

            // 核心版本号相同情况下，正式版大于预览/预发布版
            if (!isPrerelease && other.isPrerelease) return 1
            if (isPrerelease && !other.isPrerelease) return -1
            if (!isPrerelease && !other.isPrerelease) return 0

            // 均为预发布版
            val typeComp = prereleaseType.compareTo(other.prereleaseType)
            if (typeComp != 0) return typeComp
            return prereleaseNum.compareTo(other.prereleaseNum)
        }
    }

    private val prereleaseRegex = Regex("""([a-zA-Z]+)[._-]?(\d+)?""")

    fun parse(versionStr: String): ParsedVersion {
        val clean = versionStr.trim().removePrefix("v").removePrefix("V")
        val dashIndex = clean.indexOf('-')

        val corePart: String
        val prePart: String?

        if (dashIndex >= 0) {
            corePart = clean.substring(0, dashIndex)
            prePart = clean.substring(dashIndex + 1)
        } else {
            corePart = clean
            prePart = null
        }

        val segs = corePart.split('.')
        val major = segs.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = segs.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = segs.getOrNull(2)?.toIntOrNull() ?: 0

        val isPrerelease = !prePart.isNullOrBlank()
        var prereleaseType = ""
        var prereleaseNum = 0

        if (prePart != null) {
            val match = prereleaseRegex.find(prePart)
            if (match != null) {
                prereleaseType = match.groupValues.getOrNull(1)?.lowercase() ?: ""
                prereleaseNum = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            } else {
                prereleaseType = prePart.lowercase()
            }
        }

        return ParsedVersion(
            major = major,
            minor = minor,
            patch = patch,
            isPrerelease = isPrerelease,
            prereleaseType = prereleaseType,
            prereleaseNum = prereleaseNum,
            raw = versionStr,
        )
    }

    /**
     * 判断 remoteTag 是否比 currentVersion 新
     */
    fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
        val remote = parse(remoteTag)
        val current = parse(currentVersion)
        return remote > current
    }
}

/**
 * 双语更新日志解析与按语言裁剪工具
 */
object ReleaseNotesLocalizer {
    private val EN_MARKERS = setOf("english", "en", "en-us", "english (en)")
    private val ZH_MARKERS = setOf("简体中文", "中文", "chinese", "zh", "zh-cn", "简体中文 (zh-cn)", "中文 (简体)")
    private val HEADING_LINE_REGEX = Regex("""^#{1,2}\s+(.+?)\s*$""")
    private val HR_LINE_REGEX = Regex("""^\s*(?:[-*_]\s*){3,}$""")
    private val SWITCHER_LINE_REGEX = Regex("""^\s*\[English]\([^)]+\)\s*\|\s*\[简体中文]\([^)]+\)\s*$""", RegexOption.IGNORE_CASE)

    fun shouldPreferChinese(locale: Locale): Boolean {
        val lang = locale.language.lowercase(Locale.ROOT)
        return lang == "zh" || lang == "cat"
    }

    fun selectLanguageSection(body: String, preferChinese: Boolean): String {
        val normalized = body.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isEmpty()) return ""

        val enLines = mutableListOf<String>()
        val zhLines = mutableListOf<String>()
        var currentTarget: MutableList<String>? = null
        var sawAnyLanguageHeader = false

        for (line in normalized.split('\n')) {
            val match = HEADING_LINE_REGEX.matchEntire(line.trim())
            val titleKey = match?.groupValues?.get(1)?.trim()?.lowercase(Locale.ROOT)
            when {
                titleKey in EN_MARKERS -> {
                    sawAnyLanguageHeader = true
                    currentTarget = enLines
                }
                titleKey in ZH_MARKERS -> {
                    sawAnyLanguageHeader = true
                    currentTarget = zhLines
                }
                else -> currentTarget?.add(line)
            }
        }

        if (!sawAnyLanguageHeader) return normalized

        val enText = trimSectionEdges(enLines)
        val zhText = trimSectionEdges(zhLines)
        return when {
            preferChinese && zhText.isNotEmpty() -> zhText
            !preferChinese && enText.isNotEmpty() -> enText
            zhText.isNotEmpty() -> zhText
            enText.isNotEmpty() -> enText
            else -> normalized
        }
    }

    fun stripDocumentHeader(markdown: String): String {
        val normalized = markdown.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n').toMutableList()
        while (lines.isNotEmpty() && lines.first().isBlank()) {
            lines.removeAt(0)
        }
        if (lines.isNotEmpty() && lines.first().trimStart().startsWith("# ")) {
            lines.removeAt(0)
        }
        while (lines.isNotEmpty() && lines.first().isBlank()) {
            lines.removeAt(0)
        }
        if (lines.isNotEmpty() && SWITCHER_LINE_REGEX.matches(lines.first())) {
            lines.removeAt(0)
        }
        while (lines.isNotEmpty() && (lines.first().isBlank() || HR_LINE_REGEX.matches(lines.first()))) {
            lines.removeAt(0)
        }
        return lines.joinToString("\n").trim()
    }

    private fun trimSectionEdges(lines: List<String>): String {
        var start = 0
        var end = lines.size
        while (start < end && (lines[start].isBlank() || HR_LINE_REGEX.matches(lines[start]))) {
            start++
        }
        while (end > start && (lines[end - 1].isBlank() || HR_LINE_REGEX.matches(lines[end - 1]))) {
            end--
        }
        return if (start < end) lines.subList(start, end).joinToString("\n").trim() else ""
    }
}

/**
 * 聚合从当前版本到最新版本之间的更新日志
 */
object ReleaseNotesAggregator {
    fun mergeNewerReleaseNotes(
        releases: List<Pair<String, String>>,
        currentVersion: String,
        preferChinese: Boolean? = null,
    ): String {
        val newer = releases
            .mapNotNull { (tag, body) ->
                if (tag.isBlank() || body.isBlank() || !VersionComparator.isNewerVersion(tag, currentVersion)) {
                    null
                } else {
                    val localized = if (preferChinese != null) {
                        ReleaseNotesLocalizer.selectLanguageSection(body, preferChinese)
                    } else {
                        body.trim()
                    }
                    if (localized.isBlank()) null else (tag to localized)
                }
            }
            .sortedWith { a, b ->
                VersionComparator.parse(b.first).compareTo(VersionComparator.parse(a.first))
            }
        if (newer.isEmpty()) return ""
        if (newer.size == 1) return newer.first().second.trim()
        return newer.joinToString("\n\n---\n\n") { (tag, body) ->
            val heading = if (tag.startsWith("v", ignoreCase = true)) tag else "v$tag"
            "## $heading\n\n${body.trim()}"
        }
    }
}
