# CleanMyTV Android companion: implementation contract

Written before implementation. Baseline: CleanMyTV **v0.7.0**, commit
`b73b330e264107ca51a34bf84dd07095b13e517d`; Home **1.7.1**, versionCode 19.
This is an unofficial smartphone port. The original desktop and Home source remain unchanged.

## Source analysis
The Electron main process routes typed IPC to `tv`, `debloat`, `launcher`, `home`,
`system`, `themes`, `discover`, `bootcheck`, `doctor`, `toolchain`, and `web`.
The React shell has six ordered stages and eight Home tabs. It uses JetBrains Mono,
10 dp cards, 6 dp buttons, dark background #1c2a1f, card #2d3a2e, muted #252f26,
primary #4caf50, foreground #f0ebe5, border #3e4a3d, warning #e0a100.

The desktop native TCP transport implements CNXN/AUTH/OPEN/OKAY/WRTE/CLSE,
2048-bit RSA with a 20-byte challenge, bounded frames and negotiated payloads.
Desktop Wireless Debugging relies on an external ADB tool; the phone must replace it.
Discovery reads `_androidtvremote2`, `_adb-tls-connect`, `_adb-tls-pairing` mDNS.

Debloat data is pinned separately to Gathin23/cleanyourtv commit
`d4ac32c344987ae4e8faf1ee32d8b9572fb57fe3`; content SHA-256
`80b39939892ce1a13abb2fd7254b6d3acdec5db842dbfe8067bd5b91834d6ff5`.
371 profile directories have been validated by the upstream tree-hash implementation.
Package states, original Home component and settings are the restoration baseline.
Upstream family inference and safety overrides are deliberately excluded by the user's
stricter exact-model and protected-device requirements.

Home exposes only shell/root-authorised `content call` on
`content://io.github.wingedpanther.cleanmytv.home.config`.
`get`, `set64`, `apps`, `keys`, `lock_pin`, `lock_clear`, wallpaper begin/chunk/end/read/remove,
pack begin/meta/file/end/clear, slides begin/file/end/clear and fire_dir/ready/clear
are the actual 1.7.1 methods. No invented Home RPCs are permitted.
Provider image transfers use MD5 as a protocol checksum, maximum 4 MiB per JPEG;
phone-side snapshots additionally use SHA-256. Slides support up to 12 pictures;
28 original themes have 3 deterministic procedural pictures each.

Fireplace uses the upstream fireplace-1 release: fireplace.mp4 (9,683,738 bytes,
SHA-256 35a99bfd61111e05ef5941a8dfcc0cdde08f4359ee0d2ac1b0a2fe254d28727c),
crackle.m4a (1,120,535 bytes,
e941736970cf0074c2bcf22d323c01ab9fcbb2bcfcad5b164b25724ba8a630a8).
Home opens fullscreen via `am start -n <home>/.MainActivity --es open fireplace`,
wallpaper via fire_wall, screensaver via `<home>/.FireplaceDream`, timer 0..240 min.
Themes/slides are staged by Home, but 1.7.1 does not expose APIs to read old slide
or theme-pack bytes. Replacement without a verified phone copy must be blocked
or clearly require a separately reviewed irreversible operation; never claim rollback.
PIN hashes are not readable; an existing unknown PIN cannot be backed up.

## Feature acceptance matrix
| Stage | Required behavior | Original reference |
|---|---|---|
| Clean & Launch | Guided exact profile review, cleanup, Home installation, final verification; explicit approved steps | Guided.tsx |
| Setup | Wi-Fi discovery, manual TCP/RSA, SPAKE2/TLS pairing, USB OTG, explicit TV selection, diagnostics, recovery | Setup.tsx, adb/, discover.ts |
| Clean up | Enumerate installed/disabled apps; exact profile; risk and core guards; snapshot; disable/restore; firmware drift | debloat.ts, fake-adbd.ts |
| Home overview | Signed 1.7.1 installation/update preserving data; launch/default handover; verified stock restoration | launcher.ts, home.ts |
| Layout | Shelf/Split/Rail; sections, sizes, clock, greeting, quick actions, idle | Config.kt, HomeScreen.kt |
| Apps, rows & folders | Order, hidden apps, dock, up to 8 rows and 12 folders of 60 apps | Config.kt, AppOrder.tsx |
| Look & wallpaper | 28 exact themes and original procedural artwork; custom themes; import/export; wallpaper and slideshow | shared/themes.ts, lib/themes.ts, Slides.kt |
| Widgets / Music bars | Weather place/coordinates/unit, clock; bars/mirror/wave, audio permission and parameters | Weather.kt, Visualiser.kt, Bars.kt |
| Remote buttons / App lock | Supported key actions, key finder, accessibility preservation; PIN creation and recovery | KeyService.kt, Lock.kt |
| TV extras | Private DNS, three animation scales, phone remote, Google screensaver, cache trim, restart | system.ts |
| Finish | Restart/read-only health check; recovery records; close-debugging advice and honest port check | bootcheck.ts, Finish.tsx |

## Architecture and safety
`:core` is JVM-testable protocol/domain/safety and recovery logic. `:transport` adapts
Android TCP, USB, NSD, keys and pairing. `:pairing` vendors existing
SPAKE2 implementation (no homegrown cryptography). `:app` is native Kotlin Compose UI,
Android document pickers, private storage, lifecycle and foreground operation service.
An isolated renderer retains original deterministic Canvas artwork; it has no network access.

Every mutation is bound to the explicitly selected TV's serial/android_id, model, device,
manufacturer and firmware. Unknown identity or non-TV devices are read-only. Fresh identity
is checked before every write. MiTV-MZTU1 is denied at the shared mutation boundary, even
for restoration, remote input or media transfer. No physical TV is used in cloud testing.

Preparation reads original state, validates compatibility/recovery, persists a checksummed
atomic snapshot, then returns an immutable concrete plan. The user approves that plan.
Execution rechecks identity and preconditions, journals before writing, independently reads
results and runs only the approved reverse plan on failure. Recovery failures remain pending.
Interrupted records are visible after app restart and require review before recovery.
No uninstall, factory reset, app-data clear or Wi-Fi/setup-wizard removal is allowed.

## Validation plan
JVM fake-TV tests exercise identity changes, protected model, stale approval, backups,
package restoration, failed writes, network interruption, Home handover and media checksums.
Loopback fake-adbd exercises packet fragmentation, RSA, malformed frames and shell closure.
Compose UI tests verify stages, disconnected safety, confirmation and English labels.
Build APK, run lint/tests, verify APK signing and SHA-256; emulator tests when available.
Physical TV, Wireless Debugging vendor variants, USB host hardware and playback quality
must be reported separately from simulated evidence.
