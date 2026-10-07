# UltimateMaps

100 % offline, private Android maps and navigation app with an Apple Maps–style UI that stays very smooth. GPLv3, for F-Droid and GitHub. Full documentation in `docs/` (start with `docs/mapas-README.md`).

## Language (hard rule)

**Everything in the project is written in English**: documentation (`docs/`, `README`, `CHANGELOG`, `RELEASING`), GitHub releases and their notes, pull request titles and bodies, issues, commit messages, code comments and KDoc, identifiers, log messages and test names. The app UI is localized (English is the default `values/`, Spanish in `values-es/`) and the privacy policy keeps a Spanish section; those are the only Spanish content. Talk to the user in Spanish in the chat, but never write Spanish into the repository or GitHub.

## Non-negotiable decisions

- Android only. Kotlin + Compose for the UI; C++ (NDK/JNI) for the core.
- Map, search and routing run on the device. No traffic. No Google, Waze or Apple as providers (only their links are parsed).
- Zero telemetry. All network access goes through `NetworkPolicy`; no analytics libraries and no remote crash reporting.
- Works without Google Play Services. `play-services-*`, Firebase and any proprietary SDK are forbidden in the `foss` flavor. If GMS is present it is used only through dependency-free paths (for example `LocationManager.FUSED_PROVIDER`).
- GPLv3 license. Before adding a dependency, check that its license is compatible and record it in `LICENSES.md`.
- OpenStreetMap data with visible attribution (ODbL).
- Performance: 60/120 fps and a cold start ≤ 1 s are requirements, not wishes. See `docs/mapas-02-requisitos.md`.

## Current state

Phase 1 plus the first navigation work, with **option C** (hybrid: MapLibre renders, the CoMaps core searches and routes; chosen by the user on 2026-10-06, see `docs/decisions.md`). Spike report: `docs/spike-informe.md`. The code is on GitHub (`qtekfun/UltimateMaps`, branch `master`, public since 2026-10-07) but there is **no CI and no branch protection yet**: there are no checks to wait for, so pull requests stay open until the user reviews them or sets up CI. Open risks: long-route and search latency of the CoMaps core, long routes returning `ROUTE_NOT_FOUND`, and inherited licenses (bsdiff, code2000, Entypo).

## Standing rules from the user

- **The Pixel 8 is used only with the user's explicit permission, each time** (given on 2026-10-07 for testing the rc builds; ask again for later sessions). Every `adb` call that touches the device goes through `flock /tmp/pixel-device.lock` (another session shares the phone) and first checks which app is in the foreground before sending taps or text.
- **At most 5 subagents at a time** (raised from 3 by the user on 2026-10-07). Native builds are serialized across agents with `flock /tmp/claude-1000/native-build.lock` (little RAM).
- Test builds published to GitHub are **pre-releases signed with the debug key**, tagged `test-v…` (never `v*`, which triggers the release workflow), pointing at the exact commit they were built from, with notes that say what was and was not tested on a device.

## Commands

- Prepare the native core (once per clone; downloads ~2 GB of submodules, needs network and PyPI): `git submodule update --init third_party/comaps && scripts/comaps-prepare.sh`. Without it, `assembleDebug` fails at `:native-comaps:configureCMake` (empty submodule).
- Build: `./gradlew assembleDebug -Dorg.gradle.workers.max=2` (little RAM: no LTO).
- Unit tests (JVM; they do not need the submodule): `./gradlew test`.
- Instrumented tests: `./gradlew connectedDebugAndroidTest`.
- Lint: `./gradlew lint`.
- Release build: `./gradlew :app:assembleFossRelease` (unsigned unless the `UM_*` signing variables are set; see `RELEASING.md`).
- Debug bench for the native core (debug builds only): `am start -n com.qtekfun.mapas/.bench.CoreBenchActivity` with `--ez guidance true` or `--ez matrix true`.
- CoMaps spike (outside the repo, `~/repos/comaps-spike`, tag `v2026.10.05-19`): `spike/comaps-build/03-build.sh` (needs JDK 21, NDK 28.2, the SDK's CMake 3.31.6 and a hand-built `uconv`; see `docs/spike/comaps-build.md`).

## How to work (autonomy)

- Work without asking for approval to edit code, build, run tests, measure, make commits, push branches and open pull requests; merge them when the checks pass.
- Split the work into small tasks; one commit per task with a clear message.
- Before calling something done: build it, pass the tests and, if it affects performance, measure.
- Update `docs/` when a decision or a requirement changes.

## Branch and pull request flow (autonomous)

1. One branch per task (`feat/<task>`, `fix/<task>` or `spike/<topic>`). Never work on or push directly to `master`.
2. Small commits; push the branch with `git push -u origin <branch>` without asking.
3. Open the PR with `gh pr create`: what changes, how it was tested and which requirement (RF/RNF) it covers. One task per PR.
4. Wait for checks with `gh pr checks --watch`. If they all pass, squash-merge and delete the branch: `gh pr merge --squash --delete-branch` (or `--auto` if auto-merge is enabled).
5. If a check fails, fix it on the same branch and push again. After 3 attempts at the same failure, note it in `docs/decisions.md`, leave the PR open and move on.
6. Never merge with red or pending checks, never use `--admin`, and never weaken, delete or disable tests or checks to make them pass. If a test is wrong, fix it and explain why in the PR.
7. After the merge: `git switch master && git pull`, then the next task.

## Decision protocol

When in doubt, decide with the most reasonable judgment from the documents and record it in `docs/decisions.md` (date, decision, reason, discarded alternative). Do not stop to ask about reversible details.

## When to ask (and only then)

1. Changing the license, or adding a proprietary dependency or one with a doubtful license.
2. Changing a non-negotiable decision in this file.
3. Changing the engine option (A, B, C) already decided in the spike report.
4. Any irreversible action outside the repository, publishing, sending data to third parties or spending money.
5. Touching what decides whether something gets merged: `.github/workflows/`, branch protection or repository permissions; rewriting history or deleting protected branches.

## Privacy and user data

- The user's locations are never written to logs by default and never leave the device.
- Credentials (WebDAV) go in the Android Keystore.
- Never read or write signing keys, `.env` or `keystore.properties`.

## Code style

- Idiomatic Kotlin, small modules, interfaces for the engines (`MapEngine`, `SearchEngine`, `RoutingEngine`, `LocationSource`, `VoiceGuide`, `NetworkPolicy`).
- No allocations in the render loop or in critical navigation threads.
- Externalized strings (English default, Spanish translation) from day one.
- Tests for link parsers, GPX/KML/Takeout importers and sync; navigation tests use a simulated `LocationSource`.
- Tests must not depend on real time or on recomposition from other threads (three flaky tests have already been fixed for that).

## References

- Requirements: `docs/mapas-02-requisitos.md`
- Architecture and no-GMS design: `docs/mapas-03-arquitectura.md`
- Spike and decision criteria: `docs/mapas-04-spike.md`
- Roadmap and risks: `docs/mapas-05-roadmap.md`
- Release process: `RELEASING.md`
