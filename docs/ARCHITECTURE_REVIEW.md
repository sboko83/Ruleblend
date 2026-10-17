# Architecture Review

Independent review of the codebase as of 2026-10-17 (appBuild 285). Method: static reading of the
three Gradle modules, build scripts and CI; no builds or test runs were executed for it. Findings
carry file/line evidence. Sections: strengths to keep, then findings ordered by priority
(P1 correctness/reliability, P2 structural debt, P3 hygiene), then a suggested order of work.

Scale at review time: core ≈ 14.2k main lines + 15.8k test, mcp ≈ 5.6k + 5.6k, app ≈ 32k + 21.5k
(unit + UI E2E).

## Strengths to keep

- **Layering is real and verified**: no `dev.ruleblend.app` import under `core/` or `mcp/`, no
  `dev.ruleblend.mcp` import under `core/`; facades share core use cases (`InstallPolicy`,
  `InstallObject`, `MutateLibrary`) instead of re-deciding safety questions.
- **Atomic-write and locking discipline**: all user-file writes go through temp+rename;
  read-modify-write cycles serialize on one advisory `ruleblend.lock` via
  `TargetMutationCoordinator`; atomicity, rollback and per-file result reporting are designed, not
  accidental.
- **Test culture**: test code ≈ 43k lines against ≈ 52k main lines; CI runs every module's suite on
  macOS and Windows, treats compiler warnings as errors, checks license coverage inside the
  packaged image, and rehearses releases. The opt-in UI E2E suite runs in an isolated environment.
- **Small, current dependency set** (Kotlin 2.4, Compose 1.11, JGit, kaml, MCP SDK) with a declared
  JVM-only target; JGit binding core to the JVM is a documented, accepted trade.
- **Agent adapters via one interface with defaults** (`core/integration/AgentAdapter.kt:13-80`);
  per-agent adapters are 15–25 lines each — no copy-paste.
- **stdout discipline in `--mcp` mode**: the only `System.out` use in `mcp` is the transport sink;
  JGit logging is silenced; banner suppressed.
- **Security posture of the import paths**: bounded clones, path/symlink/size limits, re-validation
  of stored provenance, zip-slip protection, credential masking in tool output.
- **Compiler-checked i18n parity** — 829-member `Strings` interface with per-language objects and a
  parity test.

## P1 — correctness and reliability

### 1.1 Library mutations run on the UI thread

`LibraryModel.guard` is synchronous (`app/library/LibraryModel.kt:933-944`) and ~20 mutation entry
points go through it (`createBlock` :666, `deleteBlock` :835, `saveGroupDraft` :853,
`applyGitSkillImport` :762 — a git clone —, `forkSkill` :798, `addMarkedToGroup` :448,
`exportMarked` :458). Dialogs call them directly from click handlers
(`app/library/LibraryDialogs.kt:274`, `app/library/LibraryScreen.kt:343`). Every such call performs
repository writes + a JGit local commit, which also contend on the 10 s advisory lock the MCP
server may hold. `IntegrationModel` already solved this correctly (`guard` hops to
`Dispatchers.IO`, `app/integration/IntegrationModel.kt:1463-1481`; 57 suspend functions vs
LibraryModel's 13).

**Why it matters:** UI freezes up to the lock timeout whenever the MCP server or a background sync
holds the lock; git commit latency shows as frame drops on every save.
**Recommendation:** make `LibraryModel.guard` suspend + `withContext(Dispatchers.IO)` and wrap the
remaining synchronous call sites in the screen's coroutine scope — the `IntegrationModel` pattern
applies verbatim.

### 1.2 Two `InterProcessLock` instances over one lock file

The app builds one shared lock (`app/Main.kt:174`), but `ClaudeModRuntime` default-constructs its
own instance on the same `~/.ruleblend/ruleblend.lock` (`core/integration/ClaudeModRuntime.kt:18`),
and `Main.kt:453` constructs `ClaudeModRuntime` with defaults. In-process reentrancy is
per-instance (`core/storage/InterProcessLock.kt:36`), and an overlapping in-JVM lock attempt is
folded to "busy" (:75-79), so a thread that already holds the shared lock and then enters mod
runtime busy-polls up to 10 s and then throws `LockTimeoutException`. Worse,
`ClaudeModRuntime.locked` holds the lock across external `claude` CLI calls, including 8+ `--help`
capability probes on first use (`ClaudeModRuntime.kt:315-330`).

**Why it matters:** spurious 10 s stalls or lock-timeout failures that look random; a slow CLI
probe blocks every other writer in both processes.
**Recommendation:** inject the shared lock instance everywhere (delete the constructor default);
probe capabilities once outside the lock and cache.

### 1.3 MCP tool failures bypass the `isError` path

`Server.tool` catches only `ToolError` (`mcp/RuleblendServer.kt:640-642`), but `RuleblendTools`
inline handlers rethrow core exceptions directly (`report.firstError()?.let { throw it }`,
`mcp/RuleblendTools.kt:656`, also :746) and IO/lock failures surface as raw exceptions too. A lock
timeout or filesystem error therefore reaches the agent as an SDK-level protocol error with no
readable message, instead of a tool result the model can act on. The four per-file wrappers
(`GitSourceTools.kt:305-316` keeps messages, `LibraryExchangeTools.kt:320-331` drops them,
`SkillFileTools.kt:109-120`, `TargetEntryTools.kt:375-379`) also disagree on whether an exception
message is disclosed.

**Why it matters:** the MCP facade is a first-class surface; half its failures are invisible or
unusable to the caller.
**Recommendation:** one boundary mapper in `Server.tool`: catch `ToolError` plus
`IOException`/`IllegalStateException`, map to `isError` results with a consistent disclosure rule;
delete the per-file wrappers.

## P2 — structural debt

### 2.1 `IntegrationModel` is a god model with no direct tests

1,797 lines, ~90 functions, 13 constructor dependencies and 6 use cases built internally
(`app/integration/IntegrationModel.kt:156-188`): adoption, install policy, drift decisions,
backups, profile reconciliation, launchers — plus ~40 view-state fields. The `app/jvmTest` tree has
no `integration` package; it is exercised only indirectly through Place tests.

**Recommendation:** split along the use cases it orchestrates (adopt, drift/keep, backups,
reconciliation) or move the orchestration back into core use cases; give the biggest methods
direct tests either way.

### 2.2 `PlaceFileColumn` is a 655-line composable that recomputes previews

The function spans `app/place/PlaceFileColumn.kt:110-764`; derivations (`modes`, `files`,
`sections`, `shown`, `quiet`, `handFiles`, lines 132-192) are not remembered and
`placePreviewParts(model, file)` re-runs inside the tab strip (:208-220). Any watched model state
change re-derives the whole tab model and file previews.

**Recommendation:** remember the derivations against the model snapshot they read, and split the
tab strip / section headers / dialogs into private composables like the rest of the file already
does.

### 2.3 `Main.kt` concentrates wiring, refresh and navigation

985 lines: process mode, the whole DI graph (~26 `remember` blocks, `Main.kt:372-543`),
route-triggered refresh and a second focus-triggered refresh with a duplicated `when(route)`
(:597-633 vs :662-682), palette ranking, three global dialogs, and cross-screen intents carried as
ad-hoc mutable flags (`pendingRemoteConflict` :512, `compareRemoteConflict` :509,
`placeLibraryEdit` :547). `AppRoute` is a bare enum with no typed arguments. `LibraryUsageScanner`
is constructed 4× and `LibraryPlaceInstallService` 2× (:449, :450, :473, :482-483, :489) — correct
only while they stay stateless.

**Recommendation:** extract an `AppGraph` for construction, a single refresh coordinator owning one
`when(route)`, and typed navigation events instead of shared mutable flags.

### 2.4 Three god classes in core

- `storage/LibraryGit.kt` (1,310 lines, ~30 public functions): commits, the inter-process lock,
  remote sync with retries, per-object-kind merge reconciliation (:647-980), line-ending repair
  (:262-337), background scheduling (:90-105, :372-385). Split candidates: `RemoteSync`,
  `LineEndings`, `SyncScheduler`.
- `storage/LibraryRepository.kt` (675 lines, ~50 public members): per-kind CRUD repetition; a
  per-kind object-storage abstraction would halve it.
- `integration/IntegrationService.kt` (665 lines): encoding selection and legacy migration
  (:40-57), rollback (:342-382), redirect migration (:425-449), adopt scanning (:575-579) and the
  `@import` BFS (:525-543) in one class.

None is broken; all are at the size where every change touches everything.

### 2.5 The install lifecycle exists three times

`SkillInstallService` (522 lines) and `SubagentInstallService` (427) are structural twins — the
same install/remove/entries/adopt/release/removeOrphan lifecycle — and their state stores
(`SkillInstallStateStore.kt:61-120` vs `SubagentInstallStateStore.kt:41-76`) are near line-for-line
copies; `mcp/install/McpInstallService` + `McpStateStore` is a third variant. One
"agent-file installation" abstraction (lifecycle + state store) would replace three
implementations and make the fourth (mods) cheaper.

Related duplication in `mcp`: six re-implemented JSON argument parsers with diverging blank-value
and error rules (`RuleblendTools.kt:1299-1348`, `GitSourceTools.kt:290-303`,
`LibraryExchangeTools.kt:303-318`, `SkillFileTools.kt:100-107`, `TargetEntryTools.kt:382-388`,
`ProjectTools.kt:243-254`); connector status/version/legacy-cleanup logic repeated across four
connectors (`McpServersJsonConnector.kt:87` shows the parameterized shape that could absorb them);
`install`/`uninstall` mirror each other's per-kind reporting (`RuleblendTools.kt:618-766`).

### 2.6 Background failures are silent; core has no logging

`slf4j-nop` is bound and core logs nowhere: a background push failure is visible only through the
volatile `lastRemoteSync` (`LibraryGit.kt:452`); `catch (_: Exception) { fail(INVALID_STATE) }`
(`ClaudeModRuntime.kt:304`) and the equivalent in `CheckSources.kt:224` turn programming bugs into
benign statuses; corrupt sidecars silently read as empty (`SkillInstallStateStore.kt:101-109`).
stdout discipline in MCP mode is right; silence everywhere else is not.

**Recommendation:** keep stdout clean, bind a real slf4j backend in the GUI, and make background
failures log to stderr at minimum; narrow the `catch (Exception)` mappings to expected types.

### 2.7 RuleblendTools/RuleblendServer size

`RuleblendTools.kt` (1,368 lines, 70 handlers) is borderline — half is one-line delegation — but
the natural seams exist (library CRUD :194-611, install/target :618-870). `runRuleblendServer`
spans 544 lines of tool registration with two mixed declaration styles
(`RuleblendServer.kt:134-598`) and has no direct test (only smoke via a spawned child in
`McpLaunchProcessTest`). A registry list would shrink it and make completeness testable.

## P3 — hygiene

- **`Strings.kt` monolith** (`app/i18n/Strings.kt`, 2,954 lines, 829 members × 2 languages): the
  mechanism is sound, the single file is a merge-conflict magnet. Split per language; three files,
  zero mechanical cost.
- **Build logic concentration** (`app/build.gradle.kts`, ~370 lines): PowerShell patching of the
  packaged exe, Swift helper build, a custom `uiE2e` Test task with full environment isolation, and
  a `assistantSpike` compilation with its own jpackage packaging all live inline. Move to included
  build scripts or convention plugins; the Windows exe-patching branch is the most fragile code in
  the repo and is untestable where it sits.
- **Stringly-typed origins**: `g=<id>`/`p=<id>` are built and parsed by string ops in four places
  (`IntegrationService.kt:167,292`, `usecase/InstallObject.kt:88`, `usecase/ReconcileProfiles.kt:105-136`)
  with no shared `Origin` type. The `~/.ruleblend` default is re-declared in 8 files.
  `RuleblendTools.installProfile` calls a sibling through a synthetic JSON args object
  (`RuleblendTools.kt:961`); `ProjectTools.snapshot` round-trips through the tools' own JSON API and
  digests `public.toString()` (`ProjectTools.kt:177-192`).
- **Exception taxonomy**: `LockTimeoutException` and `WrappedRunDriftException` extend
  `IllegalStateException` (`InterProcessLock.kt:13`, `WrappedRun.kt:6`), so a caller's generic
  `IllegalStateException` handling swallows them; `ModRuntimeError` is a 12-case enum where sealed
  types with payloads would carry context.
- **Defaults that drop safety**: `TargetMutationCoordinator()` defaults to `lock = null`
  (`TargetMutationCoordinator.kt:24`) — cross-process safety depends on every wiring passing the
  shared coordinator; `FileReadScope` correctness ("nothing that writes may run inside one") rests
  on convention (`FileReadScope.kt:15-17`).
- **Test gaps at hot spots**: `IntegrationModel` (see 2.1), `RuleblendServer` (see 2.7),
  `app/place/PlaceFileBlocks.kt` (698 lines, no dedicated test), `core/compare/HunkApply.kt`
  (exercised only via `LineDiffTest`), `AppleTranslator` (only in the opt-in macOS integration
  gate, so untested in CI).
- **No static analysis**: the compiler-warning gate is good; at ~52k main lines add detekt (or
  ktlint) with a curated rule set — complexity/file-size rules would have flagged most of P2
  automatically.
- **`commonMain` without a second target**: `core/commonMain` (3.1k lines) is a purity boundary no
  target enforces; `app/commonMain` holds only compose resources. Fine today; worth remembering
  that "multiplatform" here is organizational, not tested against a second compilation.
- **MCP SDK 0.15.0** is pre-1.0; pin consciously and expect breaking API churn on upgrades.

## Suggested order of work

1. 1.1 + 1.3 — small, isolated, user-visible reliability wins (UI-thread writes; MCP error mapping).
2. 1.2 — inject the shared lock; unbundle CLI probing from `locked`.
3. 2.6 — stderr logging for background paths; narrow `catch (Exception)`.
4. 2.1 — split/test `IntegrationModel` before the next feature lands on it.
5. 2.3 — extract `AppGraph` and one refresh coordinator while touching `Main.kt` anyway.
6. 2.5 — the install-lifecycle abstraction, next time a fourth installer (mods runtime) grows.
7. 2.2, 2.4, P3 items — opportunistic, driven by the detekt baseline.
