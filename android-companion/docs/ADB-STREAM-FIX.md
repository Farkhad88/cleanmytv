# ADB stream lifecycle correction — 0.7.0-port.2

The reported physical-device failure happens after identity discovery, when confirming the TV opens another shell stream. The old client returned on the first `CLSE` and then required every packet read by the next command to address the new stream. A fake ADB peer reproduces the exact "ADB replied on an unexpected stream" failure by delivering a late close for the preceding command. No packet capture from the physical TV was available, so the particular late packet sent by that firmware is not claimed to be confirmed.

## Packet routing and teardown

- Shell commands and authentication share one reader monitor. Each command has a unique, never-reused local ID and exactly one remote ID established by `OKAY`. Remote IDs retain their full unsigned 32-bit representation.
- The last 128 successfully closed local/remote pairs are retained. Matching late `OKAY`/`CLSE` packets are handled as retired-stream control notifications. A `CLSE(0, oldLocal)` is also permitted for a known retired ID: adbd can send this after deleting its socket before receiving the host's final acknowledgement. These notifications never open the active stream or trigger another close acknowledgement.
- Unknown destinations, mismatched nonzero remote IDs, data for a closed stream, writes before `OKAY`, duplicate active `OKAY`, malformed control payloads, and invalid active closes terminate the transport. A rejected `OPEN` is an error, not successful empty output.
- Retired history and late notifications per command are bounded. Header/checksum validation, negotiated payload limits, the 64 MiB response bound, and an absolute command deadline remain enforced. Fragmented reads cannot indefinitely extend the deadline.
- Closing is idempotent and does not wait for the shell lock; it interrupts a blocked read. Reconnection requires a fresh transport and handshake.

## Confirmation and identity

`SelectedTv.connect` requires a verified TV identity before creating a candidate. The app's confirmation and refresh share `readVerifiedState`: verify identity, read state, then verify identity again. The UI publishes its selection only after the complete confirmation snapshot succeeds. Transport and identity failures close the session and clear the selected/candidate state. Optional Home provider parsing cannot conceal transport errors.

The Xiaomi MiTV-MZTU1/MZTU1 mutation guard remains enforced in the shared write boundary. The regression peer uses an Android 14/API 34 Xiaomi identity, but all test sockets bind only to loopback; no physical TV is contacted or modified.

## Automated regression coverage

The new suites contain 40 tests (31 stream tests and 9 TV connection tests), in addition to the original 25 host tests. The initial regression suite failed 6 of 11 tests against the old client, including both late-control-packet cases. The corrected implementation passes the expanded suite.

| Required scenario | Coverage |
|---|---|
| Consecutive shell commands | Eight commands and remote-ID reuse; 129 commands exercise retired-history eviction |
| Late close from previous command | Matching remote ID and zero-sender close, before the next handshake and between response chunks |
| Duplicate closes | Repeated notifications without close ping-pong or output contamination |
| Delayed acknowledgements | Delayed current handshake; retired `OKAY`; stale acknowledgement cannot authorize a new stream |
| Fragmented responses | Byte-fragmented frames, multiple acknowledged `WRTE` packets, split UTF-8 and CRLF |
| Unexpected ordering | Write before handshake, duplicate active acknowledgement, rejected open, data after close, unexpected connection packet |
| Repeated identity checks | Twelve independent identity streams with injected late controls |
| Connect → confirm → refresh | Shared application connection/snapshot path, Home configuration/apps and protected-device guard |
| Disconnect/reconnect | Blocked read interrupted, repeated close, fresh transport/handshake and identity verification |
| Changed identity / invalid packets | Changed serial/firmware, incomplete/non-TV/invalid identity, saved-address mismatch, checksum/header/payload/ID validation |

The Android artifact workflow runs core/native host tests, application unit-test tasks, lint, and complete debug/application-test APK assembly. It verifies the APK signature, uploads APK/source/reports/checksums, downloads the uploaded bundle again, and runs `sha256sum --check`. Exact run links and artifact hashes are recorded in PR #1 after publication.

UI instrumentation is compiled but not executed by this workflow. Physical Android-to-TV compatibility, including confirmation on the reported Xiaomi, remains unverified until a human tests the new APK on hardware. Other feature-parity limitations in PARITY-REPORT.md remain unchanged.
