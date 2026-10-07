# Mapas project (provisional name) — Feasibility and specification package

Date: 2026-10-06 · Status: feasibility study and interview closed, spike pending

## In one sentence

An Android maps and navigation app that works 100% on the device (map, search and routes), with no traffic data and no telemetry, with a carefully designed, very fluid Apple Maps-style interface. It is GPLv3, distributed through F-Droid/GitHub, works without Google Play Services and takes advantage of GMS when it is present.

## Package contents

| File | Purpose |
| --- | --- |
| `mapas-README.md` | This index, closed decisions and open questions |
| `mapas-01-viabilidad.md` | Feasibility study: what is feasible, risks, data sources and licenses |
| `mapas-02-requisitos.md` | Functional and non-functional requirements with measurable criteria |
| `mapas-03-arquitectura.md` | Architecture, options A/B/C, design without GMS and performance design |
| `mapas-04-spike.md` | Spike plan (phase 0): what is measured, with which thresholds and how the decision is made |
| `mapas-05-roadmap.md` | Phases, rough estimates and risk register |
| `mapas-06-claude-code-playbook.md` | How to work with Claude Code with autonomy and guardrails |
| `mapas-CLAUDE.md` | Copy to the repo as `CLAUDE.md` |
| `mapas-claude-settings.json` | Copy to the repo as `.claude/settings.json` |
| `mapas-ci.yml` | Copy to the repo as `.github/workflows/ci.yml` (the gate for automatic merging) |

## Closed decisions

- **Platform:** Android only (Kotlin + Compose for the UI, C++ via the NDK for the core). Android Auto, later.
- **Everything on the device:** map, search and routing without a server. No traffic data and no Google, Waze or Apple providers.
- **Fluidity:** map at 60/120 fps and instant startup.
- **Use:** car, motorcycle and on foot/bike.
- **Must-haves to leave Google Maps:** lane guidance, speed limits, and saving places and lists.
- **Motorcycle:** avoid motorways and tolls, an always-on screen that is usable with gloves, recording and exporting the track, twisty routes.
- **Aesthetics:** Apple Maps style.
- **License and distribution:** GPLv3, F-Droid and GitHub.
- **Data:** OpenStreetMap, the whole world with downloadable, configurable regions. The specific origin of the data does not matter.
- **Places and lists:** local, with GPX and KML import/export, optional Nextcloud/WebDAV sync and import from Google Takeout.
- **Links:** open Google Maps, Apple Maps, Waze and `geo:` links.
- **GMS:** never mandatory; if the device has it, it is used without depending on it.
- **Spike:** start with the cheapest option (unmodified CoMaps) and replace it if it is not convincing.
- **Working with Claude Code:** autonomy, without approving every change (see the playbook).
- **Git:** Claude Code pushes branches, opens the PRs and merges them (squash) when the CI checks pass. GitHub branch protection is the real guarantee that only green builds get merged.

## Open questions

1. App name and package identifier (for example `com.qtekfun.<name>`).
2. `minSdk`: 26 (Android 8.0) is proposed until there is data from the test devices.
3. Design of the Nextcloud sync: GPX/JSON files over WebDAV (proposed) or the Nextcloud Maps API.
4. How to implement twisty routes, which none of the candidate engines provides out of the box (evaluated in the spike).
5. Voice alternative when the device has no TTS engine (common without GMS).
6. Hosting of the map data after the spike (third-party CDN or own mirror).
7. When to tackle Android Auto and with what dependency policy for F-Droid.

## How to use the package

1. Create the repository and copy these files to `docs/` (the `mapas-0x-*.md` files).
2. Copy `mapas-CLAUDE.md` as `CLAUDE.md` in the root, `mapas-claude-settings.json` as `.claude/settings.json` and `mapas-ci.yml` as `.github/workflows/ci.yml`.
3. On GitHub, configure the protection of `main` and auto-merge as the playbook indicates (section "Push, PR and automatic merge") and log in with `gh auth login`.
4. Follow `mapas-06-claude-code-playbook.md` to choose the level of autonomy.
5. Start with the playbook's initial prompt: Claude Code runs the spike in `mapas-04-spike.md` and delivers the decision report.
