# Dependency provenance

- CleanMyTV v0.7.0: b73b330e264107ca51a34bf84dd07095b13e517d (MIT). Desktop and Home source unchanged.
- Pinned debloat data: Gathin23/cleanyourtv d4ac32c344987ae4e8faf1ee32d8b9572fb57fe3. Original tree hash checked before generating 371 profiles.
- LibADB Android 3.1.1: source archive SHA-256 3afbe40b278ddea4480ebb8e7b707404f827d8e41b383e12f4b1b0b82dab1d84; Apache-2.0 option. Only pairing helpers vendored. Changes: connection/read timeout, authenticated peer certificate access, GUID type validation, null-safe cleanup, no peer-info logging, no static SSLContext reuse across keys.
- spake2-java 2.2.1: archive SHA-256 ee08d37d187eca2868a66aa49493fc5754e674e506cfeef03300d9b219860e59. Native Java/JNI bridge retained, LGPL-3.0; native implementation source is vendored under native-spake/src with its original Google/Muntashir copyrights and permissive license. Pure-Java reference implementation is excluded under experimental/ after failed upstream tests.
- Native C archive SHA-256 e41b94f08792ae3ede7f07ed498df83f9c6682d6fe4bbb4238c9c4acaa0977ef. Source: vendored from MuntashirAkon/spake2-c master at development time; the exact shipped files are the reproducible source baseline. Changes replace non-secure rand() ephemeral generation with fail-closed OS getrandom(), add stdint header, and leave context ownership to idempotent Java destruction instead of freeing it twice after rejected input. No protocol or elliptic-curve arithmetic was redesigned.
- Conscrypt Android 2.5.3, BouncyCastle 1.81, Kotlin 2.1.20, Compose BOM 2025.04.01, AGP 8.9.2 and Gradle 8.11.1. Pinned versions appear in Gradle Kotlin DSL.

A successful native exchange test is not an independent cryptographic audit or proof of Android TV interoperability. Full pairing and vendor firmware validation remain required.
