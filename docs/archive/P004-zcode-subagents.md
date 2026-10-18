---
id: P004
title: ZCode subagent support
closed: 2026-10-18
---
## Outcome

A Library subagent installs into ZCode the way it installs into Claude Code: `~/.zcode/agents/<id>.md`
and `<project>/.zcode/agents/<id>.md` are fingerprint-owned destinations through the existing
`SubagentInstallService`, Git import recognizes `.zcode/agents`, and Settings, Place, Coverage and
the MCP tools report ZCode as a supported subagent target. The full core/mcp/app gate ran green.

## Decisions

- Declare only `model`/`tools`/`color` — parity with the Claude Code field set keeps the editor
  small; a wider ZCode field set was rejected because those knobs are not portable values worth
  an editor row per assistant.
- Fixed `~/.zcode` root instead of reading `storage.dir` — consistent with the ZCode skill and
  rules surfaces, which already ignore that relocation; one assistant must not grow a second
  root-resolution mechanism.

## Gotchas

- `~/.zcode/cli/agents/` looks like the right directory name but is a live session store
  (`sess_*` directories); it is never a definition destination. Per-agent enable state in
  `~/.zcode/v2/agents-state.json` can only disable user-scope agents; Ruleblend does not manage
  that file.
- ZCode model values are `providerId/modelId`; the Claude-style aliases
  (inherit/main/sonnet/opus/haiku) are parsed but ignored, which is why ZCode's model field
  carries no suggestions.
- On a name conflict between scopes a project definition overrides a user one, so installing the
  same library block at both scopes cannot shadow itself.
- Coverage agent columns expand only when the group holds more than one place; a single agent
  place stays folded under the `agents` column.

## Follow-ups

Нет.
