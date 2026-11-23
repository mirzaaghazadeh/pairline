# Link protocol — draft v0

> **Status: draft.** The wire version is `0` until an outside cryptographer has reviewed this
> design (required before 1.0, see ROADMAP M7). Nothing has shipped yet, so v0 is still being filled in;
> after the first release, any change to the wire format bumps the version and updates the shared test
> vectors in `protocol/test-vectors/`.

## Roles and keys

- **B** = bridge (Android, has the SIM). **R** = remote (iPhone).
- **Identity key:** each device creates a long-term P-256 key pair on first launch.
  - **iOS:** Secure Enclave key (`SecureEnclave.P256.KeyAgreement.PrivateKey`) when available; otherwise Keychain with
    `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`. The Simulator has no Secure Enclave, so it uses the Keychain path.
  - **Android:** key agreement inside Android Keystore (`PURPOSE_AGREE_KEY`) exists only on API 31+. On
    API 30, keep the private key in no-backup app storage, encrypted with an AES-GCM key held in
    Android Keystore. On API 31+, use the keystore key directly.
- **Why P-256 and not X25519:** P-256 ECDH is built into both platforms at our minimum versions (Android API 30,
  iOS 16). X25519 isn't reliably available in Android's built-in crypto providers at API 30.
- **Primitives:** ECDH P-256, SHA-256, HKDF-SHA256 (RFC 5869, built on the platform's HMAC), HMAC-SHA256,
  AES-256-GCM. Platform implementations only.
- **Encodings:** public keys are 65-byte uncompressed X9.62 points (`04 ‖ X ‖ Y`). An ECDH result is the 32-byte
  big-endian X coordinate (what both JCA `KeyAgreement` and CryptoKit return). Binary values in JSON and URIs are
  base64url without padding.
- **One pairing per device in v0.** Pairing again replaces the previous peer.

## Pairing (once)

1. B creates a random 8-byte device id (kept until the app's data is cleared) and a random 32-byte pairing
   secret `ps`. `ps` is valid for 5 minutes and can be used once.
2. B shows a QR code:
   `pairline://pair?v=0&id=<B device id>&k=<B identity public key>&s=<ps>`
3. R scans it, finds B by its BLE service UUID, connects, and reads the `id` characteristic to check the
   device id. If it doesn't match, R disconnects and keeps scanning.
4. R → B frame `PAIR_HELLO`: `{"t":"pair_hello","v":0,"k":<pubR>,"n":<nonceR, 32 bytes>,"mac":<macR>}`, where
   `macR = HMAC-SHA256(ps, T)` and `T = "pairline-pair-v0" ‖ pubB ‖ pubR ‖ nonceR` (the label is ASCII).
5. B checks `macR` in constant time. On success it replies `PAIR_OK`:
   `{"t":"pair_ok","v":0,"n":<nonceB, 32 bytes>,"mac":<macB>}`, with `macB = HMAC-SHA256(ps, T ‖ nonceB ‖ "B")`.
   On failure it replies `PAIR_FAIL` `{"t":"pair_fail","v":0,"reason":"expired"|"used"|"bad_mac"|"busy"}` and
   disconnects.
6. R checks `macB`. Both store the peer's identity public key (pinned) and delete `ps`. The QR secret is never logged.
7. R continues on the same connection with the session handshake below.

Seeing the QR code on the bridge screen is the trust anchor. Someone who didn't see it can't pair.

**Debug builds only:** the iPhone app also accepts the same `pairline://pair?...` link through its URL scheme or a
paste field, because the Simulator has no camera. Release builds accept pairing links only from the camera, so
another app or a message can't make the iPhone pair with someone else's bridge.

## Session handshake (every connection)

A Noise-KK-style handshake built on the pinned identity keys (`sB`, `sR`):

1. Each side creates an ephemeral P-256 key pair (`eB`, `eR`) and a 32-byte random value.
2. R (the side that connected) sends `HELLO` first, then B replies with its own. A hello body is exactly 99 bytes:
   `version (1 byte, 0) ‖ role (1 byte: 'R' = 0x52, 'B' = 0x42) ‖ ephemeral public key (65) ‖ random (32)`.
3. Both compute `ikm = ECDH(eB, eR) ‖ ECDH(sB, eR) ‖ ECDH(eB, sR)` (96 bytes; R computes the same values from
   its side: `ECDH(eR, eB) ‖ ECDH(eR, sB) ‖ ECDH(sR, eB)`).
4. `salt = SHA-256(helloR ‖ helloB)` (the two 99-byte bodies, R's first).
   `okm = HKDF-SHA256(ikm, salt, info = "pairline v0 session", L = 64)`.
   `kBR = okm[0..32]` (B→R) and `kRB = okm[32..64]` (R→B).
5. Each side sends `confirm`: a record with counter 0 and an empty plaintext. If the peer's confirm doesn't decrypt,
   the peer is wrong: drop the connection. Application messages start at counter 1.
6. After the confirms, each side sends `device.info`, and B sends `status`.

Only the pinned peer can derive the keys, because both static keys are mixed in. The ephemeral keys give forward secrecy.

## Records

- **AEAD:** AES-256-GCM with a 16-byte tag.
- **Nonce (12 bytes):** a 4-byte big-endian direction label (`B→R` = `00000001`, `R→B` = `00000002`) followed by an
  8-byte big-endian counter.
- **Associated data (2 bytes):** protocol version ‖ channel (0 = control on BLE or the debug TCP link, 1 = Wi-Fi
  media, 2 = Wi-Fi bulk).
- **Record on the wire:** counter (8 bytes, big-endian) ‖ ciphertext ‖ tag.
- **Replay:**
  - The control channel is ordered, so the receiver rejects any counter ≤ the last one it accepted and drops the connection.
  - The UDP media channel uses a 64-record sliding replay window instead.
- **Rekeying:** new keys on every reconnect, and the connection is closed and re-established before any counter
  reaches 2³².
- **Wi-Fi channel keys:** `HKDF-SHA256(okm, salt = empty, info, L = 64)` with info `"pairline v0 media"` or
  `"pairline v0 bulk"`; the first 32 bytes are B→R, the last 32 R→B.

## Frames

A frame is one complete unit after transport reassembly. Its first byte is the frame type:

| Type | Name | Body |
|------|------|------|
| `0x01` | `PAIR_HELLO` | UTF-8 JSON (above) |
| `0x02` | `PAIR_OK` | UTF-8 JSON |
| `0x03` | `PAIR_FAIL` | UTF-8 JSON |
| `0x10` | `HELLO` | 99-byte hello body |
| `0x20` | `RECORD` | encrypted record |

Frames are at most 65,535 bytes. Unknown frame types close the connection.

## Transports

### BLE (production)

- Android is the GATT server and advertises the service UUID. The iPhone is the central.
- **Service** `8CC09D09-4084-4E91-A358-A153EB79D422` with three characteristics:
  - `rx` `15347F70-79F0-40C4-92DE-ED6612B4CE5F`: write without response, R → B
  - `tx` `8A38CBD3-5771-4174-9C10-7EB7FD219E6B`: notify, B → R
  - `id` `CFFC7A8D-1012-4A2D-B52F-50A9E32F8B32`: read, returns the 8-byte device id
- Use the largest MTU. The fragment size is the ATT payload: `MTU − 3` on Android, and
  `maximumWriteValueLength(for: .withoutResponse)` on iOS.
- Each fragment starts with a 1-byte header (bit 0 = first, bit 1 = last). The first fragment adds the frame's
  2-byte big-endian total length. A frame that fits in one fragment has header `0x03`.
- Flow control: Android sends the next notification only after `onNotificationSent`. iOS writes only while
  `canSendWriteWithoutResponse` is true, and continues in `peripheralIsReady(toSendWriteWithoutResponse:)`.
- Reconnection: iOS keeps a pending `connect` to the known peripheral and also scans for the service UUID, because
  Android rotates its BLE address.

### TCP (debug builds only)

For development on the Android emulator and the iOS Simulator, which have no shared Bluetooth (D-018).

- Each frame is preceded by its 4-byte big-endian length.
- The Android debug build listens on `127.0.0.1:47800` inside the emulator. On the Mac,
  `adb forward tcp:47800 tcp:47800` makes it reachable, and the iOS Simulator connects to `127.0.0.1:47800`.
- Pairing, handshake and records are exactly the same as on BLE. Release builds contain no TCP transport.

## Messages

Each record's plaintext is a UTF-8 JSON object: `{"t": "<type>", …}`.

- Requests from R carry `"id"`: an integer, unique within the session. B answers with the same `"id"`, either in a
  typed reply or in `ok` / `error`.
- Events carry no `id`.
- Receivers ignore unknown types and unknown fields, so newer versions stay compatible.
- Times are milliseconds since the Unix epoch (UTC). `sim` is the 0-based SIM slot, or `null` for "default".
- A frame is at most 65,535 bytes, so B splits long lists into pages (`more: true`) of at most 30 KB of JSON each.

### Shared objects

```jsonc
// Message (SMS)
{"key": "sms:1234", "thread": 17, "addr": "+15551234567", "body": "text", "time": 1764000000000,
 "box": "in" | "out", "sim": 0, "state": "received" | "pending" | "sent" | "delivered" | "failed"}

// SIM, in status.sims
{"slot": 0, "carrier": "Carrier", "label": "SIM 1", "number": "+15551234567" | null,
 "signal": 0-4, "net": "5G" | "LTE" | "3G" | "2G" | "none", "roaming": false,
 "defaultSms": true, "defaultVoice": true}

// Contact
{"key": "c:42", "name": "Ada Lovelace", "starred": false,
 "numbers": [{"n": "+15551234567", "label": "mobile"}], "emails": ["ada@example.com"]}

// Call-log entry
{"key": "cl:99", "number": "+15551234567", "name": "Ada Lovelace" | null,
 "type": "in" | "out" | "missed" | "rejected" | "blocked" | "voicemail",
 "time": 1764000000000, "dur": 63, "sim": 0}
```

### Catalog

| Type | Direction | Milestone | Body |
|------|-----------|-----------|------|
| `device.info` | either | M1 | `name`, `platform` (`android`/`ios`), `os`, `app` (version), `proto` (0) |
| `status` | B→R | M1 | `battery` `{level, charging}`, `sims` [SIM], `app`, `caps` [capability], `time`. Sent after the handshake, on every change, and at least every 60 s |
| `status.get` | R→B | M1 | `id` → `status` with the same `id` |
| `ping` / `pong` | either | M1 | `id`. Keep-alive; also used to measure latency |
| `unpair` | either | M1 | — The receiver deletes the pinned key and closes the link |
| `ok` | B→R | M1 | `id` |
| `error` | B→R | M1 | `id`, `code` (`unsupported` / `permission` / `invalid` / `busy` / `failed`), `msg` |
| `sms.new` | B→R | M2 | `msg` (Message), for each incoming SMS |
| `sms.send` | R→B | M2 | `id`, `sim`, `to`, `body`, `cid` (client-chosen id) → `ok`, then `sms.state` events |
| `sms.state` | B→R | M2 | `cid`, `state` (`sent` / `delivered` / `failed`), `key` (once stored), `error` |
| `sms.threads` | R→B, B→R | M2 | request: `id`, `limit`, `before` / reply: `id`, `threads` [`{thread, addrs, snippet, time, count}`], `more` |
| `sms.history` | R→B, B→R | M2 | request: `id`, `thread` or null, `before` or `after`, `limit` (≤ 100) / reply: `id`, `msgs` [Message], `more` |
| `call.incoming` | B→R | M3 | `call` (id), `sim`, `from`, `name` (if the contact is known) |
| `call.state` | B→R | M3 | `call`, `state` (`dialing` / `ringing` / `active` / `held` / `ended`), `dir` (`in`/`out`), `number`, `name`, `since`, `reason` (`local` / `remote` / `busy` / `missed` / `rejected` / `failed`, only when ended) |
| `call.answer`, `call.reject`, `call.end` | R→B | M3 | `id`, `call` → `ok` / `error` |
| `call.dial` | R→B | M3 | `id`, `sim`, `number` → `ok`, then `call.state` events |
| `call.dtmf` | R→B | M3 | `id`, `call`, `digit` (needs capability `call.dtmf`) |
| `call.audio` | R→B | M3 | `id`, `call`, `mute`, `speaker` (either optional; needs `call.audio`) |
| `contacts.sync` | R→B, B→R | M4 | request: `id`, `since` (cursor or null) / reply: `id`, `contacts` [Contact], `deleted` [key], `cursor`, `more` |
| `calllog.sync` | R→B, B→R | M4 | request: `id`, `since` / reply: `id`, `calls` [Call-log entry], `cursor`, `more` |
| `contacts.add` | R→B | M4 | `id`, `name`, `numbers` (only when the user turned this on in the bridge app) |
| `ussd.run` | R→B | M4 | `id`, `sim`, `code` |
| `ussd.result` | B→R | M4 | `id`, `ok`, `text`, `error` |
| `wifi.offer` | B→R | M5 | ssid, password, band, ip, port |
| `link.switch` | either | M5 | move bulk traffic to Wi-Fi / back to BLE |
| `file.offer`, `file.answer` | either | M5 | name, size, type / accept or decline (data goes over the bulk channel) |
| `net.share` | R→B, B→R | M8 | start or stop internet sharing / proxy address, ports, per-session credentials |

### Capabilities (`status.caps`)

The bridge lists only what this phone can do right now: the permission is granted and the hardware or Android
version supports it. The iPhone hides or explains anything missing instead of guessing from the Android version.

`sms.receive`, `sms.send`, `sms.history`, `call.observe`, `call.control` (answer/reject/end), `call.dial`,
`call.dtmf`, `call.audio`, `contacts`, `calllog`, `contacts.add`, `ussd`, `dualsim`

## Test vectors

`protocol/test-vectors/v0.json` holds fixed keys and inputs with the expected ECDH results, HKDF outputs, pairing
MACs, hello bodies, session keys, confirm records, encrypted records, records that must fail, BLE fragments and
TCP framing. It is generated by an independent Python implementation (`protocol/tools/gen_vectors.py`). Unit tests
in both apps must reproduce every value.

## Open questions for review

- Should pairing add a short comparison code (like Bluetooth numeric comparison) for users who
  pair in public?
- JSON vs CBOR inside records. JSON for v0 readability; CBOR may be worth it for BLE bandwidth.
- Whether `hello` should be padded or encrypted to hide protocol fingerprinting on BLE.
- Supporting more than one paired peer per device.
