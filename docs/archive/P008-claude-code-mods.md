---
id: P008
title: Claude Code mods in the library
closed: 2026-10-31
---
## Outcome

Claude Code mods in the Ruleblend library were implemented 2026-10-10…11 — portable packages, Git
sync, source import, the Claude Code adapter and Library/Global UI, transfer verified between
isolated device roots — and frozen 2026-10-16 as premature. The app hides every mod surface behind
`ModsFeature.enabled = false` (commit `b95bcc3`) and never runs `claude plugin`; core storage, Git
sync and archives keep carrying mods, their tests stay in the regular run. The implementation
contract lives in
[ARCHITECTURE.md#claude-code-mods-implementation-contract](../ARCHITECTURE.md#claude-code-mods-implementation-contract);
plan P002 is collapsed into this note. Research baseline: CLI 2.1.287, app 0.5.0 build 262.

## Decisions
- Freeze mods in the app — building them in is premature at this stage (user, 2026-10-16) —
  deleting the mods code entirely was rejected; the code stays behind the flag, so unfreezing is
  a flag flip plus re-acceptance, not a rebuild.
- No new navigation item: Library type `Mods`, Global → Claude Code `Mods` tab, sync through the
  existing library Git — approved direction 2026-10-10.
- Removing from Library, uninstalling and disabling are separate actions; an installed package is
  updated only by an explicit Apply update — sync must never enable code by itself.

## Gotchas

- A mod is a plugin with JS/TS function hooks (`.claude-plugin/plugin.json`, `hooks/hooks.json`, a
  file exporting `register(on)`) and may also carry skills, agents, commands and MCP — one
  package, never split; `hooks.json.modules` paths are relative to the hooks file.
- An install record does not prove the current session runs that revision: `/reload-plugins` or a
  new session is required.
- Mods policy or `disableAllHooks` may block execution after a successful install; do not change
  managed policy or enable all hooks for one mod.
- `--keep-data` does not keep options/secrets; `marketplace remove` may remove all its plugins —
  never use it to remove one mod.
- `plugin validate` does not run code; `plugin test` does. Docs describe `plugin install
  --marketplace` since 2.1.292; 2.1.287 lacks it — check local help and use separate
  `marketplace add` and `plugin install`.
- claude.ai sync does not send CLI-installed plugins back; `pluginConfigs`, secrets and
  `${CLAUDE_PLUGIN_DATA}` are not package files. Local relative-path marketplaces load in place,
  not as a cache copy.

## Follow-ups

- If the topic returns: replan from this note and the ARCHITECTURE contract; re-run `claude
  --version` and `claude plugin --help` first — CLI facts here are baseline 2.1.287.
