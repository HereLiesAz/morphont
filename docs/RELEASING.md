# Releasing Morphont

Every release runs centrally in [HereLiesAz/workflows](https://github.com/HereLiesAz/workflows).
This repository holds only trigger contracts; the central sync replaces each with a tracker that
reports the central result as a commit status. Signing keys, the Play service account and the
write token live in the `morphont` environment of `HereLiesAz/workflows`, never here.

| Target | Contract | Central workflow | Output |
| --- | --- | --- | --- |
| Android | `.github/workflows/android-release.yml` | `android-release.yml` | Google Play (internal, alpha, beta live; production as a draft) and a GitHub Release with the APK |
| Desktop | `.github/workflows/desktop-release.yml` | `multi-platform-app-release.yml` | `.msi` (Windows), `.dmg` (macOS, Apple silicon and Intel), `.deb` and `.rpm` (Linux) on the patch-grouped GitHub Release |
| Web (PWA) | `.github/workflows/pages.yml` (runs locally) | — | <https://hereliesaz.github.io/morphont/> |

All three run on a push to `main` that touches their sources, and Android and Desktop can be run
by hand from the Actions tab.

## Versions

`version.properties` is the one source.

- `versionMajor`, `versionMinor`, `versionPatch` are set by hand when a release means it.
- Android: `versionCode = max(recorded, highest Play ever accepted) + 1`; `versionName` raises
  the last field of the recorded four-part name. The central job writes the published pair back
  (`versionCode`, `versionName`, `versionBuild`) with a `[skip ci]` commit. Gradle receives
  `-PversionCode`/`-PversionName` and never increments anything itself.
- Desktop: `major.minor.patch.<run>` on asset names, grouped under the `vmajor.minor.patch`
  Release. macOS refuses a `0.x` bundle version, so the DMG's internal version is `1.minor.patch`.

## Signing

The central job exposes the upload key to Gradle as `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS` and `KEY_PASSWORD` (`androidApp/build.gradle.kts`; the older `MORPHONT_`-prefixed names
still work locally). Release builds run R8 so Play receives `mapping.txt`
(`androidApp/proguard-rules.pro` keeps the serialization model).

Desktop installers are unsigned.

## One-time setup outside this repository

- `morphont` environment in `HereLiesAz/workflows`: `GH_TOKEN` (also needs `read:packages` for
  `HereLiesAz/convey`), `KEYSTORE_RAW` or the legacy `KEYSTORE_PRIVATE` + `KEYSTORE_CHAIN`,
  `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `PLAY_SERVICE_ACCOUNT_JSON`.
- Google Play Console: the app `com.hereliesaz.morphont` must exist, with its first bundle
  uploaded by hand (the Play API cannot create an app), the service account granted release
  access, and the privacy policy URL set to <https://hereliesaz.github.io/morphont/privacy.html>.
- GitHub Pages: source set to **GitHub Actions** (already done; the site is live).

## Privacy

[`docs/PRIVACY.md`](PRIVACY.md) is the policy; `src/wasmJsMain/resources/privacy.html` is its
published copy, regenerated from it.
