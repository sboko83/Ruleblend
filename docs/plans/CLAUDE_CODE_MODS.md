# Claude Code Mods — integration plan

Created: 2026-10-10. Status: `IN PROGRESS`, direction approved by the user.
Next phase: **6**, transfer acceptance and platform gates.
Goal: move whole mods between devices through the Ruleblend library,
and conveniently add, enable, disable and remove them in Claude Code.

## How to continue in a new session

1. Read this file, `AGENTS.md` and the relevant sections of `docs/ARCHITECTURE.md`.
2. Check `git status --short`, the current APIs and `claude --version` / `claude plugin --help`.
   At research time the CLI was **2.1.287**, the app **0.5.0 / build 262**.
   This is the research baseline, not a requirement to keep these numbers.
3. Until the proposal is approved, do only research and plan edits.
   Once implementation is authorized, work on the next unfinished phase;
   do not move to the next one if a mandatory criterion of the current one is not met.
4. Move `TODO → IN PROGRESS → DONE`; on an obstacle use `BLOCKED`
   with a concrete reason and next action. A block on one platform gate does not
   forbid independent work, but does not allow declaring the whole plan done.
5. Before ending the session update statuses, the handover section and the next step.
   Report check output in chat; carry over here only remaining limitations.
   Collapse a closed phase to one status line and drop its working notes.
6. Until the work is finished, this file is the only working plan for the topic.
   Move permanent decisions made during implementation to ARCHITECTURE with a link from here;
   public behaviour — to CHANGELOG. Do not duplicate the working plan in ROADMAP.
7. For shipped code/resources raise `appBuild` once per finished change,
   do not change `appVersion`. After the required checks pass, make a separate
   English commit of only your own files, without push and without others' staged changes.

Apply the instructions in this file when working on mods; do not change AGENTS/skills.

## Verified external facts

Documentation checked 2026-10-10; before phase 1 compare it with the installed version.
Newer pages may describe capabilities newer than the local CLI.

- A mod is a plugin with JS/TS function hooks. Minimum: `.claude-plugin/plugin.json`,
  `hooks/hooks.json`, a file exporting `register(on)`. It may also contain
  skills, agents, commands and MCP; the package must not be split into independent items.
  [Overview](https://code.claude.com/docs/en/plugins/mods/overview),
  [Create a mod](https://code.claude.com/docs/en/plugins/mods/create).
- `hooks.json.modules` points to code relative to the hooks file itself.
  Plain settings hooks without modules are not a mod by themselves. Account for
  manifest-declared hooks and inline forms, not only the standard path.
  [Mods reference](https://code.claude.com/docs/en/plugins/mods/reference),
  [Manifest](https://code.claude.com/docs/en/plugins-reference).
- In the terminal mods are enabled by default since 2.1.287. The old
  `CLAUDE_CODE_ENABLE_FUNCTION_HOOKS` is ignored there. Hooks can run without UI;
  drawn panels are available in the terminal and the Desktop Code tab, with surface limitations.
  [Overview](https://code.claude.com/docs/en/plugins/mods/overview).
- The CLI supports `marketplace add <path>`, `install`, `list --json`, `enable`, `disable`,
  `update`, `uninstall`; operations take a scope. These commands and the presence of JSON/install,
  JSON/uninstall, keep-data were verified via local `--help`, without profile changes.
  Install/result JSON is the last stdout line; list JSON is a separate array form.
  [CLI reference](https://code.claude.com/docs/en/plugins/cli-reference).
- Plugins from a relative-path local marketplace load **in place**,
  not as an independent cache copy. A project's `.claude/plugins/` is not
  an automatically scanned directory. There is an alternative auto-loading of plugins
  from `.claude/skills/*`, but that is a different origin and a different lifecycle.
  [Loading](https://code.claude.com/docs/en/plugins/loading).
- Sync from claude.ai exists, but CLI-installed plugins are not sent back
  to the account. It does not replace transferring the user's Ruleblend library.
  [Install and manage](https://code.claude.com/docs/en/plugins/install).
- `pluginConfigs` and secure-storage secrets differ from package files;
  `${CLAUDE_PLUGIN_DATA}` is runtime data. The last uninstall also removes
  options/secrets; `--keep-data` keeps data but does not promise to keep options/secrets.
  Removing a marketplace may remove all its plugins. Built-in mods have separate
  restrictions and must not become Ruleblend-managed copies.
  [Manifest](https://code.claude.com/docs/en/plugins-reference),
  [CLI reference](https://code.claude.com/docs/en/plugins/cli-reference).
- `plugin validate` analyzes a mod without running its code; it is a format check,
  not proof of safety. `plugin test` runs the tested mod's code.
  Mods run with the user's permissions. [Create](https://code.claude.com/docs/en/plugins/mods/create).

Format references: [official mods](https://github.com/anthropics/claude-code/tree/main/mods),
[agents-md manifest](https://raw.githubusercontent.com/anthropics/claude-code/main/mods/agents-md/.claude-plugin/plugin.json),
[agents-md hooks](https://raw.githubusercontent.com/anthropics/claude-code/main/mods/agents-md/hooks/hooks.json).
For acceptance use our own small inert fixture; do not enable someone else's mod.

## Proposed place in the app

| Surface | What to add |
|---|---|
| Library | New `Mods` type, `Claude Code` badge, existing search, filters, groups and bulk actions |
| Adding to Library | Git URL → package selection; local folder → snapshot; import of a found installed mod → preview |
| Inspector | Description, manifest name/version, source/revision, file list, compatibility errors and local status |
| Global → Claude Code | `Mods` tab: installed and external packages, install, enable/disable, apply update, remove installation |
| Settings → Library | Existing Git sync carries mods together with the rest of Library |
| Settings → Assistants | CLI/version diagnostics only; no separate mod manager needed here |

Direction approved. No new navigation item is needed.
In the first version the scope is **user**; mods apply to the user in all projects.
Project screens do not offer a project installation; they may show the
inherited global status. Other assistants do not accept mods.

Removing from Library, uninstalling from the device and disabling are different actions.
On a new device: Git sync → select mods/group → `Install in Claude Code`.
One bulk action moves the selected set into the local installation; sync itself does not enable code.
An installed package is updated only by an explicit `Apply update`, even if Library is already newer.
After a change, show that `/reload-plugins` or a new session is required; an install
record does not prove that the current session runs the intended revision.

Out of scope for the first version: JS/TS authoring, a visual hooks editor, a catalog of
third-party marketplaces, new Ruleblend MCP tools for mods, project/local scope, profiles with mods,
syncing secrets/runtime data, automatic activation on another device.

## Storage and installation contract

Permanent decisions: [ARCHITECTURE](../ARCHITECTURE.md#claude-code-mods-implementation-contract),
[Russian version](../ru/ARCHITECTURE.md#моды-claude-code-контракт-реализации).
Reproducible gate: `python3 tools/claude_mod_transport_spike.py --dry-run`, then without the flag
on macOS with Claude Code installed. The spike is not the runtime adapter implementation.
Adapter gate: `python3 tools/claude_mod_runtime_gate.py --dry-run`, then without the flag.

## Phases and completion criteria

| Phase | State |
|---|---|
| 0. Research of the format and the current app's capabilities | DONE 2026-10-10 |
| 1. Transport check and contract fixation | DONE 2026-10-10 |
| 2. Portable model, storage, sync and archive | DONE 2026-10-10 |
| 3. Adding packages and source updates | DONE 2026-10-10 |
| 4. Claude Code adapter and lifecycle | DONE 2026-10-10 |
| 5. Library and Global UI | DONE 2026-10-11 |
| 6. Cross-device transfer acceptance and platform gates | IN PROGRESS |

### 6. Cross-device transfer acceptance and platform gates

- Transfer between isolated device roots via a local bare remote, offline import/install,
  explicit Apply, conflict, cold restart and orphan lifecycle: DONE 2026-10-11.
- TODO: show an orphaned snapshot in the native packaged UI after Library removal via sync.
- Pass the real native packaged UI on macOS; separately Windows packaged UI and CLI
  with Unicode/space paths, case collisions and executable metadata. Another OS/Gradle
  build does not close the Windows gate. Desktop Code tab is a separate gate if we claim support.
- For real loading use only the agreed inert fixture; do not treat a
  record/probe as proof that hooks run in the current Claude session.
  External/paid requests are not required; if a check needs them, the gate stays open.
- After the check, end the spawned processes and delete only owned temp fixtures;
  confirm there are no changes in the real Claude/Ruleblend home.

**Gate:** transfer proven by the full scenario, native gates explicitly DONE/BLOCKED,
all required checks passed; only then the whole plan's status is `DONE`.

## Code entry map and checks

Read selectively; this is navigation for the implementation, not a list of mandatory edits.

| Area | Start here |
|---|---|
| Core model | `core/src/commonMain/kotlin/dev/ruleblend/core/model/{Mod,Skill,Group,Profile}.kt` |
| Storage/sync | `core/.../storage/{ModStorage,ModTree,LibraryRepository,LibraryGit,LibraryMembership,LibraryLineEndings}.kt` |
| Safe trees | `core/.../storage/{SkillTreeLimits,SkillTreeFingerprint,LibraryObjectId,GitImportCheckout}.kt` |
| Source import | `core/.../storage/{GitSkillImporter,GitSubagentDiscovery}.kt`, `core/.../usecase/ImportLibrary.kt` |
| Archive | `core/.../exchange/LibraryArchive.kt` |
| Shared policies | `core/.../usecase/{InstallObject,InstallPolicy,MutateLibrary}.kt`, `core/.../integration/` |
| Mod lifecycle | `core/.../usecase/ModLifecycle.kt`, `core/.../integration/{ModRuntime,ClaudeModRuntime}.kt` |
| Library UI | `app/.../library/{LibraryCatalog,LibraryModel,LibraryDialogs,LibraryInspector,LibraryBulk}.kt` |
| Mod UI | `app/.../library/{ModUiModel,ModPanels,ModAddDialog}.kt` |
| Place UI | `app/.../place/PlaceFiles.kt`, other Place models/screens |
| CLI lookup | `app/.../util/AgentLaunch.kt`, `core/.../integration/AgentCli.kt` |
| Acceptance harness | `docs/UI_E2E.md`, `tools/ui_e2e.py` |

`core/...` / `app/...` mean `src/jvmMain/kotlin/dev/ruleblend/core` / `app`.
Base commands for the implementation: `./gradlew :core:jvmTest :mcp:jvmTest :app:jvmTest`,
`./gradlew :app:createDistributable`. Pick targeted tests per phase, then the mandatory
regression set; take the concrete UI-E2E commands from `docs/UI_E2E.md`.
Do not declare these commands run just because they are listed here.

## Traps for any phase

- The Git library has root allowlists and explicit staging; a new folder will not start syncing by itself.
  Archive, membership, merge and source identity are extended consistently.
- Metadata LF and immutable payload bytes differ; do not normalize hooks/assets
  by the machine line-ending setting and do not lose executable metadata in ZIP/Windows transfer.
- Do not reuse the current skill DirectoryReplace as a guarantee of an atomic mod replacement:
  it allows a brief mix of files. The runtime needs a separate immutable snapshot publish.
- Manifest name and marketplace entry name are different identities. Foreign/builtin/managed,
  @skills-dir/@inline/@synced cannot become owned by a matching name or path.
- `--keep-data` does not keep everything; `marketplace remove` is not a way to remove one mod.
- Mods policy/disableAllHooks may block execution even after a successful install.
  Do not change managed policy and do not enable all hooks for the sake of one mod.
- A full package import may bring MCP and commands; do not show only the UI hook
  and hide the other components. Import does not equal consent to run them.
- Do not run `npm install`, build scripts, plugin test/eval of a third-party package on import.
- The docs already describe `install --marketplace` since 2.1.292; 2.1.287 lacks this flag.
  Check local help, use separate marketplace add / plugin install.
- Gradle cache locks and `.git/index.lock`/EPERM may be environment limitations;
  do not fix the product for them and do not remove others' lock files.

## Handover to the next session

- Next action: phase 6 only; [contract](#storage-and-installation-contract).
- macOS native packaged UI and running inert hooks in a session remain TODO.
- Windows packaged UI/CLI — BLOCKED in the current macOS environment; next step is a Windows host.
- Desktop Code tab not checked; do not treat support for this surface as accepted.
- Do not transfer or read secrets in the isolated spike; removal semantics follow the CLI reference.
- The user's Android Studio configs were saved in a separate commit `7d37e6d`.
