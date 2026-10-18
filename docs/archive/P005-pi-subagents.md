---
id: P005
title: Pi subagent support
closed: 2026-10-18
---
## Outcome

Library subagents install into Pi at `~/.pi/agent/agents/<id>.md` and `<project>/.pi/agents/<id>.md`
with Pi's own `model`/`tools`/`thinking` fields; Git import recognizes `.pi/agents`. Settings marks
the capability `◐ Subagents*` with a footnote naming the pi-subagents extension and its install command.

## Decisions

- Pi subagents are unconditionally SUPPORTED, the extension is never probed — the surface is a plain
  file tree, and support flickering with the packages in `~/.pi/agent/settings.json` would make
  installs unstable — rejected: a new `CapabilityLimit` value or an adapter check for the package.
- The requirement is data on the adapter (`subagentExtension`: name + install command) that Settings
  renders as an asterisk and footnote — the app must not guess from an agent id — rejected: a
  hard-coded Pi branch in the Settings screen.
- The format declares only `model`, `tools`, `thinking` — the intersection of nicobailon's
  `pi-subagents` (reference by adoption, ~583k vs ~35k downloads/month) and `@tintinweb/pi-subagents`
  plus Pi's own thinking levels, so a file loads under either package — rejected: a wider
  per-package field set that one of the readers would ignore.
- Tests that used Pi as the unsupported subagent target now refuse an unknown agent id — no real
  adapter lacks subagent support anymore — rejected: a synthetic unsupported adapter.

## Gotchas

- Without an installed subagents extension Pi ignores the written files, yet Ruleblend reports them
  as installed and in sync; only the Settings footnote tells the user.
- pi-subagents discovers nested `agents/**/*.md`; Ruleblend lists direct children only, so a foreign
  nested definition stays invisible.
- Ruleblend never writes the shared `.agents/agents/` root, although both extensions read it.
- `LEGACY_VARIANT_AGENT_IDS` stays claude-code/codex/kimi-code: a pre-variants `model` must not leak
  onto Pi.

## Follow-ups

None.
