package app.pairline.android.update

import org.json.JSONArray
import org.json.JSONObject

/** A plain `major.minor.patch` version, as in the release tags `android-v1.0.2` (D-042). */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val strict = Regex("""^(0|[1-9]\d{0,5})\.(0|[1-9]\d{0,5})\.(0|[1-9]\d{0,5})$""")

        /** Exactly `x.y.z`; anything else (a suffix, a leading `v`, missing parts) is rejected. */
        fun parse(text: String): SemVer? {
            val m = strict.matchEntire(text.trim()) ?: return null
            return SemVer(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }

        /** The installed app's version: `BuildConfig.VERSION_NAME`, with a build suffix such as `-debug` dropped. */
        fun installed(versionName: String): SemVer? = parse(versionName.substringBefore('-'))
    }
}

/** A published Android release that can be offered as an update. */
data class ReleaseCandidate(
    val version: SemVer,
    val tag: String,
    /** The release page on github.com, for "What's new". */
    val notesUrl: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    /** Lowercase hex, from the release notes' "APK SHA-256" row. */
    val sha256: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("version", version.toString())
        .put("tag", tag)
        .put("notes", notesUrl)
        .put("apk", apkName)
        .put("url", apkUrl)
        .put("size", apkSize)
        .put("sha256", sha256)

    companion object {
        fun fromJson(json: JSONObject): ReleaseCandidate? {
            val version = SemVer.parse(json.optString("version")) ?: return null
            val sha = json.optString("sha256").takeIf { ReleaseNotes.isSha256(it) } ?: return null
            val url = json.optString("url").takeIf { GitHubHosts.isAllowedUrl(it) } ?: return null
            return ReleaseCandidate(
                version = version,
                tag = json.optString("tag"),
                notesUrl = json.optString("notes"),
                apkName = json.optString("apk"),
                apkUrl = url,
                apkSize = json.optLong("size", -1),
                sha256 = sha,
            )
        }
    }
}

/** The hosts the update package may talk to (checked again by CI's network-code-check.sh). */
object GitHubHosts {
    val allowed = setOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

    fun isAllowedUrl(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host?.lowercase() in allowed && uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443)
    }
}

object ReleaseNotes {
    // The row the release workflow writes: | APK SHA-256 | `<64 hex>` |
    private val shaRow = Regex("""^\s*\|\s*APK SHA-256\s*\|\s*`([0-9A-Fa-f]{64})`\s*\|""", RegexOption.MULTILINE)

    /** The APK's SHA-256 from the release notes, lowercase, or null if the row is missing or there are two different ones. */
    fun sha256(body: String?): String? {
        if (body == null) return null
        val all = shaRow.findAll(body).map { it.groupValues[1].lowercase() }.toSet()
        return all.singleOrNull()
    }

    fun isSha256(text: String) = text.length == 64 && text.all { it in '0'..'9' || it in 'a'..'f' }
}

/** Picks the update from GitHub's release list (`GET /repos/{owner}/{repo}/releases`). Pure, so it's unit-tested. */
object ReleasePicker {
    private const val TAG_PREFIX = "android-v"

    /**
     * The newest release that is published (not a draft), not a pre-release, tagged `android-v<x.y.z>`, with the
     * asset `pairline-bridge-<x.y.z>.apk` on github.com and exactly one SHA-256 row in its notes. Releases that
     * miss any of these are skipped: an APK we can't check is never offered. Null if there is none.
     */
    fun latest(releasesJson: String): ReleaseCandidate? {
        val array = runCatching { JSONArray(releasesJson) }.getOrNull() ?: return null
        var best: ReleaseCandidate? = null
        for (i in 0 until array.length()) {
            val release = array.optJSONObject(i) ?: continue
            val candidate = candidate(release) ?: continue
            if (best == null || candidate.version > best.version) best = candidate
        }
        return best
    }

    private fun candidate(release: JSONObject): ReleaseCandidate? {
        if (release.optBoolean("draft", true) || release.optBoolean("prerelease", true)) return null
        val tag = release.optString("tag_name")
        if (!tag.startsWith(TAG_PREFIX)) return null
        val version = SemVer.parse(tag.removePrefix(TAG_PREFIX)) ?: return null
        val sha = ReleaseNotes.sha256(release.optString("body")) ?: return null
        val apkName = "pairline-bridge-$version.apk"
        val assets = release.optJSONArray("assets") ?: return null
        var asset: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name") == apkName) asset = a
        }
        val url = asset?.optString("browser_download_url")?.takeIf { it.startsWith("https://github.com/") } ?: return null
        if (!GitHubHosts.isAllowedUrl(url)) return null
        val notes = release.optString("html_url").takeIf { it.startsWith("https://github.com/") }
            ?: "https://github.com/mirzaaghazadeh/pairline/releases/tag/$tag"
        return ReleaseCandidate(version, tag, notes, apkName, url, asset.optLong("size", -1), sha)
    }

    /** Whether [candidate] should be offered to an app running [installedVersionName]. */
    fun isNewer(candidate: ReleaseCandidate?, installedVersionName: String): Boolean {
        val installed = SemVer.installed(installedVersionName) ?: return false
        return candidate != null && candidate.version > installed
    }
}

/** When the next automatic check is due (D-042): daily, or 3 hours after a failed attempt. */
object UpdateSchedule {
    const val DAY_MS = 24L * 3600 * 1000
    const val RETRY_MS = 3L * 3600 * 1000

    fun isDue(now: Long, lastAttempt: Long, lastFailed: Boolean): Boolean {
        if (lastAttempt <= 0L) return true
        if (lastAttempt > now + 3600_000L) return true // the clock went back: don't wait days
        val wait = if (lastFailed) RETRY_MS else DAY_MS
        return now - lastAttempt >= wait
    }
}

/** Compares signing certificates by their SHA-256 digests (hex). Pure. */
object SignerCheck {
    /** True only when both sets are non-empty and identical: the update is signed exactly like the installed app. */
    fun sameSigners(installed: Set<String>, candidate: Set<String>): Boolean =
        installed.isNotEmpty() && installed == candidate
}
