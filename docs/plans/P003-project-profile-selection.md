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
- Live run driven through a Python PTY with the Place button's exact argv instead of clicking the button — this session has no Accessibility or Screen Recording permission and the mobile MCP desktop and repl tools fail — rejected: blocking the unit until UI automation is available
- Only the real ~/.claude/skills/ruleblend was swapped temporarily; the real launcher and MCP entry were left untouched because the launcher already runs this branch's packaged build (its embedded SKILL.md matches the source byte for byte) and JAVA_TOOL_OPTIONS selects the fixture library — rejected: reconnecting Claude from a real-home Ruleblend run, which would rewrite the MCP entry, launcher owner and skill state
- Fixture expected profile cut from 12 to 9 after the live run: the three generic items become `unneeded` and the project gets a GitHub remote — the old oracle broke the skill's own rules (at most 10, generic agents out) and cited a remote that did not exist; the live 8 of 8 matches the new oracle except the GitHub MCP, which is not re-run live — rejected: loosening the skill to fit the fixture
- The skill leaves installed files uncommitted (marker v13) — in the live run the assistant committed them in the project, following the user's global commit rule (user, 2026-10-17) — rejected: leaving commits to whatever instructions the assistant has
- Place AI help is a menu with two items, "Ask a question" / «Задать вопрос» and "Pick a profile" / «Подобрать профиль» — the user found a profile request behind a generic AI help label wrong (user, 2026-10-17) — supersedes the single Place button with the profile text; rejected: a second header button, a button in the palette's Profiles section
- Project starting texts follow the UI language — the user runs the RU interface and got English (user, 2026-10-17) — supersedes "Starting text stays English"; texts: RU help «Проект <name> (<path>). Нужна помощь с », EN help "Project <name> (<path>). I need help with ", RU profile «Подберём профиль для проекта <name> (<path>). Используй Ruleblend MCP и skill ruleblend. Сначала обсудим.», EN profile = the approved English text
- Project help and profile picking are separate target kinds — the one-session rule keys on (kind, id), so the second item for the same project must ask to replace, not refocus the first — rejected: one kind with a purpose flag outside the identity

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

### [x] P003.1.4 Accept profile selection live
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
Handoff:
- Live run on fixture root /private/tmp/p003-live.oLG9 (kept for inspection; backup of the real ruleblend skill in /private/tmp/p003-live-backup, PTY driver and transcript in /private/tmp/p003-live-tools/raw.log). Result: profile kotlin-ktor-service with 8 items installed into the fixture project, target_status (checked by a direct MCP call against the fixture home) shows all 8 synced.
- Comparison with --expected: 8 matches, 0 extras, 4 misses. Misses with both sides' reasons: mcp github (fixture: CI and the remote live on GitHub; assistant: no git remote and ci.yml alone does not prove hosting; the fixture project indeed has no remote, so the fixture contradicts its own reason); conventional-commits (fixture: generic and cheap; assistant: not stack-bound and conflicts with the commit format in the user's real global CLAUDE.md); secure-coding-basics and code-reviewer (fixture: generic; assistant: generic, not tied to the stack). All overlap traps (kotlin-style-strict, junit4-testing, spring-boot-conventions, gitlab-ci-pipelines) and every foreign/unneeded item were excluded correctly.
- The run was not started from the Place button: this session has no Accessibility or Screen Recording permission (osascript -25211, screencapture fails), and the mobile MCP desktop companion and repl supervisor are unavailable. Claude Code was started in a Python PTY with exactly the argv the Place button builds: claude --prefill '<approved text>', cwd = project folder, app environment with JAVA_TOOL_OPTIONS. The prefill appeared unsubmitted.
- Proof before writes: the skill loaded via Skill(ruleblend) from the real ~/.claude/skills/ruleblend, temporarily replaced with this branch's v12, which contains 'Picking a profile for a project'. The first MCP call was list_targets, not list_rules; it returned the fixture home path. The following list_rules returned fixture ids.
- JAVA_TOOL_OPTIONS from the parent env reaches the Ruleblend MCP that Claude Code spawns through the real shared launcher: confirmed by ruleblend.lock appearing in the fixture home after `claude mcp get ruleblend`, and by fixture paths in the live session's MCP results.
- Real state restored: ~/.claude/skills/ruleblend is back to v10 and identical to the backup; sha256 of ~/.ruleblend/bin/ruleblend-mcp, skill-state.json, mcp-state.json and config.json is unchanged; the ruleblend entry in ~/.claude.json is unchanged. Remaining on purpose: Claude Code's own trust entry projects['/private/tmp/p003-live.oLG9/project/orbit-api'] in ~/.claude.json and the directory ~/.claude/projects/-private-tmp-p003-live-oLG9-project-orbit-api. Reason: the global rule forbids deleting under ~/.claude without an explicit request, and ~/.claude.json is rewritten concurrently by other sessions.
- The assistant also committed the installed files in the fixture project (8f2d72c), following the user's real global CLAUDE.md commit rule; the user's real global instructions influence the live selection.
- Unverified: Place button click -> embedded terminal launch not exercised in the real window (no Accessibility or Screen Recording permission); only the identical argv was run in a PTY, and AssistanceUiTest covers the click
- Unverified: The fixture app's own readiness for the Place button (Claude MCP registered and skill installed in the fixture home via Settings) was not set up or checked
- Unverified: Packaged build identity is checked only via the embedded SKILL.md digest, not by comparing class jars with the branch sources
- Unverified: docs/UI_E2E.md paragraph (left from the earlier attempt) not re-reviewed against the button path, only against the PTY path used here

## Phase 2: Assistance polish

### [ ] P003.2.1 Split Place AI help into a menu
Read:
- app/src/jvmMain/kotlin/dev/ruleblend/app/assistance/AssistanceStartup.kt
- app/src/jvmMain/kotlin/dev/ruleblend/app/assistance/AssistanceButton.kt
- app/src/jvmMain/kotlin/dev/ruleblend/app/Main.kt:L826-860
- app/src/jvmMain/kotlin/dev/ruleblend/app/i18n/Strings.kt:L245-260
- app/src/jvmTest/kotlin/dev/ruleblend/app/assistance/AssistanceStartupTest.kt
- app/src/uiE2e/kotlin/dev/ruleblend/e2e/AssistanceUiTest.kt:L190-240
- docs/ARCHITECTURE.md:L912-940
Do:
- Turn the Place project header button "AI help" / «AI-помощь» into a dropdown with two items: "Ask a question" / «Задать вопрос» and "Pick a profile" / «Подобрать профиль». The Library button stays a single click.
- Add a target kind for profile picking next to `AssistanceObject.Kind.PROJECT`; `PROJECT` becomes plain project help. Both use the absolute project path as id and the project folder as the launch directory.
- Build both project starting texts in the UI language, verbatim from the Decisions line "Project starting texts follow the UI language"; the help texts end with a space so the cursor stays after "с"/"with".
- Remove the English-only branch and its comment from `draft`.
- Unit tests: each kind and language gives its exact text; help and profile for the same project are different sessions (the second asks to replace a live first one).
- Update the Place scenario in `AssistanceUiTest`: open the menu, pick each item, the fake terminal gets the matching unsubmitted text. Give the menu items test tags.
- Update the Place paragraph in docs/ARCHITECTURE.md#ui and docs/ru/ARCHITECTURE.md; update the CHANGELOG bullet about the Place AI help button in place (it is unreleased); raise `appBuild` by one.
Verify: `./gradlew :app:jvmTest && python3 -B tools/ui_e2e.py ui --tests '*AssistanceUiTest*'`
Traps: The texts are approved by the user; do not reword them. `AssistanceSessions.open` compares kind and id; a new kind must not be mapped back to `PROJECT` anywhere on the way to the launch.
Depends: -
Commit: P003.2.1: split Place AI help menu

### [ ] P003.2.2 Match the terminal to the app theme
Read:
- app/src/jvmMain/kotlin/dev/ruleblend/app/assistance/PtyAssistanceTerminal.kt
- app/src/jvmMain/kotlin/dev/ruleblend/app/assistance/AssistanceLaunch.kt
- app/src/jvmMain/kotlin/dev/ruleblend/app/theme/Theme.kt:L395-420
- app/src/jvmMain/kotlin/dev/ruleblend/app/assistance/AssistanceUi.kt
Do:
- Give the embedded terminal the app's resolved theme (light or dark after `ThemeMode.SYSTEM`): default background and foreground from the app color scheme's surface and on-surface, a matching selection color, and a 16-color palette readable on that background. Today `DefaultSettingsProvider` paints it white whatever the app theme is.
- Carry the colors into `startAssistanceTerminal` as plain data captured from the composition at launch; the PTY start stays off EDT.
- Check whether Claude Code can be told light or dark per launch through a documented option (`claude --help`, Claude Code settings docs). If it can, pass the app's light/dark through it; if not, leave Claude Code's own theme alone and record that in the Handoff. Never write `~/.claude.json` or the user's Claude settings.
- A theme change during a running session applies from the next session; say so in the code comment.
- Unit-test the color mapping for light and dark.
- Add a CHANGELOG bullet; raise `appBuild` by one.
Verify: `./gradlew :app:jvmTest`
Traps: JediTerm's settings provider is read when the widget is created; overriding colors after `start()` does not repaint the buffer.
Depends: -
Commit: P003.2.2: theme the assistance terminal

### [ ] P003.2.3 Show the terminal panel at once
Read:
- app/src/jvmMain/kotlin/dev/ruleblend/app/assistance/AssistanceSessions.kt
- app/src/jvmMain/kotlin/dev/ruleblend/app/assistance/AssistanceButton.kt
- app/src/jvmMain/kotlin/dev/ruleblend/app/assistance/AssistanceUi.kt
- app/src/jvmMain/kotlin/dev/ruleblend/app/Main.kt:L215-300
- app/src/jvmTest/kotlin/dev/ruleblend/app/assistance/AssistanceSessionsTest.kt
Do:
- Measure first: log to stderr how long a click spends in `settings.refreshAssistanceAssistants()`, in `startAssistanceTerminal` and until the PTY's first output. Record the numbers in the Handoff.
- When no live session exists, show the bottom panel on the click itself, before the refresh, in a starting state: the target's title, an indeterminate progress indicator and "Starting Claude Code…" / «Запускаем Claude Code…», test tag `assistance-starting`. The terminal replaces it when the session starts.
- The starting state ends without a session on Configure (settings open, as now), on an assistant choice menu and on failure (the existing failure message).
- With a live session the click keeps today's path: the running terminal stays visible and the replace confirmation appears; no starting state.
- Unit-test the state transitions: starting → session, starting → cleared on configure and failure, live session → confirmation without starting.
- Add a CHANGELOG bullet; raise `appBuild` by one.
Verify: `./gradlew :app:jvmTest`
Traps: `AssistanceSessions` serialises operations with a mutex; the starting state must be visible before the refresh coroutine takes it, or the panel still waits for the slow part. A second click during the starting state must not launch a second process.
Depends: P003.2.1
Model: strong
Commit: P003.2.3: show terminal panel at once

## Phase 3: Acceptance

### [ ] P003.3.1 Accept assistance polish
Read:
- app/src/uiE2e/kotlin/dev/ruleblend/e2e/AssistanceUiTest.kt
- docs/UI_E2E.md:L100-172
Do:
- In `AssistanceUiTest` cover the Goal of phase 2: the Place menu's two items in RU and EN give the approved texts unsubmitted; the panel shows `assistance-starting` before the terminal when the fake terminal start is delayed; the terminal's background follows a light and a dark app theme.
- Close each `Unverified:` from the Handoffs of P003.2.1–P003.2.3 with a test, or carry it into this unit's Handoff.
- Update docs/UI_E2E.md for the changed Place scenario.
Verify: `./gradlew :core:jvmTest :mcp:jvmTest :app:jvmTest && python3 -B tools/ui_e2e.py ui --tests '*AssistanceUiTest*'`
Traps: A failing scenario is fixed in this unit if the fix is small; otherwise block with the reason. No live model requests in this unit.
Depends: P003.2.1, P003.2.2, P003.2.3
Model: strong
Commit: P003.3.1: accept assistance polish
