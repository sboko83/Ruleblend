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
- No live model requests except in P003.1.4.
- The live run uses the fixture library and project, never the user's library or projects.

## Traps

- Ruleblend takes every path from the JVM `user.home`, not `$HOME`: the fixture library is
  selected with `-Duser.home` in `JAVA_TOOL_OPTIONS`; Claude Code reads `$HOME`/`CLAUDE_CONFIG_DIR`.
- Claude Code starts the Ruleblend MCP through the shared launcher `~/.ruleblend/bin/ruleblend-mcp`;
  which build it runs and which `user.home` that build sees decide which library the assistant
  edits.
- The profile targets the project's enabled assistants (`set_project_agents`), not only the one
  running in the terminal.

## Decisions

- The procedure lives in the skill, not in the app — the assistant already reads the library
  through MCP and the procedure changes faster than the app — a built-in ranking engine.
- Two-stage selection, screening then choice over the shortlist — in an earlier experiment with a
  local model a single pass judged each item alone and took up to 31 items, ignoring a limit
  stated in the prompt — one-pass ranking. Not yet tried with Claude Code.
- Place entry point and starting text: the assistance button in the project header next to its
  assistants; text "Let's pick a profile for the project <name> (<absolute path>). Use Ruleblend
  MCP and the ruleblend skill. Let's discuss first." — approved by the user 2026-10-17; matches
  the Library entry point — a separate profile wizard.
- Live run with real model requests in P003.1.4, Claude Code not isolated: its real config and
  login are used — approved by the user 2026-10-17 — a separate `CLAUDE_CONFIG_DIR` with its own
  login.
- Starting text stays English for the RU interface — the plan requires the approved wording verbatim and a Russian variant needs user approval — rejected: a translated RU draft like the Library ones
- Project identity for the one-session rule is (kind, absolute path) via the existing AssistanceObject — reuses open()/Replace prompt unchanged — rejected: a separate project session controller
- The button is passed to PlaceScreen as a composable slot — keeps the place package independent of settings/assistance sessions — rejected: reading LocalAssistanceSessions and SettingsModel inside PlaceHeader
- Added a closing sentence that the profile targets the project's enabled assistants (set_project_agents) — it comes from the plan's Traps and keeps the assistant from installing for only its own agent — rejected: leaving it out of the skill text
- Place scenario appended to A01 instead of a separate test — it depends on the Claude connection made in A01, and JUnit4 method order is not guaranteed, so a separate test could break A01's initial not-connected assertion — rejected: a standalone A02 test
- Project added through the substituted DesktopActions.directory picker, as in the Phase tests — it exercises the real Place add flow — rejected: writing the project into config.json directly
- Fixture verdicts are include/overlap/foreign/unneeded, with an expected profile of 12 of 30 — gives P003.1.4 a measurable limit and overlap traps — rejected: a flat include/exclude list

## Phase 1: Profile selection

### [x] P003.1.1 Add Place assistance entry point
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
Traps: Use the approved starting text from Decisions verbatim; a different wording needs the
  user's approval first.
Depends: -
Commit: P003.1.1: add Place assistance entry
Handoff:
- Place entry point: AssistanceObject.Kind.PROJECT, id = absolute project folder path, name = folder name; the launch directory is the project folder. Button is injected into PlaceScreen/PlaceFileColumn/PlaceHeader via the projectAssistance slot, wired in Main.kt next to the Library one.
- The starting text for PROJECT is English for both UI languages (approved wording only); the project is named by its folder name, not by a registered display name.
- The panel title shows '<project name> · Claude Code'; the testTag 'assistance-open' is reused (the Library and Place buttons are never on screen together).
- The bundled SKILL.md does not yet contain the profile-selection procedure; nothing in this unit touched it, so SKILL.md and MCP_ENTRY_VERSION markers were not bumped.
- Unverified: Header layout of the button next to a collapsed/expanded ProjectAssistants panel not checked visually in the running app or with a Compose UI test
- Unverified: No UI test for the Place button itself (click -> launch); only the starting text and one-session rule are covered by unit tests
- Unverified: Russian ARCHITECTURE paragraph placement under '## UI' is a judgement call; not reviewed by the user

### [x] P003.1.2 Add profile picking to the skill
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
Handoff:
- SKILL.md marker is now v11 and BundledSkillTest.EXPECTED_DIGEST is c620779e...; any further SKILL.md edit (e.g. after the P003.1.4 live run) must raise the marker again and replace the digest with the one the test failure prints.
- The new section sits before 'Choosing the target' in SKILL.md; appBuild is 276.
- Unverified: Skill procedure not exercised with a real assistant: the live run is P003.1.4
- Unverified: Auxiliary-directory rules and the 10-item limit are unchecked in behaviour; only the text is present

### [x] P003.1.3 Add profile selection fixture
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
Handoff:
- Fixture: `python3 -B tools/profile_pick_fixture.py <empty root>` creates <root>/project/orbit-api and <root>/home/.ruleblend/library (30 items: blocks/*.md for rules, subagents and MCP; skills/<id>/). `--expected` prints the expected 12-item composition with a reason per item. Run Ruleblend with -Duser.home=<root>/home and Claude Code with HOME=<root>/home.
- The library has no profiles on purpose: the assistant is expected to create one. The fixture runs git init and one commit in the library and in the project, with a throwaway identity and an empty global git config.
- The Place scenario is the tail of A01 (first stage only; the restart stage returns early). It needs the Claude connection that A01 sets up through Settings.
- The fixture library is not connected to any Claude config. P003.1.4 must set up HOME, the launcher and the MCP registration so the assistant edits the fixture library, not the user's.
- Unverified: The Ruleblend app and MCP were not run against the generated library. Format conformity rests on reading BlockFile and McpConfigCodec, not on loading it in the app.
- Unverified: Git identity/config isolation in the fixture was checked only by running the script once on macOS.
- Unverified: The fixture script was not checked on Windows.

### [ ] P003.1.4 Accept profile selection live
Read:
- tools/profile_pick_fixture.py
- docs/UI_E2E.md:L100-172
Do:
- Rebase `feat/ai-assistance` onto `main`: project subagents for Codex and MCP installation with a
  working directory containing `.ruleblend` need `e2ca27f`.
- Build the fixture into a temp root and run this branch's Ruleblend with `-Duser.home` set to it.
- Make the terminal's Claude Code (real config and login) reach a Ruleblend MCP that serves the
  fixture library, and have the P003.1.2 skill installed in it.
- Before any write, prove it: the assistant's first MCP read (`list_rules` or `list_skills`)
  returns fixture ids, and the installed skill contains "Picking a profile for a project".
- From the Place entry point start Claude Code, complete the starting message, let the assistant
  pick and, after confirmation, install the profile into the fixture project.
- Restore every change made to the real `~/.claude` and `~/.ruleblend` (MCP entry, skill version,
  launcher owner) or list it in the Handoff with the reason it stays.
- Compare the result with `tools/profile_pick_fixture.py --expected`: matches, extras, misses,
  with both sides' reasons; check `target_status` shows every member `synced`.
- Record the comparison and every unchecked item in the Handoff.
Verify: `./gradlew :core:jvmTest :mcp:jvmTest :app:jvmTest`
Traps: The user agreed to real model requests (Decisions). If the MCP read shows the user's real
  library, end the session before any write and block: a profile created there lands in the
  user's library. Unverified: that `JAVA_TOOL_OPTIONS` from the terminal reaches the MCP process
  Claude Code spawns — check, do not assume. The branch is local and never pushed;
  check `git log --oneline origin/main..HEAD` before rebasing. Rebase conflicts: `main` still has
  the stale pre-docflow `docs/plans/AI_ASSISTANCE.md` and `CLAUDE_CODE_MODS.md` — keep them
  deleted, the docflow plans win (user, 2026-10-17). `AGENTS.md` is Ruleblend-managed: never
  hand-merge it; take either side, then reinstall the project rules through Ruleblend.
Depends: P003.1.1, P003.1.2, P003.1.3
Model: strong
Commit: P003.1.4: accept profile selection
