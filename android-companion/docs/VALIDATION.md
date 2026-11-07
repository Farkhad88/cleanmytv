# Validation results

Historical results from the v0.7.0 implementation checkout. The publication branch is based on the fork's current main, keeps desktop source unchanged, and adds pinned v0.7.0/Home 1.7.1 references. No new local build was run for publication.

Build command completed successfully:

`./gradlew :core:test :spake:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon`

- 21 Android-port JVM/domain/loopback integration tests: passed.
- 4 native SPAKE2 JNI host tests: passed (including 20 matching exchanges, wrong password, wrong identity and malformed message).
- Original v0.7.0 desktop suite: 155 tests in 10 files passed.
- Android debug APK and Android instrumentation test APK: assembled successfully.
- Lint: 0 errors, 7 warnings. Warnings include dependency trust managers (pairing uses PAKE authentication and subsequent sessions use a pinned key), backup compatibility metadata, Compose naming and style suggestions. They remain visible in the included report; no blanket lint baseline suppresses them.
- Debug APK signature verified with apksigner. Package dev.cleanmytv.companion, version 0.7.0-port.1, min Android API 28, target API 35.
- Home 1.7.1 built from unchanged upstream Kotlin source; original signing certificate verified (cf511447bac0b9fb79767fc8f3499328e5082e0fb41ee764984adaa2c7cc0620).
- APK contains SPAKE2 JNI binaries for arm64-v8a, armeabi-v7a, x86 and x86_64.
- Both official Fireplace assets match upstream size and SHA-256.
- Original tracked desktop/Home source diff against the mandatory commit: empty. Original /workspace/cleanmytv checkout also remains clean.

UI instrumentation tests were compiled, not executed in this workspace. No Android emulator/system image or /dev/kvm is available here. GitHub Actions includes emulator execution, but the workflow has not been run remotely. No physical television was contacted or modified. TLS/USB/pairing and all vendor operations still need physical-device qualification. See PARITY-REPORT.md for functional gaps; build success is not a full-parity claim.

The failed third-party Java reference implementation has been preserved outside the active build under experimental/. It is not included in the APK. Active production pairing uses the native implementation and the passing native tests.
