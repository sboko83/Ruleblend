---
id: P001
title: Embedded AI-assistant terminal
type: feature
status: active
created: 2026-10-10
gate: ./gradlew :core:jvmTest :mcp:jvmTest :app:jvmTest
---
## Goal

A bottom-panel terminal in Ruleblend starts Claude Code for a Library object or a Place project
with an editable, unsubmitted starting text; for a project the assistant picks and installs a
profile through the bundled ruleblend skill. Accepted on macOS arm64, macOS x64 and Windows.

## Context

Ordinary embedded terminal, as in Orca: Ruleblend provides the terminal and the process launch;
conversation, authorization, permissions and tools stay the chosen CLI's own. Done 2026-10-11
on branch `feat/ai-assistance` (local, never pushed): terminal/PTY and CLI launch, starting text
and Claude Code selection, single-process lifecycle, bottom panel and Settings, invocation from
Library with data refresh. Automated checks and macOS arm64 packaging are done; the interactive
and platform gates are phase 2. Phase 1 was phase 7 of the pre-docflow plan, phase 2 its phase 6.

- docs/ARCHITECTURE.md#ui
- CONTRIBUTING.md:L44-60

## Constraints

- Keep `app → mcp → core`; terminal component and PTY belong to `app`.
- Claude Code only; Codex stays deferred (reason in docs/ARCHITECTURE.md#ui).
- Library, editors, navigation and their styling are not reworked; additions only.
- No live AI requests outside the scenario agreed for P001.1.3; launching the CLI without
  submitting and offline fake checks are allowed. Never change the user's real profiles,
  projects or library for tests.
- macOS arm64 is the first platform; macOS x64 and Windows are accepted separately.

## Traps

- Terminal text is absent from the macOS AX tree. Programmatic writes to the PTY are not user
  input. Native automation got no AWT focus and delivered no keys even to a plain Compose field:
  none of that counts as interactive acceptance.
- Ruleblend takes every path from the JVM `user.home`, not `$HOME`: isolate with `-Duser.home`
  in `JAVA_TOOL_OPTIONS` plus `CODEX_HOME` and `CLAUDE_CONFIG_DIR`.
- Process exit is tracked, not the `/exit` string; the exited CLI's output stays until the panel
  is closed.
- A starting-prompt argument is acceptable only if the text stays in the CLI editor unsubmitted.
  Bracketed paste needs a ready input field and no Enter; a timer alone does not prove readiness.

## Decisions

- Plain terminal running the CLI — the user asked for a terminal as in Orca (2026-10-11) — a
  separate AI session system, snapshot schema, mutation preconditions, change journal and
  dedicated MCP protocol were cancelled; do not revive them from old notes.
- Starting text names the object's type, name and ID and suggests Ruleblend MCP and the skill,
  e.g. "Let's discuss the rule Commit (ID: commit). Use Ruleblend MCP and the ruleblend skill.
  Let's discuss the task first." — the assistant reads content through MCP — no context snapshot.
- Panel: "object · assistant" header, close button on the right, terminal, height divider; one
  session; repeat for the same object focuses it, another object offers to end the process; close
  confirms only while the process runs; app quit confirms the same way — approved mockup
  2026-10-11 (mockup files not kept).
- Settings offer only assistants with CLI, Ruleblend MCP and skill installed, nothing selected by
  default; one selected launches directly, several via a compact picker, none navigates to setup —
  no hidden substitution.
- Library edits and versions go through the existing Ruleblend MCP — no Save/Apply, modes, task
  form or panel toolbar.
- Project profile selection: the assistant in the terminal analyses and installs; Ruleblend adds
  no selection engine, model calls, ranking or wizard — the procedure lives in the bundled skill.
  Direction approved 2026-10-16; the Place entry point and its starting text are a proposal,
  approval pending.
- Two-stage selection (screening, then choice over the shortlist) — a single pass judged items
  alone and took up to 31; a limit stated in the prompt was ignored (local model, 2026-10-16
  course experiment) — one-pass ranking. Not yet tried with Claude Code.

## Phase 1: Project profile selection

### [ ] P001.1.1 Add Place assistance entry point
Read:
- app/src/jvmMain/kotlin/dev/ruleblend/app/assistance/AssistanceButton.kt
- app/src/jvmMain/kotlin/dev/ruleblend/app/assistance/AssistanceLaunch.kt
- app/src/jvmMain/kotlin/dev/ruleblend/app/assistance/LibraryAssistance.kt
- app/src/jvmMain/kotlin/dev/ruleblend/app/assistance/AssistanceSessions.kt
- app/src/jvmMain/kotlin/dev/ruleblend/app/place/PlaceScreen.kt
- app/src/jvmMain/kotlin/dev/ruleblend/app/place/PlaceProjectAssistants.kt
- app/src/jvmTest/kotlin/dev/ruleblend/app/assistance/AssistanceStartupTest.kt
Do:
- Add the existing assistance button to a project in Place, next to its assistants.
- Build the starting text from the project name and absolute path: ask to pick a profile for this
  project through Ruleblend MCP and the ruleblend skill, discussing first.
- Reuse the Library launch path: native prefill, no automatic submission, the same single session
  and replacement prompt.
- Cover the starting text and the one-session rule with unit tests.
- Describe the Place entry point in docs/ARCHITECTURE.md#ui and its Russian counterpart.
- Add a CHANGELOG bullet; raise `appBuild` by one.
Verify: `./gradlew :app:jvmTest`
Traps: The entry point and starting text need the user's approval before code. If the plan's
  Decisions do not record that approval, stop with `docflow block`. The profile targets the
  project's enabled assistants, not only the one running in the terminal.
Depends: -
Commit: P001.1.1: add Place assistance entry

### [ ] P001.1.2 Add profile picking to the skill
Read:
- mcp/src/jvmMain/resources/dev/ruleblend/mcp/skill/SKILL.md
- docs/ARCHITECTURE.md#profiles
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
  stack-agnostic generic agents stay out; ordered by need, at most 10 (the reference took 2–6).
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
Commit: P001.1.2: add profile picking to skill

### [ ] P001.1.3 Accept profile selection against the reference
Read:
- docs/ARCHITECTURE.md#profiles
- docs/UI_E2E.md:L100-172
Do:
- Rebase `feat/ai-assistance` onto `main`: project subagents for Codex and MCP installation with a
  working directory containing `.ruleblend` need `e2ca27f`.
- Prepare a clone of the alkaa project and an isolated library rebuilt by
  `week-7-task-5/tools/build_library.py` from the course repository `../AIAC9`.
- Run Ruleblend isolated (see plan Traps), open Place for the clone, start the assistant from the
  new entry point and let it pick a profile.
- Compare its picks with `week-7-task-5/reference/` for alkaa: matches, extras, misses, with both
  sides' reasons; jrnl and number-flow are optional.
- Record the comparison and every unchecked item in the Handoff.
Verify: `./gradlew :core:jvmTest :mcp:jvmTest :app:jvmTest`
Traps: The live run sends real model requests: get the user's agreement on the scenario first.
  Open question — how the terminal's Claude Code stays authenticated while the library is
  isolated; ask the user before the run, block if undecided. Never touch the user's projects or
  library. The reference is pinned to project and source SHAs; check out those SHAs. The branch
  is local and never pushed; check `git log --oneline origin/main..HEAD` before rebasing.
  Rebase conflicts: `main` still has the stale pre-docflow `docs/plans/AI_ASSISTANCE.md` and
  `CLAUDE_CODE_MODS.md` — keep them deleted, the docflow plans win (user, 2026-10-17).
  `AGENTS.md` is Ruleblend-managed: never hand-merge it; take either side, then reinstall the
  project rules through Ruleblend.
Depends: P001.1.1, P001.1.2
Model: strong
Commit: P001.1.3: accept profile selection

## Phase 2: Terminal acceptance

### [ ] P001.2.1 Accept terminal input on macOS arm64
Read:
- CONTRIBUTING.md:L44-60
- docs/UI_E2E.md:L100-172
- app/src/assistantSpike/kotlin/dev/ruleblend/app/spike/AssistanceNativeCheck.kt
Do:
- Run `./gradlew :app:assistanceNativeCheck` in an active native window with a human at the
  keyboard.
- Check user typing, system paste, focus return after leaving the panel, panel resize, IME input
  and accessibility of the panel controls.
- Repeat the same in the packaged image from `:app:packageAssistanceNativeCheck`.
- Fix small defects in this unit; record each gate as passed or blocked with the reason in the
  Handoff.
Verify: `python3 -B tools/ui_e2e.py ui --tests '*AssistanceUiTest*'`
Traps: Needs a person at the keyboard; an agent alone cannot pass it (see plan Traps). The check
  invokes no model.
Depends: -
Model: strong
Commit: P001.2.1: accept terminal on macOS arm64

### [ ] P001.2.2 Accept terminal on macOS x64
Read:
- CONTRIBUTING.md:L44-60
- docs/UI_E2E.md:L100-172
Do:
- On a macOS x64 host build the packaged app and repeat the P001.2.1 checks.
- Record each gate as passed or blocked in the Handoff.
Verify: `./gradlew :app:checkAssistanceLifecycle`
Traps: Another architecture's build or Rosetta on arm64 does not close this gate.
Depends: P001.2.1
Commit: P001.2.2: accept terminal on macOS x64

### [ ] P001.2.3 Accept terminal on Windows
Read:
- CONTRIBUTING.md:L44-60
- docs/ARCHITECTURE.md#windows-distribution
Do:
- On a Windows host build the packaged app and repeat the P001.2.1 checks, including paths with
  Unicode and spaces.
- Record each gate as passed or blocked in the Handoff.
Verify: `./gradlew :app:jvmTest`
Traps: A macOS host or another OS's Gradle build does not close the Windows gate.
Depends: P001.2.1
Commit: P001.2.3: accept terminal on Windows
