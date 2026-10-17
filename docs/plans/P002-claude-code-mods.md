---
id: P002
title: Claude Code mods in the library
type: feature
status: frozen
created: 2026-10-10
gate: ./gradlew :core:jvmTest :mcp:jvmTest :app:jvmTest
---
## Goal

Whole Claude Code mods move between devices through the Ruleblend library and are added,
enabled, disabled and removed in Claude Code from Ruleblend, accepted on native packaged macOS
and Windows.

## Context

Done 2026-10-10…11: format research and transport check, portable model with storage, sync and
archive, package import and source updates, Claude Code adapter and lifecycle, Library and Global
UI; transfer between isolated device roots via a local bare remote, offline import/install,
explicit Apply, conflict, cold restart and orphan lifecycle. Frozen 2026-10-16: the app hides
every mod surface behind `ModsFeature.enabled` and never runs `claude plugin`; core, storage,
Git sync and archives keep mods, their tests stay in the regular run. Research baseline: CLI
2.1.287, app 0.5.0 build 262.

Facts checked 2026-10-10 against the Claude Code docs (plugins/mods overview, create, reference,
plugins-reference, cli-reference, loading, install at https://code.claude.com/docs/en/plugins/):
a mod is a plugin with JS/TS function hooks (`.claude-plugin/plugin.json`, `hooks/hooks.json`, a
file exporting `register(on)`) and may also carry skills, agents, commands and MCP — one package,
never split. `hooks.json.modules` is relative to the hooks file. Mods are on by default in the
terminal since 2.1.287. Local relative-path marketplaces load in place, not as a cache copy.
claude.ai sync does not send CLI-installed plugins back. `pluginConfigs`, secrets and
`${CLAUDE_PLUGIN_DATA}` are not package files. `plugin validate` does not run code;
`plugin test` does. Mods run with the user's permissions.

- docs/ARCHITECTURE.md#claude-code-mods-implementation-contract
- app/src/jvmMain/kotlin/dev/ruleblend/app/library/ModsFeature.kt

## Constraints

- First version: user scope only; project screens show the inherited global status; other
  assistants do not accept mods.
- Out of scope: JS/TS authoring, visual hooks editor, third-party marketplace catalog, new
  Ruleblend MCP tools for mods, project/local scope, profiles with mods, syncing secrets or
  runtime data, automatic activation on another device.
- Acceptance uses only our own small inert fixture; never enable someone else's mod. No
  external or paid requests; a check that needs them stays open.
- Desktop Code tab is not claimed; it is a separate gate only if support is claimed.

## Traps

- The Git library has root allowlists and explicit staging; archive, membership, merge and
  source identity extend together.
- Metadata LF and immutable payload bytes differ: do not normalize hooks/assets by the line-ending
  setting, do not lose executable metadata in ZIP/Windows transfer.
- Skill DirectoryReplace is not an atomic mod replacement; the runtime needs an immutable snapshot
  publish.
- Manifest name and marketplace entry name are different identities; foreign, builtin, managed,
  @skills-dir, @inline and @synced never become owned by a matching name or path.
- `--keep-data` does not keep options/secrets; `marketplace remove` may remove all its plugins,
  never use it to remove one mod.
- Mods policy or `disableAllHooks` may block execution after a successful install; do not change
  managed policy or enable all hooks for one mod.
- Import may bring MCP and commands: show every component; import is not consent to run them.
  Never run `npm install`, build scripts or plugin test/eval of a third-party package on import.
- Docs describe `install --marketplace` since 2.1.292; 2.1.287 lacks it. Check local help; use
  separate `marketplace add` and `plugin install`.
- An install record does not prove the current session runs that revision: `/reload-plugins` or a
  new session is required.
- Gradle cache locks and `.git/index.lock`/EPERM may be environment limits; do not fix the product
  for them, do not remove others' lock files.

## Decisions

- Freeze mods in the app — building them in is premature at this stage (user, 2026-10-16) —
  deleting the mods code.
- No new navigation item: Library type `Mods`, Global → Claude Code `Mods` tab, sync through the
  existing library Git — approved direction 2026-10-10.
- Removing from Library, uninstalling and disabling are separate actions; an installed package is
  updated only by an explicit Apply update — sync must never enable code by itself.

## Phase 1: Unfreeze

### [ ] P002.1.1 Re-enable mods in the app
Read:
- app/src/jvmMain/kotlin/dev/ruleblend/app/library/ModsFeature.kt
- app/src/jvmTest/kotlin/dev/ruleblend/app/library/ModsUiTest.kt
- docs/ARCHITECTURE.md#claude-code-mods-implementation-contract
Do:
- Run `claude --version` and `claude plugin --help`; compare with the facts in Context and the
  plan Traps; record differences in the plan's Decisions and fix the adapter if a command or
  flag changed.
- Switch `ModsFeature.enabled` on and update `ModsUiTest` to expect visible mod surfaces.
- Restore the CHANGELOG bullets removed by commit `b95bcc3` (`git show b95bcc3 -- CHANGELOG.md`)
  under [Unreleased].
- Drop the frozen note from docs/ARCHITECTURE.md and docs/ru/ARCHITECTURE.md.
- Raise `appBuild` by one.
Verify: `./gradlew :app:jvmTest --tests '*ModsUiTest*'`
Traps: Run `python3 tools/claude_mod_transport_spike.py --dry-run` and
  `python3 tools/claude_mod_runtime_gate.py --dry-run` before their live forms; live forms need
  Claude Code installed on macOS and must not touch the real Claude or Ruleblend home.
Model: strong
Commit: P002.1.1: re-enable Claude Code mods

## Phase 2: Acceptance

### [ ] P002.2.1 Accept mods in native macOS UI
Read:
- docs/UI_E2E.md:L100-172
- app/src/jvmMain/kotlin/dev/ruleblend/app/library/ModPanels.kt
- app/src/jvmMain/kotlin/dev/ruleblend/app/library/ModUiModel.kt
- tools/claude_mod_runtime_gate.py
Do:
- Build the packaged app with `./gradlew :app:createDistributable` and run it on isolated roots.
- Show an orphaned snapshot in the native packaged UI after Library removal via sync.
- Pass the full transfer scenario in the native packaged UI: Git sync → select mods → Install in
  Claude Code → explicit Apply update → removal.
- Run the inert fixture's hooks in a real Claude Code session after `/reload-plugins`.
- End spawned processes, delete only owned temp fixtures; confirm the real Claude and Ruleblend
  home are unchanged.
- Record each gate as passed or blocked in the Handoff.
Verify: `python3 tools/claude_mod_runtime_gate.py`
Traps: A record or probe is not proof that hooks run in the current session.
Model: strong
Commit: P002.2.1: accept mods on macOS

### [ ] P002.2.2 Accept mods on Windows
Read:
- docs/ARCHITECTURE.md#windows-distribution
- docs/ARCHITECTURE.md#claude-code-mods-implementation-contract
Do:
- On a Windows host build the packaged app and pass the P002.2.1 transfer scenario.
- Check the CLI path with Unicode and space paths, case collisions and executable metadata.
- Record each gate as passed or blocked in the Handoff.
Verify: `./gradlew :core:jvmTest :app:jvmTest`
Traps: Another OS's Gradle build does not close the Windows gate.
Depends: P002.1.1
Commit: P002.2.2: accept mods on Windows
