// The TLS connection for pairing with Android's Wireless debugging (D-054).
//
// adb pairing needs RFC 5705 keying material from its TLS 1.3 connection. Android's own API for that exists only
// from Android 12, so pairing uses BoringSSL's TLS on every version: one code path, no private APIs. It connects to
// this phone itself (127.0.0.1) only; the pairing protocol on top (SPAKE2, HKDF, AES-GCM) is in
// privileged/adb/AdbPairing.kt.

#include <jni.h>

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#include <cerrno>
#include <cstdint>
#include <vector>

#include <openssl/bytestring.h>
#include <openssl/evp.h>
#include <openssl/ssl.h>
#include <openssl/x509.h>

namespace {

struct Tls {
    SSL_CTX *ctx = nullptr;
    SSL *ssl = nullptr;
    int fd = -1;
};

void destroy(Tls *t) {
    if (t == nullptr) return;
    if (t->ssl != nullptr) SSL_free(t->ssl);
    if (t->ctx != nullptr) SSL_CTX_free(t->ctx);
    if (t->fd >= 0) close(t->fd);
    delete t;
}

Tls *tls_of(jlong handle) { return reinterpret_cast<Tls *>(handle); }

// adbd's certificate isn't known in advance and isn't checked: the pairing itself is authenticated by SPAKE2 with
// the code shown on screen.
ssl_verify_result_t accept_peer(SSL *, uint8_t *) { return ssl_verify_ok; }

std::vector<uint8_t> bytes_of(JNIEnv *env, jbyteArray array) {
    jsize n = env->GetArrayLength(array);
    std::vector<uint8_t> out(static_cast<size_t>(n));
    if (n > 0) env->GetByteArrayRegion(array, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_app_pairline_android_privileged_adb_PairingTls_nativeConnect(JNIEnv *env, jclass, jint port, jbyteArray cert_der,
                                                                   jbyteArray key_pkcs8, jint timeout_ms) {
    auto *t = new Tls();
    t->fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (t->fd < 0) { destroy(t); return 0; }
    timeval tv{timeout_ms / 1000, (timeout_ms % 1000) * 1000};
    setsockopt(t->fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(t->fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));  // also bounds connect()
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<uint16_t>(port));
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (connect(t->fd, reinterpret_cast<sockaddr *>(&addr), sizeof(addr)) != 0) { destroy(t); return 0; }

    t->ctx = SSL_CTX_new(TLS_method());
    if (t->ctx == nullptr ||
        !SSL_CTX_set_min_proto_version(t->ctx, TLS1_3_VERSION) ||
        !SSL_CTX_set_max_proto_version(t->ctx, TLS1_3_VERSION)) {
        destroy(t);
        return 0;
    }
    SSL_CTX_set_custom_verify(t->ctx, SSL_VERIFY_PEER, accept_peer);

    std::vector<uint8_t> cert = bytes_of(env, cert_der);
    const uint8_t *cert_ptr = cert.data();
    X509 *x509 = d2i_X509(nullptr, &cert_ptr, static_cast<long>(cert.size()));
    std::vector<uint8_t> key = bytes_of(env, key_pkcs8);
    CBS cbs;
    CBS_init(&cbs, key.data(), key.size());
    EVP_PKEY *pkey = EVP_parse_private_key(&cbs);
    bool ok = x509 != nullptr && pkey != nullptr &&
              SSL_CTX_use_certificate(t->ctx, x509) == 1 && SSL_CTX_use_PrivateKey(t->ctx, pkey) == 1;
    if (x509 != nullptr) X509_free(x509);
    if (pkey != nullptr) EVP_PKEY_free(pkey);
    if (!ok) { destroy(t); return 0; }

    t->ssl = SSL_new(t->ctx);
    if (t->ssl == nullptr || SSL_set_fd(t->ssl, t->fd) != 1 || SSL_connect(t->ssl) != 1) { destroy(t); return 0; }
    return reinterpret_cast<jlong>(t);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_pairline_android_privileged_adb_PairingTls_nativeExportKeyingMaterial(JNIEnv *env, jclass, jlong handle,
                                                                               jbyteArray label, jint length) {
    std::vector<uint8_t> l = bytes_of(env, label);
    std::vector<uint8_t> out(static_cast<size_t>(length));
    if (SSL_export_keying_material(tls_of(handle)->ssl, out.data(), out.size(), reinterpret_cast<const char *>(l.data()),
                                   l.size(), nullptr, 0, 0) != 1) {
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(length);
    if (result != nullptr) env->SetByteArrayRegion(result, 0, length, reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_pairline_android_privileged_adb_PairingTls_nativeWrite(JNIEnv *env, jclass, jlong handle, jbyteArray data) {
    std::vector<uint8_t> d = bytes_of(env, data);
    size_t done = 0;
    while (done < d.size()) {
        int n = SSL_write(tls_of(handle)->ssl, d.data() + done, static_cast<int>(d.size() - done));
        if (n <= 0) return JNI_FALSE;
        done += static_cast<size_t>(n);
    }
    return JNI_TRUE;
}

// Exactly `length` bytes, or null when the connection ends first (adbd hangs up on a wrong code).
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_pairline_android_privileged_adb_PairingTls_nativeRead(JNIEnv *env, jclass, jlong handle, jint length) {
    std::vector<uint8_t> out(static_cast<size_t>(length));
    size_t done = 0;
    while (done < out.size()) {
        int n = SSL_read(tls_of(handle)->ssl, out.data() + done, static_cast<int>(out.size() - done));
        if (n <= 0) return nullptr;
        done += static_cast<size_t>(n);
    }
    jbyteArray result = env->NewByteArray(length);
    if (result != nullptr) env->SetByteArrayRegion(result, 0, length, reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_app_pairline_android_privileged_adb_PairingTls_nativeClose(JNIEnv *, jclass, jlong handle) {
    destroy(tls_of(handle));
}
