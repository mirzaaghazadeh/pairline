package app.pairline.android.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    private fun fixture(): String =
        requireNotNull(javaClass.getResource("/github-releases.json")) { "fixture missing" }.readText()

    // ---- Versions ----

    @Test
    fun versionsCompareNumerically() {
        assertTrue(SemVer.parse("1.10.0")!! > SemVer.parse("1.9.9")!!)
        assertTrue(SemVer.parse("2.0.0")!! > SemVer.parse("1.99.99")!!)
        assertTrue(SemVer.parse("1.0.10")!! > SemVer.parse("1.0.2")!!)
        assertEquals(0, SemVer.parse("1.0.2")!!.compareTo(SemVer(1, 0, 2)))
        assertEquals("1.0.2", SemVer(1, 0, 2).toString())
    }

    @Test
    fun onlyPlainVersionsParse() {
        assertNull(SemVer.parse("1.0"))
        assertNull(SemVer.parse("v1.0.0"))
        assertNull(SemVer.parse("1.0.0-rc1"))
        assertNull(SemVer.parse("1.0.0.1"))
        assertNull(SemVer.parse("01.0.0"))
        assertNull(SemVer.parse("1.a.0"))
        assertNull(SemVer.parse(""))
    }

    @Test
    fun installedVersionDropsTheBuildSuffix() {
        assertEquals(SemVer(1, 0, 2), SemVer.installed("1.0.2-debug"))
        assertEquals(SemVer(1, 0, 2), SemVer.installed("1.0.2"))
        assertNull(SemVer.installed("dev"))
    }

    // ---- Picking the release ----

    @Test
    fun picksTheNewestCompletePublishedAndroidRelease() {
        val latest = ReleasePicker.latest(fixture())
        assertNotNull(latest)
        latest!!
        // Newer entries are skipped: a draft, a suffixed tag, an APK off GitHub, a wrongly named asset, notes without a
        // checksum, a pre-release and an iOS tag. 1.10.0 beats 1.9.0 because versions compare as numbers.
        assertEquals(SemVer(1, 10, 0), latest.version)
        assertEquals("android-v1.10.0", latest.tag)
        assertEquals("pairline-bridge-1.10.0.apk", latest.apkName)
        assertEquals(
            "https://github.com/mirzaaghazadeh/pairline/releases/download/android-v1.10.0/pairline-bridge-1.10.0.apk",
            latest.apkUrl,
        )
        assertEquals(3059797L, latest.apkSize)
        assertEquals("becaf1d7a9bdbc1d8cedcaa707b20f00c49d09daa0ca03b6919b1862cbde660f", latest.sha256)
        assertEquals("https://github.com/mirzaaghazadeh/pairline/releases/tag/android-v1.10.0", latest.notesUrl)
    }

    @Test
    fun newerOnlyWhenTheReleaseIsAboveTheInstalledVersion() {
        val latest = ReleasePicker.latest(fixture())
        assertTrue(ReleasePicker.isNewer(latest, "1.0.2"))
        assertTrue(ReleasePicker.isNewer(latest, "1.9.0-debug"))
        assertFalse(ReleasePicker.isNewer(latest, "1.10.0"))
        assertFalse(ReleasePicker.isNewer(latest, "1.10.0-debug"))
        assertFalse(ReleasePicker.isNewer(latest, "2.0.0"))
        assertFalse(ReleasePicker.isNewer(null, "1.0.0"))
        assertFalse(ReleasePicker.isNewer(latest, "not a version"))
    }

    @Test
    fun badOrEmptyListsGiveNothing() {
        assertNull(ReleasePicker.latest("[]"))
        assertNull(ReleasePicker.latest("{\"message\": \"Not Found\"}"))
        assertNull(ReleasePicker.latest("not json"))
    }

    @Test
    fun candidateSurvivesStorage() {
        val latest = ReleasePicker.latest(fixture())!!
        assertEquals(latest, ReleaseCandidate.fromJson(JSONObject(latest.toJson().toString())))
        assertNull(ReleaseCandidate.fromJson(latest.toJson().put("url", "https://evil.example.com/x.apk")))
        assertNull(ReleaseCandidate.fromJson(latest.toJson().put("sha256", "abc")))
    }

    // ---- Release notes ----

    @Test
    fun readsTheChecksumRow() {
        val sum = "d36490c31314ae858ed5131885f0b2260fb92fcc4ada088350b8e561cc9f6f62"
        assertEquals(sum, ReleaseNotes.sha256("| | |\n|---|---|\n| APK SHA-256 | `$sum` |\n| Signing certificate SHA-256 | `958c` |"))
        assertEquals(sum, ReleaseNotes.sha256("|APK SHA-256|`${sum.uppercase()}`|"))
        assertEquals(sum, ReleaseNotes.sha256("\r\n| APK SHA-256 | `$sum` |\r\n"))
    }

    @Test
    fun rejectsMissingShortOrConflictingChecksums() {
        assertNull(ReleaseNotes.sha256(null))
        assertNull(ReleaseNotes.sha256("no table here"))
        assertNull(ReleaseNotes.sha256("| APK SHA-256 | `abc123` |"))
        assertNull(ReleaseNotes.sha256("| Signing certificate SHA-256 | `${"a".repeat(64)}` |"))
        assertNull(ReleaseNotes.sha256("| APK SHA-256 | ${"a".repeat(64)} |"))
        assertNull(ReleaseNotes.sha256("| APK SHA-256 | `${"a".repeat(64)}` |\n| APK SHA-256 | `${"b".repeat(64)}` |"))
    }

    // ---- Hosts, schedule, signers ----

    @Test
    fun onlyGitHubOverHttps() {
        assertTrue(GitHubHosts.isAllowedUrl("https://api.github.com/repos/x/y/releases"))
        assertTrue(GitHubHosts.isAllowedUrl("https://release-assets.githubusercontent.com/a?b=c"))
        assertFalse(GitHubHosts.isAllowedUrl("http://github.com/x"))
        assertFalse(GitHubHosts.isAllowedUrl("https://github.com.evil.example/x"))
        assertFalse(GitHubHosts.isAllowedUrl("https://user@github.com/x"))
        assertFalse(GitHubHosts.isAllowedUrl("https://github.com:8443/x"))
        assertFalse(GitHubHosts.isAllowedUrl("not a url"))
    }

    @Test
    fun checksDailyAndRetriesSoonerAfterAFailure() {
        val now = 10 * UpdateSchedule.DAY_MS
        assertTrue(UpdateSchedule.isDue(now, 0, false))
        assertFalse(UpdateSchedule.isDue(now, now - UpdateSchedule.DAY_MS + 1, false))
        assertTrue(UpdateSchedule.isDue(now, now - UpdateSchedule.DAY_MS, false))
        assertFalse(UpdateSchedule.isDue(now, now - UpdateSchedule.RETRY_MS + 1, true))
        assertTrue(UpdateSchedule.isDue(now, now - UpdateSchedule.RETRY_MS, true))
        assertTrue("clock moved back", UpdateSchedule.isDue(now, now + 2 * UpdateSchedule.DAY_MS, false))
    }

    @Test
    fun signersMustMatchExactly() {
        assertTrue(SignerCheck.sameSigners(setOf("aa"), setOf("aa")))
        assertFalse(SignerCheck.sameSigners(setOf("aa"), setOf("bb")))
        assertFalse(SignerCheck.sameSigners(setOf("aa"), setOf("aa", "bb")))
        assertFalse(SignerCheck.sameSigners(emptySet(), emptySet()))
    }
}
