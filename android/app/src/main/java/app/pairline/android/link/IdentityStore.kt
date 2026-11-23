package app.pairline.android.link

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import app.pairline.android.log.Log
import app.pairline.android.protocol.P256
import app.pairline.android.protocol.Proto
import app.pairline.android.protocol.SoftwareKey
import app.pairline.android.protocol.StaticKey
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The bridge's long-term P-256 identity key and device id (PROTOCOL.md, "Roles and keys").
 *
 * - Android 12+ (API 31): the key is created inside Android Keystore with PURPOSE_AGREE_KEY and never leaves it.
 * - Android 11, or a Keystore without key agreement: the private key lives in no-backup storage, encrypted
 *   with an AES-256-GCM key that stays in Android Keystore.
 */
class IdentityStore(context: Context) {
    enum class Storage { KEYSTORE, WRAPPED }

    private val dir: File = context.noBackupFilesDir
    private val random = SecureRandom()
    private var cached: StaticKey? = null

    var storage: Storage? = null
        private set

    /** 8 random bytes, kept until the app's data is cleared. */
    val deviceId: ByteArray by lazy {
        val file = File(dir, DEVICE_ID_FILE)
        file.takeIf { it.isFile }?.readBytes()?.takeIf { it.size == Proto.DEVICE_ID_LENGTH }
            ?: ByteArray(Proto.DEVICE_ID_LENGTH).also {
                random.nextBytes(it)
                file.writeBytes(it)
            }
    }

    @Synchronized
    fun identity(): StaticKey {
        cached?.let { return it }
        val key = loadOrCreate()
        cached = key
        return key
    }

    private fun loadOrCreate(): StaticKey {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !File(dir, WRAPPED_FILE).exists()) {
            try {
                loadKeystoreKey()?.let {
                    storage = Storage.KEYSTORE
                    return it
                }
                val key = createKeystoreKey()
                storage = Storage.KEYSTORE
                Log.i(TAG, "identity key created in Android Keystore")
                return key
            } catch (e: Exception) {
                // Some phones updated to Android 12 keep an older Keymaster without key agreement.
                Log.w(TAG, "Keystore key agreement unavailable; using the wrapped key instead", e)
                runCatching { keystore().deleteEntry(IDENTITY_ALIAS) }
            }
        }
        storage = Storage.WRAPPED
        return loadOrCreateWrapped()
    }

    private fun keystore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun loadKeystoreKey(): StaticKey? {
        val entry = keystore().getEntry(IDENTITY_ALIAS, null) as? KeyStore.PrivateKeyEntry ?: return null
        return KeystoreKey(entry.privateKey, entry.certificate.publicKey as ECPublicKey)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun createKeystoreKey(): StaticKey {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(IDENTITY_ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .build(),
        )
        val pair = generator.generateKeyPair()
        val key = KeystoreKey(pair.private, pair.public as ECPublicKey)
        // Prove agreement works now, so a broken Keystore falls back before anyone pairs.
        key.agree(SoftwareKey.generate(random).let { P256.decodePublicKey(it.publicKey) })
        return key
    }

    private fun loadOrCreateWrapped(): StaticKey {
        val file = File(dir, WRAPPED_FILE)
        val wrapper = KeystoreAesWrapper(WRAP_ALIAS)
        if (file.isFile) {
            val (public, pkcs8) = WrappedKeyFile.decode(file.readBytes(), wrapper)
            val privateKey: PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            pkcs8.fill(0)
            return SoftwareKey(privateKey, P256.decodePublicKey(public))
        }
        val pair = P256.generateKeyPair(random)
        val public = P256.encodePublicKey(pair.public as ECPublicKey)
        val pkcs8 = pair.private.encoded
        val tmp = File(dir, "$WRAPPED_FILE.tmp")
        tmp.writeBytes(WrappedKeyFile.encode(public, pkcs8, wrapper))
        tmp.renameTo(file)
        pkcs8.fill(0)
        Log.i(TAG, "identity key created (wrapped with an Android Keystore key)")
        return SoftwareKey(pair.private, pair.public as ECPublicKey)
    }

    /** A Keystore-held key; ECDH runs inside Keystore. */
    private class KeystoreKey(private val privateKey: PrivateKey, publicKey: ECPublicKey) : StaticKey {
        override val publicKey: ByteArray = P256.encodePublicKey(publicKey)
        override fun agree(peer: ECPublicKey): ByteArray = P256.ecdh(privateKey, peer, ANDROID_KEYSTORE)
    }

    companion object {
        private const val TAG = "identity"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val IDENTITY_ALIAS = "pairline.identity.v0"
        private const val WRAP_ALIAS = "pairline.wrap.v0"
        private const val WRAPPED_FILE = "identity-v0.bin"
        private const val DEVICE_ID_FILE = "device-id"
    }
}

/** Encrypts small secrets with a key the caller can't read. */
interface KeyWrapper {
    /** Returns iv ‖ ciphertext ‖ tag. */
    fun wrap(plaintext: ByteArray, aad: ByteArray): ByteArray
    fun unwrap(blob: ByteArray, aad: ByteArray): ByteArray
}

/** AES-256-GCM with a non-exportable Android Keystore key; Keystore picks the IV. */
class KeystoreAesWrapper(private val alias: String) : KeyWrapper {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun wrap(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintext)
        return cipher.iv + ct
    }

    override fun unwrap(blob: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, 12, blob.size - 12)
    }
}

/** File layout for the wrapped identity key: version (1) ‖ public key (65) ‖ wrap(PKCS#8 private key). */
object WrappedKeyFile {
    const val VERSION: Byte = 1

    fun encode(publicKey: ByteArray, privatePkcs8: ByteArray, wrapper: KeyWrapper): ByteArray {
        require(publicKey.size == Proto.PUBLIC_KEY_LENGTH)
        val header = byteArrayOf(VERSION) + publicKey
        return header + wrapper.wrap(privatePkcs8, header)
    }

    fun decode(bytes: ByteArray, wrapper: KeyWrapper): Pair<ByteArray, ByteArray> {
        val headerLength = 1 + Proto.PUBLIC_KEY_LENGTH
        require(bytes.size > headerLength && bytes[0] == VERSION) { "unknown identity file format" }
        val header = bytes.copyOfRange(0, headerLength)
        val pkcs8 = wrapper.unwrap(bytes.copyOfRange(headerLength, bytes.size), header)
        return header.copyOfRange(1, headerLength) to pkcs8
    }
}
