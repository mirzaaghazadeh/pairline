package app.pairline.android.privileged.adb

import java.io.Closeable

/**
 * BoringSSL's SPAKE2 in the role of adb's pairing client (src/main/cpp/spake2_jni.cpp, D-054). One instance per
 * pairing attempt.
 */
internal class Spake2 private constructor(private var handle: Long) : Closeable {

    /** Our SPAKE2 message for [password] (the 6-digit code followed by the TLS keying material). */
    fun generateMessage(password: ByteArray): ByteArray =
        nativeGenerateMessage(handle, password) ?: throw AdbPairingException("SPAKE2 couldn't start")

    /** The shared key from adbd's message, or null when adbd's message isn't valid. */
    fun processMessage(theirs: ByteArray): ByteArray? = nativeProcessMessage(handle, theirs)

    override fun close() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    companion object {
        /** False on a processor the APK has no native library for; pairing then isn't possible. */
        val available: Boolean by lazy { runCatching { System.loadLibrary("pairline_adb") }.isSuccess }

        fun create(): Spake2 {
            if (!available) throw AdbPairingException("pairing isn't available on this phone's processor")
            val handle = nativeCreate()
            if (handle == 0L) throw AdbPairingException("SPAKE2 couldn't start")
            return Spake2(handle)
        }

        @JvmStatic private external fun nativeCreate(): Long

        @JvmStatic private external fun nativeGenerateMessage(handle: Long, password: ByteArray): ByteArray?

        @JvmStatic private external fun nativeProcessMessage(handle: Long, theirs: ByteArray): ByteArray?

        @JvmStatic private external fun nativeFree(handle: Long)
    }
}

/** Pairing failed for a reason other than a wrong code. */
internal open class AdbPairingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** adbd couldn't decrypt our message, or we couldn't decrypt its: the code was wrong (or expired). */
internal class AdbWrongCodeException : AdbPairingException("wrong pairing code")
