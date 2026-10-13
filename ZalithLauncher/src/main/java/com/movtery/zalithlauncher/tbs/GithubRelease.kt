package com.movtery.zalithlauncher.tbs

import com.movtery.zalithlauncher.upgrade.RemoteData
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** TBS Launcher releases ship as GitHub Releases here; the in-app update check reads them. */
const val TBS_RELEASES_API = "https://api.github.com/repos/slashdaemon/TBS-Launcher-Releases/releases?per_page=10"

/** The subset of a GitHub release the update check needs. */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("name") val name: String? = null,
    @SerialName("body") val body: String? = null,
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("assets") val assets: List<Asset> = emptyList(),
) {
    @Serializable
    data class Asset(
        @SerialName("name") val name: String,
        @SerialName("browser_download_url") val url: String,
        @SerialName("size") val size: Long = 0L,
        /** "sha256:<hex>" (GitHub fills this for uploaded assets). */
        @SerialName("digest") val digest: String? = null,
    )

    /** launcher_version_code for this tag: vX.Y.Z -> X*1000000 + Y*10000 + Z (0.2.0 = 20000). */
    val versionCode: Int?
        get() = tagVersionCode(tagName)

    /** Into the upstream update dialog's model; null when the release has no launcher APK. */
    fun toRemoteData(): RemoteData? {
        val code = versionCode ?: return null
        val apks = assets.filter { it.name.endsWith(".apk") }
        if (apks.isEmpty()) return null
        apks.forEach { a -> a.digest?.removePrefix("sha256:")?.let { ApkDigests.put(a.url, it) } }
        return RemoteData(
            code = code,
            version = tagName.removePrefix("v"),
            createdAt = publishedAt ?: "",
            files = apks.map { a ->
                RemoteData.RemoteFile(fileName = a.name, uri = a.url, arch = archOf(a.name), size = a.size)
            },
            defaultBody = RemoteData.RemoteBody(language = "NULL", markdown = body ?: (name ?: tagName)),
            bodies = emptyList(),
        )
    }

    companion object {
        fun tagVersionCode(tag: String): Int? {
            val m = Regex("""^v?(\d+)\.(\d+)\.(\d+)""").find(tag) ?: return null
            val (x, y, z) = m.destructured
            return x.toInt() * 1_000_000 + y.toInt() * 10_000 + z.toInt()
        }

        private fun archOf(fileName: String): RemoteData.RemoteFile.Arch = when {
            fileName.contains("arm64-v8a") -> RemoteData.RemoteFile.Arch.ARM64
            fileName.contains("x86_64") -> RemoteData.RemoteFile.Arch.X86_64
            fileName.contains("armeabi-v7a") -> RemoteData.RemoteFile.Arch.ARM
            fileName.contains("-x86") -> RemoteData.RemoteFile.Arch.X86
            else -> RemoteData.RemoteFile.Arch.ALL
        }
    }
}

/** sha256 digests of release APKs by download URL, filled when the feed is read. */
object ApkDigests {
    private val byUrl = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun put(url: String, sha256: String) { byUrl[url] = sha256.lowercase() }
    fun get(url: String): String? = byUrl[url]
}
