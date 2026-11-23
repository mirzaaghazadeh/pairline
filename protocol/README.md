# Protocol test vectors

`test-vectors/v0.json` holds the expected values for the link protocol in `../docs/PROTOCOL.md` (wire version 0):
ECDH results, HKDF outputs, pairing MACs, hello bodies, session keys, confirm records, encrypted records, records
that must fail to decrypt, BLE fragments and TCP framing.

Both apps' unit tests load this file and must reproduce every value with their platform crypto (Android JCA,
iOS CryptoKit):

- Android: `android/app/src/test/…`, reading `../protocol/test-vectors/v0.json`
- iOS: the `PairlineTests` target, with `v0.json` added as a test resource

## Regenerating

The vectors come from an independent Python implementation using `pyca/cryptography`. It's a dev tool only and is
never shipped in an app:

```sh
python3 -I protocol/tools/gen_vectors.py > protocol/test-vectors/v0.json
```

All inputs are fixed, so the output only changes when the protocol changes. A change to the output is a wire-format
change: update `docs/PROTOCOL.md` in the same commit.
