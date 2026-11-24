package app.pairline.android.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

/** Why a downloaded APK may not be installed. */
enum class VerifyFailure { CHECKSUM, SIGNATURE, PACKAGE }

/**
 * Checks a downloaded APK before Android's installer ever sees it (D-042, AGENTS.md rule 6): its SHA-256 must equal
 * the one published in the release notes, it must be this app's package with a higher version code, and it must be
 * signed by exactly the same certificate(s) as the installed app.
 */
internal object ApkVerifier {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest())
    }

    /** Null when the file may be handed to the installer. */
    fun verify(context: Context, file: File, expectedSha256: String): VerifyFailure? {
        if (!ReleaseNotes.isSha256(expectedSha256) || sha256(file) != expectedSha256) return VerifyFailure.CHECKSUM
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val candidate = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return VerifyFailure.PACKAGE
        @Suppress("DEPRECATION")
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (candidate.packageName != context.packageName) return VerifyFailure.PACKAGE
        if (candidate.longVersionCode <= installed.longVersionCode) return VerifyFailure.PACKAGE
        if (!SignerCheck.sameSigners(signers(installed), signers(candidate))) return VerifyFailure.SIGNATURE
        return null
    }

    /** SHA-256 digests of the signing certificates (Android 9+ signing info). */
    private fun signers(info: PackageInfo): Set<String> {
        val signing = info.signingInfo ?: return emptySet()
        val certs = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        return certs.orEmpty().map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
