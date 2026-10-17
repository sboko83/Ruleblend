---
id: P001
title: Embedded AI-assistant terminal
type: feature
status: active
created: 2026-10-10
gate: ./gradlew :core:jvmTest :mcp:jvmTest :app:jvmTest
---
## Goal

A bottom-panel terminal in Ruleblend starts Claude Code for a Library object with an editable,
unsubmitted starting text, accepted interactively on macOS arm64, macOS x64 and Windows.

## Context

Ordinary embedded terminal, as in Orca: Ruleblend provides the terminal and the process launch;
conversation, authorization, permissions and tools stay the chosen CLI's own. Done 2026-10-11
on branch `feat/ai-assistance` (local, never pushed): terminal/PTY and CLI launch, starting text
and Claude Code selection, single-process lifecycle, bottom panel and Settings, invocation from
Library with data refresh. Automated checks and macOS arm64 packaging are done; what remains are
the interactive and platform gates (pre-docflow phase 6). Project profile selection, the
pre-docflow phase 7, moved to P003.

- docs/ARCHITECTURE.md#ui
- CONTRIBUTING.md:L44-60

## Constraints

- Keep `app → mcp → core`; terminal component and PTY belong to `app`.
- Claude Code only; Codex stays deferred (reason in docs/ARCHITECTURE.md#ui).
- Library, editors, navigation and their styling are not reworked; additions only.
- No live AI requests: launching the CLI without submitting and offline fake checks only. Never
  change the user's real profiles, projects or library for tests.
- macOS arm64 is the first platform; macOS x64 and Windows are accepted separately.

## Traps

- Terminal text is absent from the macOS AX tree. Programmatic writes to the PTY are not user
  input. Native automation got no AWT focus and delivered no keys even to a plain Compose field:
  none of that counts as interactive acceptance.
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

## Phase 1: Terminal acceptance

### [ ] P001.1.1 Accept terminal input on macOS arm64
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
Commit: P001.1.1: accept terminal on macOS arm64

### [ ] P001.1.2 Accept terminal on macOS x64
Read:
- CONTRIBUTING.md:L44-60
- docs/UI_E2E.md:L100-172
Do:
- On a macOS x64 host build the packaged app and repeat the P001.1.1 checks.
- Record each gate as passed or blocked in the Handoff.
Verify: `./gradlew :app:checkAssistanceLifecycle`
Traps: Another architecture's build or Rosetta on arm64 does not close this gate.
Depends: P001.1.1
Commit: P001.1.2: accept terminal on macOS x64

### [ ] P001.1.3 Accept terminal on Windows
Read:
- CONTRIBUTING.md:L44-60
- docs/ARCHITECTURE.md#windows-distribution
Do:
- On a Windows host build the packaged app and repeat the P001.1.1 checks, including paths with
  Unicode and spaces.
- Record each gate as passed or blocked in the Handoff.
Verify: `./gradlew :app:jvmTest`
Traps: A macOS host or another OS's Gradle build does not close the Windows gate.
Depends: P001.1.1
Commit: P001.1.3: accept terminal on Windows
