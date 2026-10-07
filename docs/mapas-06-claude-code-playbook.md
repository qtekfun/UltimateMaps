# 06 · Claude Code playbook (autonomy with guardrails)

Goal: have Claude Code work without asking you for approval on every change, and stop only for what really matters. Data checked against the official documentation on 2026-10-06 (code.claude.com/docs: permissions, settings and memory).

## What to copy into the repository

| Package file | Destination in the repo | Purpose |
| --- | --- | --- |
| `mapas-CLAUDE.md` | `CLAUDE.md` | Immovable decisions, decision protocol and when to ask |
| `mapas-claude-settings.json` | `.claude/settings.json` | Shared project permission rules |
| `mapas-ci.yml` | `.github/workflows/ci.yml` | Minimal CI: the gate that decides whether a PR can be merged |

Personal settings (for example, your own approvals) go in `.claude/settings.local.json`, which Claude Code keeps out of git.

## Three levels of autonomy

| Level | How it is enabled | What it does | When to use it |
| --- | --- | --- | --- |
| **1. Auto-accept edits + allowlist** (recommended to start) | Already included in `mapas-claude-settings.json` (`defaultMode: acceptEdits`) | Accepts file edits and `mkdir`, `touch`, `mv`, `cp` in the working directory; commands in the `allow` list do not ask for permission | Normal development |
| **2. `auto` mode** | Launch with `claude --permission-mode auto` or set it in `~/.claude/settings.json` | Works without routine prompts; a background classifier checks that commands and network requests are consistent with what you asked for. Depends on your plan or session having it available | When level 1 still asks for too many confirmations |
| **3. `bypassPermissions`** | `claude --permission-mode bypassPermissions` or in your user settings | Skips the prompts, even on protected paths such as `.git` and `.claude` | Only inside an isolated virtual machine or container, with no access to anything that matters |

## Important points from the docs

1. **Approve the project's trust once.** The `allow` rules in a project's `.claude/settings.json` only apply after accepting the directory trust dialog. `deny` and `ask` rules always apply.
2. **`auto` and `bypassPermissions` do not work from project settings.** They must be set in `~/.claude/settings.json` or passed with `--permission-mode`. That is why the package file uses `acceptEdits`.
3. **Evaluation order:** `deny` first, then `ask`, then `allow`. An `allow` rule cannot make an exception to a `deny`.
4. **Bash rule syntax:** the `*` goes after the subcommand (`Bash(git commit *)`); the space before the `*` is part of the rule (`Bash(ls *)` does not cover `lsof`).
5. **Bash rules are not a security boundary.** For example, `Bash(git push origin main *)` does not stop `git -C . push origin main`. For real guarantees, use GitHub branch protection (see below), the sandbox or a `PreToolUse` hook.
6. **`CLAUDE.md` is context, not a blocking mechanism.** What must never happen goes in `deny`; what Claude should decide goes in `CLAUDE.md`.
7. **`CLAUDE.md` should be under about 200 lines** so that it is followed well. The one in the package meets that guideline.

## What is allowed, asked and blocked in the package file

- **Allowed without asking:** `./gradlew`, `gradle`, git (`add`, `commit`, `switch`, `checkout -b`, `branch`, `stash`, `merge`, `submodule`, `tag`, `fetch`, `pull` and `push` of branches), `gh` for PRs (`create`, `view`, `list`, `status`, `diff`, `checks`, `comment`, `merge`), CI runs (`gh run list/view/watch`) and issues (`gh issue create/list/view/comment`), development `adb` (install, logcat, `dumpsys`, `am start`, `input`, `push`, `pull`), `cmake`, `ninja`, `sdkmanager`, repo scripts (`./scripts/*`, `python3 scripts/*`), `pmtiles`, `zip`/`unzip`/`tar`, web search and reading documentation from specific technical domains.
- **Asks before running:** `git reset --hard`, `git clean`, `git rebase`, `gh api`, `gh repo`, `gh secret`, `gh release`, `curl`, `wget`, `rm -rf`, and changes to `LICENSE` and to `.github/workflows/`.
- **Always blocked:** `sudo`, force push (`--force`, `-f`, `--force-with-lease`, `+branch`), direct push to `main`, `gh pr merge --admin`, `gh repo delete`, and reading `.env`, `keystore.properties`, `*.jks`, `*.keystore`, `*.p12`, `~/.ssh` and `~/.gnupg`.

**If `curl` and `wget` interrupt you too much** (for example, when downloading maps), move them to `allow`, or better, enable the sandbox and allow only the domains you need (Codeberg, GitHub, the maps CDN and the Maven/Google repositories).

## Decision protocol

- When in doubt, Claude decides using the most reasonable judgment and records it in `docs/decisions.md` (date, decision, reason, discarded alternative).
- It stops only in the five "When to ask" cases in `CLAUDE.md`: a proprietary license or dependency, a change to the immovable decisions, a change to an already decided A/B/C, irreversible or costly actions, and touching what decides whether something gets merged (CI workflows, branch protection, repo permissions).
- You review `decisions.md` and the already merged PRs whenever you want, not at every step.

## Push, PR and automatic merge

Flow: branch per task → push → `gh pr create` → wait for checks → if all pass, squash merge and delete the branch. The details are in `CLAUDE.md` (section "Branch and PR flow").

**What needs to be set up once on GitHub** (so that "if they pass, merge" is true and not just an intention):

1. **Copy `mapas-ci.yml`** as `.github/workflows/ci.yml` and push it. Without CI there are no checks and the merge would have no condition.
2. **Protect `main`** (Settings → Branches or Rulesets): require a PR before merging, require the `build` check to pass, block force pushes and enable "do not allow bypassing these rules" (also for administrators).
3. **Enable "Allow auto-merge" and "Automatically delete head branches"** in Settings → General. That way `gh pr merge --auto --squash` leaves the PR scheduled and GitHub merges it on its own only when the check ends green.
4. **Authenticate `gh`** with `gh auth login`. The safest option is a fine-grained access token limited to this repository, with read and write permissions on contents and PRs (and on workflows if Claude Code must be able to change them), and without repo administration permissions [check the exact permission names when creating it].

**Why branch protection is the real guarantee:** Claude Code's permission rules try to prevent pushing to `main` or merging with `--admin`, but they are rules on command text and can be circumvented with other forms. Branch protection is enforced by GitHub on the server: even if Claude Code tried, a direct push or a merge with red checks is rejected.

**Limit of automatic merging:** a check only detects what the tests and the lint cover. That is why the CI must grow with the project (tests for link parsers, importers, simulated navigation, checks for proprietary dependencies, and later, performance benchmarks). After the spike and in each phase, review what it covers.

**Why workflows ask for confirmation:** changing `.github/workflows/` is equivalent to changing the merge condition. The package file leaves it as "ask". If you prefer not to have even that friction, move it to `allow`, knowing that CI then stops being an independent control.

## Work loop (spec-driven)

1. Read the requirement and the acceptance criterion.
2. Plan in 3-5 steps and split into small tasks.
3. Implement.
4. Build, pass tests and, if performance is affected, measure.
5. One commit per task.
6. Update `docs/` and `decisions.md`.

## Suggested initial prompt

> Read `CLAUDE.md` and `docs/mapas-README.md`. Run the spike in `docs/mapas-04-spike.md` from start to finish without asking me for approval at each step: set up the environment, build CoMaps without modifying its engine, download the map of Spain, measure with the defined thresholds on the connected devices, and deliver `docs/spike-informe.md` with the recommendation A, B or C. Work with branches and PRs as `CLAUDE.md` indicates: push, open the PR and merge when the checks pass. Record your decisions in `docs/decisions.md`. Stop only in the "When to ask" cases.

## Useful checks

- `/status`: see which settings files have been loaded.
- `/permissions`: see the active rules and which file they come from.
- `/context`: check that `CLAUDE.md` has been loaded.
- `claude doctor`: see settings entries that have been rejected.

## Before granting more autonomy

If you move to level 3, do it in a VM without your credentials, without access to your servers and with the repository as the only content of value.
