# Link protocol — draft v0

> **Status: draft.** The wire version is `0` until an outside cryptographer has reviewed this
> design (required before 1.0, see ROADMAP M7). Any change to the wire format bumps the version
> and updates the shared test vectors in `protocol/test-vectors/`.

## Roles and keys

- **B** = bridge (Android, has the SIM). **R** = remote (iPhone).
- **Identity key:** each device creates a long-term P-256 key pair on first launch.
  - **iOS:** Secure Enclave key (`SecureEnclave.P256.KeyAgreement.PrivateKey`) when available; otherwise Keychain with
    `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`.
  - **Android:** key agreement inside Android Keystore (`PURPOSE_AGREE_KEY`) exists only on API 31+. On
    API 30, keep the private key in no-backup app storage, encrypted with an AES-GCM key held in
    Android Keystore. On API 31+, use the keystore key directly.
- **Why P-256 and not X25519:** P-256 ECDH is built into both platforms at our minimum versions (Android API 30,
  iOS 16). X25519 isn't reliably available in Android's built-in crypto providers at API 30.
- **Primitives:** ECDH P-256, HKDF-SHA256, HMAC-SHA256, AES-256-GCM. Platform implementations only.

## Pairing (once)

1. B creates a random 32-byte pairing secret `ps`. It is valid for 5 minutes and can be used once.
2. B shows a QR code:
   `pairline://pair?v=0&id=<B device id, 8 bytes>&k=<B identity public key, 65-byte uncompressed>&s=<ps>`
   (binary values are base64url without padding).
3. R scans it, finds B by its BLE advertisement (service UUID + device id) and connects.
4. R → B `pair_hello`: version, R's identity public key, R's 32-byte nonce, and
   `HMAC-SHA256(ps, T)`, where `T = "pairline-pair-v0" ‖ pubB ‖ pubR ‖ nonceR`.
5. B checks the HMAC, then replies `pair_ok` with its own 32-byte nonce and
   `HMAC-SHA256(ps, T ‖ nonceB ‖ "B")`.
6. Both store the peer's identity public key (pinned) and delete `ps`. The QR secret is never logged.

Seeing the QR code on the bridge screen is the trust anchor. Someone who didn't see it can't pair.

## Session handshake (every connection)

A Noise-KK-style handshake built on the pinned identity keys (`sB`, `sR`):

1. Each side creates an ephemeral P-256 key pair (`eB`, `eR`) and a 32-byte random value.
2. They exchange `hello` messages in plaintext: version, ephemeral public key, random value.
3. Both compute `ikm = ECDH(eB, eR) ‖ ECDH(sB, eR) ‖ ECDH(eB, sR)`.
4. `salt = SHA-256(transcript of both hellos)`.
   `okm = HKDF-SHA256(ikm, salt, info = "pairline v0 session", L = 64)`.
   `kBR = okm[0..32]` (B→R) and `kRB = okm[32..64]` (R→B).
5. Each side sends `confirm`, an encrypted record with an empty body. If decryption fails, the peer is wrong: drop the connection.

Only the pinned peer can derive the keys, because both static keys are mixed in. The ephemeral keys give forward secrecy.

## Records

- **AEAD:** AES-256-GCM with a 16-byte tag.
- **Nonce (12 bytes):** a 4-byte direction label (`B→R` = `00000001`, `R→B` = `00000002`) followed by an 8-byte big-endian counter starting at 0.
- **Associated data:** protocol version (1 byte) ‖ channel (1 byte: 0 = BLE control, 1 = Wi-Fi media, 2 = Wi-Fi bulk).
- **Record on the wire:** counter (8 bytes) ‖ ciphertext ‖ tag.
- **Replay:**
  - BLE delivery is ordered, so the receiver rejects any counter ≤ the last one it accepted.
  - The UDP media channel uses a 64-record sliding replay window instead.
- **Rekeying:** new keys on every reconnect, and a rekey before any counter reaches 2³².
- **Wi-Fi channel keys:** media and bulk derive their own keys from the session `okm` with HKDF, info
  `"pairline v0 media"` / `"pairline v0 bulk"`.

## Framing on BLE

- One GATT service (128-bit UUID, generated in M1) with two characteristics:
  - `rx`: write without response, R → B
  - `tx`: notify, B → R
- Negotiate the largest MTU. Split each record into fragments, each with a 1-byte header
  (bit 0 = first, bit 1 = last). The first fragment carries a 2-byte big-endian total length.

## Messages

Inside each record is a UTF-8 JSON object: `{"t": "<type>", "id": <request id>, …}`. Receivers ignore unknown
types so newer versions stay compatible.

| Type | Direction | Milestone | Body |
|------|-----------|-----------|------|
| `status` | B→R | M1 | battery, charging, signal per SIM, carrier names, SIM list, bridge app version |
| `unpair` | either | M1 | — (both sides delete the pinned key) |
| `sms.new` | B→R | M2 | sim, from, body, time, thread |
| `sms.send` | R→B | M2 | sim, to, body, client id |
| `sms.state` | B→R | M2 | client id, state (`sent` / `delivered` / `failed`) |
| `sms.history` | R→B, B→R | M2 | page request / page |
| `call.incoming` | B→R | M3 | call id, sim, from, contact name if known |
| `call.state` | B→R | M3 | call id, state (`ringing` / `active` / `held` / `ended`), reason |
| `call.answer`, `call.reject`, `call.end` | R→B | M3 | call id |
| `call.dial` | R→B | M3 | sim, number |
| `contacts.sync`, `calllog.sync` | R→B, B→R | M4 | since-cursor / changes |
| `contacts.add` | R→B | M4 | name, numbers (only when the user has turned this on) |
| `ussd.run`, `ussd.result` | R→B, B→R | M4 | sim, code / response text |
| `wifi.offer` | B→R | M5 | ssid, password, band, ip, port |
| `link.switch` | either | M5 | move bulk traffic to Wi-Fi / back to BLE |
| `file.offer`, `file.answer` | either | M5 | name, size, type / accept or decline (data goes over the bulk channel) |
| `net.share` | R→B, B→R | M8 | start or stop internet sharing / proxy address, ports, per-session credentials |

## Test vectors

`protocol/test-vectors/` (created in M0/M1) holds fixed keys and inputs with the expected HKDF
outputs, nonces and ciphertexts. Unit tests in both apps must pass them.

## Open questions for review

- Should pairing add a short comparison code (like Bluetooth numeric comparison) for users who
  pair in public?
- JSON vs CBOR inside records. JSON for v0 readability; CBOR may be worth it for BLE bandwidth.
- Whether `hello` should be padded or encrypted to hide protocol fingerprinting on BLE.
