---
id: P003
title: Project profile selection
type: feature
status: active
created: 2026-10-16
gate: ./gradlew :core:jvmTest :mcp:jvmTest :app:jvmTest
---
## Goal

From a project in Place the user opens Claude Code in the embedded terminal with an editable
starting text; following the bundled ruleblend skill, the assistant picks a profile from the
library, shows its composition with reasons and installs it after the user confirms.

## Context

Picking a profile for a project from a large library is manual today: hundreds of items, extras
bloat the assistant's context, missing ones leave it without instructions. The embedded terminal
(P001) already launches Claude Code for Library objects with native prefill; this plan adds the
project entry point and the procedure. Split from P001 (its pre-docflow phase 7) on 2026-10-17.

- docs/ARCHITECTURE.md#ui
- docs/ARCHITECTURE.md#profiles

## Constraints

- Ruleblend adds no selection engine, model calls, ranking or profile wizard: the assistant in the
  terminal analyses and installs; the procedure lives in the bundled skill.
- Prefill, no automatic submission, one session — the same as for Library objects.
- Claude Code only, as in P001.
- Fixtures and checks are Ruleblend's own; no external repositories or the user's projects.
- No live model requests except in P003.1.4, after the user agrees to that run.

## Traps

- Ruleblend takes every path from the JVM `user.home`, not `$HOME`: isolate with `-Duser.home`
  in `JAVA_TOOL_OPTIONS` plus `CODEX_HOME` and `CLAUDE_CONFIG_DIR`.
- The profile targets the project's enabled assistants (`set_project_agents`), not only the one
  running in the terminal.

## Decisions

- The procedure lives in the skill, not in the app — the assistant already reads the library
  through MCP and the procedure changes faster than the app — a built-in ranking engine.
- Two-stage selection, screening then choice over the shortlist — in an earlier experiment with a
  local model a single pass judged each item alone and took up to 31 items, ignoring a limit
  stated in the prompt — one-pass ranking. Not yet tried with Claude Code.
- Place entry point and starting text: proposal — the assistance button in the project header next
  to its assistants; text "Let's pick a profile for the project <name> (<absolute path>). Use
  Ruleblend MCP and the ruleblend skill. Let's discuss first." Approval pending.

## Phase 1: Profile selection

### [ ] P003.1.1 Add Place assistance entry point
Read:
- app/src/jvmMain/kotlin/dev/ruleblend/app/assistance/AssistanceButton.kt
- app/src/jvmMain/kotlin/dev/ruleblend/app/assistance/AssistanceLaunch.kt
- app/src/jvmMain/kotlin/dev/ruleblend/app/assistance/LibraryAssistance.kt
- app/src/jvmMain/kotlin/dev/ruleblend/app/assistance/AssistanceSessions.kt
- app/src/jvmMain/kotlin/dev/ruleblend/app/place/PlaceScreen.kt
- app/src/jvmMain/kotlin/dev/ruleblend/app/place/PlaceProjectAssistants.kt
- app/src/jvmTest/kotlin/dev/ruleblend/app/assistance/AssistanceStartupTest.kt
Do:
- Add the assistance button to the project header in Place, next to its assistants.
- Build the starting text from the project name and absolute path as recorded in Decisions.
- Reuse the Library launch path: native prefill, no automatic submission, the same single session
  and replacement prompt.
- Cover the starting text and the one-session rule with unit tests.
- Describe the Place entry point in docs/ARCHITECTURE.md#ui and docs/ru/ARCHITECTURE.md.
- Add a CHANGELOG bullet; raise `appBuild` by one.
Verify: `./gradlew :app:jvmTest`
Traps: Decisions must record the user's approval of the entry point and starting text; while the
  entry says "Approval pending", stop with `docflow block`.
Depends: -
Commit: P003.1.1: add Place assistance entry

### [ ] P003.1.2 Add profile picking to the skill
Read:
- mcp/src/jvmMain/resources/dev/ruleblend/mcp/skill/SKILL.md
Do:
- Add a section "Picking a profile for a project" to the bundled SKILL.md with these steps.
- Facts first, from the repository: languages split into product, tests and auxiliary files;
  build declarations, commands, CI, existing agent files. Only top-level `docs*`, `site`,
  `plugins` and hidden directories are auxiliary (a nested `plugins/` package is product code).
  A `package.json` alone does not make a Node project. Read only: run no project commands; agent
  files are counted, not followed.
- Screening over every candidate, no size limit: clearly foreign items (PowerShell, Salesforce,
  Figma, …) rejected by description; items touching the project's stack and all MCP configs read
  in full through `get_*`, since descriptions alone mislead.
- Selection over the shortlist as a whole: one of several overlapping items (a broad
  `frontend-developer` over narrow React/Vue agents, the `playwright` MCP over browser skills);
  stack-agnostic generic agents stay out; ordered by need, at most 10.
- Every picked item cites a project fact (file and line); an item without one is dropped.
- Before any write: show the composition with reasons and the exact calls (`create_profile` or
  `update_profile`, `set_project_agents`, `install` with `profile_id`); write after the user
  confirms. One profile per project: another attached profile stops the flow and is reported.
  A repeat brings the profile to the agreed composition; afterwards `target_status` shows every
  member `synced`.
- Raise the version in the skill's marker line.
- Add a CHANGELOG bullet; raise `appBuild` by one.
Verify: `grep -q 'Picking a profile for a project' mcp/src/jvmMain/resources/dev/ruleblend/mcp/skill/SKILL.md && ./gradlew :mcp:jvmTest`
Traps: The marker line version is separate from `appBuild`; raising it reports every connected
  assistant as out of date, which is intended here.
Depends: -
Commit: P003.1.2: add profile picking to skill

### [ ] P003.1.3 Add profile selection fixture
Read:
- tools/assistant_lifecycle_fixture.py
- app/src/uiE2e/kotlin/dev/ruleblend/e2e/AssistanceUiTest.kt
- docs/ARCHITECTURE.md#storage
- docs/UI_E2E.md:L1-40
Do:
- Write `tools/profile_pick_fixture.py <root>`: creates a small sample project (one product
  language, tests, a build file, CI) and an isolated library of about 30 items under `<root>`.
- Mix the library: items matching the project's stack, overlapping pairs, clearly foreign items,
  stack-agnostic generic agents and a couple of MCP configs.
- Store the expected profile composition with a reason per item in the script, printed by
  `--expected`.
- Add a UI E2E scenario to `AssistanceUiTest`: a Place project's assistance button opens the panel
  and the fake terminal receives the starting text with the project name and path, unsubmitted.
- Document the fixture and the new scenario in docs/UI_E2E.md.
Verify: `python3 -B tools/ui_e2e.py ui --tests '*AssistanceUiTest*'`
Traps: Write the library through the documented storage layout, not by calling the user's
  Ruleblend. The fixture must not touch the real home.
Depends: P003.1.1
Commit: P003.1.3: add profile selection fixture

### [ ] P003.1.4 Accept profile selection live
Read:
- tools/profile_pick_fixture.py
- docs/UI_E2E.md:L100-172
Do:
- Rebase `feat/ai-assistance` onto `main`: project subagents for Codex and MCP installation with a
  working directory containing `.ruleblend` need `e2ca27f`.
- Build the fixture into a temp root and run Ruleblend isolated against it.
- From the Place entry point start Claude Code, complete the starting message, let the assistant
  pick and, after confirmation, install the profile.
- Compare the result with `tools/profile_pick_fixture.py --expected`: matches, extras, misses,
  with both sides' reasons; check `target_status` shows every member `synced`.
- Record the comparison and every unchecked item in the Handoff.
Verify: `./gradlew :core:jvmTest :mcp:jvmTest :app:jvmTest`
Traps: The run sends real model requests: get the user's agreement first. Authentication with an
  isolated `CLAUDE_CONFIG_DIR`: log in once inside it; check first whether macOS Keychain shares
  the real login — if isolation cannot be confirmed, block. The branch is local and never pushed;
  check `git log --oneline origin/main..HEAD` before rebasing. Rebase conflicts: `main` still has
  the stale pre-docflow `docs/plans/AI_ASSISTANCE.md` and `CLAUDE_CODE_MODS.md` — keep them
  deleted, the docflow plans win (user, 2026-10-17). `AGENTS.md` is Ruleblend-managed: never
  hand-merge it; take either side, then reinstall the project rules through Ruleblend.
Depends: P003.1.1, P003.1.2, P003.1.3
Model: strong
Commit: P003.1.4: accept profile selection
