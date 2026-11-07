# CleanMyTV Android port — feature parity and verification

Baseline: official CleanMyTV v0.7.0, commit **b73b330e264107ca51a34bf84dd07095b13e517d**.
This is an **unofficial development build**, not an official upstream release and not a claim of complete production parity. The original desktop and Home sources have not been edited. The pre-implementation specification is PARITY-SPEC.md.

## Implemented behavior

| Area | Delivered implementation | Limits / remaining verification |
|---|---|---|
| Identity and design | Native Kotlin/Compose smartphone UI; original six stages, eight Home tabs, JetBrains Mono, upstream dark-green palette, English labels | No screenshot comparison or real-phone usability acceptance yet; complex lists use JSON editors rather than desktop drag-and-drop |
| Clean & Launch | Guided links through reviewed cleanup, Home installation and restart check | Guided checklist, not the original automatic multi-step orchestration |
| Setup | Local mDNS discovery; manually entered IP/port; dedicated RSA identity; explicit device confirmation; encrypted saved TVs | Wi-Fi discovery and reconnect behavior require physical network testing; no automatic TV selection |
| Direct ADB | Native Kotlin packet transport over TCP and Android USB Host; bounded payloads, 20-byte RSA challenges, shell streams, timeouts | TCP loopback test executed; USB electrical/interface compatibility untested |
| Wireless Debugging | TLS exporter-bound SPAKE2 pairing using vendored native implementation; paired server public-key pin for later TLS sessions | Cryptographic exchange tested on Linux host; full Android TLS/TV pairing not exercised; independent security review required before production distribution |
| Clean up | 371 pinned profiles; strict model/manufacturer/SDK matching; installed/disabled enumeration; explicit package selection and risk labels; guarded core/network/setup/launcher packages | Model alias extraction is conservative and needs device-specific review; no family fallback; no dedicated firmware-leftover dashboard |
| Recovery | Encrypted private snapshots, SHA-256 envelopes, atomic writes, single-use/expiring approval, fresh identity check before every mutation, checked inverse commands, persistent interrupted-operation records | Fake-TV tests cover state changes, interruption, restore and protected TV. Physical firmware recovery not verified |
| Protected owner TV | MiTV-MZTU1 blocked at shared mutation boundary, including remote input and rollback | Read-only identity queries remain available; no physical TV contacted |
| Home install | Source-built Home 1.7.1, versionCode 19, original package and upstream signing identity; staged/checksummed APK; launch-before-handover; stock recovery | Existing-version updates require Android rollback support. Vendor rollback CLI behavior remains untested; operations fail closed when support cannot be established |
| Home layouts | Shelf/Split/Rail; section order, dock/tile sizes, columns, quick actions, clock, greeting, idle settings | Native form controls; sections and app lists are validated JSON arrays |
| Apps/rows/folders | Installed Home app listing; order, hidden packages, row/folder JSON editors with original limits | No touch drag-and-drop or rich folder picker yet |
| Look/wallpaper | All 28 theme definitions; original deterministic procedural Canvas renderer; three variations; custom palette/art; JPEG upload; theme import/export; initial theme-pack transfer | Renderer runs in isolated, network-blocked Android WebView; on-device rendering not exercised |
| Slideshow | Initial upload of up to 12 prepared pictures; provider checksums; interval/source settings | Existing slide replacement is blocked because Home 1.7.1 cannot return original slide bytes for verified recovery |
| Theme pack | Initial transfer of 84 original pictures; provider checksums; carousel metadata | Replacing an existing pack is blocked for the same backup API limitation. Individual themes/wallpapers can be applied and restored |
| Widgets | Weather place search, manual coordinates/unit, clock | Open-Meteo Android network behavior untested; no automatic location permission used |
| Music bars | Original visualiser options, bars/mirror/wave, parameters and reviewed RECORD_AUDIO grant | Actual mixed-audio capture and notification-listener behavior untested on TV |
| Remote buttons | Key-log diagnostics, validated mappings, supported actions; accessibility-service setting preservation | Mapping editor uses JSON; vendor key interception requires real-TV testing |
| App lock | PIN creation when none exists, lock settings and accessibility integration; rollback clears newly created PIN; disable-lock recovery | Existing PIN replacement blocked because hashes cannot be exported; no false PIN backup claim |
| Fireplace | Official video/audio bundled and checked against upstream SHA-256 and exact sizes; verified staged transfer; fullscreen, wallpaper, sound/volume/timer and screensaver settings | Physical video/audio playback and timer untested; no arbitrary asset replacement; no remove-assets UI |
| TV extras | Private DNS, three animation scales, remote button input, Google screensaver restoration, cache trim request/storage refresh | Remote actions are explicit taps and cannot be undone; cache trim is explicitly non-reversible; vendor command support untested |
| Finish | Read-only health checks, approved reboot/reconnect/Home process check with stock recovery, debugging-port probe, diagnostics export, recovery list | Reboot/port checks untested on physical TV; timeout is correctly inconclusive |

## Safety limitations that remain material

The implementation is a substantial development port, not yet a complete, production-qualified companion. Before routine physical-TV use, review installation/update rollback behavior, native pairing integration, command compatibility and the broad settings editors. A successful build is not proof that every TV firmware supports every operation.

The pairing dependency's pure-Java reference implementation failed its own tests. It is preserved under `experimental/java-spake-reference/` for investigation and is **excluded from the application and active tests**. The application uses the upstream JNI/native implementation instead. Its source adaptations are documented in DEPENDENCIES.md, including OS secure randomness, idempotent destruction and avoiding a double free on rejected messages. This is not a cryptographic audit.

No Windows PC, desktop ADB executable, root or external intermediary is used by the app. No physical TV was modified during development. Emulator/UI instrumentation execution was not available in this workspace: no emulator/system image is installed and `/dev/kvm` is absent. UI tests are supplied and compiled; the publication workflow does not execute an emulator or UI instrumentation. The 0.7.0-port.2 ADB lifecycle correction and its fake-peer regression coverage are documented in ADB-STREAM-FIX.md; physical-device confirmation of that fix remains pending.

## Acceptance status

Downloadable debug APK and source are provided with automated host validation. Full user acceptance — complete v0.7.0 parity and real Android-to-TV workflow qualification — remains **incomplete** for the gaps and hardware checks above. Do not describe this artifact as production-ready or fully verified.
