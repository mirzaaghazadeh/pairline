// The SPAKE2 step of pairing with Android's Wireless debugging (D-054).
//
// Android's adb pairs a client with SPAKE2 over a TLS connection: both sides derive a key from the 6-digit code
// plus TLS keying material, so a wrong code (or someone in the middle) can't pair. SPAKE2 isn't in Android's crypto
// APIs, so this uses BoringSSL's own implementation with the same role and names as AOSP's adb
// (packages/modules/adb/pairing_auth). Everything else in pairing (TLS, HKDF, AES-GCM) is done in Kotlin with the
// platform's crypto (privileged/adb/AdbPairing.kt).

#include <jni.h>

#include <cstdint>

#include <openssl/curve25519.h>

namespace {

// Like adb, the names include their trailing NUL.
const uint8_t kClientName[] = "adb pair client";
const uint8_t kServerName[] = "adb pair server";

SPAKE2_CTX *ctx_of(jlong handle) { return reinterpret_cast<SPAKE2_CTX *>(handle); }

jbyteArray to_java(JNIEnv *env, const uint8_t *data, size_t size) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(size));
    if (out != nullptr) env->SetByteArrayRegion(out, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte *>(data));
    return out;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_app_pairline_android_privileged_adb_Spake2_nativeCreate(JNIEnv *, jclass) {
    // The pairing client is "alice", adbd is "bob".
    return reinterpret_cast<jlong>(
        SPAKE2_CTX_new(spake2_role_alice, kClientName, sizeof(kClientName), kServerName, sizeof(kServerName)));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_pairline_android_privileged_adb_Spake2_nativeGenerateMessage(JNIEnv *env, jclass, jlong handle,
                                                                       jbyteArray password) {
    uint8_t msg[SPAKE2_MAX_MSG_SIZE];
    size_t msg_len = 0;
    jsize password_len = env->GetArrayLength(password);
    jbyte *password_bytes = env->GetByteArrayElements(password, nullptr);
    if (password_bytes == nullptr) return nullptr;
    int ok = SPAKE2_generate_msg(ctx_of(handle), msg, &msg_len, sizeof(msg),
                                 reinterpret_cast<const uint8_t *>(password_bytes), static_cast<size_t>(password_len));
    env->ReleaseByteArrayElements(password, password_bytes, JNI_ABORT);
    if (ok != 1 || msg_len == 0) return nullptr;
    return to_java(env, msg, msg_len);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_pairline_android_privileged_adb_Spake2_nativeProcessMessage(JNIEnv *env, jclass, jlong handle,
                                                                      jbyteArray their_msg) {
    jsize their_len = env->GetArrayLength(their_msg);
    if (their_len <= 0 || their_len > static_cast<jsize>(SPAKE2_MAX_MSG_SIZE)) return nullptr;
    jbyte *their_bytes = env->GetByteArrayElements(their_msg, nullptr);
    if (their_bytes == nullptr) return nullptr;
    uint8_t key[SPAKE2_MAX_KEY_SIZE];
    size_t key_len = 0;
    int ok = SPAKE2_process_msg(ctx_of(handle), key, &key_len, sizeof(key),
                                reinterpret_cast<const uint8_t *>(their_bytes), static_cast<size_t>(their_len));
    env->ReleaseByteArrayElements(their_msg, their_bytes, JNI_ABORT);
    if (ok != 1 || key_len == 0) return nullptr;
    return to_java(env, key, key_len);
}

extern "C" JNIEXPORT void JNICALL
Java_app_pairline_android_privileged_adb_Spake2_nativeFree(JNIEnv *, jclass, jlong handle) {
    SPAKE2_CTX_free(ctx_of(handle));
}
