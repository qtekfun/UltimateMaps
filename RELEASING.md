# Releases

Same mechanics as UltimateDeck: a `vX.Y.Z` tag triggers the **Release** workflow, which builds the signed APK and publishes it to GitHub Releases with the notes from `CHANGELOG.md`.

## Versions

- The version lives in one place: `appVersion` in `gradle.properties`, as SemVer (`1.2.3`) or `1.2.3-rc.N` for a release candidate. Before 1.0.0 the app is `0.x`.
- The Android version code is derived, never written by hand: `(MAJOR*10000 + MINOR*100 + PATCH) * 100 + N`, with `N = 99` for a final release. `0.1.0-rc.1` is `10001`; `1.0.0` is `1000099`. A final version always sorts after its candidates and nothing depends on dates or on the machine (reproducible builds).

## Signing (one time)

Releases are signed with the project's own key. **If it is lost, users would have to uninstall to update:** keep it backed up.

1. Create the key (outside the repo):
   ```sh
   keytool -genkeypair -v -keystore ultimatemaps-release.jks -alias ultimatemaps \
     -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Add these secrets to GitHub (Settings → Secrets and variables → Actions):
   - `UM_KEYSTORE_BASE64`: `base64 -w0 ultimatemaps-release.jks`
   - `UM_KEYSTORE_PASSWORD`, `UM_KEY_ALIAS` (`ultimatemaps`), `UM_KEY_PASSWORD`
3. For F-Droid, the certificate fingerprint (`AllowedAPKSigningKeys`):
   ```sh
   keytool -list -v -keystore ultimatemaps-release.jks -alias ultimatemaps | grep SHA256
   ```

Without those variables, `./gradlew :app:assembleFossRelease` produces an **unsigned** APK (`app-foss-release-unsigned.apk`), which is what F-Droid compares against. The workflow **fails** if the secret is missing, so an unsigned APK is never published.

## Making a release

1. Move the `[Unreleased]` notes in `CHANGELOG.md` under `## [X.Y.Z] - YYYY-MM-DD`.
2. Set `appVersion=X.Y.Z` in `gradle.properties`.
3. Commit (`chore: release X.Y.Z`), merge to `master`, then tag:
   ```sh
   git tag vX.Y.Z && git push origin vX.Y.Z
   ```
4. The workflow checks that the tag matches `appVersion` and that there are notes, runs `test` and `lintFossRelease`, builds the signed APKs and publishes the Release with `UltimateMaps-X.Y.Z.apk` (the `foss` build, the one for F-Droid) and `UltimateMaps-X.Y.Z-play.apk` (the optional `play` build that uses Google Play Services when the phone has it; application id `com.qtekfun.ultimatemaps.play`, installs next to the other one), each with its `.sha256`, both signed with the same key. Release candidates (`-rc.N`) are published as pre-releases.

## Releases are signed with the project key only (owner decision, 2026-10-08)

From `0.1.0-rc.10` on, only APKs signed with the project key are published: a `vX.Y.Z-rc.N` tag (published as a pre-release) or `vX.Y.Z`. The earlier test builds (`test-v0.1.0-rc.1` to `rc.9`) were pre-releases signed with the debug key; they cannot be updated to a build signed with the project key, so those users must uninstall first. No more debug-signed test builds are published.

## Status (2026-10-07)

**Ready in the repo** (checked locally, not on GitHub): `LICENSE` (GPL-3.0), `README.md`, `PRIVACY.md` (English and Spanish), `CHANGELOG.md`, `fastlane/metadata/android/{en-US,es-ES}` (title, descriptions and changelog), a draft `fdroid/com.qtekfun.ultimatemaps.yml` (without the `Builds` section), a single `appVersion` with a derived `versionCode`, environment-based signing, `release.yml` (installs the exact NDK and CMake, prepares the submodule, fails without the key, publishes the APK and `.sha256`), `usesCleartextTraffic="false"`, `allowBackup="false"`, and an up-to-date `LICENSES.md`.

### Only the project owner can do these (see CLAUDE.md, "When to ask" items 4 and 5)

1. **Key and secrets** `UM_KEYSTORE_BASE64`, `UM_KEYSTORE_PASSWORD`, `UM_KEY_ALIAS`, `UM_KEY_PASSWORD` (see "Signing").
2. **Protect `master`** (a template is in `~/repos/ruleset-master.json`).
3. **CI (`ci.yml`)**: a `mapas-ci.yml` sits in the repository root with `main` instead of `master`; touching `.github/workflows/` needs the owner's approval. Without CI there is no gate for automatic merging.
4. **First tag** `v0.1.0-rc.3` (or later) after dating the `CHANGELOG`: `git tag v0.1.0-rc.3 && git push origin v0.1.0-rc.3`.
5. **Tell the CoMaps project** that we host copies of their `.mwm` files (we found no terms of use for their CDN) and decide whether to announce before that.
6. **Icon and screenshots** for the stores (`fastlane/.../images/`): there is only a provisional `ic_launcher` and no screenshot.

### Needs a device (the Pixel 8 only with the user's explicit permission)

- Navigation screen, voice and the navigation service on a real device.
- Traffic capture with **offline mode** on (RF-12: zero outgoing connections) and at start-up (must be zero).
- Search latency (target 100 ms; measured 300–4000 ms) and long routes with all 25 regions (target 2 s); the cause of `ROUTE_NOT_FOUND` on long routes.
- Smoothness with labels and several regions, memory, and a second device (mid-range, without GMS).
- **Minification (R8):** currently off; enabling it requires testing JNI and MapLibre minified.

### Known F-Droid risks

- **Building the core needs network and PyPI** (`scripts/comaps-prepare.sh` installs `protobuf` with pip and clones ~2 GB of submodules). F-Droid's build servers restrict network access during builds: the generated files (classificator, categories, style rules, strings; <3 MB) will probably have to be **committed**, keeping the script only to regenerate them.
- The `Builds` entry (version, `versionCode`, commit, `submodules: true`, `sudo` with JDK 21) is written once the tag exists; use `~/repos/ultimatedeck/fdroid/` as a model.
- Anti-features: downloading from GitHub may attract `NonFreeNet` or similar; the review decides.
- The Artistic License 2.0 text of `kdtree++` does not ship in the submodule: include it in a `NOTICE` or an "About" screen (the app has no "About" screen yet; the OSM attribution is always on the map).
