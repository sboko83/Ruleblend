---
id: P005
title: Pi subagent support
type: feature
status: active
created: 2026-10-18
gate: ./gradlew :core:jvmTest :mcp:jvmTest :app:jvmTest
---
## Goal

A Library subagent installs into Pi the way it installs into Claude Code: Pi gets its own variant
fields, `~/.pi/agent/agents/<id>.md` and `<project>/.pi/agents/<id>.md` are first-class install
destinations with fingerprinted ownership, Git import recognizes `.pi/agents`, and Settings, Place,
Coverage and the MCP tools report Pi as a supported subagent target. Pi itself loads those files
only through a subagents extension; Ruleblend documents that requirement instead of gating on it.

## Context

Format research, done 2026-10-18 against the installed Pi 1.3.0 (npm latest, released 2026-10-15)
and the two leading extension packages, supersedes the "no subagent feature at all" note in the
architecture snapshot.

Pi has no built-in subagents: the word appears nowhere in its docs or code, and pi.dev states Pi
"skips features like sub-agents" — they arrive through extension packages. The de-facto standard is
`pi-subagents` by nicobailon (~583k npm downloads/month; `@tintinweb/pi-subagents` is second at
~35k). Both packages converge on the same file surface: one subagent per Markdown file, YAML
frontmatter on top and the body as the system prompt. User scope is
`$PI_CODING_AGENT_DIR/agents/**/*.md` (default `~/.pi/agent/agents/`), project scope is
`.pi/agents/**/*.md`, read only after project trust; both packages also read the shared
`.agents/agents/` root. The agent name comes from frontmatter `name`, defaulting to the filename,
and a project file shadows a same-name user file.

Frontmatter common to both packages: `description`, `model` (fuzzy aliases such as haiku or sonnet
accepted), `thinking` (Pi's levels: off, minimal, low, medium, high, xhigh, max) and `tools`
(comma-separated built-in list). Package-specific extras differ (`color`, `max_turns` in tintinweb;
`excludeTools`, `aliases`, timeouts in nicobailon) and survive as unmodelled fields.

Definition files are inert until the user installs one of the extensions
(`pi install npm:pi-subagents`); Ruleblend writes them either way.

- docs/ARCHITECTURE.md:L744-860
- docs/archive/P004-zcode-subagents.md
- core/src/jvmMain/kotlin/dev/ruleblend/core/integration/SubagentFormat.kt
- core/src/jvmMain/kotlin/dev/ruleblend/core/integration/AgentAdapter.kt

## Constraints

- `PiSubagentFormat` reuses `MarkdownSubagentFormat` and declares only `model`
  (MODEL_SUGGESTIONS), `tools` (LIST) and `thinking` (TEXT; suggestions
  `off, minimal, low, medium, high, xhigh, max`). Every other field stays unmodelled: foreign
  fields survive a parse→render round trip through the extra-fields mechanism.
- The global directory is `paths.pi.resolve("agents")`, so `PI_CODING_AGENT_DIR` keeps relocating
  it together with rules, MCP and skills; the project directory is `<project>/.pi/agents`.
- Installs go through the existing `SubagentInstallService` (atomic write, fingerprint from
  `render` bytes); no new writer or ownership scheme.
- Never write the shared `.agents/agents/` root: both extensions read it, and Ruleblend's rule is
  each agent's own root only.
- `LEGACY_VARIANT_AGENT_IDS` in core/src/commonMain/kotlin/dev/ruleblend/core/storage/BlockFile.kt
  stays frozen at claude-code/codex/kimi-code: a pre-variants `model` must not leak onto Pi.
- Raise `appBuild` by one and add the CHANGELOG bullet in the unit that ships the change; bump the
  bundled SKILL.md marker (`v13` → `v14`) in the same edit that touches its text.

## Traps

- P004 (ZCode subagents, queued ahead of this plan) rewrites the same unsupported-target tests
  this plan touches: `SubagentInstallServiceTest` and `RuleblendToolsTest`'s
  `ZCode and Pi remain unsupported subagent targets`. After P004 those tests list only Pi; if it
  has not run they still list ZCode and Pi — in both states this plan replaces the Pi half with a
  Pi install case and leaves the ZCode half as the running tree has it.
- pi-subagents discovers nested `agents/**/*.md`; Ruleblend lists direct children only. A foreign
  nested file stays undiscovered — the same limitation every other agent has, not a bug to fix
  here.
- `SubagentVariantsTest` drives the real `PiAdapter` into `subagentAgentFields()`; deferring its
  count assertion to a later phase turns the gate red in phase 1.

## Decisions

- Unconditional SUPPORTED, no gating on the extension — the surface is a plain file tree (unlike
  ZCode's session store), and support that flickers with `~/.pi/agent/settings.json` packages would
  make installs unstable. The requirement is documented in ARCHITECTURE.md and the bundled SKILL.md
  instead. Rejected: a new `CapabilityLimit` value or an adapter check for the installed package.
- Declare only `model`/`tools`/`thinking` — the intersection of both leading packages plus Pi's own
  `--thinking` vocabulary; `color` (tintinweb only) and `excludeTools`/`aliases` (nicobailon only)
  stay extras. Rejected: a wider per-package field set, because the packages are interchangeable
  readers of one directory and the editor must not promise a knob one of them ignores.
- nicobailon's `pi-subagents` is the reference implementation by adoption (~583k vs ~35k
  downloads/month); the declared fields stay within the intersection so files load under either.
- Rebased the three collateral tests (RuleblendToolsTest x2, ProjectToolsTest) onto the post-flip reality instead of keeping a synthetic unsupported agent — no real adapter lacks subagent support anymore, so an unknown id is the only honest refusal case left

## Phase 1: Core support

### [x] P005.1.1 Pi adapter, format and tests
Read:
- core/src/jvmMain/kotlin/dev/ruleblend/core/integration/SubagentFormat.kt:L60-120
- core/src/jvmMain/kotlin/dev/ruleblend/core/integration/AgentAdapter.kt:L187-209
- core/src/jvmTest/kotlin/dev/ruleblend/core/integration/AgentAdapterTest.kt:L82-107
- core/src/jvmTest/kotlin/dev/ruleblend/core/integration/SubagentFormatTest.kt:L29-90
- core/src/jvmTest/kotlin/dev/ruleblend/core/integration/SubagentInstallServiceTest.kt:L160-180
- mcp/src/jvmTest/kotlin/dev/ruleblend/mcp/RuleblendToolsTest.kt:L830-850
- app/src/jvmTest/kotlin/dev/ruleblend/app/library/SubagentVariantsTest.kt:L86-96
Do:
- Add `PiSubagentFormat` beside the Kimi one: `SubagentFormat by MarkdownSubagentFormat(...)`
  with `model` (MODEL_SUGGESTIONS), `tools` (LIST) and `thinking` (TEXT; suggestions
  `off, minimal, low, medium, high, xhigh, max`).
- In `PiAdapter` override `subagentSupport`, `subagentFormat`, `globalSubagentDirectory()` to
  `paths.pi.resolve("agents")` and `projectSubagentDirectory(projectDir)` to
  `<project>/.pi/agents`.
- Update the Pi block in `AgentAdapterTest.adaptersUseTheirOwnFileNames`: expect `SUPPORTED`,
  `globalSubagentFile("review")` at `~/.pi/agent/agents/review.md` and the project file at
  `.pi/agents/review.md`; add a `capabilities().subagents` SUPPORTED assertion for Pi in
  `capabilityMatrixNamesProjectCodexSubagentsAndUnsupportedAgents`.
- Add render and parse tests for `PiSubagentFormat` in `SubagentFormatTest`: a
  model+tools+thinking render, and a parse that keeps an unknown `color` and `excludeTools` as
  extra fields.
- In `SubagentInstallServiceTest` replace the Pi target in the unsupported-target test with a Pi
  global-target install assertion (written bytes, SYNCED status); the ZCode target stays as the
  running tree has it.
- In `RuleblendToolsTest` drop the Pi half of `ZCode and Pi remain unsupported subagent targets`
  and add a Pi install case asserting `~/.pi/agent/agents/<id>.md` exists with no
  `skipped_unsupported_subagents` key.
- In `SubagentVariantsTest.each assistant shows the fields its own definition file understands`
  replace the `pi/model` `assertCountEquals(0)` with a present-but-empty assertion and reword the
  "Pi hosts no subagents at all" comment.
Verify: `./gradlew :core:jvmTest :mcp:jvmTest :app:jvmTest`
Traps: `thinking` suggestions are Pi CLI levels, not Claude's — copy the list from Constraints;
the fake home in `RuleblendToolsTest` already contains `.pi/agent`, so the new install path
writes there for real; an extension need not be installed for any of these tests — the adapter
flip alone is what is under test.
Commit: P005.1.1: add pi subagent support
Handoff:
- After the adapter flip no adapter remains without subagent support: the refusal branch in RuleblendTools.subagentVariants now fires only on an agent id outside the supported list; the old 'assistant without subagent support' test is now 'a variant for an unknown agent id is refused'
- The createSubagent/updateSubagent 'model' shortcut now also writes a pi variant — both variant-map expectations in RuleblendToolsTest include pi
- ProjectToolsTest 'profile writes report scope' case: the Pi profile attach now installs the subagent into .pi/agents/agent.md instead of skipping it as unsupported; the case asserts the written file

## Phase 2: Import, docs and version

### [x] P005.2.1 Git import, architecture docs, version
Read:
- core/src/jvmMain/kotlin/dev/ruleblend/core/storage/GitSubagentDiscovery.kt:L45-140
- core/src/jvmTest/kotlin/dev/ruleblend/core/storage/GitRepositoryImporterTest.kt:L55-80
- docs/ARCHITECTURE.md:L744-860
- docs/ARCHITECTURE.md:L1380-1390
- docs/ru/ARCHITECTURE.md:L750-860
- mcp/src/jvmMain/resources/dev/ruleblend/mcp/skill/SKILL.md:L28-34
- README.md:L28-32
- README.ru.md:L28-32
- CHANGELOG.md:L1-20
- app/build.gradle.kts:L14-18
Do:
- In `GitSubagentDiscovery` add `GitAgentRoot(".pi/agents", "pi")` and map `"pi"` to
  `PiSubagentFormat` in `MARKDOWN_FORMATS`.
- Extend the importer test fixture with `.pi/agents/reviewer.md` and add `"pi"` to the expected
  source assistants.
- Update `docs/ARCHITECTURE.md`: refresh the Pi snapshot row (dated 2026-10-18: subagents through
  the pi-subagents extension family — `~/.pi/agent/agents/*.md` and `<project>/.pi/agents/*.md`,
  Markdown frontmatter, files inert without the extension), rewrite the closing paragraph of
  *Subagent formats* so Pi's UNSUPPORTED reason becomes the extension requirement, flip the Pi
  capability-matrix row to ✓, and drop Pi from the "no destination" sentences in *Subagent
  ownership and drift*, *Subagent Library UI* and the MCP facade paragraph.
- Mirror every one of those edits in `docs/ru/ARCHITECTURE.md`.
- In the bundled `SKILL.md` rewrite the "Subagents install for …" sentence to list exactly the
  assistants whose adapters report SUPPORTED in the running tree, stating Pi's extension
  requirement, and bump the trailing `ruleblend-managed skill` marker from v13 to v14.
- Update the subagent-format sentence in `README.md` and its Russian mirror in `README.ru.md` the
  same way.
- Add the CHANGELOG bullet under [Unreleased] and raise `appBuild` by one.
Verify: `./gradlew :core:jvmTest`
Traps: the Russian mirrors keep their own wording — translate the meaning of each changed
sentence, do not patch byte offsets; the SKILL.md sentence may still name ZCode as unsupported
when P004 has not run — the adapter list is the truth, not the sentence's current shape; the
bundled SKILL.md marker is the file's last line, and forgetting the bump makes every connected
assistant report the skill as out of date; find the MCP facade paragraph by its "fallback
definition path" claim, not by line number, in the Russian file.
Model: fast
Commit: P005.2.1: document pi subagent format
Handoff:
- The extension requirement is documented in five doc spots and the bundled SKILL.md: snapshot table row, Subagent formats closing paragraph, capability matrix, the dropped 'no destination' sentences in ownership/Library-UI/MCP-facade — both language versions
- SKILL.md marker is now v14 with its digest repinned in BundledSkillTest; appBuild raised to 295
- A Markdown file in a hint-less Git directory (categories/, agents/, subagents/) now also parses as Pi: the importer and preview assistant lists include pi

## Phase 3: Acceptance

### [x] P005.3.1 Acceptance
Read:
- app/src/jvmTest/kotlin/dev/ruleblend/app/library/SubagentVariantsTest.kt:L1-115
- app/src/jvmTest/kotlin/dev/ruleblend/app/settings/SettingsScreenTest.kt:L378-396
- app/src/jvmTest/kotlin/dev/ruleblend/app/place/PlaceFilesTest.kt:L40-105
- app/src/jvmTest/kotlin/dev/ruleblend/app/coverage/CoverageMatrixTest.kt:L149-170
Do:
- Cover each Goal claim with a test: the subagent editor shows a Pi field section with
  model/tools/thinking; the Settings Pi row renders the Subagents capability as supported; a
  project target enabled for Pi lists `.pi/agents` as a subagent destination on the Place screen;
  Coverage no longer marks a subagent out of scope for a Pi place (flip the fake Pi agent's
  `supportsSubagents` flag and assert the cell is no longer out of scope).
- Close every `Unverified:` item from earlier Handoff entries with a test, or carry it into
  this unit's Handoff.
- Fix a failing scenario in this unit when the fix is small; otherwise mark the unit blocked
  with the reason.
Verify: `./gradlew :core:jvmTest :mcp:jvmTest :app:jvmTest`
Traps: a UI test that pins test tags may not name the Pi row explicitly — extend the
existing agent list in the test model instead of writing a parallel fixture.
Depends: P005.1.1, P005.2.1
Model: strong
Commit: P005.3.1: accept pi subagent support
Handoff:
- Acceptance closed with no Unverified items: no earlier handoff carried one and this unit's scenarios are all test-covered
- Place coverage reuses the ZCode test extended with Pi in the same ProjectTarget (titles .zcode/agents/ then .pi/agents/); Coverage has a Pi mirror of the ZCode place test taking supportsSubagents from the real adapter via appliesTo
