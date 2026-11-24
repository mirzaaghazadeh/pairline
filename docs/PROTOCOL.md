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
- Use the largest MTU. The fragment size is the ATT payload, capped at 512 bytes (the longest attribute value):
  `min(MTU − 3, 512)` on Android, and `min(maximumWriteValueLength(for: .withoutResponse), 512)` on iOS. An iPhone
  negotiates MTU 517, and Android 13+ rejects longer notifications. Receivers accept any fragment size.
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
| `call.media.start` | R→B | M6 | `id`, `call` (in progress), `talk` (default true) → `ok` once audio flows, or `error` (`unsupported`: not set up, no Shizuku or no Wi-Fi link; `failed`: capture didn't start). Needs `call.media` |
| `call.media.stop` | R→B | M6 | `id`, `call` → `ok`. The relay also stops by itself when the call ends or the Wi-Fi link drops |
| `call.media.state` | B→R | M6 | `call`, `state` (`active` / `stopped` / `failed`), `listen`, `talk`, `reason` (English, for diagnostics) |
| `call.media.feedback` | R→B | M6 | `id`, `heard` (the iPhone heard the caller), `heardMe` (the caller heard the iPhone); either may be null → `ok`. The bridge uses it to pick the capture source and mic handling that work on this phone |
| `contacts.sync` | R→B, B→R | M4 | request: `id`, `since` (cursor or null) / reply: `id`, `contacts` [Contact], `deleted` [key], `cursor`, `more` |
| `calllog.sync` | R→B, B→R | M4 | request: `id`, `since` / reply: `id`, `calls` [Call-log entry], `cursor`, `more` |
| `contacts.add` | R→B | M4 | `id`, `name`, `numbers` (only when the user turned this on in the bridge app) |
| `ussd.run` | R→B | M4 | `id`, `sim`, `code` |
| `ussd.result` | B→R | M4 | `id`, `ok`, `text`, `error` |
| `wifi.offer` | B→R | M5 | ssid, password, band, ip, port |
| `link.switch` | either | M5 | move bulk traffic to Wi-Fi / back to BLE |
| `file.offer`, `file.answer` | either | M5 | name, size, type / accept or decline (data goes over the bulk channel) |
| `net.share` | R→B, B→R | M8 | request: `id`, `on` / reply (same `id`) or event (no `id`, when the bridge's user stops it): `on`, and while on `host`, `port` (one port for SOCKS5 and HTTP), `user`, `pass` (new for every start), `pac` (`http://host:port/proxy.pac`), `vpn` (the bridge's traffic goes through a VPN), `direct` [domain suffix]. `error` `permission` while the bridge's user hasn't allowed sharing. Needs `net.share` |

### Capabilities (`status.caps`)

The bridge lists only what this phone can do right now: the permission is granted and the hardware or Android
version supports it. The iPhone hides or explains anything missing instead of guessing from the Android version.

`sms.receive`, `sms.send`, `sms.history`, `call.observe`, `call.control` (answer/reject/end), `call.dial`,
`call.dtmf`, `call.audio`, `contacts`, `calllog`, `contacts.add`, `ussd`, `dualsim`, `call.media` (call audio can
be relayed to the iPhone: "Talk on iPhone" is on and Shizuku is ready), `call.media.talk` (the iPhone's microphone
can also be fed into the call; without it the iPhone listens and the user talks on the bridge phone), `net.share`
(the bridge's user allows internet sharing, M8)

### Call audio (M6)

Call audio travels only on the Wi-Fi link's **media channel** (UDP, channel 1, media keys, 64-record replay window;
see "Records"). Each media record's plaintext is one packet:

| Bytes | Field |
|-------|-------|
| 1 | kind: `0x01` = 16-bit little-endian mono PCM at 16 kHz, one 20 ms frame |
| 2 | sequence number, big-endian, +1 per frame, wraps at 65 536 |
| 640 | 320 samples (kind `0x01`) |

- Both directions use the same format; packets are sent every 20 ms while the relay is active. Receivers drop
  unknown kinds and wrong sizes, play frames in sequence order behind a 60 ms jitter buffer, and cover a lost frame by
  repeating the previous one at half volume (twice at most), then silence.
- Uncompressed PCM (32 KB/s each way) is fine on a local network and needs no codec on either side; a compressed kind
  can be added later without breaking older receivers.
- R starts the relay with `call.media.start` once the call is in progress and its media channel is open, and plays
  silence (not nothing) while muted, so B's jitter buffer stays steady. Audio is never stored on either phone.

### Clarifications (v0)

Gaps found while implementing the iPhone app (2026-11-24). Both apps follow these; they don't change the wire format.

- **Handshake order.** Each side sends its confirm as soon as it has both hellos. A side sends application messages
  (counter ≥ 1) only after it has received and verified the peer's confirm. After a successful pairing, R sends its
  `HELLO` on the same connection right after it has verified `PAIR_OK`.
- **Hello checks.** A hello whose length isn't 99, whose version isn't 0, whose role isn't the expected peer role, or
  whose public key isn't a valid P-256 point closes the connection.
- **Absent = null.** An optional field may be left out; a missing field means `null`. Senders still write `"sim": null`
  explicitly for "default SIM".
- **Opaque ids.** `call` ids and the `contacts.sync` / `calllog.sync` `cursor` are opaque JSON values (string or
  number). R stores them and sends them back unchanged.
- **Unknown requests.** B ignores unknown event types, but answers a message with an unknown `t` that carries an `id`
  with `error` / `unsupported`, so R doesn't wait for a timeout.
- **`ping` from B.** Either side may send `ping` with an `id`; the receiver answers `pong` with the same `id`.
  `unpair` is `{"t":"unpair"}` with no `id`; the sender closes the link right after sending it.
- **Timeouts.** R gives up on a request after 15 s (`ussd.run`: 60 s) and keeps the connection. R treats a link on which
  nothing arrived for 180 s (three missed `status` intervals) as dead and reconnects.
- **Paging.** Times are the cursors:
  - `sms.threads`: threads whose newest message is older than `before` (or all, when `before` is null), newest first,
    at most `limit` (default 50).
  - `sms.history` with `before`: messages with `time < before`, newest first. With `after`: messages with
    `time > after`, oldest first. `limit` defaults to 50, at most 100. With `thread: null` it covers all threads.
  - A page may hold fewer than `limit` items and still say `more: true` (the 30 KB page limit). R continues from the
    oldest (`before`) or newest (`after`) time in the page and de-duplicates by `key`.
  - B never splits messages (or threads) that share the same `time` across two pages; a page may go over `limit` to
    keep such a group together, so the strict `<` / `>` cursors can't skip any.
- **Sync cursors B no longer knows** (for example after its data was cleared): B answers with a full sync from the
  start, marked with `full: true` (see the bridge notes below; this replaces an earlier `reset` flag). R drops its
  cached contacts (or call log) once, at the first `full` page of a sync run, so B may repeat `full: true` on the
  following pages.
- **Malformed `PAIR_HELLO`** (bad JSON, wrong `v`, wrong lengths, invalid point) is answered with `bad_mac`.
- **`ussd.run`** gets no `ok`; the `ussd.result` itself carries the request's `id`.
- **`sms.send`.** `to` is one address (no group messages in v0); `cid` is unique per R install. B answers `ok` once
  the message is handed to the radio queue, then sends `sms.state` events with the same `cid`. Long messages are
  split by B (multipart SMS); R sends the whole text.
- **`sms.threads` items.** `addrs` is an array of address strings, `snippet` the newest message's text, `time` its
  time, `count` the number of messages in the thread.
- **`contacts.add`.** `numbers` uses the Contact shape: `[{"n": "+1555…", "label": "mobile"}]`.
- **`call.state` `since`** is when the call entered that state (ms). `call.dial` replies `ok` once the call is placed;
  the new call's id arrives in the following `call.state` (`dir: "out"`).
- **`device.info` from R:** `name` is the generic device model ("iPhone"), never the user-chosen device name.
- **BLE reassembly errors** (a non-first fragment without a frame in progress, a first fragment while one is in
  progress, a length mismatch, or more than 65,535 bytes) close the connection.
- **Debug TCP** accepts one connection at a time; a new connection replaces the old one.

Found while implementing the Android bridge (2026-11-24). Additive only; the wire format is unchanged.

- **Frames** of length 0 (no type byte) close the connection, on both transports.
- **Ids in each direction are separate.** B numbers its own `ping` requests independently of R's request ids. A
  `ping` is never a reply, so the two spaces can't be confused. R answers every `ping` from B with `pong`.
- **Keep-alive on B:** B sends `ping` after 60 s without receiving anything and closes the link after 180 s of
  silence. B also closes a connection that hasn't finished the handshake within 30 s.
- **Debug TCP has no `id` characteristic,** so R can't check the device id there before pairing (debug builds only).
- **`device.info` from B:** `name` is the generic model, `"<maker> <model>"` (for example `"Google Pixel 7"`), never
  the user-chosen device name.
- **`status.sims[]`:** `number` is null when Android doesn't know it; `label` falls back to `"SIM <slot + 1>"`;
  `signal` is 0 and `net` is `"none"` when the SIM has no service.
- **`error.msg`** is short English text for developers. It never contains user content; R shouldn't show it as is.
- **`sms.new` keys:** `msg.key` is `"sms:<id>"` once Android's messaging app has stored the message (B waits up to
  3 s). If it isn't stored by then, or `READ_SMS` is missing, B sends `"rx:<time>"` with `thread: null`, and R
  should treat a later history entry with the same `addr` and `body` within ±60 s as the same message.
- **`sms.state`:** `key` is the stored message's key once known, else null; `error` is a short English reason on
  `failed`, else null. A multipart SMS is `sent` once every part is sent and `delivered` once every part is.
- **`ussd.run`:** the reply is `ussd.result` with the request's `id` (no `ok` first). Problems found before the code
  reaches the network (permission, invalid code, no SIM in that slot) are answered with `error` instead. B gives up
  after 55 s with `{"ok": false, "error": "timeout"}`.
- **`call.*` events:** call ids are strings chosen by B (like `"c7uekrq-3"`), unique across bridge app restarts.
  `call.incoming` is always followed by `call.state` `ringing` for the same call. `call.state` also carries `sim`.
  After a reconnect, B re-sends `call.incoming` / `call.state` for calls still in progress. Without the companion
  InCallService (most phones, see ROADMAP M3), an outgoing call stays `dialing` until it ends, and `call.dtmf` /
  `call.audio` answer `error` / `unsupported`.
- **`contacts.sync` / `calllog.sync` replies** also carry `full` (boolean). `full: true` starts a full sync: R
  replaces its copy with this page and the ones that follow until `more: false`. B starts a full sync when `since`
  is null or older than Android's 30-day deletion log. `deleted` holds contact keys (`"c:<id>"`). Cursors are
  opaque strings.
- **`contacts.add`** answers `error` / `permission` while the user hasn't turned the option on in the bridge app.

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
