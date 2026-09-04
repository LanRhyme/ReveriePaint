/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

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
