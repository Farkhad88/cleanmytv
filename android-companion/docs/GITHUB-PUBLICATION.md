# GitHub publication

The development branch is based on fork main `5ddaacb6e3dc5f85e55574307c63e924e3bf0140`.
The desktop application, root `home/`, existing workflows and upstream MIT license are unchanged.
The port's functional baseline remains CleanMyTV v0.7.0 `b73b330e264107ca51a34bf84dd07095b13e517d`.
`upstream-home-1.7.1/` and `upstream-reference/` contain exact pinned copies so the newer desktop sources on main do not silently change the Android port.

The companion APK is never committed to Git history. The smaller, signed Home 1.7.1 APK and verified official Fireplace media are required bundled assets and are included. No new signing keystore, password, token, machine SDK path or release-signing credential is included.

`.github/workflows/android-companion.yml` runs on pushes to this development branch and declares `workflow_dispatch`. It installs JDK 17 / SDK 35 / NDK r27c, verifies source and assets, builds the native libraries, runs host tests and lint, builds the companion and UI test APKs, and uploads source, APKs, parity report and SHA-256 checksums. Artifact retention is 90 days. The exact artifact URL is written to the job summary.

GitHub normally requires a workflow to exist on the default branch before manual dispatch is available. The initial development-branch build therefore starts from the push event; after the workflow is merged, manual dispatch is available from Actions. This PR is not merged automatically.

The workflow rebuilds a debug APK with the runner's development signing key. Its SHA-256 may differ from the previously built APK. Use the checksum inside that run's artifact; do not assume binary identity.

Previously built APK SHA-256:
`99d7614346b0c6ef787d1fb2c128d524bf4223743b49dc9606d8c016a8e9e653`.

The existing parity and validation reports describe the original implementation checkout and its earlier build/tests. Publishing from fork main does not represent a new local build or completed physical-TV verification.
