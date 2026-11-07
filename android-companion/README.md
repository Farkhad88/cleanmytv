# CleanMyTV for Android — unofficial development port

Android Studio project based strictly on CleanMyTV v0.7.0 commit
`b73b330e264107ca51a34bf84dd07095b13e517d`. Read [the parity report](docs/PARITY-REPORT.md) before use. This development build is not production-qualified.

## Install and connect

1. Copy `CleanMyTV-Android-debug.apk` to an Android 9+ smartphone. Verify its SHA-256 against SHA256SUMS.
2. Open the APK on the phone and temporarily allow installation from that file manager. This is a debug build for personal testing.
3. Put phone and TV on the same local network. Enable TV Developer options and USB/network debugging. For Wireless Debugging, use Pair with a code first, then enter the separate connection port.
4. In Setup, enter the TV address or select a discovered address, tap Connect, approve the RSA fingerprint on the TV, and explicitly confirm the displayed TV identity.
5. Review changes before applying. Recovery records remain under Finish. Leave debugging enabled until the restart check has succeeded.
6. After finishing, turn debugging off on the TV and revoke this phone's authorization if desired.

USB OTG requires an Android USB-host phone, suitable adapter/data cable and a TV exposing an ADB USB device interface. Most TV USB ports are host-only; availability cannot be inferred from the connector.

The owner's Xiaomi MiTV-MZTU1 is read-only. Core/network/ADB/launcher components are guarded. Cache cleanup and remote key presses cannot be undone. The app explains unsupported safe-recovery cases.

## Build

Open this directory in Android Studio. Use JDK 17, Android SDK 35 / build tools 35.0.0. The bundled Home APK is built from the unchanged original Home 1.7.1 source with its upstream signing key. It preserves package `io.github.wingedpanther.cleanmytv.home` and signing certificate SHA-256 `cf511447bac0b9fb79767fc8f3499328e5082e0fb41ee764984adaa2c7cc0620`.

The prebuilt SPAKE2 JNI libraries for arm64-v8a, armeabi-v7a, x86 and x86_64 are included. To rebuild them on Linux, install NDK 27.2.12479018, export JAVA_HOME and ANDROID_HOME, and run `python3 scripts/build-native.py`. Host native tests additionally require GCC/G++.

```
python3 scripts/build-native.py
./gradlew :core:test :spake:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

The signed Home 1.7.1 APK is included and verified against `home-info.json`. Its exact v0.7.0 source is preserved in `upstream-home-1.7.1/`; the fork's main-branch `home/` source is not used by this Android port. Rebuilding Home for an update requires separately configured signing credentials; do not commit them. The normal companion build and CI do not require release-signing secrets.

The companion debug APK uses a development key. A permanent release requires your own protected signing key, release signing configuration, secure dependency review, physical-TV qualification and stable update distribution. Do not use the upstream Home key to sign the companion.

## Architecture, data and attribution

`:core`: identity, ADB wire protocol, plans, recovery, Home schema, installer and media checks.
`:transport`: Android TCP/TLS, USB Host, NSD and encrypted credentials.
`:pairing` / `:spake`: existing libadb pairing protocol and native SPAKE2 bridge.
`:app`: native Compose UI, foreground operations, document pickers and private storage.
`:home-bundle`: separate build adapter over the pinned original `upstream-home-1.7.1/` source (SDK/target 35 for this build, versus upstream compile 37/target 36).

Recovery data is encrypted with an Android Keystore key, excluded from OS backup, and bound to the selected TV identity. Losing/uninstalling the phone app loses this local recovery storage. Theme exports intentionally include the chosen appearance and wallpaper; diagnostics omit PINs, pairing codes and settings.

Original CleanMyTV copyright and MIT license are preserved in the repository and `licenses/`. JetBrains Mono uses OFL. libadb is used under its Apache-2.0 option; native SPAKE2 and the Java bridge retain their included licenses. Full corresponding source and build scripts are supplied so the LGPL bridge can be modified and relinked. See DEPENDENCIES.md.
