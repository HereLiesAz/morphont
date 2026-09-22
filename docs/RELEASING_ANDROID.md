# Releasing Morphont for Android

Android publishing is executed by the centralized `HereLiesAz/workflows` controller. Morphont contains only a secretless OIDC proxy; signing keys, the GitHub write token, and the optional Google Play service account remain in the centralized workflows repository.

> **Currently stale:** as of the "Centralize workflow bindings and version state" commit, `.github/workflows/morphont-publish.yml` and `.github/workflows/platform-parity.yml` were removed from this repo (along with `ci.yml` and `jules-glee.yml`) pending re-sync from the shared `HereLiesAz/workflows` catalog -- the same mechanism that populated the earlier "Refresh ... from shared catalog" commits. Until a proxy workflow lands back in `.github/workflows/`, the steps below describe the intended pipeline, not one that currently runs: pushing a release tag will not trigger anything.

## GitHub release

1. Update `versionName` and `versionCode` in `androidApp/build.gradle.kts`.
2. Merge the version change to `main`.
3. Create and push a tag matching `v<versionName>` exactly, for example `v0.4.0`.

Once the `.github/workflows/morphont-publish.yml` proxy is back in place, it dispatches the exact tagged commit to the central publisher, which builds a signed release APK and AAB, writes SHA-256 checksums, and creates or updates the GitHub Release in `HereLiesAz/morphont`.

A mismatched tag and `versionName` fails before publication.

## Google Play

Run **Publish Android [central proxy]** manually from `main`.

- Leave **Upload the signed AAB to Google Play?** off to perform a signed release build only.
- Turn it on to upload the AAB using the central `PLAY_SERVICE_ACCOUNT_JSON`.
- `internal` + `draft` is the safe default.
- `alpha`, `beta`, and `production` are available explicitly.

Play publication is rejected from non-`main` refs and is never automatic on a tag push.

## Central credentials

Morphont does not store these credentials. The central workflow expects them in `HereLiesAz/workflows`:

- `GH_TOKEN`
- `KEYSTORE_PRIVATE`
- `KEYSTORE_CHAIN`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD` (optional; falls back to `KEYSTORE_PASSWORD`)
- `PLAY_SERVICE_ACCOUNT_JSON` (only when Play upload is requested)

The central workflow reports success or failure back to the Morphont commit using the `.github/workflows/morphont-publish.yml` status context.
