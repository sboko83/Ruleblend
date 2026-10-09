# Architecture

[Русская версия](ru/ARCHITECTURE.md)

How Ruleblend is built. For what changed when see [CHANGELOG.md](../CHANGELOG.md); for what is still
open see [ROADMAP.md](ROADMAP.md).

## Purpose

Desktop app that organizes a library of instruction blocks for AI coding agents and installs them
into agent globals or project folders. Public distribution decisions live in [RELEASING](RELEASING.md).

## Modules

Kotlin Multiplatform with Gradle 9.7 and the modern KMP DSL. The project has JVM targets only;
Android Gradle Plugin is not applied.

```
ruleblend/
├── core/   # models, storage, git, marker parser, agent adapters, translation
├── mcp/    # MCP server facade over core (stdio) + MCP-config connectors + MCP-block installers
├── app/    # Compose Desktop UI over core; `--mcp` runs the mcp server instead
└── tools/translate-helper/  # Swift helper over the macOS Translation framework, shipped in the bundle
```

Dependencies point one way: `app → mcp → core`. `core` has no UI knowledge — a future CLI is just
another facade over it, as the MCP server already is. JGit ties `core` to the JVM; acceptable, all
desktop targets are JVM.

On macOS the standard Gradle `run` entrypoint executes the generated `.app`, not a bare `JavaExec`:
LaunchServices derives the Dock and app-switcher identity from the bundle, while a direct JVM launch
can still be presented as `java` even with the legacy `-Xdock:name` flag.

### Use cases

`core/usecase` holds what a facade *does*, as opposed to what the services underneath it *write*:

| Use case | Owns |
|---|---|
| `InstallObject` / `RemoveObject` | one install or removal across targets × objects, reported per pair |
| `InstallPolicy` | the single answer to "may this be written here, and if not, why" |
| `AcceptLocalChange` | taking an edit made in a target file into the library as its next version |
| `MutateLibrary` | every write to the library, each as one transaction |
| `ImportLibrary` | archive import/export and Git skill/subagent import |

A facade — the Compose models, the MCP server — turns its input into a command and renders the typed
result. It does not decide. Before this layer the same safety questions (is the rule in scope, can
this target take the object at all, was it edited by hand since Ruleblend wrote it) were asked
independently by the Place surface, the Library bulk installer and the MCP facade, and they had
already drifted apart on a modified MCP entry. A divergence there writes into the wrong file or
drops a user's edit, so there is one implementation and the facades share it.

Removal is symmetric with installation: a copy edited by hand after Ruleblend wrote it is kept and
reported, for every object kind and whichever facade asked. Discarding such an edit is a decision
about that one file, taken on its row in Place, not a side effect of taking a bundle out.

`core` must not depend on `mcp`, so MCP writes reach the use cases through `McpBlockService`, a core
interface the `mcp` module's `McpInstallService` implements. A facade with no MCP support passes
`McpBlockService.None`.

## Windows distribution

- MSI installs per user into `%LOCALAPPDATA%/Ruleblend`, with Start menu and desktop shortcuts.
  A fixed install directory keeps registered MCP commands valid across upgrades.
- The UpgradeCode stays constant; the MSI version is `<major>.<minor>.<appBuild>` from `appVersion`
  (three fields, for example `0.5.240`). The UI retains the full `appVersion` and separate build.
  `appBuild` increases across patch releases; MSI's limits are 255, 255 and 65535 respectively.
  A fourth version field would be ignored by Windows Installer, preventing build-only upgrades.
- MSI wraps the prepared app image so the UTF-8 launcher manifest survives packaging. The bundled
  runtime includes the additional modules found by dependency analysis; external Java is unnecessary.
- Installer ownership ends at application files and shortcuts. The library, `~/.ruleblend` state
  and assistant configs remain outside the installation; upgrades and uninstall preserve them.

## Data model

- **Terminology** — an `agent` is an external tool (Claude Code, Codex, Pi, Kimi Code or ZCode); a
  `subagent` is a library item that describes behaviour for that tool. UI headings call the former
  an AI assistant, while persistent names and MCP contracts keep `agent`.
- **`Block`** — `id` (slug, also the filename), `name`, `description`, `version` (int,
  auto-incremented on an installable-definition change; new blocks start at 0 with an empty body, so
  the first saved body is v1), `type`, `content`. A `subagent` also carries `variants`: per-assistant
  fields keyed by agent id (see [Subagent formats](#subagent-formats)); changing them is a new
  definition version.
- **Types** — `rule` (markdown), `mcp` (canonical config JSON, see [MCP blocks](#mcp-blocks)) and
  `subagent` (Markdown). Skills are separate directory-backed items because their scripts,
  references and assets must stay together with `SKILL.md`.
- **`Skill`** — stable local id, name, description, opaque repository version, `SKILL.md` content
  and optional Git provenance (repository URL, exact commit SHA and repository-relative path).
  Imported skills are immutable; an editable fork moves that provenance to `forkedFrom` and starts
  a local numeric version under a `-changed` name. Local skills can also be created directly; saving
  synchronizes their structured name and description into `SKILL.md` frontmatter while preserving
  instructions and extra frontmatter keys.
- **Rule scope** — global or one project. Scope is deliberately not a `Block` field: its absolute
  project path is machine state in `config.json`, keyed by block id. Moving scope therefore neither
  bumps the content version nor leaks a local checkout path into the portable library Git history.
- **`Group`** — `id`, `name`, `description`, ordered `blockIds`, `skillIds`, and `version`
  (auto-incremented when either membership list changes).
- **`Profile`** — a portable, versioned project mode with ordered direct rule/MCP block, skill and
  subagent ids plus flat group ids. It can include groups but not another profile; its version
  increments when any membership list changes, while name-only and description-only edits keep it.
- **Reserved group `all`** — auto-maintained, holds every block and skill id. Recomputed on load and
  library-item changes; profiles are deliberately not members. The group is not editable or
  deletable, is pinned first, and is excluded from export/import (it is derived locally, not
  portable content).

## Storage

Everything lives under `~/.ruleblend/` (library path configurable):

On the first launch after the rename, when `~/.ruleblend/` does not exist but `~/.kitbash/` does,
Ruleblend copies the complete old home through a sibling staging directory and publishes it with an
atomic rename. The old home remains as a rollback copy; generated launchers and the process lock are
not carried over. If both homes exist, Ruleblend never merges or overwrites them and uses the current
home. An owned `kitbash` MCP registration is reported as stale until the user reconnects; reconnect
replaces it with `ruleblend` and migrates the owned bundled skill. An unrelated server or skill that
reuses the old name is preserved.

The old `kb` and `kb1` instruction formats remain readable across the Kitbash → Ruleblend rename.
New writes use `rb1`; the first content-changing write to an old run re-renders it as `rb1` without
changing hand-written text outside the managed region.

| Path | Holds | Disposable |
|---|---|---|
| `library/blocks/<id>.md` | YAML frontmatter (name, description, version, type, optional `heading`/`headingLevel`, optional `variants`, `source`/`forkedFrom` for subagents) + body | no |
| `library/groups/<id>.yaml` | group definition | no |
| `library/profiles/<id>.yaml` | portable profile definition | no |
| `library/skills/<id>/meta.yaml` | skill metadata and Git provenance | no |
| `library/skills/<id>/files/` | complete portable skill directory rooted at `SKILL.md` | no |
| `library/.git/` | JGit repo — every save is a local commit; optional remote is a two-way sync peer | no |
| `config.json` | app state: projects, profile bindings, rule scopes, settings, window frame, pane sizes, agent toggles, place list, kept drifts, hidden entries | no |
| `backups/` | manual whole-file snapshots + `index.json` manifest | no |
| `mcp-state.json` | what Ruleblend last wrote into agent MCP configs | degrades safely to foreign |
| `skill-state.json` | fingerprints of complete skill trees Ruleblend installed into targets | degrades safely to foreign |
| `subagent-state.json` | fingerprints of accepted standalone subagent definition files | degrades safely to foreign |
| `sources-state.json` | latest source-check time, remote HEAD and upstream fingerprints per imported skill/subagent | yes |
| `targets.index` | checksummed cache of target modes and block hashes | yes |
| `ruleblend.lock` | advisory cross-process lock over library, config, targets and sidecar state | yes |
| `launchers/` | generated macOS `.command` scripts that open an agent CLI in a project | yes |

### Line endings

The machine-local LF/CRLF setting defaults to LF on every platform. Generated block, group, profile
and skill metadata files use that choice on disk; Git always stores these definitions as LF, so
devices with different choices share the same objects and revisions. Managed rule runs use the
choice on their next write, preserving every byte outside them. Local skill editing formats its
`SKILL.md`; imported payloads, sibling assets, binary files and backups retain their original bytes.
The library owns marked sections of `.gitattributes` and `.git/info/attributes`; the latter protects
payload bytes even against nested upstream attributes. Repository-local `core.autocrlf=false` and
explicit `core.eol` keep native Git and JGit consistent without changing machine-wide settings.
Startup and setting changes normalize only clean definitions and commit any canonical migration
without bumping object versions or rewriting history. Staged and unsaved edits are preserved. A
normalization-only merge side cannot override a real edit from a device still using the old policy.

A project's header launches the agents whose CLIs this machine has. The launch is a generated shell
script handed to the terminal through `open`, not a direct spawn: only that puts the agent in a
terminal the user can type into, and it works with whichever terminal owns `.command` files rather
than with the two that happen to be scriptable. The script runs the agent through the user's own
login shell — an app started from Finder inherits `launchd`'s bare PATH, which knows nothing of
Homebrew, nvm or mise, and the agent re-reads that PATH when it spawns `node`. The same probe that
found the executable decides whether the shell is interactive, so a launch repeats what already
worked. Which agents can be launched is read from the machine and never configured; the terminal
application and the per-agent argument line are settings, and live in `config.json` like the rest of
the machine state.

Windows resolves native CLIs through PATH/PATHEXT and standard user installation directories;
relative PATH entries and the current project are excluded to avoid launching a project-local
executable accidentally. Windows Terminal receives an encoded PowerShell payload; unavailable
Terminal aliases fall back to a new PowerShell console. Explicit terminal choices support Windows
Terminal, PowerShell and cmd. The payload uses .NET process creation with an explicit working
directory: this preserves Unicode on Java 17 systems using an ANSI code page and keeps Terminal's
semicolon parser away from project paths and arguments. Native executables use CRT argument quoting;
`.cmd`/`.bat` wrappers forwarding `%*` receive two cmd escape layers. Batch arguments containing line
breaks are rejected before launch because cmd cannot transport them safely. No Windows launcher
files are persisted. Selected Windows editors use the same process boundary.

Machine state stays out of the library repo, so a remote contains only portable library content and
its history. `config.json` is forward/backward compatible (`ignoreUnknownKeys` +
`coerceInputValues`), so a new field loads with defaults from an older file.

Library object ids are portable single file names, validated before resolving a read or write path.
Block writes also reject `ruleblend-import` and `kitbash-import`: redirect migration owns these ids
and would otherwise mistake a user rule for an import pointer. Name-derived ids skip them.
Windows device names, trailing dots/spaces and case aliases are rejected on every OS, so a library
created on macOS remains usable on Windows. Skill trees also reject file/directory collisions.
Archive and Git skill batches validate all selected objects against current names before writing;
fresh Git checkouts validate tree paths before Windows can collapse two files onto one.

Project identity resolves existing filesystem aliases (including junctions) through the nearest
existing ancestor, then folds case on Windows. Missing projects retain a lexical identity. Config
and sidecar keys migrate on read and persist on the next write; project attachments and settings are
merged, with an inactive binding winning a conflict and the first set retaining project membership.
The original config is saved atomically as `config.json.before-path-migration` (numbered if occupied)
before migration is persisted, retaining conflicting choices and unknown fields for recovery.
Conflicting ownership records remain stored but cannot authorize overwriting an installed copy.
Manual backups match the same file identity while retaining their original blob addresses, so a
path migration cannot strand an earlier snapshot; the newest matching snapshot is used for restore.

`config.json.projectProfiles` maps each normalized project key to ordered `{id, active}` bindings.
It is machine-local because an attachment names one checkout rather than portable profile content.
Removing a project drops its bindings in the same config write as the project and its place state, so
an old checkout cannot leave a profile mode behind.

## Profiles

A **group** is an additive, portable answer to “what should be installed”; installing or removing it
affects only copies carrying that group's origin. A **profile** is a portable, versioned project mode:
it is attached only to a project, can be enabled or disabled there, and its removal reconciles its
members away. A profile may reference flat groups as well as its direct rules, MCP blocks, skills and
subagents; groups never contain profiles, and profiles never contain profiles. This leaves profiles
as the only nesting level and avoids a second kind of group.

The portable definition lives in `library/profiles/<id>.yaml`; the ordered attachment list and its
`active` flags live only in `config.json.projectProfiles`. An attachment therefore never travels with
library Git, export or import. Attaching through the GUI offers **merge** (the existing installation
becomes the base) or **replace** (remove the base after showing the affected objects). MCP exposes both
modes through `attach_profile`: Replace requires the revision from `get_project_profiles`, checked
under the library lock against bindings, base copies and library definitions before removal. The
shared removal policy protects edited copies and reports every refusal or failure. `install(profile_id)`
keeps Merge semantics; `set_profile_active` switches a binding without detaching it, while
`uninstall(profile_id)` disables and detaches it. Detaching in the GUI is the same
operation: the binding is disabled and reconciled first, so it leaves exactly what disabling leaves,
and only then is the binding dropped.

A profile references library objects by id, so deleting one of those objects takes it out of every
profile that named it, exactly as it is taken out of every group — otherwise reconciliation keeps
chasing an id the library can no longer resolve. Deleting a profile likewise detaches it from every
project: the binding is machine state that only that profile explains.

There is no separately stored base bundle. The base is every installed object without a `p=<profile>`
origin tag, including an object tagged `g=<group>`; it wins when an active profile also names it.
Managed rule headers retain the origin in the manifest suffix (`:g=<id>` or `:p=<id>`; a legacy bare
id remains a group), and the MCP, skill and subagent sidecars retain the same origin field.
Only a write that names an origin sets it. A write without one — an update, a restore, a per-row or
bulk install over an existing copy — keeps the origin the copy already has, so updating a member
never re-owns it as base. An update also keeps a rule's position in its managed run.

`ReconcileProfiles(project)` makes the installed set equal to the base plus all active profiles,
delegating every install, removal and retag to the existing ownership-aware services. An object shared
by active profiles has one physical copy; binding order selects its `p=` tag, so disabling one profile
retags it to the remaining owner. A protected hand edit is never overwritten or removed: it remains
in place and is reported by the reconciliation result.

A copy whose library object was deleted is taken out too. It has no library body left, so neither
`InstallPolicy` nor the ordinary remove can decide about it; the services answer `removeOrphan` by id
instead, measuring the copy against what they recorded when they wrote it — the run manifest for a
rule, the sidecar fingerprint or config for a skill, subagent or MCP entry. The guarantee does not
change, only its yardstick: a copy that still matches the record goes, anything else is reported as a
protected local edit. This applies to profile-owned copies only; a base copy of a deleted object stays
until the user removes it.

A profile switch writes the target files immediately. It does not claim that a running assistant has
re-read them: Home and the project header each list the available, enabled adapters whose
`profileSwitchRequiresRestart` flag is set, and the user restarts those sessions. The flag belongs to
the adapter rather than to the UI, so the notice follows the agent's actual reload behaviour.

`config.json.places` holds the place list's navigation state: `pinned` and `recent` are place ids
(`agent:<id>` / `project:<absolute path>`), and `sets` are named groups of project keys. It is
machine-local like the rest of the config — a set is a view of this machine's checkouts, so it never
travels with an export the way a group of library objects does. `recent` keeps the last five visited
places, most recent first. A project belongs to **at most one set**: the list is a tree, and the same
project under two headers would make "where does this install go" ambiguous for the user and for
Coverage, which aggregates by set. Removing a project drops its pin, its recent entry and its set
membership in the same write, so the config cannot accumulate places that no longer exist.

`config.json.keptDrifts` records the third answer to a drift — leave the edit alone — as place key to
object id to a SHA-256 of the kept copy. It is stored rather than held on screen because a decision
the app forgets when the user looks elsewhere is not a decision; it is machine-local because it is
about one checkout's files, not about library content. Keying by digest is what keeps the answer
honest: it silences the edit it was given about and nothing else, so a later edit of the same object
is reported as the new drift it is. Every other resolution — restore, save as version, remove —
clears the entry, because the drift it answered is over.

`config.json.ignoredEntries` maps a place key to foreign physical-entry keys the user chose not to
see. It is local to that checkout and does not change the directory; discovery still classifies the
entry as ignored so a later directory with the same key stays hidden until the user restores it.

The additive bundle of library objects is the **group**; project modes use profiles. It holds rules and MCP servers in
`blockIds` (both are blocks) and skills in `skillIds`, carries a version, and lives in the library,
so it travels with an export and its Git history and is offered from every list a library object is
offered from. Setting up a project is therefore one install, not a dozen. Saving a place captures
what is installed there as a group; a group is never captured into itself, so the saved membership
stays a flat list of what an agent actually reads. An existing group **name** updates that group
rather than doubling it, and `LibraryRepository.saveGroup` raises the version only when membership
changed. A machine-local second bundle was tried and removed: two entities answering "a named set of
library objects" left the user deciding which one to reach for, and only one of them was portable,
versioned, and visible in the library.

The optional external Git sync is two-way rather than a backup. Local commits remain authoritative
until sync begins. Every sync fetches first, then fast-forwards or merges, and only then pushes the
resulting `HEAD`; it never force-pushes or pushes without a preceding fetch. A non-fast-forward
rejection or Git's temporary remote ref lock causes one fresh fetch, merge and push retry. Fast-forwards and
merges of disjoint objects are silent. A concurrent block, ordinary group or profile preserves both
Git parents and writes the local definition as the next numeric revision above them. This is
*last-sync-wins*: the device that syncs last supplies the current version, while both versions stay
in history. Delete-versus-modify restores the surviving edit. Resolution is per object rather than
per file throughout: a skill is a directory, and a file-level merge would keep only the files each
side happened to touch, leaving a directory without `meta.yaml` that is no longer a skill — so the
whole winning tree is restored, bytes and executable bits included. Conflict reporting is by library
object, not Git file, and the Home sync card keeps every resolved object visible with links to its
history and Compare; otherwise the policy would silently replace work. Equivalent normalized
content is not a conflict; the reserved `all` group is rebuilt from merged objects instead of
text-merged, in the order the library itself writes that group — two orderings would make each
device renumber the other's `all` forever and every target reinstall an unchanged group. A merge
that cannot be resolved is discarded rather than abandoned half-done: Git refuses both saves and
syncs while a merge is pending, so leaving one behind would make the library unwritable until
someone ran Git by hand. An empty local library clones an existing configured branch instead of creating an
unrelated first commit. When two non-empty histories have no common commit, Settings requires the
user to merge the remote as an import under that same policy or first export the local library as
ZIP and replace it atomically with the remote branch. A network or authentication failure leaves the
local save and commit intact. The Library save action runs the local commit off the UI thread and
shows a locked progress state until it lands. Manual synchronization uses the application coroutine
scope so closing its settings form or navigating away does not cancel the operation or its result.
Automatic sync runs on `LibraryGit`'s own daemon
thread; fetch and push stay outside the library write lock, so a save is finished once the local
commit lands and a slow or unreachable remote can hold neither the caller nor the next save. A UI
wait for the latest status is bounded; reaching that bound leaves the background sync running and
does not turn a completed local save into an application failure. Only
resolving incoming work into the index and the working tree takes that lock, and only when there is
something to merge — otherwise a save landing mid-merge would commit the half-resolved index under
its own message. Whole syncs are serialized against each other as well, because Home and Settings
start one from their own threads while the worker may already be running one. Sync is requested
at app launch, after every local commit, and after a window-focus return no more than once in five
minutes; every push still first fetches and merges. At most one sync is queued at a time, because it
carries every commit made before it starts. `lastRemoteSync` retains the observed ahead/behind commit
counts, whether a merge happened, the resolved library objects (including restored deletions), and a
failure message for the next UI refresh. Remote URL, branch and the automatic-sync toggle live in
`config.json`; HTTPS secrets remain in the user's standard Git credential helper, with `.netrc` as a
fallback.
The credential helper may open a system sign-in dialog; each request allows two minutes for the
user to finish it. Successful remote operations approve the helper's returned credential description;
HTTP authentication retries reject it before asking again. Secrets are passed over UTF-8 pipes and
are never written to Ruleblend configuration or logs.
On Windows, Git lookup also covers standard Git for Windows installations when desktop PATH lacks
`git.exe`. Credential requests use the same Unicode-safe process boundary as assistant launches.

`BackupService` keeps one slot per absolute path (`<sha1>.txt`), written and restored through
`AtomicWrite` — a restore is an exact rollback, including installs made since.

## Git repository imports

The Library accepts an HTTPS or local Git repository and shallow-clones its current default branch
to a temporary directory. Discovery prefers `.codex-plugin/plugin.json`; otherwise it reads
`.claude-plugin/plugin.json`, with `skills/` as the conventional fallback. A declared Codex `skills`
path is resolved inside the checkout only. Every direct child containing `SKILL.md` is offered as a
separate skill; a repository may also declare one skill at the skills root.

The same temporary checkout discovers subagents in plugin-declared `agents` paths, native assistant
agent directories and the `agents/`, `subagents/` and `categories/` collections. Markdown requires
agent frontmatter; documentation is excluded. A declared or native path identifies its assistant;
generic Markdown keeps both Claude Code and Kimi Code interpretations until an explicit choice.
Codex accepts string-valued TOML, including multiline instructions and `[instructions].text`.
Unsupported values produce a per-file error with the original text instead of a partial definition.
Discovery does not write library objects. Before checkout, Git objects are bounded to 20,000 files,
16 path components, 10 MiB per file and 100 MiB total; links and submodules are rejected. Manifests
and individual subagent definitions are limited to 1 MiB. No repository code is executed.

The plugin/package version is the user-facing skill version. When no manifest declares one, the
first 12 characters of the commit SHA are used. The full SHA is always stored, so two equal display
versions from different revisions are still distinguishable. The source list is derived from the
`repository`, `revision` and repository-relative `path` stored with every imported skill or subagent; there is no
second source registry to drift away from that provenance.

Subagent provenance additionally identifies the source assistant. That identity selects exactly one
native variant; its fields never become defaults for other assistants. The library's numeric
definition version is independent of Git SHA: an unrelated upstream commit advances provenance
without announcing a definition change. Reimport/update matches repository, path and assistant to
preserve local ids and memberships. Ordinary writes cannot edit an imported definition or strip its
source; an editable fork moves the source to `forkedFrom` and starts at v1. Both fields travel in
block frontmatter through ZIP exchange; older definitions without them remain local and editable.

Source checks have two levels. One `ls-remote` request reads HEAD for each unique repository. When
HEAD still equals every revision imported from that repository, the check stops without a clone and
the definitions are current. Otherwise one bounded shallow clone serves every imported path in that repository;
the fingerprint of each complete upstream tree is compared with the library tree, distinguishing an
actual update from a revision that changed elsewhere in the repository. Subagents compare parsed
portable instructions and native fields, excluding local ids and provenance. A missing path and an
unavailable repository remain separate results; malformed subagent files are unavailable individually.

`~/.ruleblend/sources-state.json` keeps the latest check time and remote HEAD per repository plus the
upstream-tree fingerprint keyed by skill path (subagent fingerprints use assistant plus path), and the paths the check reached the repository for and
did not find, so a deleted path is replayed as such instead of discarding the whole cached result. It is a disposable UI cache, not provenance: a
missing or unreadable sidecar starts empty, and the current imported definitions always decide which
sources exist. Checks are manual by default; the optional on-launch mode runs the same check once.

Updating selected skills and subagents resolves every id and upstream path before writing, groups
the selection by repository and fetches each repository once. Every matching complete tree replaces
the imported object atomically while its stable local id is preserved, so groups and installed copies
keep referring to it. Files deleted upstream disappear locally as part of that replacement.

Repository content is untrusted: credentials in URLs, non-HTTPS remote schemes, paths escaping the
checkout, symlinks, oversized files and oversized skill trees are rejected. Stored provenance is
untrusted the same way — a library archive carries whatever URL it was exported with — so every
remote is validated again before a check or an update contacts it. A skill directory may be the
repository root, and the checkout's own `.git` is never part of its tree: a fresh clone rewrites its
index and reflog, so capturing it would install clone bookkeeping and report an endless update. Imported trees are
read-only in the editor. *Create changed copy* copies every sibling file, retains the source revision
as portable `forkedFrom { repository, path, revision }` metadata through library export and import,
and uses a collision-proof `-changed` id/name, so later upstream updates cannot overwrite local edits.
When the imported skill with the same repository and path moves to another revision, the fork reports
that upstream has advanced and opens the existing Compare tool with that imported skill selected; no
upstream tree or automatic merge is stored for the fork.

## Skill installation

Library skills install as complete directory trees for agents with a known skill location. Claude
Code uses `~/.claude/skills/<id>` globally and `<project>/.claude/skills/<id>` per project; Codex
uses only its native `.codex/skills` root. Kimi Code and ZCode each write their native root
(`.kimi-code/skills`, `.zcode/skills`) and the shared `.agents/skills` root at both scopes; Pi reads
the shared root only. Ruleblend reserves `.agents/skills` for a separate shared-assistant integration
rather than making Codex duplicate every library skill there. A project target writes every tree of
each enabled agent and skips agents without a known skill location.

`skill-state.json` records the fingerprint last written per target, agent and skill. An exact library
tree is `synced`; an unchanged installed tree after a library edit is `update-available`; an unknown
or hand-edited tree is `modified`. Unknown or edited trees require explicit overwrite, and removal
never deletes one.
Only a record makes a tree managed: without one it is foreign even when it equals the library tree,
so a lost state file turns copies into foreign entries, never into silently owned ones.
Agents may declare one physical skill root: Pi's default root and the shared roots of Kimi Code and
ZCode are all `.agents/skills`. Ownership follows the declared paths, not agent ids: a record holds a
tree when its agent's root with that directory id, in the same target scope, resolves to the same
normalized path. Removing one agent's installation drops its ownership record and native copy; the
shared tree stays until the last such owner removes it, even when that owner is not among the
current target's agents.

Executable intent belongs to the portable snapshot: `meta.yaml` is authoritative in the library,
and `skill-state.json` stores the executable paths last installed, including for orphan recovery.
Tree fingerprints still cover paths, exact bytes and executable intent. Installed POSIX trees use
their real mode bits, so chmod remains a protected edit; filesystems without POSIX attributes use
the installation record instead of Windows ACL execution access. A legacy record without modes
can match the current library; an unverifiable older tree remains protected. Foreign local captures
without POSIX modes default to non-executable rather than guessing from ACLs or file extensions.
Git skill imports and source checks read modes from the checkout index, which also exposes symlinks
materialized as ordinary files on Windows. Fresh skill clones disable newline conversion before
checkout; library Git follows the [line-ending policy](#line-endings). ZIP and Git transport carry
the same metadata; installing on POSIX restores it.

A skill directory never disappears while it is updated (`DirectoryReplace`). The complete new tree is
staged in a sibling directory, the existing directory is copied aside, and then updated in place:
each file lands by an atomic rename, `SKILL.md` last, and files the new tree drops go at the end. An
assistant scanning its skills mid-update always finds the skill, and a directory another tool creates
meanwhile cannot fail the write. A reader can briefly see old and new files together; `SKILL.md`
landing last means whoever sees the new text finds every new file beside it. A failure at any step,
or in a later agent of a multi-agent install, restores the copied tree the same way. A new directory
lands by one rename; a target that is itself a symlink or a file is moved aside whole, and no link is
followed. The library's own `skills/<id>` tree is written the same way. Rejected: exchanging whole
directories (`renamex_np(RENAME_SWAP)`, `renameat2(RENAME_EXCHANGE)`) is reachable from the JDK 17
toolchain only through a new native dependency (JNA) or a JDK 22+ toolchain for FFM, and filesystems
without the call would still need the two-rename fallback with its gap.

## Integration mechanics

**The target file is the only source of truth for managed rule regions.**
State travels with the file: a clone, a fresh
machine, a teammate all read what Ruleblend reads. There is no install database and no manifest
committed next to the file — a second source of truth would diverge from the first.
`~/.ruleblend/targets.index` is a cache; deleting it costs nothing.

Ownership is a per-target-file property, detected by sniffing the file itself:

| Mode | Footprint | Meaning |
|---|---|---|
| `none` | — | not managed |
| `partial` | notice + header + terminator | one managed run beside hand-written text |
| `owned` | header only | the whole file is rendered from the library |
| `legacy` | `N+1` marker lines | old per-block `kb` format, still read, never written again |

An `rb1` run collapses the whole manifest into its opening line:

```markdown
<!-- Ruleblend-managed. Do not edit below; changes are overwritten. -->
<!-- rb1 8f2a1c04 git-commit-no-push@2:412 swift-style@1:180:g=ios-projects -->
...rendered blocks, back to back...
<!-- rb:end -->
```

- `rb1` — format marker and version.
- `8f2a1c04` — hash of the rendered run body; a mismatch means the run was hand-edited.
- `<id>@<version>:<utf8-byte-length>[:<origin>]` per block, in document order.
  The origin is `g=<group-id>` or `p=<profile-id>`; a bare legacy id still means a group.
  The byte length makes
  concatenated bodies self-delimiting, so no second manifest is needed. `owned` folds the notice
  into the same line (`<!-- rb1 owned … -->`) as the first line of the file.

Two Kitbash formats remain supported for reading: the per-block format writes one
`<!-- kb <id> v<version> [g:<group>] [<hash>] -->` line per block plus one `<!-- kb:end -->` per run;
the later wrapped format uses `<!-- kb1 … -->` with the same `<!-- kb:end -->` terminator. Any
content-changing write converts either format to `rb1` / `rb:end`. Old owned notices are accepted
while parsing and replaced with the Ruleblend notice during that write.

- **Access is funnelled through `TargetEncoding`** (`LegacyMarkers` / `WrappedRun` / `FullFile`);
  `IntegrationService` picks an implementation per file and is otherwise encoding-agnostic.
- **Status per block**: `synced` (hash and version match the library), `update available` (library
  is newer), `modified` (the target differs at the same version, or its hash mismatches). A target
  with several agent files reports the worst status of its files, so a hand edit is never hidden by
  the others.
- **Never overwrite silently.** A `modified` rule can be restored from the library or saved back as
  the library's next version, and so can a `modified` MCP entry: its config file holds a whole server
  object, which is exactly what a library block of that type is, so the library can adopt it as its
  own next version. A skill has no such single body and resolves by restoring or keeping only.
  Restoring rebuilds the whole managed run from current library bodies.
  Saving first isolates the selected body using the manifest and unchanged neighbouring library
  bodies; if another rule in the same run also changed, Ruleblend refuses the ambiguous operation
  instead of blessing or discarding either edit. The target is checked again after the library
  commit, then atomically rewritten with the new version marker.
- **Edit from Projects.** A managed rule row opens the same structured Library editor. A successful
  save writes the new version back into the place that opened it and carries forward the region's
  exact base, group or profile origin; if that copy drifted while the editor was open, the normal
  no-silent-overwrite rule refuses the target write while keeping the new library version.
- **Reorder is a sequence-only write.** `TargetEncoding.reorder` re-renders the run with its blocks
  in a requested order, from the bodies the file already holds: no body is rewritten and nothing
  outside the run moves. Ids absent from the requested order keep their relative sequence after the
  ordered ones, and a run that no longer matches its manifest is refused rather than reordered —
  drifted block boundaries cannot be trusted to move. Legacy marker runs reorder within their own
  run: a run is contiguous, so crossing the hand-written text between two runs would be a different
  edit. An `rb1` file holds a single run, so there every block can reach either end. An order that changes nothing writes nothing, so a no-op reorder cannot drag the
  legacy-to-`rb1` conversion along with it.
- **Block headings.** A block may carry a markdown heading of its own (`heading` plus `headingLevel`
  in its frontmatter). `regionFor` renders it as the region's first line, then a blank line, then the
  body — the heading is part of the region's content, so the run hash and the manifest's byte lengths
  cover it like any other text, and no second mechanism is needed to place it. The heading is stored
  apart from the body so that renaming it stays one edit and the depth can differ per block; an empty
  heading means the block deliberately blends into whatever surrounds it, which is what every block
  written before this existed does. The reverse direction matters as much: taking an edited region
  back into the library splits the leading heading line off again, so accepting a local edit cannot
  fold the rendered heading into the body and print it twice on the next install.
- **Group install** expands into individual regions tagged with `g=`. Members update independently;
  removing the group removes them unless also installed individually.
- **Content outside the managed region is never modified**, except by a user-invoked *Save to
  library* with replace ticked.
  Manifest lengths and hashes use LF-normalized bodies; edits translate those offsets back to the
  original text. A partial run owns its marker lines and the terminator's single line ending, not
  subsequent blank lines. Removal and legacy migration preserve outside bytes, including mixed
  line endings and trailing whitespace; disown also keeps the original body bytes. Newly rendered
  managed content uses the configured LF/CRLF setting. Preview text is normalized for display and is never a source for these writes.
- **Escape hatch:** *disown* drops the Ruleblend header and terminator and leaves the content exactly
  as it is on disk. A wrapped file can also be reverted to legacy markers through the core API.
- Writes to user files are atomic (temp + rename).

## Adopting existing rules

A project usually has hand-written rules before Ruleblend touches it. Files with content outside
managed regions are listed under **Found in project** (**Found in agent** for a global target) with
each row's path relative to the root and its unmanaged line count, in the Place palette — adopting a
project is the first move there, and it is the only surface that offers it.

- Scanned set = `Target.ownedFiles()` plus, under a **Referenced via @import** sub-header, files
  reached transitively through Claude Code `@path` directives. Imports resolve at the target root
  (that is where Claude Code resolves them); only hand-written text is scanned, and broken paths are
  skipped — the adopt dialog flags them with a `⚠` instead.
- **Save to library** opens a dialog with name, description and a checkbox *"Replace the original
  text with a managed region"* (off by default). Off = copy, file untouched. On = the text is cut
  and rewritten back as a managed region at the position of the first cut fragment, in one atomic
  write; the result is `synced` and functionally unchanged for the agent. The library commit lands
  first, so the text exists in git before it leaves the target file.
  A new block adopted from a project is scoped to that project; adoption from an agent-global file
  creates a global rule. Overwriting a matched block preserves its existing scope.
- **Multi-section adopt:** when the unmanaged text splits into ≥ 2 offerable markdown sections, the
  dialog shows a per-section checklist. The split is at the shallowest heading level present
  (descending into a lone wrapper heading), so a parent's text never mixes with content from
  elsewhere. Extraction is lossless — heading-only and comment-only sections are kept for write-back
  but never offered. Each section is matched against the library by `slugify` of its effective name
  plus Jaccard similarity: **new**, **duplicate** or **similar**, resolved per section as
  Skip / Overwrite / Create new. The section's heading is kept as the block's own heading (see
  *Block headings*), so write-back reproduces the source file heading for heading, section by section.
  With replacement, the file keeps its single run and every line its place: the replaced sections
  and the run already in the file must form one unbroken stretch, and the run takes that stretch's
  place. A kept section inside it would have to move to one side of the run, so the dialog refuses
  such a selection before anything is saved and names the section in the way; the write refuses it
  too, and refuses a plan whose text no longer matches the file.
- **Split** (`Splitter` in `core`) cuts a rule into several along arbitrary boundaries, from the
  Library editor or from an adopt-dialog section row. In Library mode the source block is left
  untouched and group memberships are mirrored onto the parts.
- An in-app plain **Edit file** action opens a found file for a manual fix. A pure `@AGENTS.md`
  pointer file is shown as already configured, not offered for adoption. Whole-file snapshots
  (`backups/`) and ownership changes are offered from the text file's header on the place screen
  (see *UI*).

## Agent adapters

An adapter declares a detection path, a global file, a project filename and an optional pointer file:

| Agent | Detect | Global | Project | Pointer |
|---|---|---|---|---|
| Claude Code | `~/.claude` | `~/.claude/CLAUDE.md` | `AGENTS.md` | `CLAUDE.md` |
| Codex | `~/.codex` | `~/.codex/AGENTS.md` | `AGENTS.md` | — |
| Pi | `~/.pi/agent` | `~/.pi/agent/AGENTS.md` | `AGENTS.md` | — |
| Kimi Code | `~/.kimi-code` | `~/.kimi-code/AGENTS.md` | `AGENTS.md` | — |
| ZCode | `~/.zcode` | `~/.zcode/AGENTS.md` | `AGENTS.md` | — |

Global roots are resolved once per integration from the native OS user home and its process
environment; explicit test homes ignore that environment. Detection, rules, MCP, subagents and the
bundled skill share the same resolution. Overrides must be absolute native paths (or `~/...`);
WSL homes are a separate environment and are rejected instead of being mapped onto a Windows drive.
A rejected override is ignored for its assistant only, logged to stderr and shown in Settings
diagnostics: one stray variable must not stop the app or the MCP server from starting.
`HOME`, `APPDATA` and `XDG_CONFIG_HOME` do not relocate these assistants' shared `.agents` roots.

| Override | Global root | MCP configuration |
|---|---|---|
| [`CLAUDE_CONFIG_DIR`](https://code.claude.com/docs/en/env-vars) | replaces `~/.claude` | `<override>/.claude.json`; without override, `~/.claude.json` |
| [`CODEX_HOME`](https://learn.chatgpt.com/docs/config-file/config-advanced) | replaces `~/.codex` | `<root>/config.toml` |
| [`PI_CODING_AGENT_DIR`](https://pi.dev/docs/latest/environment-variables) | replaces `~/.pi/agent` | `mcp.json` in the overridden directory |
| [`KIMI_CODE_HOME`](https://moonshotai.github.io/kimi-code/en/configuration/config-files.html) | replaces `~/.kimi-code` | `<root>/mcp.json` |

Project roots stay relative to the chosen native checkout. ZCode uses the defaults in the tables;
its [native skills](https://zcode.z.ai/en/docs/skill) and shared roots do not move with another agent's override.

### Installed distribution surfaces (snapshot: 2026-09-04)

This dated inventory records the distributions examined at the time, not a claim about the latest
assistant releases. Current Ruleblend write destinations and support limits are specified in the
adapter, skill, subagent and MCP sections below; no adapter changes follow from this snapshot alone.
`<project>` is the repository root unless a row says
`<cwd>` or `<workspace>`.

| Agent | Configuration / MCP | Skills | Subagents |
|---|---|---|---|
| Codex 0.153.2 | `~/.codex/config.toml`; `<project>/.codex/config.toml` | Native: `~/.codex/skills/`, `<project>/.codex/skills/`. Shared: `~/.agents/skills/`, `<project>/.agents/skills/`. | `~/.codex/agents/*.toml` only; the format is TOML, not Markdown. |
| Pi | [Built-in MCP in 1.0+](https://pi.dev/docs/latest/mcp): `~/.pi/agent/mcp.json` and `<project>/.pi/mcp.json`; project trust is required. | Shared: `~/.agents/skills/`, `<project>/.agents/skills/`. | No subagents. |
| Kimi Code 0.40.1 | `<KIMI_CODE_HOME>/mcp.json` (fall back to `~/.kimi-code/mcp.json`); `<project>/.mcp.json`; `<cwd>/.kimi-code/mcp.json`. Later files override earlier ones. | `<KIMI_CODE_HOME>/skills/` and `<project>/.kimi-code/skills/`; `.agents/skills/` is also read for shared skills. | `<KIMI_CODE_HOME>/agents/*.md` and `<project>/.kimi-code/agents/*.md`; Markdown frontmatter carries the definition. |
| ZCode 3.10.1 | Compatibility MCP: `~/.agents/mcp.json`, `<project>/.mcp.json`. Native settings: `~/.zcode/cli/config.json`, `<workspace>/.zcode/config.json`. | Native: `~/.zcode/skills/`, `<workspace>/.zcode/skills/`. Shared: `~/.agents/skills/`, `<workspace>/.agents/skills/`. | No stable user or workspace file format. `~/.zcode/cli/agents/` holds runtime sessions and is never a definition destination; plugins may contribute agents. |

A target is an agent global **or** a project folder; a project writes to each enabled agent's file
(per-project toggles in `config.json`, default = all detected; switched from the project's file
column, and a switch leaves existing files untouched). A registered project whose folder no longer
exists stays in the list but is never written to: every install and removal into it fails instead of
recreating the path around one instruction file.

An adapter may declare more than one skill root at either scope. `SkillInstallService` installs and
checks every distinct tree atomically, with a separate `directoryId` in `skill-state.json`; a shared
tree claimed by two enabled agents is written once. Codex receives only its native `.codex/skills`
copy; Kimi Code and ZCode also receive their shared `.agents/skills` copy, while Pi receives the
shared copy only, globally and per project or workspace.

### Subagent formats

`AgentAdapter` declares a subagent format plus its global and project directories independently of
rules and skills. The definition file is always `<id>.<extension>` and is rendered before any
ownership or state check, so the later installer fingerprints exactly the bytes it writes. Claude
Code uses `~/.claude/agents/<id>.md` and `<project>/.claude/agents/<id>.md`; Kimi Code the analogous
`~/.kimi-code/agents/` and `<project>/.kimi-code/agents/` roots; Codex has only
`~/.codex/agents/<id>.toml`, where the body is escaped into `developer_instructions`.

`name`, `description` and the instruction body are portable and live on the `Block` itself.
Everything else is not: a model name, a tool list or a colour valid in one assistant is meaningless
in the next. Those are `Block.variants`, one entry per agent id, and a `SubagentFormat` declares
which of them it understands as `SubagentFieldSpec` — a canonical `id`, the `nativeKey` its own file
calls it (`model` for Claude Code and Codex, `modelPreference` for Kimi Code), an edit kind and
non-binding value suggestions. Canonical ids keep a preference recognisable across assistants;
native keys keep each file in the syntax its agent reads.

Three rules follow from having one object with per-assistant values:

- **Nothing is invented.** An assistant with no variant is installed with the portable definition
  alone — no model is carried over from another assistant, because it would name a model that one
  does not have.
- **Nothing is dropped.** A parse keeps a field the format does not declare under its native key, so
  adopting a foreign definition and reinstalling it does not quietly delete what Ruleblend has no
  spec for. A value that is not flat (a nested map) is left to the file instead of being flattened;
  a list of scalars becomes the comma-separated form these agents also accept.
- **A variant never leaks sideways.** Rendering reads only the entry for the agent being written.

The installed ZCode distribution has no stable standalone subagent-definition format:
`~/.zcode/cli/agents/` is a live session store, while plugins carry their own agents. Its adapter
therefore reports `UNSUPPORTED` rather than writing Markdown into a runtime directory, and the
derived matrix carries the same `NOT_SUPPORTED` limit as Pi, which has no subagent feature at all.
The reason is documented here, not modelled: a limit value no adapter can produce would only be
dead code.

### Capability matrix

The adapter is the sole source of the capability matrix: it derives a destination at global and
project scope instead of a Settings row guessing from an agent name. Settings renders `✓`, `✗`, or
`partial` with the missing scope; the same applicability reaches the cross-place scan and Coverage,
where an object without a destination is out of scope rather than a missing installation. The
persisted `TargetIndex` continues to index managed instruction regions only; standalone subagents
are indexed by their native destination and `subagent-state.json`, because they are not regions in
an instruction file.

| Agent | Rules | MCP | Skills | Subagents |
|---|---|---|---|---|
| Claude Code | ✓ | ✓ | ✓ | ✓ |
| Codex | ✓ | ✓ | ✓ | partial — global only |
| Pi | ✓ | ✓ | ✓ | ✗ — no subagent feature |
| Kimi Code | ✓ | ✓ | ✓ — native and shared roots | ✓ |
| ZCode | ✓ | ✓ | ✓ | ✗ — no stable definition format |

### Subagent ownership and drift

Standalone definitions cannot contain Ruleblend markers. `subagent-state.json` therefore records
one SHA-256 fingerprint per target key, agent id and subagent id. The digest is computed from the
exact UTF-8 bytes returned by that agent's `SubagentFormat.render`, not from a portable block or a
reparsed target file. This keeps the ownership record aligned with Claude Code and Kimi Code
Markdown as well as Codex TOML.

An unknown file whose bytes differ from the current render is a conflict and is never overwritten
without an explicit overwrite decision. Adopt records the current bytes of an existing regular file
without changing it. At that one moment the file is also parsed and compared with the parsed
render: when fields and body are equal and only the layout differs (field order, quoting, the blank
line under the header), the record also stores `equivalentRender`, the render fingerprint it stands
for. While both fingerprints hold the file is `SYNCED`; once the library render changes it is an
available update, as is an adopted file that differed in meaning from the start. Any later byte
change is `MODIFIED`. Status checks themselves stay byte comparisons. A file with no record is
foreign even when it matches the current render: ownership is the record, never a coincidence of
bytes. ZCode and Pi have no destination or record in this flow.

### Subagent Library UI

The Library catalog indexes a subagent separately from rules, MCP servers, skills and groups, while
retaining its portable `Block` record and group membership in `blockIds`. Its editor renders one
section per assistant that supports subagents, generated from that format's field specs plus any
field a foreign definition brought along; every field stays free-form text, because model names and
tool sets change independently of Ruleblend releases. Saving a foreign definition into the Library
stores its fields as the variant of the assistant it came from, and nothing else.

A library written before variants existed carries one shared `model`. `BlockFile` migrates it on
read onto the three assistants that could have consumed it back then and stops writing the key; the
migration list is frozen, so a newer assistant never inherits a value chosen before it existed.
Library bulk actions, Place actions, usage, history and Coverage use `InstallItem.Subagent` and the
same `SubagentInstallService` instance as status scanning. The service is the only file writer: UI
code never renders, fingerprints, adopts or deletes assistant-owned definition files itself. An
unsupported agent contributes no destination, so ZCode and Pi remain visible as unsupported rather
than receiving a guessed file format.

A parsed foreign definition can become a Library `SUBAGENT` and then be adopted without rewriting
its file. Its sidecar records the accepted current fingerprint and, when a taken Library id differs
from the foreign filename, that physical filename; discovery resolves the sidecar by that address.

### Place entry discovery

The Skills, Subagents and MCP tabs describe physical entries at a target, not just Library objects.
Discovery reads only the destinations their installers support: direct child skill directories with a
`SKILL.md`, standalone subagent definitions in the adapter's exact directories and format, and MCP
entries in the installer-owned `mcpServers` JSON object or Codex `[mcp_servers.*]` tables, where a
dotted sub-table is part of the server it is written under rather than a server of its own. It neither
walks a project tree nor reads other configuration sections; in particular, Claude's per-project
sections in its global config are not project entries. The services return the entry's text and
physical address to the UI, so rendering does not read user files.

Each address has one origin. A sidecar record that still has its Library object is `MANAGED`; a record
whose object was deleted is `ORPHAN`; an entry without a record is `FOREIGN`; and a foreign address
hidden in `config.json.ignoredEntries` is `IGNORED`. The key is the physical address rather than an
agent root name or Library id, because different agents may use roots with the same name. Discovery
and classification are read-only. Foreign and ignored entries do not change a Place status dot: that
status reports only what Ruleblend manages.

Supported foreign entries may be saved to the Library and adopted by recording their current state,
without rewriting the source. Saving and recording are one step: a Library object saved from an entry
but not yet recording it would sit unowned on the entry's own address. A foreign entry named like an
existing Library object is taken under that object instead, and is not offered for saving: a second
copy under another name would only split one definition in two. When its content differs it reads as
an available update or a modification. Releasing a managed skill, subagent or MCP server drops only
its records: the entry stays on disk and is foreign from then on. An address a foreign entry holds is
listed once, as that entry, even when a Library object of the same name exists. Orphans can restore their recorded Library object or remove only their
sidecar record. The local hide switch changes neither file nor sidecar. The normal rule remains that
Ruleblend does not alter user content outside its managed regions. There are two explicit exceptions:
the user may choose *Save to library* with replace enabled, or confirm deletion of a named foreign
skill directory or MCP entry; MCP deletion preserves adjacent entries in the same config file.

Global rules are available to every target. A project-scoped rule is available only when that exact
normalized project path is selected. Moving a rule's scope changes availability but does not
silently remove an already-installed region from another target; that out-of-scope installed rule
remains visible there until the user removes it.

**One managed file per project.** Every agent reads `AGENTS.md`, so a project holds exactly one file
with rules in it. Claude Code reads `CLAUDE.md` and gets a *pointer* instead: the plain line
`@AGENTS.md`, with no markers around it. The line is what a person would have written, and a marker
pair around a one-line file is three times the text it guards — so Ruleblend writes the import and
does not own it. It is written when `AGENTS.md` holds a managed region and it is not there already,
and removed in one case only: the pointer is nothing but that line and there is nothing left to point
at (no region, no hand-written text). A pointer beside other text, or one whose target still holds
text of its own, is left alone — outside a managed region Ruleblend cannot tell its own line from the
user's. `Target.files()` = files rules go into, `redirects()` = pointer → target pairs,
`ownedFiles()` = both. `IntegrationService.syncRedirects` is idempotent and also migrates projects
written by older versions: rules that still sit in `CLAUDE.md` move into `AGENTS.md`, and an import
region written under the reserved id `ruleblend-import` — or under the app's former name
`kitbash-import` — is converted to the plain line. Both ids stay reserved: read as a rule such a
region would be migrated into the file it points at, which is how a target ends up importing itself.

**Detection** is by config directory, never by the instruction file — an agent installed but never
configured has no `AGENTS.md` yet, which is the case Ruleblend exists to fix. It is rescanned on every
Integration load, so agents installed later just appear. Agents sharing a project filename share one
file: the unit of writing is the file, not the agent.

## UI

Compose Desktop uses a shared collapsible navigation sidebar and 44 dp top bar over typed application
routes. The sidebar animates between 204 dp and 68 dp without swapping layouts: icons keep one column,
labels fade out before the width can clip them, and the collapsed state keeps every destination as a
square icon target with its label on hover. The brand tile doubles as the expand control once there
is no room for the toggle. The folded state is `config.json.sidebarExpanded`. Settings stays at the bottom,
separated from Home, Place (labelled Targets / «Проекты» in the UI), Library and Coverage. Resolve is
a nested flow entered from Home or Coverage. The screens use these routes:

- **Home** — the attention feed is an aggregate over one cross-place scan, not a stream of alerts:
  one card per class of problem (hand-edited blocks, available updates, hand-written text not yet
  adopted, files still in legacy markup, an agent installed on the machine but unmanaged), each
  carrying its total, the places it occurs in and the files the count came from. Counting happens
  once per scan and never during recomposition, because the scan is a pass over every file of every
  place; a rescan is therefore an explicit action, and a failed scan keeps the previous answer rather
  than blanking the fleet because one place became unreadable. An untouched place produces no card —
  a project nobody set up yet is not a problem. "Nothing to do" is a scanned fleet with no cards,
  which is a different state from "not scanned yet".
  The screen draws that snapshot and nothing else: no file is read and no counter recomputed while
  rendering. Every card carries one action and, folded open, the places it covers, each a link into
  Place; the conflicts card leads to Resolve.
  Home owns exactly one write — hiding an unmanaged agent, which drops it from the fleet instead of
  dismissing a card, so the card cannot return. Three empty states stay distinct because they call for
  different moves: not scanned yet offers a scan, an empty library points at Library, and a fleet in
  sync reports its coverage. Below the feed, fleet health repeats the place list's own grouping —
  agent globals, named sets, ungrouped projects — with one chip per place showing how its installed
  objects split across the three statuses, followed by the same pinned and recent shortcuts.
- **Library** — facets (type, group, scope, usage, Git source) → category list → inspector, with
  editing in a separate full-screen focus view. The inspector answers what an object is and where it
  lives: body or group members, Git provenance of an imported skill or subagent, the places holding it with a
  per-place status and a jump into Place, and the object's version history read from the library's own
  commits. Rules edit as markdown and can move between scopes; MCP blocks stay global and use a
  structured form. Imported skills and subagents stay immutable in the editor and Compare, and expose
  update/fork actions. Their common Git import dialog requires a source-assistant choice for ambiguous
  definitions; already imported objects start unticked so rediscovery cannot silently replace them.
  Home and Settings combine both types by repository, keeping their separate id namespaces.
  Groups edit inline in the focus view and contain rules, subagents, MCP servers and skills.
  Rows tick for bulk actions: install into chosen places, join a group, export the selection.
  Bulk update and removal belong to Coverage, which shows every object × place pair they would touch;
  the Library does not offer a second entry to them.
  The rule and skill bodies can open a translation beside the text (see
  [Translation](#translation)); it never runs on its own, because a rule is written once and read
  many times, and an automatic call per keystroke would spend the machine's translator on drafts.
- **Place** — place list → what one place holds, as tabs by kind: Rules, MCP, Skills and Subagents, named the way
  the library names them. A tab is a kind of object because that is the question the screen answers —
  "what does this project have"; a file name is an address, read while tracing an edit or deciding
  where an install lands, so it sits one level down as the header of a section inside the tab. Every
  address of a kind is therefore read in one scroll (`AGENTS.md` and the pointer beside it, both MCP
  configs of a place) instead of a click each. A tab carries the worst status of everything under it
  and a count of the objects it holds, zero included — an empty kind answers before it is opened,
  which a row of empty file tabs could not. Ownership rides on the section of the file it belongs to;
  a pointer section shows the import it holds rather than a block list. A file with neither text nor
  an installed object is not drawn as an empty block: it is named in one closing line ("Nothing in:
  …"), and a kind that is empty at every address gives one answer plus the addresses it would be
  written to — the file names stay visible, at the cost of a line rather than a screenful.
  The Rules, Skills, Subagents and MCP tabs each keep an independent default folded state in
  `config.json`; the section header's outlined `+` and `−` controls open or fold all entries in the
  current tab for the current visit. Rules and MCP retain their file-level Edit action because one
  header names the file to change. Skills and subagents are separate definition files, so their Edit
  action belongs to each entry rather than to the directory header.
  The file is still the unit of writing, so it is the unit previewed:
  hand-written text is rendered as the file's own text, managed runs are highlighted and
  list their blocks with version, group and status. Preview order is the file's own: a managed run does not
  always come last, and a run whose body drifted still lists the blocks its manifest claims — and
  unfolds into the text the file now carries: a single block owns the whole run body, and among
  several the untouched library bodies anchor the edited one, the same attribution `localChange`
  trusts. Bodies stay empty only when nothing can be attributed — a block missing from the library,
  two blocks edited at once, or a split more than one block could explain. An MCP entry unfolds the
  same way, its config file's copy against the library's in one serialization, because a server
  edited outside Ruleblend has to say what changed and not only that something did. A place covering
  several agents can hold that entry differently in each of them; there the row unfolds into one copy
  per agent, and "save as version" is offered on each copy rather than on the row — the library keeps
  one body per block, so which copy becomes it is the user's answer, and saving it writes it into the
  other agents too. A translation is
  offered per file section rather than per tab — a place holds several addresses and only one is
  being read — and only on prose: a config entry and a skill directory get no second column. The text
  file's own header is also its small ownership surface: it can snapshot or restore the whole file,
  migrate legacy markers, toggle the warning notice, or disown the rendered text. Restore and disown
  are confirmed; all five actions delegate to the existing atomic core operations, so neither a config
  nor a skills directory acquires a second ownership path. A project's header lists every detected,
  visible AI assistant, including assistants disabled for that one project; switching one updates only
  `config.json.disabledAgents` and never changes files already on disk. The right column is
  the library palette:
  the hand-written files found here first, each offering the adopt dialog and the configured file
  editor, then set recommendations, then the library catalog by type with a search over it. It reads
  the same catalog Library builds and the statuses this place already
  carries, so it is a view of the library rather than a second index of it; a group has no status of
  its own and takes the worst of its installed members. Recommendations exist only for a project in a
  set — an agent global has no peers — and read the cross-place usage scan: "installed in 6 of 8"
  counts the siblings, out of the whole set. The one move that goes the other way is saving the place
  as a group: it captures what is installed here right now, group rows excluded. Its scroll position
  is per place, so entering a place always starts at the section that names it. The place list is the left column: a filter, pinned and recent
  shortcuts, then the canonical listing of agent globals, project sets and ungrouped projects. Pinned
  and recent are views over places the canonical sections already hold, so a place shows twice on
  purpose; recent leaves out what is pinned. Only an explicit selection counts as a visit, so the
  fallback pick made when nothing is selected never fills the recent list. Section folds are
  per-session, because a fold is a glance, not a preference.
- **Coverage** — one grid answering "where is what installed", aggregated from the same cross-place
  scan and the same library catalog the other surfaces read; Coverage owns no index of its own. Rows
  go from every object through the object types to the library groups, columns are the agent globals
  as one group, named project sets and the ungrouped projects — a column is a *set of places*, which is what keeps the
  304 × 30 grid from ever being drawn. A cell counts objects, not writes: `installed / members` says
  how much of the row the column holds, with a bar splitting the installed part across the three
  statuses and the worst place of the column deciding an object's status. What cannot live in a column
  is left out of the denominator instead of being counted as missing — a rule pinned to another project
  and a skill where no agent reads a skills directory — and a column where nothing of the row fits at
  all is a dash. The scan is kept in memory, so type filters, the "only installed" and "needs attention"
  cuts and folding any column group, the agent globals included, re-aggregate without touching disk.
  Rows render lazily and share one horizontal scroll with the header. Types and groups open into
  objects; "all objects" stays a summary to avoid duplicating types.
  All rows or columns can be folded together without disk reads. Assistant globals initially open
  at up to four places, independently of the row-group default; expansion remains session state.
  Summary, row filters and column controls stay separate so status is not mistaken for a toggle.
  Conflicts lead to Resolve; updates enable the attention filter rather than starting a bulk write.
  Assistant visibility lives in a menu that does not displace the matrix; the legend remains below it.
  Each contiguous column group owns one band and one fold control, so opening it does not repeat
  the parent heading on every child. Place names remain separate from group names even when a group
  holds only one place; the lower heading opens that destination, while the band folds the group.
  A column opens into its places by re-cutting that same scan: an opened object counts *places*
  rather than objects, and the dash it draws comes from the rule the aggregate above it used, so the drill-down cannot contradict the
  summary. A cell is a write only where one object meets one place — an aggregate stands for several
  of either, and guessing which one a click meant would be a silent mass edit. That write goes
  through the shared place installer, so it refuses a hand-edited copy and a rule pinned elsewhere the
  same way the Library's bulk install does, and it re-reads the fleet afterwards instead of patching
  the matrix. Coverage writes agent visibility into the same `hiddenAgents` Home writes, and it lists
  hidden agents too — a hidden agent is no longer a place, and can be restored here or in Settings. Rows
  can also be ticked, whether opened or not, for a bulk action — every row but "all objects", because
  a batch has to be a set the user named or narrowed to. A bulk action resolves to a plan first: the
  marked objects and the chosen column split into exactly the "one object × one place" writes a cell
  click performs, minus the pairs already in the asked-for state and minus the ones the scope rules
  refuse, which are counted and shown. Install and remove take a column as their target; update takes
  none, because it is an install over the copies the scan already calls outdated, and it never offers
  a hand-edited one. The plan is applied one place at a time through the same shared installer, so a
  place that refuses everything costs the batch that place and no other, and it is named in the
  report next to what was written and what was skipped.
- **Resolve** — the fleet's conflicts, sorted into the classes core can actually tell apart. It reads
  the same cross-place scan as Home and Coverage and owns no conflict index of its own: a
  `MODIFIED` block *is* the input. The classification is core's, not a second opinion about it — a
  block is hand-edited exactly when core would hand its edited body over as one rule's local change,
  and ambiguous exactly when core refuses, because another block of the same managed run changed too.
  One isolated edit proves the rest of its run still matches the library, so an attributable edit and
  an ambiguous run never appear in the same file; when nothing can be isolated, every candidate of
  that run is ambiguous, since an edit that broke the manifest offsets leaves its neighbours
  unreadable as well. Legacy-format files form the third class: not a conflict of content but of
  markup, needing no decision because the next write rewrites them. All three classes stay listed at
  zero, so "no ambiguous runs" cannot be misread as "ambiguity is not a thing here". A hand-edited row
  carries the diff of the library body against the file body, computed once during the scan, so
  opening one reads no file. Only the hand-edited class takes a strategy — restore from library, save
  as a new version, or skip — chosen once for the batch with per-row exceptions; the other two classes
  offer their explanation and the way into Place instead, because there is no answer to apply. Marked
  rows resolve into a plan before anything is written, with results reported by place.
  Applying a place reads every edit to be kept, commits those as library versions, and only then
  rewrites existing copies. Restore and save re-check neighbouring rules under the write lock;
  a newly ambiguous run is refused, and files without the selected rule stay untouched. Resolving
  one row authorizes discarding only that rule's edit, not the skipped edits elsewhere in the place.
  A place that refuses is named in the report and costs the batch that place alone: unwinding the
  places that already succeeded would need a write that puts a hand edit back, and that text no longer
  exists once its file has been rewritten. The fleet is read again once after the batch, not once per
  row. Nothing is marked when the surface opens — the batch can discard hand-written text in dozens of
  files, so "select all" is a press, not a default.
- **⌘K** — the one navigation that belongs to no surface: library objects, places and jumps between
  surfaces in a single ranked list, drawn over the whole window and reachable from any route (the
  shortcut is intercepted at the shell root, so it fires while a screen's own field has focus). The
  index is a view over read-models the surfaces already build — the catalog and the place list — and
  is assembled when the palette opens and dropped when it closes; the palette runs no scan and keeps
  no index of its own. It never writes: every row is a selection on an existing surface, so a library
  row leads to that object in Library and a place row opens that place. Ranking puts an exact name
  before a prefix, a prefix before the start of a word inside the name, and the name before the
  description — at 304 objects an answer ranked tenth is the same as no answer. Equal matches are
  offered rarest kind first (action, place, rule, skill, MCP, group). A single letter followed by a
  colon scopes the search (`r:` rules, `s:` skills, `m:` mcp, `p:` places) and drops every other kind
  including actions; a longer prefix such as `mcp:` stays a plain search, because trimming it would
  search for something the user did not type. An empty query is the palette's resting state rather
  than a search: it shows the surface jumps and the pinned and recent places, in the order the place
  list itself uses.
- **Keyboard and layout** — chords belong to the shell, not to a surface: ⌘K opens the palette,
  ⌘1–⌘4 select the rail destinations top to bottom, ⌘, opens Settings, and Ctrl is accepted wherever
  ⌘ is. Hints use ⌘ on macOS and Ctrl elsewhere to match the user's keyboard.
  All of them are read in one preview pass at the shell root, so they fire while a screen's own
  field has focus, and none of them writes — a mistyped chord costs a glance. Rail items and the ⌘K
  pill are Tab stops with a visible focus ring and activate on Enter, because an icon-only target
  shows the caret nowhere else.
  A three-column surface (Place, Library) is laid out against the window instead of a fixed sum of
  widths: the centre keeps 360 dp, a side pane shrinks to 140 dp before it is dropped, and panes are
  dropped from the leading side — the place list and the facet list are still one ⌘K or one query
  away, while the trailing pane (the palette, the inspector) has no substitute in the centre. A drag
  on a resize handle is capped by the same rule, so a pane cannot push the centre out of the window.
  At the minimum window all three columns still fit; what is persisted is the width a pane asks for,
  never the width a narrow window granted it. The window itself has a floor (900 x 600) enforced by
  the window manager, and a stored frame smaller than that is raised on load: below it the centre is
  already under its minimum and both panes are at theirs, so there is nothing left for the layout to
  give up — a resize could only make the app unreadable.
- **Settings** — everything machine-local, behind a section navigation that shows one section at a
  time: *General* (appearance, language, file editor, workspace state), *Library* (path, repository
  statistics, name format, external Git synchronization, import/export), *Agents*, *Shortcuts* (read-only) and
  *About* (version, diagnostics, paths). Every control writes on change; there is no Apply. One agent
  table replaces the former visibility matrix and *Connect to MCP* list: capability (Rules / MCP /
  Skills / Subagents), visibility and connection state live in the same row. The configured library path is
  compared against the one this process opened — they differ only until a restart, so the row says so
  and offers to take the change back.

Column widths and the adoption pane's height persist in `config.json` (`columnWidths`); the window's
own size and position persist in `config.json` (`window`), written when a move or resize settles.
Restoration fits the saved frame to a connected monitor's usable area, preserving negative desktop
coordinates. A disconnected monitor falls back to an available screen; the title bar stays reachable
even when the usable area is smaller than the layout floor.
A first run has no stored frame and opens at 70% of the usable screen area. Name format
(kebab / camel / snake / free) is applied on **every** save of a rule or group name, not only on
create; ids are exempt because an id names the file and is referenced by groups and installed
regions. The GUI reloads the current data-owning route when the window regains focus, since the MCP
server may have written in the background.

"Where is this installed" is a read-only scan, never a write path: one pass over the configured
places reads each managed file once and asks the MCP and skill install services for their status,
and the result is inverted into an immutable per-object index. That single read answers everything
one file can say about itself — its regions, ownership mode, drift, the hand-written lines left in
it, its hand-edited blocks already classified against the library held in memory, and whether any of
its agents reads a skills directory at all — so Home's attention feed and the
Coverage matrix are derived from the same scan Library already pays for instead of being
a second traversal of the same files; a pointer file carrying only its `@import` reports nothing to
adopt, exactly as the palette shows it. Place and Library build their
target list from the same `configuredTargets`, so the two surfaces cannot disagree about which places
exist or which agents are enabled in them. A group counts as installed wherever any member is, taking
the worst member status — the same rule a multi-file target applies to itself. Until the first scan
finishes the index is empty, and usage-dependent facets and counts stay hidden instead of reporting
the whole library as unused.

The status dot of every place in the Place list is read off that same scan. Opening a place reads
that place — its files, its statuses, its hand-written leftovers — and nothing about its neighbours:
their dots already exist, and re-deriving them per selection made entering a project cost a pass over
the whole fleet. A place therefore claims nothing until the first scan finishes, which is the honest
state: an empty dot says "not read yet", never "in sync".

Reading is scoped rather than repeated. A scan runs inside a read scope in which each physical file
is opened once and each parsed sidecar — the MCP and skill state files, a skill tree's fingerprint —
is computed once; every question asked inside that scope is answered from it. The scope is read-only
and lives exactly as long as the scan: a writer opens none, so nothing can write against a cached
copy of a file that has since changed. It is per thread, so two scans never share one.

Writing in Place is split by what the user is looking at: the palette puts objects in and takes them
out, the file preview carries what applies to a block already there — update to the library version,
and the three answers to a hand edit. The palette never offers to install over a hand edit: choosing
between the library text and a local one is a decision about the file, taken where the edit is
visible. *Restore from library* forces the library version back, *save new version* commits the local
text as the object's next library version, and *keep* changes no target file — it settles the prompt
across restarts while that exact copy stays modified; a later edit is reported again.
A rule and an MCP entry can become a library version
this way — the MCP edit is read from the named agent's config entry; a skill directory carries no
per-target text to adopt. Installing does not reorder a managed
region: an existing block is rewritten in place, and changing block order is a separate core
operation. Adopting a found file is the third write of the surface, and the only one that writes to
the library first: it is offered on the file, never on a library row, because the text being saved
belongs to this place and not yet to the library. Every write bumps a write counter the screen
watches to re-run the cross-place usage scan — statuses of this place are repaired by the reload, but
"installed in 6 of 8" is an aggregate over the others and would otherwise stay stale. Install and
remove bump it through the model's guard; adopt reports its own half-failures (the library commit can
land while the file rewrite does not) and bumps the counter itself, whichever half landed.

A bulk install from Library writes through the same services Place writes with, so there is no second
write path to keep atomic. Nothing is forced: a rule already edited by hand in a place, a foreign MCP
entry and a project-scoped rule offered to another project are refused, and every refusal is counted
and reported — a mass action that quietly wrote less than asked would be worse than one that failed.
A ticked group installs as its members carrying the group tag, while an object ticked on its own is
written untagged, because a later group removal must not take away a standalone install. The same
path takes objects back out — one place at a time from a Coverage cell — and refuses a hand-edited
copy there as well; only the scope check is dropped, because a removal narrows a claim where an
install would widen one, and a rule that landed somewhere before it was pinned must stay removable.

A dialog is either a question or a working surface, and the two are sized by different rules. A
question sizes to itself. A working surface — the splitter, the file editor, the adopt sheet with a
section list or a translation open — is measured off the window and never opens below 70% of its
width or 80% of its height (`workingDialogSize`): under that a two-pane layout wraps every line and
the pane beside it collapses into a column of single letters.

The app theme is a semantic token bundle: Material colors and typography plus Ruleblend colors,
dimensions, shapes and icons. Screens consume those tokens instead of selecting a light or dark
value themselves. `config.json.themeMode` is `SYSTEM`, `LIGHT` or `DARK`; the system mode resolves
through the current desktop appearance, while an explicit mode updates the running UI immediately.

Status colour is one scale across every screen, ranked by what it asks the reader to do: conflict
(red), update waiting (amber), managed and in sync (green), nothing of Ruleblend's in the file
(grey). An update is a state of the content rather than a piece of information, so it wears the
warning colour one step below a conflict; the blue token is `info` and marks the MCP object type,
never a sync state.

A dot that sums several objects up paints the two leading marks of that ranking, left half then
right — two halves are read at a glance where four quarters are not, and the rest stay in the
tooltip. Green ranks below the two problems because almost every place that is set up at all holds
something in sync: leading with it would paint the same left half on nearly every dot, where the
severity ranking makes the column of left halves a list of what needs doing. Each installed object
contributes exactly one mark and each file on disk without a managed region contributes the grey
one, which is what keeps a place nobody installed anything into out of the green of "nothing is out
of sync". A dot standing for a single object keeps a single colour.

## Compare

Comparison has one JVM engine in `core/compare`; the Compose surface is a consumer, as are Place,
Resolve and version history. `LineDiff` turns UTF-8 text into JGit `RawText` and builds line hunks
with `HistogramDiff`; a replacement pair receives a secondary token diff from `WordDiff`. A hunk
carries its nearby unchanged lines and final-line-break state, so `HunkApply` can transfer it in
either direction after another transfer moved the text. It replaces only a matching, anchored source
range and refuses the operation when that range is no longer in the draft, rather than overwriting a
hand edit.

The picker compares only other library objects of the same kind. Similarity gives equal weight to
Jaccard sets of normalized words and character trigrams. Its five highest scores lead the picker
with rounded percentages; equal scores retain catalog order, and every remaining candidate keeps
that original order. This ranking only helps choose a comparison target: it does not reuse or change
the adopt decisions of `BlockMatcher`.

The pane opens on the difference, not on the two bodies. `sideBySideRows` aligns both texts against
the same hunk list the transfer buttons are built from, so one row is one slot on both sides and a
line present on one side only still occupies its row on the other. A single scrolling column holds
left cell, gutter and right cell, which is what keeps the two sides in step; lines wrap instead of
scrolling sideways, because two independent horizontal offsets would break that alignment. Only the
first row of a hunk carries its transfer buttons. Editing is the pane's other mode, reached from the
segmented control, and it is the same two drafts as before.

The same surface serves the project file editor: there the left side is the editor's draft and the
right side is any other instruction file of the fleet, ranked by the same similarity. That pane
writes nothing to disk — a transfer lands in the draft, and the editor's own save still decides
whether it reaches the file.

`ComparePane` opens two independent, transient drafts from the selected source and candidate.
Typing, a hunk transfer and a side reset are local to the pane; each transfer or reset records the
pair of drafts for one shared undo stack. Reset returns a side to the body it was last saved from,
which is also what decides whether that side still has anything to save. Changing the candidate
replaces only the right draft and the undo stack, because an undo entry pairs both sides and the
left draft holds work the user already moved there. A refused transfer leaves both drafts
untouched rather than reaching the user as a crash. Imported Git skills and subagents are read-only on either
side. Saving an editable changed side delegates to the ordinary library save path and creates its
next version; closing the pane saves neither side and comparison creates no separate persistent
format.

## Translation

Rules are written in English for the agent and read by a person, so the library editor can put a
translation beside the original. It runs on the system translator of macOS 26.4+: offline, no model
to bundle, language packs maintained by the OS, and fast paragraph translation.
Starting a separate CLI agent for every paragraph would
make process startup the dominant cost, so it is not used as this backend.

- **`core/translate`** owns both halves. `MarkdownSegmenter` splits a document into what the
  translator may see and what it may not; `AppleTranslator` speaks the helper's line protocol and
  `HelperProcessChannel` owns the process it speaks to. `Translator` is deliberately narrow so a
  second backend can be added for Windows/Linux later, per the macOS-first strategy above.
- **Structure is cut, not described.** The system translator takes plain text and knows no markdown,
  so code fences, indented code, tables, frontmatter and `<!-- rb1 -->` markers are never sent, and
  inline code, URLs, paths, link targets and CLI flags are swapped for placeholders around the call.
  A segment whose placeholders do not return intact falls back to its original text: an untranslated
  paragraph is recoverable, a mangled command is not. Round-trip identity — segment then reassemble
  with no translation — is the invariant the tests hold.
- **Reading is paired, not stacked.** A translated text is shown paragraph against paragraph, and
  the pairing comes from the same segmenter rather than a second splitter: a translation is assembled
  from the source structure, so cutting both sides at the same blocks makes them line up. Counts that
  do not match are the signal that the structure changed, and the whole text is shown as one pair
  instead of paragraphs paired with the wrong partner (`ParagraphPairs`).
- **One split, every surface.** The file preview, the library editors and the file dialogs all draw
  the same pairing in one scroll (`ParagraphSplit`), so a translation reads the same wherever it is
  opened. In an editor that means an open translation is a reading mode: the field gives way to the
  paired text, because a pairing recomputed per keystroke would drift under the reader, and typing
  goes back through "Hide translation". The paired text keeps the frame the field had and wears the
  accent fill of a managed block, so what replaced the field still reads as one surface.
- **`rb-translate`** (`tools/translate-helper`, Swift) is the bridge to the Translation framework.
  It is long-lived and speaks NDJSON over stdin/stdout, because building a `TranslationSession` is
  the expensive step and it caches one per language pair; stdout carries protocol only, diagnostics
  go to stderr. Gradle builds it from source and Compose ships it in the bundle, so nothing is
  resolved from `PATH` — which the app does not inherit when launched from Finder.
- **Language packs are the user's to install.** Downloading one needs UI (`prepareTranslation`), so
  a headless helper can only report `packMissing`, and the panel links to the System Settings pane.

## Export / import

- Export = zip of the library's portable layout only — no `.git` (history is intentionally separate
  from the portable snapshot), no derived `all` group and no stray files from the folder. A selective export narrows it to chosen objects; a chosen group takes its
  members along, while a chosen profile takes its direct members, referenced groups and their
  members, so neither portable object imports broken.
- Rule scopes are not exported: they contain local absolute paths. Newly imported rules are global;
  an update of an existing rule preserves its machine-local scope.
- Import merges by `id`: absent → `new`, archive version higher → `update`, identical → `same`
  (hidden), differing but not newer → `conflict`. Nothing is written until the user picks; `new` and
  `update` are ticked by default, `conflict` is not.
- Imported blocks, groups and profiles keep the archive's version — auto-increment would misreport
  what was imported. Groups and profiles are versioned library content like blocks: an archive
  carrying a higher version of an object the library holds is an update, and anything else that
  differs is a conflict for the user to decide, because equal versions with different members mean
  neither side is behind.
  `saveGroup` raises the version (a local edit is a new local revision); `writeGroup` keeps it (an
  import reproduces someone else's revision).
- Complete skill directories and their Git provenance are portable. Executable-path metadata keeps
  scripts executable across zip export/import; differing snapshots conflict because repository
  versions are opaque strings and cannot be ordered safely.
- Archive entries are accepted only in the known `blocks/`, `groups/`, `profiles/` and validated
  `skills/<id>/` layouts, so a crafted zip cannot write outside the library. Any other entry, a
  duplicate path or a damaged zip rejects the whole archive before anything is written; only
  Finder/Windows folder metadata (`.DS_Store`, `__MACOSX/`, `Thumbs.db`, `desktop.ini`) outside skill
  trees is skipped, because earlier exports copied it verbatim. Export and import share one layout
  so the library's own archive is always importable.
  Group and profile ids inside YAML must match the archive filename; checking the ZIP path alone
  cannot protect the destination, which is derived from that embedded id.

## MCP server

Agents work with the library directly instead of hand-editing instruction files.

- **Process:** the packaged binary with `--mcp` (`Ruleblend.app/Contents/MacOS/Ruleblend --mcp`), one
  distribution artifact rather than a separate jar; unpackaged, the development launcher described below.
  Branches
  before any AWT/Compose init; stdout belongs to the protocol (the kotlin-logging banner is
  suppressed); exits on stdin EOF. SDK: `io.modelcontextprotocol:kotlin-sdk-server` 0.15.0.
- **Cross-process safety:** GUI and server may write concurrently. An advisory OS lock
  (`~/.ruleblend/ruleblend.lock`, reentrant, 10 s timeout) is taken at the outermost `LibraryGit.withLock`
  frame and in `ConfigStore.update`. Reads need no lock — repositories re-read disk per call and all
  writes are atomic.
- **Tools:** the complete public catalog and workflow live in the
  [bundled skill](../mcp/src/jvmMain/resources/dev/ruleblend/mcp/skill/SKILL.md#tools).
  Profile installation attaches and enables, or disables and detaches, a profile through
  `ReconcileProfiles`; rows for MCP configs, subagents and skills carry a
  `kind`, rule rows come from managed regions. Subagent tools store the portable Library definition and
its per-assistant `variants` — `create_subagent`/`update_subagent` take a `variants` object keyed by
agent id, with `model` as a shortcut applied to every assistant that supports subagents;
  MCP creates local skills and edits their complete trees through the library mutation use case.
  File edits hold the library lock across read, validation and atomic replacement, preventing lost
  concurrent edits. Revisions cover bytes and portable executable intent; identical writes are no-ops.
  Reads offer strict UTF-8 or Base64; shared capture/storage limits bound trees to 500 files,
  10 MiB per file and 25 MiB total, with portable paths and no Git bookkeeping or symbolic links.
  `SKILL.md` remains mandatory and keeps maintained name/description frontmatter. Imported Git
  skills and subagents remain read-only. Deleting either kind removes its group and profile memberships but leaves
  installed target copies to be explicitly uninstalled.
  install and remove delegate to the same `SubagentInstallService` as Library and Place, preserving
  rendered-byte ownership and drift checks. Its result names written, modified and unsupported
  subagents separately; ZCode and Pi stay unsupported rather than gaining a fallback definition
  path. Handlers are pure functions over `core` (`RuleblendTools`) with no SDK types, unit-tested
  without a transport.
- **Targets:** `target` is `agent:<agentId>` for an agent's global file or an absolute project path
  — the same two kinds the GUI sidebar shows, resolved through the same `Target` interface, so the
  server needs no install logic of its own. `project_path` stays accepted as the pre-1.11 name.
  Agent ids resolve only among available, non-hidden agents; an unknown one lists what is there.
  Project registration and assistant switches also work with no enabled agents; they change only
  machine-local configuration, leaving installed files intact. Unregistering forgets bindings and
  navigation state, including a missing checkout, without removing files or portable library objects.
  Hidden or unavailable assistants retain their project switches when the visible enabled set changes.
- **Git imports through MCP:** previews retain bounded `core` import plans in the server session,
  identified by opaque ids. Only two snapshots remain, expiring after ten minutes; applying one
  uses exactly the reviewed bytes rather than fetching a moving HEAD again. Entry reads expose
  original text and alternate assistant interpretations separately from the compact discovery list.
  Apply, selected source updates and editable forks delegate to the same use cases as the GUI.
  Source checks persist the existing disposable cache; source lists still derive from provenance.
  List/get results expose complete Git provenance and editability while retaining the legacy skill
  `source` string. Validation rejects the whole selection before writes; storage failures retain
  already committed objects, with atomic rollback of the failing object rather than batch rollback.
  Source updates recheck selected definitions under the write lock after fetching, preserving objects
  concurrently deleted or replaced by another writer.
- **ZIP and history through MCP:** imports retain two reviewed snapshots for ten minutes, like Git
  previews. Apply validates the complete selection and rechecks selected local definitions under
  the library lock, so an old preview cannot replace a concurrent edit. Archive bytes are captured,
  not reread at apply. Exports use the shared dependency closure and atomic guarded replacement;
  they remain outside the active library. History uses object paths, including deleted objects.
  MCP config patches are withheld based on full documents on both sides of the commit: unchanged
  type headers can be absent from diff context. Archive preview bodies follow the same protection;
  exported ZIPs intentionally retain original credentials and require a requested destination.
- **Library remote through MCP:** status reads local state without fetching; sync requires explicit
  confirmation and delegates to the existing merge/push service, preserving its typed conflict and
  unrelated-history reports. Remote URLs and raw Git messages are withheld because either can
  contain credentials. Destination setup, replacement and session-affecting settings remain in
  the GUI; the bundled skill records the action coverage and reasons for desktop-only operations.
- **Installed copies through MCP:** discovery uses the same native classification and rule scans as
  Place, selecting physical entry keys rather than accepting arbitrary file paths. Reads expose the
  installed and library content, with file-level differences for complete skill trees. Entry writes
  recheck a revision of ownership and both contents under the library lock, then use existing target
  services; revision checks prevent a reviewed copy from silently becoming another mutation.
  Local acceptance stays limited to rules and MCP configs, matching Place. Foreign rules default to
  copying; replacing requires explicit confirmation, and referenced files remain copy-only. Saved
  rules inherit the selected project's scope. Native adoption records ownership without rewriting;
  failed adoption removes the new library object. Rule replacement can leave a committed library
  rule if the target write fails. Release and orphan removal apply to the id across the target;
  modified orphans are refused. Foreign deletion is limited to explicitly confirmed skills and MCP
  entries, preserving adjacent config entries. Built-in integrations remain outside these tools.
- **Server instructions** in the initialize response stay a pointer — hosts inject them into every
  system prompt. The workflow itself lives in the skill and in the tool descriptions, which are the
  only instruction agents without skills get.
- **Install semantics:** absolute existing paths only; unknown projects are auto-registered for GUI
  visibility; target construction mirrors the GUI; a project-scoped rule is rejected outside its
  project; modified rule regions and skill trees are skipped and reported unless `overwrite: true`.
  Group installation applies all supported member types. Agent targets are never registered — they
  exist as long as the agent does.
- **Connect to MCP (Settings):** one action per MCP-capable agent, writing both halves of the
  connection. `McpConnector` writes the `ruleblend` entry into each agent's own config — structural
  JSON edit of `~/.claude.json`, line-based section edit of `~/.codex/config.toml`, native JSON edit
  of `$KIMI_CODE_HOME/mcp.json`, compatible JSON edit of `~/.agents/mcp.json` for ZCode and of
  `$PI_CODING_AGENT_DIR/mcp.json` for Pi (defaults to `~/.pi/agent/mcp.json`). Direct
  file edit rather than the agent CLI: a GUI app's minimal `PATH` makes CLI discovery the fragile
  part. An existing same-name entry is writable only with Ruleblend's version stamp or the older
  unversioned app-bundle/dev-wrapper path with `--mcp`; any other entry is reported as foreign.
  Neither Connect nor Disconnect changes it.
- **What gets registered (`McpLauncher`):** the installed binary via `jpackage.app-path`, or a dev
  launcher under `~/.ruleblend/bin/`, always with `--mcp`. POSIX uses `ruleblend-mcp`; Windows uses
  `ruleblend-mcp.exe`, generated from the running JDK's GUI jpackage launcher. Windows dev runs need
  a full JDK with `jdk.jpackage.jmod` and Windows PowerShell. Preparation failures appear in Settings.
  The launcher retains its path across rebuilds; switching between dev and installed builds requires
  reconnecting. A content stamp avoids replacing a running Windows executable. Classpaths live in
  URI-encoded, content-addressed JAR manifests under `bin/app/`; the atomic `.cfg` update points new
  processes at the current classpath without replacing JARs held open by existing sessions.
  Both generated and packaged Windows launchers declare a
  [UTF-8 process code page](https://learn.microsoft.com/en-us/windows/apps/design/globalizing/use-utf8-code-page)
  (Windows 10 1903+): Java's default ANSI conversion otherwise loses Cyrillic paths on non-Russian
  Windows. A GUI-subsystem executable preserves inherited stdio without opening a console.
- **Bundled skill:** `FileSkillInstaller` writes the `SKILL.md` shipped as an app resource to each
  native user-skill location: `~/.claude/skills/ruleblend/SKILL.md` (Claude Code),
  `~/.codex/skills/ruleblend/SKILL.md` (Codex), `~/.agents/skills/ruleblend/SKILL.md` (Pi),
  `$KIMI_CODE_HOME/skills/ruleblend/SKILL.md` (Kimi Code), and
  `~/.zcode/skills/ruleblend/SKILL.md` (ZCode). All copies use the same text and
  marker-based ownership; each installer owns availability detection and its path. The skill is not
  a library block: the user cannot edit or delete it, and an app update replaces the copy on disk.
  Ownership is a marker comment on the last line, giving four states — not installed, installed
  (matches the bundle), outdated (ours, older text), foreign (no marker, so never touched).
  MCP-capable rows are connected only when both their registration and skill match.
- **Versions of what Ruleblend installs:** both shipped artifacts carry a number the copy on disk
  states about itself, so "out of date" can name what it is behind — and so the answer survives a
  lost sidecar. The skill's version rides in its ownership marker
  (`<!-- ruleblend-managed skill v1 -->`); the MCP entry's rides in the entry's own `env` as
  `RULEBLEND_ENTRY`, which every config format Ruleblend writes reads back. Both are raised by hand:
  the skill's when `SKILL.md` changes (a test pins the text's digest to the number, so an edit cannot
  ship under the old version), `MCP_ENTRY_VERSION` when an entry a previous build wrote would no
  longer be what this build writes. Neither follows the app version — then every release would report
  every assistant stale for nothing. A copy written before versioning reads as version 0, so the
  first run after an update repairs it once. `BundledIntegration` is the single reader of that state
  and the single writer of the repair; Home reports it as an attention card and Settings shows it per
  assistant, both offering one agent and all of them. A foreign skill is reported by both and
  rewritten by neither.
- **Both artifacts in Library:** the bundled skill and the self-registration are listed among the
  library's own skills and MCP entries, marked built-in, under the reserved ids `builtin:skill` and
  `builtin:mcp` (a colon is not a slug character, so no library object can claim them). They show
  their name, version, description and full text and nothing else: no editor, no delete, no bulk-action
  tick, no usage or source facet — they are installed from Settings, not from here.
- **Both artifacts in Projects:** their on-disk ownership markers also identify them in the Skills
  and MCP tabs. They are counted as Ruleblend-managed and their definition is readable, but Place
  offers no edit, adoption, hide or removal action; Settings remains the only update and removal path.

## MCP blocks

Blocks of `type: mcp` describe third-party MCP servers installable into agent MCP configs.

- **Canonical config** (`McpServerConfig`): JSON with a `transport` discriminator — `stdio`
  (`command`, `args`, `env`) or `http` (`url`, `headers`). `McpConfigCodec` owns parsing and
  byte-stable serialization, so the form round-trips without spurious version bumps. The entry key in
  agent configs is the block id; `ruleblend` is reserved for self-registration.
- **Editor:** structured form only. An unparseable body shows read-only text plus reset; an invalid
  config blocks Save.

  | Agent | Global | Project |
  |---|---|---|
  | Claude Code | `~/.claude.json` `mcpServers` | `<dir>/.mcp.json` `mcpServers` |
  | Codex | `~/.codex/config.toml` `[mcp_servers.<id>]` | `<dir>/.codex/config.toml` (trusted projects only) |
  | Kimi Code | `<KIMI_CODE_HOME>/mcp.json` `mcpServers` | `<dir>/.kimi-code/mcp.json` `mcpServers` |
  | ZCode | `~/.agents/mcp.json` `mcpServers` | `<dir>/.mcp.json` `mcpServers` |
  | Pi 1.0+ | `~/.pi/agent/mcp.json` `mcpServers` | `<dir>/.pi/mcp.json` `mcpServers` (project trust required) |

  Claude JSON entries carry an explicit `"type"`. Kimi infers its transport from `command` or
  `url`, so `KimiMcpJson` writes neither `type` nor a second discriminator; its project address is
  the native `<cwd>/.kimi-code/mcp.json` layer represented by Ruleblend's project target. The
  separate `<project>/.mcp.json` is the shared Claude-compatible layer, which ZCode reads as its
  project compatibility config; ZCode's global shared config uses the same JSON and explicit
  transport field. Codex writes stdio as `command`, `args`, and `env`, and streamable HTTP as `url`
  and static `http_headers`, in the same named table. Pi's adapter tells transports apart by
  `command` / `url` and ignores the Claude `"type"` field, so Pi shares the Claude JSON writer.
  Edits go through the pure helpers
  `McpServersJson` / `KimiMcpJson` / `CodexMcpTable`, preserving foreign content.
- **State:** these configs admit no portable comments, so nothing can be marked in place. The sidecar
  `~/.ruleblend/mcp-state.json` records version + config per `(targetKey, agentId, blockId)`. With a
  record: matches the library render → `synced`; matches the recorded render while the library moved
  on → `update available`; otherwise `modified`.
- **Ownership:** an entry with our name but no record is foreign whatever its content — shown as a
  foreign entry, overwritten only on explicit confirmation, skipped by group operations. A lost state
  file therefore makes entries foreign rather than silently owned.
- **Groups** are type-agnostic; MCP members carry group origin in the sidecar rather than the native
  agent config. Removing a group takes only copies with that origin; standalone copies keep theirs.
  Older builds left group copies without an origin: before build 165 for MCP, skill and subagent
  members, before build 169 for any member updated on its own. They are indistinguishable from
  standalone copies, so no migration can re-tag them. A removal names every member of any kind it kept
  for lack of an origin and takes them out only on a second, explicit confirmation; keeping is the
  default.
  Members no agent of the target can run are excluded from the group's check.
- **Ruleblend self-registration** appears above library MCP blocks as a disabled status row. It reads
  the applicable agents' global connection entries; installation and removal remain in Settings.

## Reliability & testing

The opt-in root UI suite uses a separate JVM compilation, not a Kotlin test run, so aggregate
test tasks cannot discover it. Each cold start uses a new JVM with its home and child environment
set before static paths are loaded; this preserves the production service graph and lock lifetime.
Only OS actions and translation are substituted. A JDK 17 worker write guard rejects paths outside
the canonical fixture root and symlink escapes; the native macOS prototype uses an OS sandbox.
Native AX references retain the PID predicate, since resolving elements by application name can
address another running copy. Window capture uses ffmpeg and crops the native frame, without audio.
Reports require actual testcase results and a successful process exit; skipped/blocked prerequisites
never count as acceptance. See [UI E2E](UI_E2E.md) for invocation and diagnostic artifacts.

- Every write to a user file is atomic (temp + rename).
- Atomic renames retry transient sharing violations for a bounded time on Windows, including home
  migration, library replacement and skill installation. An unsupported atomic move, a missing
  source or an occupied destination fails directly;
  deleting the original or copying over it would expose partial data. A persistent lock reports the
  source and destination. A failed skill restore retains its rollback directory for recovery, and a
  later replacement refuses to overwrite that directory. The shared process lock also bounds waits
  between threads, not only waits for the OS file lock. Guarded writes recheck the file before each
  retry, so a hand edit during a Windows sharing violation still wins.
- Atomic rename rules out a half-written file, not a lost update: two writers that read the same
  text and each replace the file keep only the last result. Every read-modify-write cycle over a
  target file, an agent MCP config or the sidecar state describing them therefore runs inside
  `TargetMutationCoordinator`, which holds the same advisory `ruleblend.lock` the library and the
  config use. Writers of one process and of the two Ruleblend processes serialize on it; whole-target
  work holds it from its first read to its last write, so cooperating writers cannot interleave a
  target update. Lock-free readers may still observe intermediate files during a multi-file write.
  The lock is advisory and covers Ruleblend only, so an external editor is caught a second way: the
  size and content digest of the file as it was read are compared against the file again immediately
  before the replacing rename. A file that no longer matches is not overwritten — the cycle is
  recomputed against the newly saved text, which keeps a hand edit outside the managed run and lets
  the ordinary drift check refuse one inside it. Three recomputes and the write is abandoned rather
  than forced. Skill directories carry the same idea with their tree fingerprint, re-checked between
  the preflight and the first change to the directory.
- A mutation covers a file and the record derived from it — a target and its `targets.index` entry, an
  MCP entry and its `mcp-state.json` record, a skill tree and its `skill-state.json` record — under
  one lock, so a record never describes a file that has since changed. The stores are named in the
  mutation alongside the file they describe; a single index or state file also holds the entries of
  every target, which makes two unrelated targets written at once one read-modify-write of it.
- Unit tests live beside the code they cover, in all three modules. Highest-risk areas, all covered:
  marker parser round-trip in both encodings, ownership-mode detection and migration, import merge,
  adopt extraction and section matching, Move round-trip, splitter, MCP codec and config editors,
  install status matrix.
- The gate runs every module's suite (`:core:allTests :mcp:allTests :app:allTests`): a test written
  for the MCP facade or for a screen protects nothing while CI runs `core` alone.
  The same gate and runner contracts run on macOS and Windows with a JDK 21 Gradle daemon and
  JDK 17 application/test toolchain. Native UI and translation acceptance remain opt-in.
- Bundled text resources use LF via `.gitattributes`, so their bytes and versions are independent
  of checkout settings. The bundled skill digest checks the actual resource without normalizing it.
- Integration tests cover the write paths that used to bypass ownership migration: fresh install,
  legacy conversion on install, redirect sync, adopt and remove.
- A write over a whole target is one operation. A project can be read by several agents, and their
  files are written one after another: every file of the set is snapshotted first, and a failure part
  way puts the files that already took the change back. The result is per file — written, unchanged,
  failed, rolled back, or stranded when even the undo failed — so a caller never has to guess whether
  the first agent kept what the second one refused. Pointer files belong to the set: a pointer that
  cannot be written undoes the rule files it would point at. MCP entries and skill trees follow the
  same rule through their own snapshot; a stranded file is always named in the error rather than
  counted as undone. A group or bundle write that fails for one member while others land reports
  the written/refused/failed tallies beside the first error, never the error alone.
- A project has one identity: its absolute, normalized path. The registered list, profile bindings,
  per-project disabled agents, rule scopes, place sets and pins, and the MCP install records all key on it, and a
  config read repairs entries written under another spelling instead of carrying two identities of
  one checkout.
- Config and sidecar reads degrade instead of failing. A config this build cannot parse is moved to
  `config.json.broken` and the app starts on defaults, saying once where the file went; an unreadable
  backup index costs the list of snapshots, not the snapshots. Forward compatibility (unknown fields,
  unknown enum values) is still handled by the decoder and is not a corruption.
- A place refresh has a read budget rather than a stopwatch: a scan of a place holding fifty rules
  opens each of its files exactly once, and the test fails if a question starts re-opening them.
- Compiler warnings fail the build on CI (`-PwarningsAsErrors=true`) and only warn locally: most of
  them name code a future Kotlin refuses outright, so they are caught on the branch that adds them.
- The unit gate stays deterministic: nothing in it depends on machine state. Tests that need the
  macOS Translation framework, a built `rb-translate` and an installed language pack are named
  `*MacOsIntegrationTest`, excluded from `jvmTest` and run by `:core:macOsIntegrationTest`. A missing
  prerequisite makes them skipped, never passed, so a green gate never stands for an integration
  nobody checked. The protocol they share with the app — ids, timeouts, one retry, error mapping —
  is covered in the gate against a scripted `TranslateChannel`.
