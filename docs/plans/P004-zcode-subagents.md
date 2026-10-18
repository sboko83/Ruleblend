---
id: P004
title: ZCode subagent support
type: feature
status: active
created: 2026-10-18
gate: ./gradlew :core:jvmTest :mcp:jvmTest :app:jvmTest
---
## Goal

A Library subagent installs into ZCode the way it installs into Claude Code: ZCode gets its own
variant fields, `~/.zcode/agents/<id>.md` and `<project>/.zcode/agents/<id>.md` are first-class
install destinations with fingerprinted ownership, Git import recognizes `.zcode/agents`, and
Settings, Place, Coverage and the MCP tools report ZCode as a supported subagent target.

## Context

Format research, done 2026-10-18 against the installed ZCode 3.10.1 bundle
(`/Applications/ZCode.app/Contents/Resources/glm/zcode.cjs` and `app.asar`), supersedes the
"no stable user or workspace file format" note in the architecture snapshot.

User scope is `<storageRoot>/agents/*.md`, where storageRoot is `storage.dir` from
`~/.zcode/cli/config.json`, default `~/.zcode`. Ruleblend keeps using the fixed `~/.zcode`
root it already uses for ZCode skills and `AGENTS.md`, and ignores `storage.dir`. Project
scope is `<workspace>/.zcode/agents/*.md`. Files `*.md` and `*.markdown` are loaded, sorted
by filename; the agent name comes from frontmatter `name`, not from the filename.

Frontmatter: `name` and `description` are required; optional `model` (written and read as
`providerId/modelId`; the aliases inherit/main/sonnet/opus/haiku are parsed but ignored),
`thoughtLevel`, `tools`, `disallowedTools`, `skills`, `mcpServers`, `color`
(red|blue|green|yellow|purple|orange|pink|cyan), `permissionMode` (ignored at project scope),
`maxTurns` (positive integer), `background`, `injectAgentsMd` and `memory`
(user|project|local). The body after the closing `---` is the system prompt; YAML scalars in
double quotes are accepted, so the existing Markdown render needs no changes.

`~/.zcode/cli/agents/` holds runtime sessions (`sess_*` directories) and is never a
definition destination. Per-agent enable state in `~/.zcode/v2/agents-state.json` can only
disable user-scope agents; Ruleblend does not manage that file. On a name conflict between
scopes a project definition overrides a user one, so installing the same library block at
both scopes cannot shadow itself.

- docs/ARCHITECTURE.md:L744-860
- core/src/jvmMain/kotlin/dev/ruleblend/core/integration/SubagentFormat.kt
- core/src/jvmMain/kotlin/dev/ruleblend/core/integration/AgentAdapter.kt

## Constraints

- `ZCodeSubagentFormat` reuses `MarkdownSubagentFormat` and declares only `model`, `tools` and
  `color` — native keys identical to Claude Code. Every other ZCode field stays unmodelled:
  foreign fields survive a parse→render round trip through the extra-fields mechanism.
- Model suggestions for ZCode stay empty: the value is `providerId/modelId`, and the
  Claude-style aliases in `MODEL_SUGGESTIONS` are values ZCode ignores.
- Never write to `~/.zcode/cli/agents/` or `~/.zcode/v2/agents-state.json`.
- Installs go through the existing `SubagentInstallService` (atomic write, fingerprint from
  `render` bytes); no new writer or ownership scheme.
- Raise `appBuild` by one and add the CHANGELOG bullet in the unit that ships the change.

## Traps

- `RuleblendToolsTest` builds its fake home with `~/.zcode` already created, so after the
  adapter flip the MCP install path writes real files there. The "unsupported subagent targets"
  test must keep only Pi and gain a ZCode install case in the same unit, or the gate goes red.
- `SubagentInstallServiceTest` lists ZCode next to Pi as an unsupported global target — same
  unit, same reason.
- ZCode reads the agent name from frontmatter, not from the filename: an adopted foreign file
  may carry a `name` that differs from its `<id>.md`; that is fine, Ruleblend already works
  this way for Claude Code and Kimi Code.
- Do not point any destination at `~/.zcode/cli/agents`: it looks like the right directory name
  but is a live session store.

## Decisions

- Declare only `model`/`tools`/`color` — parity with the Claude Code field set keeps the editor
  small; a wider ZCode field set was rejected because those knobs are not portable values worth
  an editor row per assistant.
- Fixed `~/.zcode` root instead of reading `storage.dir` — consistent with the ZCode skill and
  rules surfaces, which already ignore that relocation; one assistant must not grow a second
  root-resolution mechanism.
- Update the dated snapshot row for ZCode to 2026-10-18 instead of leaving the stale 2026-09-04
  claim next to an adapter that contradicts it.

## Phase 1: Core support

### [x] P004.1.1 ZCode adapter, format and tests
Read:
- core/src/jvmMain/kotlin/dev/ruleblend/core/integration/SubagentFormat.kt:L60-120
- core/src/jvmMain/kotlin/dev/ruleblend/core/integration/AgentAdapter.kt:L100-185
- core/src/jvmTest/kotlin/dev/ruleblend/core/integration/AgentAdapterTest.kt:L100-195
- core/src/jvmTest/kotlin/dev/ruleblend/core/integration/SubagentFormatTest.kt:L1-90
- core/src/jvmTest/kotlin/dev/ruleblend/core/integration/SubagentInstallServiceTest.kt:L160-180
- mcp/src/jvmTest/kotlin/dev/ruleblend/mcp/RuleblendToolsTest.kt:L830-850
Do:
- Add `ZCodeSubagentFormat` beside the Kimi one: `SubagentFormat by MarkdownSubagentFormat(...)`
  with `model` (no suggestions), `tools` (LIST) and `color` (the eight ZCode values).
- In `ZCodeAdapter` override `subagentSupport`, `subagentFormat`, `globalSubagentDirectory()` to
  `~/.zcode/agents` and `projectSubagentDirectory(projectDir)` to `<project>/.zcode/agents`.
- Update the ZCode block in `AgentAdapterTest`: expect `SUPPORTED`,
  `globalSubagentFile("review")` at `~/.zcode/agents/review.md`, the project file at
  `.zcode/agents/review.md`, and a supported `capabilities().subagents`.
- Add render and parse tests for `ZCodeSubagentFormat` in `SubagentFormatTest`: a
  model+tools+color render, and a parse that keeps an unknown `thoughtLevel` as an extra field.
- In `SubagentInstallServiceTest` keep only Pi in the unsupported-target test and add a ZCode
  global-target install assertion (written bytes, SYNCED status).
- In `RuleblendToolsTest` rewrite `ZCode and Pi remain unsupported subagent targets` to Pi-only
  and add a ZCode install case asserting `~/.zcode/agents/<id>.md` exists with no
  `skipped_unsupported_subagents` key.
Verify: `./gradlew :core:jvmTest :mcp:jvmTest`
Traps: do not reuse `MODEL_SUGGESTIONS` for the ZCode model spec — ZCode ignores those alias
values; the fake home in `RuleblendToolsTest` already contains `.zcode`, so the new install
path writes there for real.
Commit: P004.1.1: add zcode subagent support
Handoff:
- The MCP install response always carries skipped_unsupported_subagents as an empty array — the plan's "no key" expectation cannot hold; assert emptiness instead
- createSubagent's model shortcut now also writes a zcode variant — two RuleblendToolsTest variant-map assertions gained a zcode line, beyond the tests named in Do:
- Unverified: app UI surfaces for ZCode subagents (Settings, Coverage, Place) — covered by units 2.1 and 3.1; only :app:jvmTest ran green here

## Phase 2: Import, docs and version

### [x] P004.2.1 Git import, architecture docs, version
Read:
- core/src/jvmMain/kotlin/dev/ruleblend/core/storage/GitSubagentDiscovery.kt:L45-140
- core/src/jvmTest/kotlin/dev/ruleblend/core/storage/GitRepositoryImporterTest.kt:L55-80
- docs/ARCHITECTURE.md:L744-860
- docs/ARCHITECTURE.md:L1380-1390
- docs/ru/ARCHITECTURE.md:L750-860
- CHANGELOG.md:L1-20
- app/build.gradle.kts:L10-20
Do:
- In `GitSubagentDiscovery` add `GitAgentRoot(".zcode/agents", "zcode")` and map `"zcode"` to
  `ZCodeSubagentFormat` in `MARKDOWN_FORMATS`.
- Extend the importer test fixture with `.zcode/agents/reviewer.md` and add `"zcode"` to the
  expected source assistants.
- Update `docs/ARCHITECTURE.md`: refresh the snapshot row for ZCode (dated 2026-10-18:
  `~/.zcode/agents/*.md` and `<workspace>/.zcode/agents/*.md`, Markdown frontmatter,
  `~/.zcode/cli/agents/` still runtime-only), rewrite the closing paragraph of
  *Subagent formats*, flip the ZCode capability-matrix row to ✓, and drop ZCode from the
  "no destination" sentences in *Subagent ownership and drift*, *Subagent Library UI* and the
  MCP facade paragraph.
- Mirror every one of those edits in `docs/ru/ARCHITECTURE.md`.
- Add the CHANGELOG bullet under [Unreleased] and raise `appBuild` by one.
Verify: `./gradlew :core:jvmTest`
Traps: the Russian mirror keeps its own wording — translate the meaning of each changed
sentence, do not patch byte offsets; the MCP facade paragraph sits near L1386 in the English
file and elsewhere in the Russian one, find it by its "fallback definition path" claim.
Model: fast
Commit: P004.2.1: document zcode subagent format
Handoff:
- Ambiguous Markdown collections now parse into three drafts (claude-code, kimi-code, zcode) — GitRepositoryImporterTest and GitSourceToolsTest expected-assistant sets were extended with zcode
- Unverified: nothing — verify and gate ran :core:jvmTest, :mcp:jvmTest and :app:jvmTest green

## Phase 3: Acceptance

### [x] P004.3.1 Acceptance
Read:
- app/src/jvmTest/kotlin/dev/ruleblend/app/library/SubagentVariantsTest.kt:L1-115
- app/src/jvmTest/kotlin/dev/ruleblend/app/settings/SettingsScreenTest.kt:L378-396
- app/src/jvmTest/kotlin/dev/ruleblend/app/place/PlaceFilesTest.kt:L40-105
- app/src/jvmTest/kotlin/dev/ruleblend/app/coverage/CoverageMatrixTest.kt:L149-170
Do:
- Cover each Goal claim with a test: the subagent editor shows a ZCode field section; the
  Settings ZCode row renders the Subagents capability as supported; a project target enabled
  for ZCode lists `.zcode/agents` as a subagent destination on the Place screen; Coverage no
  longer marks a subagent out of scope for a ZCode place.
- Close every `Unverified:` item from earlier Handoff entries with a test, or carry it into
  this unit's Handoff.
- Fix a failing scenario in this unit when the fix is small; otherwise mark the unit blocked
  with the reason.
Verify: `./gradlew :core:jvmTest :mcp:jvmTest :app:jvmTest`
Traps: a UI test that pins test tags may not name the ZCode row explicitly — extend the
existing agent list in the test model instead of writing a parallel fixture.
Depends: P004.1.1, P004.2.1
Model: strong
Commit: P004.3.1: accept zcode subagent support
Handoff:
- Coverage agent columns only expand when the group holds more than one place — the ZCode coverage test keeps Pi beside it for the out-of-scope contrast
- Unverified: nothing — all four Goal claims covered by tests and the full gate (core, mcp, app) ran green
