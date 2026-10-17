---
id: P001
title: Embedded AI-assistant terminal
closed: 2026-10-17
---
## Outcome

AI help opens Claude Code in a bottom-panel embedded terminal for a saved Library object, with an
editable unsubmitted starting text, one session at a time. Covered by automated lifecycle and UI
checks on macOS arm64 only; interactive input (typing, paste, IME, focus, accessibility) and the
macOS x64 and Windows builds were not verified.

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
- Plan abandoned with the manual gates undone — the user ruled out human-at-keyboard acceptance
  (2026-10-17): the agent verifies with automated checks only, defects come back as bug reports from
  ordinary use — scripted per-platform acceptance units.

## Gotchas

- Terminal text is absent from the macOS AX tree, and native automation delivers no keys to the
  Compose window; programmatic PTY writes are not user input, so automated checks cannot prove
  interactive input.
- Process exit is tracked, not the `/exit` string; the exited CLI's output stays until the panel is
  closed.
- A starting text is acceptable only while it stays unsubmitted in the CLI editor; a timer alone
  does not prove input readiness.

## Follow-ups

- Run the embedded terminal on macOS x64 and Windows (paths with Unicode and spaces).
