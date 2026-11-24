package app.pairline.android.privileged.adb

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.File
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Pairline's key for Wireless debugging (D-054): a 2048-bit RSA key that the phone's adbd learns when the user pairs
 * and then accepts for TLS connections. adb needs the raw key (TLS client signatures and its own key format), so it
 * is a software key stored encrypted with an Android Keystore AES key, in no-backup storage: it never leaves the
 * phone and is deleted by "Forget pairing".
 */
internal class AdbKey private constructor(private val privateKey: RSAPrivateCrtKey) {

    private val publicKey: RSAPublicKey = KeyFactory.getInstance("RSA")
        .generatePublic(RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)) as RSAPublicKey

    /** The certificate, DER-encoded (for pairing's BoringSSL TLS, [PairingTls]). */
    val certificateDer: ByteArray by lazy { AdbKeyFormat.selfSignedCertificate(publicKey, privateKey, NAME) }

    /** The private key, PKCS#8 (for pairing's BoringSSL TLS; it stays in this process). */
    val privateKeyPkcs8: ByteArray get() = privateKey.encoded

    private val certificate: X509Certificate by lazy {
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certificateDer)) as X509Certificate
    }

    /** The public key as adbd stores it; "Pairline" is the name shown under Wireless debugging › Paired devices. */
    val adbPublicKey: ByteArray by lazy { AdbKeyFormat.adbPublicKey(publicKey, NAME) }

    /**
     * TLS 1.3 with this key as the client certificate. adbd's own certificate isn't known in advance and isn't
     * checked: pairing is authenticated by SPAKE2 with the code on screen, the connection by adbd accepting only
     * paired keys, and both only ever go to this phone itself.
     */
    val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(keyManager()), arrayOf(TrustAdbd), SecureRandom()) }
    }

    private fun keyManager() = object : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == ALIAS) arrayOf(certificate) else null
        override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) privateKey else null
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object TrustAdbd : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        /** Shown by Android under Wireless debugging › Paired devices. */
        const val NAME = "Pairline"
        private const val ALIAS = "pairline"
        private const val WRAP_ALIAS = "pairline_adb_key_wrap"
        private const val FILE = "adb_key.bin"
        private const val IV_BYTES = 12

        private fun file(context: Context) = File(context.noBackupFilesDir, FILE)

        fun exists(context: Context): Boolean = file(context).exists()

        @Synchronized
        fun loadOrCreate(context: Context): AdbKey {
            val f = file(context)
            if (f.exists()) {
                runCatching { return AdbKey(decrypt(f.readBytes())) }
                // Unreadable (the Keystore key was reset): start over; the user pairs again.
                f.delete()
            }
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            val key = generator.generateKeyPair().private as RSAPrivateCrtKey
            f.writeBytes(encrypt(key.encoded))
            return AdbKey(key)
        }

        /** "Forget pairing": the key goes; adbd's copy is removed by the user under Paired devices. */
        @Synchronized
        fun delete(context: Context) {
            file(context).delete()
            runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(WRAP_ALIAS) }
        }

        private fun wrapKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
            val spec = KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(spec)
                generateKey()
            }
        }

        private fun encrypt(plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
            return cipher.iv + cipher.doFinal(plain)
        }

        private fun decrypt(stored: ByteArray): RSAPrivateCrtKey {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, stored, 0, IV_BYTES))
            val pkcs8 = cipher.doFinal(stored, IV_BYTES, stored.size - IV_BYTES)
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8)) as RSAPrivateCrtKey
        }
    }
}
