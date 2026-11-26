# Link protocol — draft v0, with remote mode as v1

> **Status: draft.** The wire version is `0` until an outside cryptographer has reviewed this
> design (required before 1.0, see ROADMAP M7). Nothing has shipped yet, so v0 is still being filled in;
> after the first release, any change to the wire format bumps the version and updates the shared test
> vectors in `protocol/test-vectors/`. Remote mode (D-080) is version 1: v0 plus the section "Remote mode (v1,
> D-080)", with its own vectors in `protocol/test-vectors/v1-remote.json`; the local links stay v0 on the wire.

## Roles and keys

- **B** = bridge (Android, has the SIM). **R** = remote (iPhone).
- **Identity key:** each device creates a long-term P-256 key pair on first launch.
  - **iOS:** Secure Enclave key (`SecureEnclave.P256.KeyAgreement.PrivateKey`) when available; otherwise Keychain with
    `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`. The Simulator has no Secure Enclave, so it uses the Keychain path.
  - **Android:** key agreement inside Android Keystore (`PURPOSE_AGREE_KEY`) exists only on API 31+. Below
    API 31 (the app supports API 28 and later, D-038), keep the private key in no-backup app storage, encrypted with an
    AES-GCM key held in Android Keystore. On API 31+, use the keystore key directly.
- **Why P-256 and not X25519:** P-256 ECDH is built into both platforms at our minimum versions (Android API 28,
  iOS 16). X25519 isn't reliably available in Android's built-in crypto providers at those API levels.
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

**Pairing over Wi-Fi (M5, D-044).** The link may carry B's local Wi-Fi addresses, so an iPhone can pair and connect
without Bluetooth (some phones can't hold a BLE link with Apple devices, D-040). All of these parameters are
optional; a receiver that doesn't know them ignores them, so older links keep working:

| Parameter | Value |
|-----------|-------|
| `ip` | Comma-separated addresses of B on its Wi-Fi and hotspot interfaces: IPv4 as four dotted decimal parts (shorthand like `192.168.1` is refused), or IPv6 without brackets or zone. Only private (RFC 1918), link-local IPv4 (169.254/16) and unique-local IPv6 (fc00::/7) addresses; at most 4 |
| `port` | B's Wi-Fi TCP port (decimal) |
| `ssid`, `pw` | Only while B's own hotspot is on: its network name and passphrase, base64url of the UTF-8 text |

R pairs over whichever transport reaches B first: BLE (checking the `id` characteristic) or a TCP connection to one of
the `ip` addresses on `port`. The pairing secret is single-use, so only one of them can pair; the other then
connects with the normal handshake. Over Wi-Fi there's no `id` characteristic: the MACs alone prove that B is the
bridge that showed the code. R never sends `PAIR_HELLO` to an address it didn't get from the link.

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
- **Associated data (2 bytes):** protocol version ‖ channel (0 = control, on every transport including the Wi-Fi
  TCP link; 1 = Wi-Fi media; 2 = Wi-Fi bulk; 3 = link media: call audio inside a Bluetooth or debug TCP session).
- **Record on the wire:** counter (8 bytes, big-endian) ‖ ciphertext ‖ tag.
- **Replay:**
  - The control channel is ordered, so the receiver rejects any counter ≤ the last one it accepted and drops the connection.
  - The bulk channel is ordered too (it shares the Wi-Fi TCP connection) and follows the same rule.
  - The link media channel is ordered too (it shares the Bluetooth or debug TCP connection) and follows the same rule.
    Counters may skip; a counter at or below the last accepted one, or a record that doesn't authenticate, closes the
    connection.
  - The UDP media channel uses a 64-record sliding replay window instead (below).
- **Rekeying:** new keys on every reconnect, and the connection is closed and re-established before any counter
  reaches 2³².
- **Wi-Fi channel keys:** `HKDF-SHA256(okm, salt = empty, info, L = 64)` with info `"pairline v0 media"` or
  `"pairline v0 bulk"`; the first 32 bytes are B→R, the last 32 R→B. `okm` is the one of the **Wi-Fi TCP session**
  the channel belongs to (M5, D-044): each Wi-Fi connection runs its own handshake, and its media and bulk keys die
  with it.
- **Link media keys (D-072):** `HKDF-SHA256(okm, salt = empty, info = "pairline v0 link media", L = 64)`; the first
  32 bytes are B→R, the last 32 R→B. `okm` is the one of the Bluetooth (or debug TCP) session, or of remote mode's
  direct connection (D-080), that carries the `MEDIA` frames. They differ from the Wi-Fi media keys, so the two media
  paths never share a key and nonce.
- **Counters per channel:** each direction of each channel has its own counter, starting at 0. On the control
  channel counter 0 is the confirm; on the bulk and media channels counter 0 is an ordinary record.
- **Media replay window (64 records):** the receiver keeps the highest counter it has accepted (`top`) and a 64-bit
  mask of which of `top − 63 … top` it has seen. A record whose counter is ≤ `top − 64`, or already marked, is dropped
  before decryption; a record that fails authentication is dropped and changes nothing; an authentic record with a
  counter above `top` slides the window up to it, otherwise it sets its bit. The window starts empty (the first
  authentic record of any counter is accepted). Drops are silent: UDP is easy to spoof, so a bad datagram never
  closes anything. `protocol/test-vectors/v0.json` (`media_replay_window`) has a sequence with the expected results.

## Frames

A frame is one complete unit after transport reassembly. Its first byte is the frame type:

| Type | Name | Body |
|------|------|------|
| `0x01` | `PAIR_HELLO` | UTF-8 JSON (above) |
| `0x02` | `PAIR_OK` | UTF-8 JSON |
| `0x03` | `PAIR_FAIL` | UTF-8 JSON |
| `0x10` | `HELLO` | 99-byte hello body |
| `0x20` | `RECORD` | encrypted record (control channel) |
| `0x21` | `BULK` | encrypted record on the bulk channel (M5): Wi-Fi TCP only, after the handshake |
| `0x22` | `MEDIA` | encrypted record on the link media channel (call audio, D-072): BLE, debug TCP and remote mode's direct connection (D-080) only, after the handshake |

Frames are at most 65,535 bytes. Unknown frame types close the connection. `BULK` is new in M5 but needs no version
bump: a side sends it only for a file transfer the peer accepted with `file.answer`, which an older app never sends.
A `BULK` frame on BLE or the debug TCP link, before the handshake, or one that doesn't authenticate closes the
connection. `MEDIA` needs no version bump for the same reason: a side sends it only for call audio the bridge
accepted with `call.media.start` `via: "link"`, which an older app never asks for or accepts. A `MEDIA` frame on a
Wi-Fi session, before the handshake, or one that doesn't authenticate closes the connection.

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

### Wi-Fi (M5, production; D-044)

Both phones on the same Wi-Fi network, or the iPhone on the bridge's own hotspot. A Wi-Fi connection is a **full
link** on its own (pairing, handshake, control messages), so it works without Bluetooth, and it adds the media and
bulk channels.

**Listening (B).**
- TCP port 47820, or any free port if that one is taken; the port travels in the pair link, `wifi.offer` and the
  DNS-SD record.
- B binds one listening socket per private address of its **Wi-Fi and hotspot interfaces** — RFC 1918, 169.254/16,
  unique-local fc00::/7 and link-local fe80::/10 — and re-binds when the networks change. Never on cellular, VPN or
  public addresses.
- B closes a connection at once if the peer's address isn't private, link-local or unique-local (RFC 1918,
  169.254/16, fe80::/10, fc00::/7).
- Framing as on the debug TCP link: each frame is preceded by its 4-byte big-endian length.
- B accepts up to 4 connections that haven't finished the handshake. An established Wi-Fi session replaces an older
  Wi-Fi session (newest wins, as on BLE).
- **Keep-alive:** each side sends `ping` after 15 s without receiving anything on a Wi-Fi session and closes the
  session after 45 s of silence, so a Wi-Fi drop is noticed quickly.

**Discovery (R).**
- B registers a DNS-SD (mDNS) service `_pairline._tcp` on its Wi-Fi port with a random instance name (`pairline-`
  and 8 random hex digits, new each time the service starts) and the TXT record `v=0`. Never the user's or the
  phone's name, and never the device id.
- R tries, in order: the address that worked last, the addresses from the last `wifi.offer` and from the pair link,
  then every `_pairline._tcp` service it finds by browsing. A candidate whose handshake fails because the keys don't
  match (someone else's bridge) is skipped for 10 minutes. R only connects to private, link-local or unique-local
  addresses, retries with backoff (1, 2, 4 … 30 s) while paired, and tries again at once when its own network changes
  or a `wifi.offer` arrives.
- R starts looking on Wi-Fi only once B has mentioned Wi-Fi: Wi-Fi parameters in the pair link, a `wifi.offer`, or
  `wifi` in `status.caps`. An older bridge therefore never causes the iPhone's Local Network prompt.
- Like B, R closes a connection that hasn't finished the handshake within 30 s.

**Two sessions at once.**
- With BLE (or the debug TCP link) and Wi-Fi both up there are two independent sessions with the same pinned peer.
  B keeps at most one established session per transport.
- B sends **events** (`status`, `sms.*`, `call.*`, `wifi.offer`) on its BLE session when one is established,
  otherwise on the Wi-Fi session. `wifi.media` and the `file.*` messages always go on the Wi-Fi session.
- R picks a session for each **request**; B answers on the session the request arrived on and drops the reply if that
  session has closed.
- R sends the bulk requests (`sms.threads`, `sms.history`, `contacts.sync`, `calllog.sync`) on the Wi-Fi session when
  it's established, and everything else on BLE when it's established (iOS wakes the app for Bluetooth, not for
  sockets), otherwise on whichever is up. A bulk request whose Wi-Fi session closes before the reply arrives is sent
  again on BLE.
- `call.media.start` with `via: "link"` goes on the Bluetooth (or debug TCP) session, whose `MEDIA` frames then carry
  the call audio (D-072); away from home, with no Bluetooth session, on remote mode's direct connection (D-080).
- `link.switch` isn't used: replies follow their request (D-044).
- After each handshake each side sends `device.info`, and B sends `status`, on that session. B re-sends calls in
  progress (`call.incoming` / `call.state`) only when its event session changes.

**Media channel (UDP).** For call audio (M6): low latency; loss and reordering are fine.
- Right after a Wi-Fi session is established, B binds a UDP socket on the local address of that TCP connection (port
  47820 when free, otherwise any) and sends `wifi.media` `{"port": <udp port>}` on the Wi-Fi session
  (`"port": null`: no media channel this time).
- Keys: the media keys of that Wi-Fi session. A **datagram is exactly one record**: counter (8 bytes) ‖ ciphertext ‖
  tag, with associated data version ‖ 1. The plaintext is 0–1200 bytes, so a datagram is at most 1224 bytes and fits
  any Wi-Fi path without IP fragmentation. Shorter (< 24 bytes) or longer datagrams are dropped.
- Each side numbers every datagram it sends (from 0, +1 each, probes included) and stops sending before 2³² (the Wi-Fi
  session must reconnect to rekey). Receivers use the 64-record replay window above.
- An empty plaintext is a **probe**: it checks the path and isn't handed to the app.
- **Path:** R sends probes from one UDP socket to B's address (the remote address of its Wi-Fi TCP connection) at the
  `wifi.media` port: one at once, then one every second until an authentic datagram from B arrives, then one whenever
  it has sent nothing for 5 s (this keeps NAT mappings alive, as in the Android emulator). B answers each authentic
  probe with a probe (at most 10 per second), sends everything to the source address and port of the latest authentic
  datagram from R, and ignores datagrams from any IP other than its Wi-Fi TCP peer's.
- The channel is **open** on a side once that side has received an authentic datagram from the other, and closes with
  its Wi-Fi session. Nothing is retransmitted, and senders never block: a datagram the socket can't take is dropped.
  Apart from probes, neither side sends before its channel is open.

**Hotspot.** The user can start Android's local-only hotspot on the bridge. While it runs, B lists the hotspot
interface's address in `wifi.offer` `ip` and adds `ssid` and `password` (and `ssid`/`pw` in the pair link). R asks
iOS to join it (`NEHotspotConfiguration`; iOS asks the user first) when its Wi-Fi session isn't up 5 s after such a
`wifi.offer` (4 s into pairing with such a link), at most once per network name while the app runs, then connects as
on any Wi-Fi network.

**Debug builds only:** the Android emulator's own NAT forwards `adb emu redir add tcp:47820:47820` and
`udp:47820:47820` to its emulated *cellular* interface, so a debug build accepts that interface when it runs on an
emulator (never on a phone, never in release). The iPhone debug build can be pointed at a fixed address with
`-PairlineWiFiHost 127.0.0.1 -PairlineWiFiPort 47820`. R sends its UDP datagrams to the address of its TCP connection,
so through such a redirect the host's UDP port must equal the `wifi.media` port (47820 on both sides).

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

// SIM, in status.sims (defaultData: Android's data SIM, D-073)
{"slot": 0, "carrier": "Carrier", "label": "SIM 1", "number": "+15551234567" | null,
 "signal": 0-4, "net": "5G" | "LTE" | "3G" | "2G" | "none", "roaming": false,
 "defaultSms": true, "defaultVoice": true, "defaultData": true}

// The bridge phone's own internet sharing, in status.net (D-073)
{"hotspot": false, "bluetooth": false, "data": true | null}

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
| `device.info` | either | M1 | `name`, `platform` (`android`/`ios`), `os`, `app` (version), `proto` (0), `caps` (R only, M5: what R can receive, see below) |
| `status` | B→R | M1 | `battery` `{level, charging}`, `sims` [SIM], `app`, `caps` [capability], `net` (D-073: the bridge phone's hotspot, Bluetooth tethering and mobile data; older bridges omit it), `ringer` (D-074: `normal` / `vibrate` / `silent`), `time`. Sent after the handshake, on every change, and at least every 60 s |
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
| `call.media.start` | R→B | M6 | `id`, `call` (in progress), `talk` (default true), `via` (`"wifi"`, the default: the Wi-Fi session's UDP media channel; `"link"`: `MEDIA` frames on the session this request arrives on, which must be Bluetooth or debug TCP, D-072) → `ok` once audio flows, or `error` (`unsupported`: not set up, the bridge's helper isn't running, no Wi-Fi link for `wifi`, a Wi-Fi session or no `call.media.link` for `link`; `failed`: capture didn't start). Needs `call.media` |
| `call.media.stop` | R→B | M6 | `id`, `call` → `ok`. The relay also stops by itself when the call ends or the Wi-Fi link drops |
| `call.media.state` | B→R | M6 | `call`, `state` (`active` / `stopped` / `failed`), `listen`, `talk`, `reason` (English, for diagnostics) |
| `call.media.feedback` | R→B | M6 | `id`, `heard` (the iPhone heard the caller), `heardMe` (the caller heard the iPhone); either may be null → `ok`. The bridge uses it to pick the capture source and mic handling that work on this phone |
| `sms.read` | R→B | M2 | `id`, `thread` (> 0): the user read this conversation on R → `ok`; B marks its received messages read (best effort: Android applies it only with Pairline's WRITE_SMS app-op). Needs `sms.read` (D-074) |
| `call.hold` | R→B | M3 | `id`, `call`, `hold` → `ok`, then `call.state` `held` / `active`. Taking a held call off hold while another is active swaps them (Android holds the other). Needs `call.hold` (D-074) |
| `contacts.update` | R→B | M4 | `id`, `key` (a contact key) and any of `name`, `numbers` (replace all of the contact's numbers), `starred` → `ok`; the next `contacts.sync` brings the change back. Needs `contacts.edit` (D-074) |
| `phone.find` | R→B | — | `id`, `on` (default true) → `ok`: B rings loudly on the alarm stream for up to a minute, or stops. Needs `phone.find` (D-074) |
| `phone.ringer` | R→B | — | `id`, `mode` (`normal` / `vibrate` / `silent`) → `ok`, then `status.ringer`; `error` `permission` when switching to or from silent without Do Not Disturb access (B then asks its user for it). Needs `phone.ringer` (D-074) |
| `notif.posted` | B→R | — | `key` (Android's notification key), `app` (package), `appName`, `title`, `text` (up to 1,000 characters), `time`: a notification from an app the bridge's user chose. The same `key` again replaces it. Only while `notif.mirror` is listed (D-077) |
| `notif.removed` | B→R | — | `key`: it went away on the bridge (read, dismissed or replaced there); R removes it |
| `notif.dismiss` | R→B | — | `id`, `key`: the user dismissed it on R → `ok`; B clears it (only keys it mirrored). Needs `notif.mirror` |
| `contacts.sync` | R→B, B→R | M4 | request: `id`, `since` (cursor or null) / reply: `id`, `contacts` [Contact], `deleted` [key], `cursor`, `more` |
| `calllog.sync` | R→B, B→R | M4 | request: `id`, `since` / reply: `id`, `calls` [Call-log entry], `cursor`, `more` |
| `contacts.add` | R→B | M4 | `id`, `name`, `numbers` (only when the user turned this on in the bridge app) |
| `ussd.run` | R→B | M4 | `id`, `sim`, `code` |
| `ussd.result` | B→R | M4 | `id`, `ok`, `text`, `error` |
| `calllog.delete` | R→B | M4 | `id`, `keys` [call-log key] or `all: true` → `ok` / `error` (needs capability `calllog.delete`) |
| `block.list` | R→B, B→R | M4 | request: `id` / reply: `id`, `numbers` [`{n, time}`]. Also sent by B without `id` when the list changes on the bridge (needs `block`) |
| `block.set` | R→B | M4 | `id`, `add` [number], `remove` [number] → `block.list` with the same `id` (needs `block`) |
| `wifi.offer` | B→R | M5 | `ip` [address], `port`, `ssid` and `password` (only while B's hotspot is on, else null), `band` (`"2.4"` / `"5"` / `"6"` or null). Sent on B's event session after `status` and whenever any of it changes; `ip: []` means B has no Wi-Fi now |
| `wifi.media` | B→R | M5 | `port` (UDP) or null. On each Wi-Fi session, right after the handshake |
| `link.switch` | — | M5 | Not used: replies follow their request's session (D-044) |
| `file.offer` | either | M5 | `fid`, `name`, `size`, `type` (MIME or null). Wi-Fi session only; see "File transfer" |
| `file.answer` | either | M5 | `fid` (the peer's), `accept`, `reason` (`too_large` / `no_space` / `busy` / `declined` / `failed`, when not accepted) |
| `file.done` | either | M5 | `fid` (the peer's), `ok`, `reason` (`cancelled` / `failed` / `no_space`, when not ok) |
| `file.cancel` | either | M5 | `fid` (the sender's own), `reason` |
| `net.share` | R→B, B→R | M8 | request: `id`, `on` / reply (same `id`) or event (no `id`, when the bridge's user stops it): `on`, and while on `host`, `port` (one port for SOCKS5 and HTTP), `user`, `pass` (new for every start), `pac` (`http://host:port/proxy.pac`), `vpn` (the bridge's traffic goes through a VPN), `direct` [domain suffix]. `error` `permission` while the bridge's user hasn't allowed sharing. Needs `net.share` |
| `net.switch` | R→B | M8 | `id`, `what` (`"hotspot"`: the bridge phone's own Wi-Fi hotspot; `"bluetooth"`: Bluetooth tethering; `"data"`: mobile data on the data SIM) and `on`, or `what: "dataSim"` and `sim` (slot) → `ok` once Android applied it (turning a hotspot off is answered first, then done), then `status` shows it. `error`: `unsupported` (the helper isn't running), `permission` (the carrier's entitlement check or Android refused), `invalid`, `failed` (`msg` has Android's reason, in English). Sent only when the user flips the switch on R. Needs `net.switch` (D-073) |

### Capabilities (`status.caps`)

The bridge lists only what this phone can do right now: the permission is granted and the hardware or Android
version supports it. The iPhone hides or explains anything missing instead of guessing from the Android version.

`sms.receive`, `sms.send`, `sms.history`, `call.observe`, `call.control` (answer/reject/end), `call.dial`,
`call.dtmf`, `call.audio`, `contacts`, `calllog`, `contacts.add`, `ussd`, `dualsim`, `call.media` (call audio can
be relayed to the iPhone: "Talk on iPhone" is on and the bridge's helper is running, D-054), `call.media.talk` (the iPhone's microphone
can also be fed into the call; without it the iPhone listens and the user talks on the bridge phone),
`call.media.link` (listed with `call.media`: the call audio can also travel inside the Bluetooth session as Opus,
`via: "link"`; the bridge has an Opus encoder and decoder, D-072), `net.share`
(the bridge's user allows internet sharing, M8), `net.switch` (R may switch the bridge phone's hotspot, Bluetooth
tethering, mobile data and data SIM: Android 11 or later and Talk on iPhone's helper running, D-073), `sms.read`
(messages can be marked read on B, D-074), `call.hold` (with the companion call service, D-074), `contacts.edit`
(with `contacts.add`, D-074), `phone.find` and `phone.ringer` (D-074), `notif.mirror` (notification access given:
chosen apps' notifications are sent as `notif.posted`, D-077), `wifi` (M5: the bridge's Wi-Fi link is switched on), `files` (M5:
it can send and receive files over a Wi-Fi session), `calllog.delete` (delete call-log entries on the bridge, M4),
`block` (the bridge keeps a block list, M4)

R lists what it can receive in its own `device.info` as `caps` (M5): `["files"]` when it accepts `file.offer`. B
offers files only to an R that listed `files`; R offers only to a B whose `status.caps` has `files`.

### File transfer (M5, D-045)

Both directions, over the **Wi-Fi session only**: the `file.*` messages are control records on it, and the data goes
in `BULK` frames on the same connection. Without a Wi-Fi session, no transfer starts.

- **`fid`** is a 32-bit unsigned integer the sender picks, unique among its own transfers in the session. The sender's
  messages (`file.offer`, `file.cancel`, data) carry its own fid; the receiver's (`file.answer`, `file.done`) answer
  about the sender's fid. So every message type says whose fid it is.
- **`file.offer`:** `name` is the base file name in UTF-8, at most 255 bytes; receivers strip path separators and
  control characters and pick a free name when one is taken. `size` is in bytes, 0 to 1 GiB (1,073,741,824). `type`
  is a MIME type or null.
- **`file.answer`:** the receiver accepts automatically (both phones belong to the user who sent it) unless the file is
  too large, there's no room, or it's already receiving 4 files; then `accept: false` with a `reason`.
- **Data:** after `accept: true` the sender sends `BULK` records whose plaintext is
  `0x01 ‖ fid (4 bytes, big-endian) ‖ offset (8 bytes, big-endian) ‖ 1 to 49,152 bytes of the file`, offsets from 0
  with no gaps. A side sends one file at a time and finishes it before starting the next.
- **End:** when the receiver has all `size` bytes it saves the file and sends `file.done` `ok: true`. A data record
  with the wrong offset, an unknown fid or more than `size` bytes ends that transfer with `file.done` `ok: false`
  `reason: "failed"` (the connection stays up).
- **Cancel:** the sender sends `file.cancel` and stops; the receiver cancels with `file.done` `ok: false`
  `reason: "cancelled"`. Both drop partial data, and data that arrives for a finished fid is ignored.
- When the Wi-Fi session closes, its transfers fail; there's no resume in v0.
- Other first bytes in a `BULK` plaintext are reserved; receivers ignore records they don't know.

### Call audio (M6)

Call audio takes one of two paths, chosen by R in `call.media.start` (`via`):

- **`wifi`:** the Wi-Fi link's **media channel** (UDP, channel 1, media keys, 64-record replay window; see
  "Records"). Each media record's plaintext is one packet.
- **`link` (D-072):** `MEDIA` frames inside the Bluetooth (or debug TCP) session, or remote mode's direct connection
  (D-080), that the request arrived on (channel 3,
  link media keys, ordered). Needs `call.media.link`. Each `MEDIA` record's plaintext holds **one or more packets**,
  each preceded by its length (2 bytes, big-endian, at least 3): `length ‖ packet ‖ length ‖ packet …`, at most 1200
  bytes in all. A record that doesn't split exactly into such packets is ignored (the connection stays up).

A packet is the same on both paths:

| Bytes | Field |
|-------|-------|
| 1 | kind: `0x01` = 16-bit little-endian mono PCM at 16 kHz, one 20 ms frame; `0x02` = one Opus packet of 20 ms (D-072) |
| 2 | sequence number, big-endian, +1 per frame, wraps at 65 536 |
| 640 or 1–400 | 320 samples (kind `0x01`), or the Opus packet (kind `0x02`) |

- **Kinds per path:** `wifi` sends kind `0x01` (32 KB/s each way is nothing on a local network and adds no codec
  delay). `link` sends kind `0x02`: Bluetooth LE can't carry raw PCM reliably. Opus is encoded from 16 kHz mono with
  20 ms frames, the VoIP application and about 32 kbit/s (packets of about 80 bytes); a receiver accepts any valid
  Opus packet of 20 ms and resamples what its decoder outputs to 16 kHz. Receivers drop unknown kinds and wrong sizes.
- Packets are produced every 20 ms while the relay is active. Receivers play frames in sequence order behind a jitter
  buffer (60 ms on `wifi`; 80 ms on `link`, whose deliveries come in bursts) and cover a lost frame by repeating the
  previous one at half volume (twice at most), then silence.
- **Sending on `link`:** audio goes ahead of control frames that haven't started yet (a frame already being sent is
  finished first: BLE fragments of two frames never interleave). When the link can't keep up, the sender puts every
  packet that is waiting into the next `MEDIA` record instead of one record per packet, and drops the oldest
  packets beyond 200 ms waiting, so a stall never builds lasting delay. While a `link` relay runs, B asks Android for
  its fastest Bluetooth connection (high connection priority, 2M PHY where available) and returns to balanced after.
- R starts the relay with `call.media.start` once the call is in progress (on `wifi`: once its media channel is open),
  and sends silence (not nothing) while muted, so B's jitter buffer stays steady. Audio is never stored on either phone.
- The relay ends when the call ends, with `call.media.stop`, or when the session or channel it runs on closes; B then
  sends `call.media.state` `stopped`, and R may start it again on the other path.
- A PCM packet is 643 bytes, under the media channel's 1200-byte limit (M5). An Opus packet with its length prefix is
  about 85 bytes, so one `MEDIA` frame of a single packet fits one BLE fragment.

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

Added for deleting and blocking (D-043, 2026-11-24). New messages only; receivers ignore unknown types, and B answers
an unknown request with `error` / `unsupported`, so the wire version stays 0.

- **`calllog.delete`:** `keys` holds keys from `calllog.sync` (`"cl:<id>"`, at most 500 per request); `all: true`
  deletes the bridge's whole call log. B answers `ok` once Android has deleted them (keys it no longer has are
  ignored), `error` / `permission` without `WRITE_CALL_LOG`, `error` / `invalid` without keys. B lists
  `calllog.delete` only while it holds both `READ_CALL_LOG` and `WRITE_CALL_LOG`. Deleting the newest entries can make
  R's next `calllog.sync` cursor point past the newest entry, which starts a full sync (`full: true`) as before.
- **SMS can't be deleted on the bridge:** Android lets only the default SMS app delete from its SMS store, and
  Pairline isn't one, so there is no `sms.delete`. R deletes conversations and messages on its side only and keeps
  tombstones so `sms.threads` / `sms.history` / `sms.new` don't bring them back.
- **`block.list` / `block.set`:** B holds the authoritative list, so blocking works while R is away. A `numbers`
  entry is `{"n": "+15551234567", "time": <ms added>}`; `n` is a phone number (normalized like a dialable number) or
  a sender name such as `"BANK"` (1–64 characters). `block.set` takes up to 100 `add` and 100 `remove` strings; an
  `add` that isn't a number or sender name answers `error` / `invalid`. B keeps at most 500 entries, so a reply stays
  under the 30 KB page size.
- **Matching** (both apps): with 7 or more digits on both sides, two numbers match when their last digits agree, as
  many as the shorter has, up to 9 (`+98 912 123 4567` = `0912 123 4567`, `+354 555 1234` = `555 1234`). Shorter
  numbers and sender names match exactly (case-insensitive). `remove` removes every entry that matches.
- **Effect on B:** an `sms.new` from a blocked number isn't sent; received texts from it are left out of
  `sms.threads` (a thread of only those disappears; counts and snippets ignore them) and `sms.history`; texts sent to
  it stay. An incoming call from it gets no `call.incoming` / `call.state`, and B declines it when it can (companion
  `InCallService`, or `ANSWER_PHONE_CALLS`); incoming, missed, declined and blocked entries from it are left out of
  `calllog.sync`. A call R already shows when the number arrives late is left alone.

## Remote mode (v1, D-080)

Remote mode lets the phones work while apart: the iPhone gets calls, SMS and codes, and sends commands, through a
mailbox in the user's own iCloud, plus a direct connection through the bridge's home router when one is possible.
There is no Pairline server; Apple stores only the sealed records described here.

**Version 1.** v1 is v0 plus everything in this section. The local links (BLE, Wi-Fi, debug TCP) are unchanged and
keep version 0 on the wire (hello and associated data). New in v1: mailbox records (version byte 1), the remote
invitation (`v=1`), the `remote.*` and `rpair.*` messages, and the direct connection. An app that supports v1 sends
`proto: 1` in `device.info`; R offers remote mode only to a B that did. A v0 app ignores the new messages and never
sees a mailbox record.

### Mailbox records

A mailbox record is one message from one phone to the other, sealed on its own, so records can be lost, repeated or
reordered without harm. Its bytes (`sealed`) start with a header that is also the associated data:

| Offset | Size | Field |
|--------|------|-------|
| 0 | 1 | version: `0x01` |
| 1 | 1 | kind: `0x01` = sealed with the pinned identity keys, `0x02` = invitation record (remote pairing, below) |
| 2 | 1 | direction: `'B'` (`0x42`) = B→R, `'R'` (`0x52`) = R→B |
| 3 | 16 | record id: random; also the CloudKit record name (32 lowercase hex digits) |
| 19 | 8 | time: milliseconds since the Unix epoch, big-endian |
| 27 | 65 | kind `0x01` only: the sender's ephemeral public key |

**Kind `0x01`** (a one-way Noise K pattern: `e, es, ss`). The sender S creates a fresh ephemeral key pair `e` for
every record. With the recipient P's pinned identity key `sP`:

- `ikm = ECDH(e, sP) ‖ ECDH(sS, sP)` (64 bytes; P computes `ECDH(sP, e) ‖ ECDH(sP, sS)`)
- `key = HKDF-SHA256(ikm, salt = SHA-256(header), info = "pairline v1 mailbox", L = 32)`
- AES-256-GCM with that key, a 12-byte zero nonce (each key seals exactly one record) and the 92-byte header as
  associated data.
- `sealed = header ‖ ciphertext ‖ tag`

Only the pinned peer can produce the `ss` term, so a record that decrypts comes from it. The ephemeral key protects
past records if the sender's identity key leaks later; a leak of the recipient's identity key would expose records
still stored, which is why records are deleted as soon as they're handled.

**Receiving.** The recipient drops a record (and deletes it from the mailbox) unless: the version is 1, the kind is
known, the direction is the peer's, the time is no more than 14 days old and no more than 10 minutes ahead, the
record id isn't one it has already handled (it keeps handled ids for 14 days), and it decrypts. Drops are silent.

**Plaintext.** A UTF-8 JSON message from the catalog, exactly as in a session record, at most 49,152 bytes (both
kinds; a longer record is dropped before any crypto). The time is an unsigned 64-bit number. Requests from R carry an
`id` from a counter R keeps across restarts; B answers through the mailbox, or on a session if one is up.

### Mailboxes in iCloud

Records are CloudKit records of type `Mail` in Pairline's container (`iCloud.app.pairline.ios`):

| Field | Type | Use |
|-------|------|-----|
| `sealed` | string | the sealed bytes, base64url without padding, when at most 2,700 characters (it then rides in the push) |
| `payload` | bytes | the sealed bytes when larger (the recipient fetches the record) |
| `mailbox` | string, queryable | public backend only: the recipient mailbox id |

There are three mailboxes: **B→R alert** (things the user should see: an SMS, a code, a call), **B→R quiet**
(everything else: status, delivery reports, the bridge's address) and **R→B**. A mailbox id is 16 random bytes as
32 lowercase hex digits, chosen by R.

- **Private backend** (the design): the user's private database, zones `ToPhone` (alert), `ToPhoneQuiet` and
  `ToBridge`.
- **Public backend** (debug builds while D-080's question to Apple is open): the public database's default zone; a
  record's `mailbox` names its mailbox, and only the iCloud user who wrote a record can read it.

R subscribes to its two mailboxes: the alert one with an alert push that its notification service extension may
modify (it unseals the record and shows the message or rings the call), the quiet one with a silent push. B can't
receive Apple's pushes; it checks the R→B mailbox while no session is up: every 5 s for 2 minutes after a record from
R (so a conversation feels live), every 30 s while R was heard from in the last 10 minutes, otherwise every 2 minutes.
While R waits for a reply with the app open, it checks its quiet mailbox every 3 s, since silent pushes can lag. B writes and reads through CloudKit Web Services with a web auth token from
R; R uses CloudKit on the iPhone. The recipient deletes each record once handled; a sender deletes its own records
older than one day.

### Remote messages

| Type | Direction | Body |
|------|-----------|------|
| `remote.config` | R→B | `id`, `on`, `backend` (`private` / `public`), `env` (`production` / `development`), `token` (web auth token), `mb` `{b, a, q}` (the R→B, alert and quiet mailbox ids) → `ok`. `on: false` stops remote mode on B: it stops checking, closes its router mapping and listener, and deletes its records |
| `remote.token` | R→B | `token`: a fresh web auth token. R sends one in the R→B mailbox every 12 hours, and at once when its records there stay unread for 10 minutes |
| `remote.addr` | B→R | `v4` (`"203.0.113.5:47830"`) and `v6` (`"[2001:db8::5]:47830"`), either null: where the direct connection listens. Sent (quiet) whenever it changes; each one replaces the previous. Only public addresses: IPv4 not private, carrier-grade NAT, loopback, link-local, multicast, reserved or documentation ranges (so the examples here are refused); IPv6 in 2000::/3, not 2001:db8::/32, 2002::/16 or 2001::/23. R ignores anything else, and connects only to addresses from this message |
| `remote.state` | B→R | `direct` (`open` / `none` / `blocked`), `reason` (null when open): `cgnat` (the router's external IPv4 is carrier-grade NAT), `no_public_address` (it's private or otherwise not public), `no_mapping` (no NAT-PMP, PCP or UPnP mapping could be made), `not_wifi` (the bridge isn't on Wi-Fi or Ethernet), `listener_failed` (its listener couldn't start). Receivers show unknown codes as no reason |
| `remote.check` | R→B | `id` → `remote.check` with the same `id`: `direct` (`likely` / `unlikely`), `reason` (null when likely; otherwise `cgnat`, `no_public_address`, `no_mapping` (the router answers none of NAT-PMP, PCP and UPnP IGD) or `not_wifi`, as in `remote.state`), `router` (`nat-pmp` / `pcp` / `upnp`, the one a mapping would use, or null) and `ipv6` (B has a global IPv6 address). "Check my home connection": answered with remote mode on or off, over any session or the mailbox. **Read-only on the router:** B sends only NAT-PMP's external-address request (opcode 0), PCP's ANNOUNCE (opcode 0, lifetime 0) and UPnP IGD's discovery and `GetExternalIPAddress`, and never creates, renews or deletes a mapping. `likely` when one answered and the address it reported (if any; ANNOUNCE carries none) is public. The reply never holds an address. B answers `busy` while a check runs and `unsupported` if it's older; it may take up to about 15 s when the router is silent |
| `rpair.hello`, `rpair.nonce`, `rpair.reveal`, `rpair.confirm` | see below | remote pairing |

B→R events go on a session when one is up, otherwise in the mailbox: `sms.new`, `call.incoming` and missed calls in
the alert mailbox, everything else in the quiet one. `status` goes to the mailbox only when it changes, never on a
timer.

### Direct connection

B opens one TCP port on its router (NAT-PMP, then PCP, then UPnP IGD) and listens on it; R connects to the address in
`remote.addr` and runs the Wi-Fi session, version 0 on the wire (framing, handshake, messages), with two differences:
there is no UDP media channel and no bulk channel, and call audio rides inside the session as `MEDIA` frames with the
link media keys, exactly as on Bluetooth (`call.media.start` `via: "link"` sent on this session; see "Call audio").
No UDP port is opened on the router. R tries IPv6 first, then IPv4, only while no Bluetooth or Wi-Fi session is up
(a local session replaces the direct one), and retries after 5, 15 and 30 s, then every 2 minutes, and at once when its
network changes or a new `remote.addr` arrives. An address whose handshake fails on the keys is skipped until the next
`remote.addr`. On the public listener B:

- accepts no pairing frames: anything but `HELLO` first closes the connection;
- parses only the frame length and the hello before the handshake has authenticated the peer;
- closes a connection that hasn't completed the handshake within 10 s, keeps at most 4 such connections, and accepts
  at most 10 new connections per minute from one address;
- closes the mapping and the listener when remote mode is turned off or the pairing is forgotten.

### Remote pairing

Pairing while the phones are apart (D-080). It replaces "seeing the QR code" with an invitation the user sends by any
means plus a 6-digit code the two people compare on a phone call. Release builds start it only from "Pair a phone
that's far away" on the iPhone and accept it only after the person at home allows it on the Android phone.

1. R creates a 32-byte invitation secret `is` and the three mailbox ids, and shows or shares the invitation:
   `pairline://invite?v=1&k=<pubR>&s=<is>&b=<backend>&e=<env>&t=<token>&m=<R→B id>&a=<alert id>&q=<quiet id>&x=<expiry>`
   (`k`, `s` base64url; `t` percent-encoded; `x` Unix seconds, 10 minutes ahead). A receiver accepts it only if: the
   path is empty; each of these parameters appears exactly once (unknown ones are ignored); values are percent-decoded
   (a raw `+` stays `+`); `v` is `1`; `k` is base64url without padding of a point on P-256 and `s` of 32 bytes; `b` and
   `e` are exactly one of the values in `remote.config`; `t` is non-empty printable ASCII without spaces (0x21–0x7E);
   `m`, `a` and `q` are 32 lowercase hex digits and all different; `x` is 1 to 12 decimal digits. Whether it has
   expired is checked separately, and release builds refuse `b=public` while that backend is debug-only.
2. Invitation records (kind `0x02`) are sealed with
   `kI = HKDF-SHA256(is, salt = empty, info = "pairline v1 invite", L = 64)` (B→R the first 32 bytes, R→B the last 32),
   the first 12 bytes of the record id as the nonce and the 27-byte header as associated data.
3. B, once its user allows it, creates a 32-byte `nonceB` and sends (kind 2)
   `{"t":"rpair.hello","k":<pubB>,"id":<B device id>,"c":<commit>}` with
   `commit = SHA-256("pairline v1 commit" ‖ pubR ‖ pubB ‖ nonceB)`.
4. R accepts only the first `rpair.hello` for an invitation (a second one means someone else has it: R cancels and
   tells the user), creates a 32-byte `nonceR` and sends `{"t":"rpair.nonce","n":<nonceR>}`.
5. B sends `{"t":"rpair.reveal","n":<nonceB>}`. R checks it against `commit` and cancels on a mismatch.
   Every `rpair.*` field is checked before use: `k` a 65-byte point on P-256, `id` 8 bytes, `c` and `n` 32 bytes
   (base64url without padding); anything else cancels the pairing.
6. Both show `code`: the first 4 bytes of `HMAC-SHA256(is, "pairline v1 code" ‖ pubR ‖ pubB ‖ nonceR ‖ nonceB)` as a
   big-endian number, modulo 1,000,000, as 6 digits. The two people read it to each other; each taps "The codes
   match" or cancels.
7. On a match each side pins the other's identity key and sends `{"t":"rpair.confirm"}` as a kind 1 record. The
   pairing is complete on a side when its own user confirmed and the peer's confirm decrypted. B takes its
   `remote.config` from the invitation.

Because B commits to `nonceB` before it sees `nonceR`, someone who copied the invitation and put their own key in the
middle gets matching codes with a chance of one in a million per try, and every try is visible to the users.
The invitation expires after 10 minutes (steps 3–5), and both sides give up 10 minutes after the codes appear.

## Test vectors

`protocol/test-vectors/v1-remote.json` covers remote mode: kind 1 and kind 2 mailbox records, the invitation link,
the commitment and the comparison code. It is generated by `protocol/tools/gen_vectors_remote.py` with the same fixed
keys as v0.

`protocol/test-vectors/v0.json` holds fixed keys and inputs with the expected ECDH results, HKDF outputs, pairing
MACs, hello bodies, session keys, confirm records, encrypted records (control, media and bulk), records that must
fail, the media replay window, a pair link with the Wi-Fi parameters, BLE fragments and TCP framing, and the link
media keys with a `MEDIA` frame of two batched packets (D-072). It is generated by an independent Python implementation (`protocol/tools/gen_vectors.py`). Unit tests
in both apps must reproduce every value.

## Open questions for review

- Should pairing add a short comparison code (like Bluetooth numeric comparison) for users who
  pair in public?
- JSON vs CBOR inside records. JSON for v0 readability; CBOR may be worth it for BLE bandwidth.
- Whether `hello` should be padded or encrypted to hide protocol fingerprinting on BLE.
- Supporting more than one paired peer per device.
