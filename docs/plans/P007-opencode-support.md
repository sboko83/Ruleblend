---
id: P007
title: OpenCode support
type: feature
status: active
created: 2026-10-30
permissions: auto
gate: ./gradlew -PwarningsAsErrors=true :core:allTests :mcp:allTests :app:allTests
executor: pi | strata/swift-1.5-iq3_s | medium
fallback: pi | opencode-go/deepseek-v4.1-flash | low; pi | zai/glm-5.3-flash | high; codex | gpt-6-luna | medium
replan: pi | opencode-go/deepseek-v4.1-flash | max; codex | gpt-6.1-sol | medium
cheap: pi | strata/swift-1.5-iq3_s | low; pi | opencode-go/deepseek-v4.1-flash | low; pi | zai/glm-5.3-flash | low; codex | gpt-6-luna | low
heavy: pi | opencode-go/deepseek-v4.1-flash | max; pi | zai/glm-5.3 | high; codex | gpt-6.1-sol | medium
acceptance: pi | opencode-go/deepseek-v4.1-flash | max; codex | gpt-6.1-sol | medium
---
## Goal

OpenCode v2 is a first-class assistant: Ruleblend detects it, installs rules, MCP servers, skills
and subagents into its native global and project locations, connects its own MCP server, imports
`.opencode/agents` from Git, and shows it in Settings, Place, Coverage and the bundled skill.

## Context

OpenCode v2.2.3 is installed on the development machine. The integration targets v2 native
formats. Its global config root is `$OPENCODE_CONFIG_DIR`, else `$XDG_CONFIG_HOME/opencode`, else
`~/.config/opencode`; the project config is `opencode.json` in the project root, merged with a
sibling `opencode.jsonc`. Rules are `AGENTS.md` globally and per project; skills are `skills/` in
the global config root and `.opencode/skills/` in a project; subagents are `agents/<id>.md` and
`.opencode/agents/<id>.md`.

OpenCode v2 MCP servers live under `mcp.servers.<name>`: local entries use
`{"type":"local","command":[binary, ...args],"environment":{...}}`, remote entries use
`{"type":"remote","url":...,"headers":{...}}`; `mcp.timeout` is a sibling of `servers`. The v1
flat `mcp.<name>` shape is out of scope. A Markdown subagent has `description`, `mode: subagent`,
an optional `model` in `provider/model[#variant]` form and an optional hex `color`; the filename is
the agent id, `name` is not a supported field, and the body becomes the system prompt.

- docs/ARCHITECTURE.md#agent-adapters
- docs/ARCHITECTURE.md#skill-installation
- docs/ARCHITECTURE.md#mcp-blocks
- core/src/jvmMain/kotlin/dev/ruleblend/core/integration/AgentAdapter.kt
- mcp/src/jvmMain/kotlin/dev/ruleblend/mcp/McpConnector.kt
- mcp/src/jvmMain/kotlin/dev/ruleblend/mcp/install/McpAgentInstaller.kt
- mcp/src/jvmMain/kotlin/dev/ruleblend/mcp/skill/FileSkillInstaller.kt

## Constraints

- Target OpenCode v2 only. Do not write the v1 flat `mcp.<name>` shape or the v1 `enabled` field.
- The global MCP config file is `<root>/opencode.json`. Never parse or rewrite an existing
  `opencode.jsonc`: OpenCode merges both, and the existing JSON helpers cannot preserve JSONC
  comments. Preserve every key outside the edited `mcp.servers` entry.
- Keep the module layering: core has no dependency on mcp or app; mcp is a facade over core; app
  holds UI only.
- All file writes stay atomic and only touch Ruleblend-owned entries. Foreign servers, skill
  directories and subagent files stay untouched.
- Raise `appBuild` by one exactly once, in P007.3.1; never raise `appVersion`.
- Do not bump `MCP_ENTRY_VERSION`: the registration content for existing agents does not change.
- Changing the bundled `SKILL.md` requires raising its marker from v14 to v15.
- Project docs stay in English; `README.ru.md` and `docs/ru/ARCHITECTURE.md` stay Russian.
- Unit tests must not depend on a live OpenCode binary. Only acceptance may use the installed CLI.

## Traps

- The default OpenCode documentation describes v1. v2 is the target: MCP is `mcp.servers`,
  servers use `disabled` rather than `enabled`, and config files may be JSONC.
- `OPENCODE_CONFIG_DIR` replaces the whole config directory; a path under it must not get another
  `opencode` segment. `XDG_CONFIG_HOME` is a base directory, so `opencode` is appended to it.
- OpenCode derives the subagent id from the filename. Writing `name` produces an unsupported field
  warning, and omitting `mode: subagent` makes the agent primary.
- OpenCode's `permissions` is a nested list, not a flat string. Do not try to carry it through the
  flat `SubagentVariant`; preserve only flat fields.
- `mcp.timeout` and `mcp.servers` are siblings. Never enumerate `timeout` as a server and never
  replace the whole `mcp` object.
- An existing `opencode.jsonc` may hold comments. Do not touch it; write `opencode.json`.
- OpenCode also reads `~/.claude/skills` and `.agents/skills`. Ruleblend writes only OpenCode's own
  roots, never the shared trees.
- The UI e2e sandbox sets `XDG_CONFIG_HOME` to `<root>/home/.config`; the snack home must contain
  `.config/opencode` before the app starts, or the adapter is not detected.
- `docs/docflow.md` is in format 2 while the installed docflow expects 3. Run `docflow migrate`
  before `docflow new`.
- The Gradle daemon needs the configured 3 GB heap; do not lower `kotlin.daemon.jvmargs`.
- The base commit fails the plan gate: `app/src/jvmMain/kotlin/dev/ruleblend/app/assistance/
  PtyAssistanceTerminal.kt` overrides the JediTerm 3.76 deprecated `UserSettingsProvider.
  getDefaultStyle()`, which `-Werror` turns into an `:app:compileKotlinJvm` failure whenever the app
  is recompiled. P007.1.1 clears it; until it is committed, the gate fails for every unit.

## Decisions

- Target native OpenCode v2 (`mcp.servers`) — the installed 2.2.3 and current v2 docs use it —
  rejected: the v1-compatible flat `mcp.<name>`, which is deprecated and can collide with the
  `servers` and `timeout` keys.
- Write `opencode.json`, never edit `opencode.jsonc` — OpenCode merges both and the existing JSON
  writer cannot preserve JSONC comments — rejected: detecting and rewriting the JSONC file.
- Recognize `.opencode/agents` in Git import, but keep OpenCode out of the ambiguous generic
  Markdown formats — parity with the other agents without making every foreign Markdown file
  ambiguous — rejected: adding OpenCode to the generic Markdown map.
- Resolve the global root in OpenCode's own order: `OPENCODE_CONFIG_DIR`, then
  `XDG_CONFIG_HOME/opencode`, then `~/.config/opencode` — one rule shared by detection, rules,
  MCP, skills and subagents.
- No `MCP_ENTRY_VERSION` bump; the bundled skill version goes from v14 to v15 because its text
  changes.
- Clear the pre-existing `-Werror` failure inside P007.1.1, not in a unit of its own: a fresh unit
  gets the next id but runs last in Phase 1, while the runner always resumes the `[~]` unit first, so
  a separate prerequisite would never run before the unit that needs it — rejected: a new
  `P007.1.4` prerequisite placed before P007.1.1.
- Fix the terminal by overriding `JediTermWidget.createDefaultStyle()` and dropping the deprecated
  `getDefaultStyle()` override — `createDefaultStyle()` is the supported hook and the default style
  must keep the theme colors — rejected: `@Suppress`/`@Deprecated` on the override, which keeps the
  deprecated call and breaks when jediterm removes the method.
- P007.1.1 touches shipped app code without raising `appBuild`: the single bump stays in P007.3.1
  and both changes ship in the same release.
- Replan changed check: P007.1.1 Verify: `./gradlew -PwarningsAsErrors=true :core:allTests` -> `./gradlew -PwarningsAsErrors=true :core:allTests :mcp:allTests :app:allTests`
- OpenCode parse выбрасывает name так же, как mode — иначе распарсенное значение попадает в SubagentVariant и показывается редактором как настраиваемое поле, которое OpenCode не читает и рендер не пишет — отклонено: хранить name как необъявленное плоское поле.
- Стиль темы читается в createDefaultStyle() через поле mySettingsProvider суперкласса — супер-конструктор вызывает хук раньше, чем присваиваются поля подкласса, поэтому захваченный в поле цвет был бы null — отклонено: мутировать StyleState.setDefaultStyle() после конструирования и статический/ThreadLocal держатель.
- Resolve XDG_CONFIG_HOME through the existing override() helper and append opencode to it, while OPENCODE_CONFIG_DIR is used verbatim — one shared validation path keeps WSL/relative rejection and its one-time stderr report — rejected: a separate silent parse of XDG_CONFIG_HOME that could leave a second, inconsistent rejection rule.
- Always overwrite draft.name with the file stem instead of only when the parsed name is null — OpenCodeSubagentFormat.parse strips name unconditionally, so a null-check would be dead code — rejected: conditional assignment that implies a frontmatter name could ever be preserved.

## Phase 1: Core surfaces

### [x] P007.1.1 Add OpenCode subagent format
Read:
- core/src/jvmMain/kotlin/dev/ruleblend/core/integration/SubagentFormat.kt:L1-260
- core/src/commonMain/kotlin/dev/ruleblend/core/model/YamlFrontmatter.kt:L1-120
- core/src/jvmTest/kotlin/dev/ruleblend/core/integration/SubagentFormatTest.kt:L1-160
- app/src/jvmMain/kotlin/dev/ruleblend/app/assistance/PtyAssistanceTerminal.kt:L1-75
- app/src/jvmMain/kotlin/dev/ruleblend/app/assistance/AssistanceTerminalColors.kt:L55-72
- app/src/jvmTest/kotlin/dev/ruleblend/app/assistance/AssistanceTerminalColorsTest.kt:L1-20
Do:
- Add `OpenCodeSubagentFormat` with its own Markdown implementation: render `description`, a fixed
  `mode: subagent`, declared flat fields `model` and `color`, then the body; never write `name`.
- Parse YAML frontmatter without requiring `name`; take `description` and the body, canonicalize
  `model` and `color`, drop `mode`, and keep other flat scalar or scalar-list fields under their
  native keys. Ignore nested values instead of flattening them.
- Leave the shared `MarkdownSubagentFormat` unchanged for Claude Code, Kimi Code, ZCode and Pi.
- Add tests: rendered text has no `name`, has `mode: subagent` and the body; parsing a rendered
  definition round-trips; an unknown flat field survives; a nested `permissions` value does not
  throw.
- Clear the gate failure that blocked this unit, in the same commit: add
  `internal fun AssistanceTerminalColors.styleState()` next to `defaultStyle()` carrying the same
  `TextStyle` as the `StyleState` default, create the widget in `PtyAssistanceTerminal.kt` through a
  private `JediTermWidget` subclass that overrides the protected `createDefaultStyle()` with it, drop
  the deprecated `getDefaultStyle()` override from the anonymous `DefaultSettingsProvider` while
  keeping `getDefaultForeground()`/`getDefaultBackground()`, and assert in
  `AssistanceTerminalColorsTest` that `styleState()` holds the theme foreground and background as
  `TerminalColor.rgb(...)` values.
Verify: `./gradlew -PwarningsAsErrors=true :core:allTests :mcp:allTests :app:allTests`
Traps: OpenCode v2 treats the body as `system`, and `mode` is a format-owned constant, not a
variant field; a foreign `mode: primary` must not leak into the rendered file. The terminal fix is
not optional and not a plain deletion: `JediTerminal` calls `StyleState.reset()` on SGR 0
(jediterm-core `JediTerminal.java`, `resetAttributes` and `reset`), so the terminal's default style
comes from `JediTermWidget.createDefaultStyle()`, and the inherited
`UserSettingsProvider.getDefaultStyle()` is black-on-white; text after a reset would lose the theme
colors. `getDefaultForeground()`/`getDefaultBackground()` only feed
`TerminalPanel.getWindowForeground()`/`getWindowBackground()`. Do not silence the diagnostic with
`@Suppress` or `@Deprecated`, and do not raise `appBuild` here: P007.3.1 raises it once.
Role: heavy
Commit: P007.1.1: add OpenCode subagent format
Handoff:
- Blocked (plan): Unit work is complete and its own Verify passed (`./gradlew -PwarningsAsErrors=true :core:allTests`, SubagentFormatTest 21 tests / 0 failures, 4 new OpenCode tests). The plan's gate `./gradlew -PwarningsAsErrors=true :core:allTests :mcp:allTests :app:allTests` fails at :app:compileKotlinJvm on a pre-existing -Werror diagnostic unrelated to this unit: app/src/jvmMain/kotlin/dev/ruleblend/app/assistance/PtyAssistanceTerminal.kt:62:30 overrides UserSettingsProvider.getDefaultStyle(), deprecated in jediterm-ui 3.76; reproduced on clean HEAD 010e9e4 in a detached worktree with the same command. The plan needs a fix: a prerequisite unit dropping that override (the supported getDefaultForeground/getDefaultBackground pair is already overridden on lines 60-61) and checking terminal colors, or an explicit decision on the gate. Until then the gate fails for every P007 unit whose core change forces app recompilation. Replan: a separate prerequisite unit cannot run first, so this unit now carries the terminal fix in its `Do:`; deleting the override alone is rejected — `createDefaultStyle()` carries the terminal default style (see `## Decisions`).
- OpenCodeSubagentFormat живёт в core, но пока ни к одному адаптеру не привязан (subagentFormat в OpenCode-адаптере и импорт .opencode/agents — более поздние юниты) и в generic Markdown-мапу добавлять его не нужно.
- Терминальный гейт закрыт: в app/src/jvmMain/kotlin/dev/ruleblend/app/assistance/PtyAssistanceTerminal.kt теперь приватные AssistanceTerminalSettings и AssistanceTerminalWidget; переопределять deprecated UserSettingsProvider.getDefaultStyle() больше нельзя, стиль идёт через createDefaultStyle().
- JediTermWidget вызывает createDefaultStyle() из своего конструктора до присваивания полей подкласса, поэтому цвета читаются обратно через защищённое mySettingsProvider, а не через поле виджета; перенос цветов в поле виджета сломает стиль (null).
- OpenCode-парсер выбрасывает и mode, и name: иначе чужой name стал бы фантомным тюнаблом в SubagentVariantsForm (форма показывает необъявленные поля варианта), а записан он всё равно не был бы.
- appBuild всё ещё не поднят — единственный бамп остаётся в P007.3.1.
- Unverified: Виджет не покрыт постоянным тестом: цвета после SGR-сброса у AssistanceTerminalWidget.createDefaultStyle() подтверждены только разовым тестом на реальном виджете, который удалён; регрессию в этом переопределении Verify (проверяет лишь styleState()) не поймает.
- Unverified: Визуальный результат в живом приложении (цвет текста после сброса на реальной сессии PTY) не наблюдался — проверено только на уровне StyleState, скриншот/UI-прогон не делался.

### [x] P007.1.2 Add OpenCode adapter and config root
Read:
- core/src/jvmMain/kotlin/dev/ruleblend/core/integration/AssistantPaths.kt:L1-80
- core/src/jvmMain/kotlin/dev/ruleblend/core/integration/AgentAdapter.kt:L1-260
- core/src/jvmTest/kotlin/dev/ruleblend/core/integration/AgentAdapterTest.kt:L1-130
- core/src/jvmTest/kotlin/dev/ruleblend/core/integration/AssistantPathsTest.kt:L1-80
Do:
- Add `openCode` to `AssistantPaths`: `OPENCODE_CONFIG_DIR` as the full config directory, else
  `XDG_CONFIG_HOME/opencode`, else `~/.config/opencode`; reuse the existing absolute-path and WSL
  rejection.
- Add `OpenCodeAdapter`: id `opencode`, name `OpenCode`, CLI `opencode` with `--continue`,
  global file `AGENTS.md` under the config root, project file `AGENTS.md` with no redirect, global
  skills `skills`, project skills `.opencode/skills`, global subagents `agents`, project subagents
  `.opencode/agents`, `SubagentSupport.SUPPORTED` and `OpenCodeSubagentFormat`.
- Add it to `shippedAgentAdapters()` after `CodexAdapter`.
- Add tests for detection by the config directory only, every destination, and the override
  precedence: `OPENCODE_CONFIG_DIR` wins over `XDG_CONFIG_HOME`, which wins over the default.
Verify: `./gradlew -PwarningsAsErrors=true :core:allTests`
Traps: do not append `opencode` to `OPENCODE_CONFIG_DIR`; OpenCode reads `AGENTS.md` directly in a
project, so no `CLAUDE.md`-style pointer is needed. The format object comes from P007.1.1.
Role: cheap
Commit: P007.1.2: add OpenCode adapter
Handoff:
- AssistantPaths.openCode is now the single global root for OpenCode: it resolves OPENCODE_CONFIG_DIR (full dir), else XDG_CONFIG_HOME/opencode, else ~/.config/opencode, through the same override() validation as the other assistants, so later MCP/skill/subagent units must read it rather than reimplementing the order.
- OpenCodeAdapter is inserted into shippedAgentAdapters() right after CodexAdapter and uses AssistantPaths.forHome(home) with a nullable home, so a UI/e2e process with XDG_CONFIG_HOME set is detected without touching the code.
- The adapter points subagentFormat at OpenCodeSubagentFormat from P007.1.1, so the format is now bound to an adapter; subagent read/write and .opencode/agents import remain for their own units.
- OpenCode detection is purely by the config directory existing (no CLI probe, no AGENTS.md requirement), matching the other adapters.
- Unverified: No test asserts that shippedAgentAdapters() contains OpenCode in the expected position; a wrong order or a forgotten list entry would only show up in the Settings/Place/Coverage UI, which this unit does not exercise.
- Unverified: No test covers an invalid OPENCODE_CONFIG_DIR or XDG_CONFIG_HOME falling back to ~/.config/opencode and being recorded in rejectedOverrides; only the precedence of valid values is tested.
- Unverified: No test covers the OpenCode CLI descriptor being usable for launching/resuming a real OpenCode session (`opencode --continue`); unit tests deliberately avoid a live binary and acceptance is a later unit.

### [x] P007.1.3 Import OpenCode subagents from Git
Read:
- core/src/jvmMain/kotlin/dev/ruleblend/core/storage/GitSubagentDiscovery.kt:L1-170
- core/src/jvmTest/kotlin/dev/ruleblend/core/storage/GitRepositoryImporterTest.kt:L55-110
Do:
- Add `.opencode/agents` to the declared roots with assistant `opencode`.
- For the `opencode` hint, parse with `OpenCodeSubagentFormat`, derive the name from the file stem
  when the frontmatter has no `name`, and bypass the strict generic Markdown field validator.
  Add the candidate as `mapOf("opencode" to draft)` with `sourceAssistant = "opencode"`.
- Keep the generic `agents/` collections and the ambiguous Markdown map unchanged; OpenCode is not
  added there.
- Add a test: `.opencode/agents/review.md` without a `name` becomes a candidate named `review`;
  a README under the same directory stays excluded.
Verify: `./gradlew -PwarningsAsErrors=true :core:allTests`
Traps: `looksLikeMarkdownAgent` requires `name`, which OpenCode files do not have; bypass it only
for the explicit `.opencode/agents` hint. Do not relax validation for arbitrary Markdown.
Role: cheap
Commit: P007.1.3: import OpenCode subagents
Handoff:
- GitSubagentDiscovery declares GitAgentRoot(".opencode/agents", "opencode") in the built-in root list, so imports from that directory arrive with sourceAssistant == "opencode" and a single drafts["opencode"] entry; later subagent-import/read units must key off that id rather than re-deriving the path.
- The OpenCode branch sits before the generic Markdown branch and bypasses looksLikeMarkdownAgent/validateMarkdownFields entirely; any future validation change to the generic branch does not reach OpenCode files, and a parity change must be made in both places.
- The candidate name is always overwritten with the file stem because OpenCodeSubagentFormat.parse (P007.1.1) drops both name and mode — a frontmatter name can never survive into the draft, so no editing UI will show it as configurable.
- Unverified: A `.toml` file under `.opencode/agents` now surfaces a per-file import error (the TOML branch requires the codex hint) instead of being ignored; no test covers a foreign repo with TOML in that directory, and Verify only runs the fixture-based suite.
- Unverified: Nested OpenCode agents (`.opencode/agents/<sub>/x.md`) are matched by the prefix rule but untested; only the flat `review.md` case is asserted.

## Phase 2: MCP integration

### [ ] P007.2.1 Install OpenCode MCP servers
Read:
- mcp/src/jvmMain/kotlin/dev/ruleblend/mcp/install/McpServersJson.kt:L1-130
- mcp/src/jvmMain/kotlin/dev/ruleblend/mcp/install/ZCodeMcpJson.kt:L1-80
- mcp/src/jvmMain/kotlin/dev/ruleblend/mcp/install/McpAgentInstaller.kt:L1-120
- mcp/src/jvmTest/kotlin/dev/ruleblend/mcp/install/McpInstallServiceTest.kt:L1-120
Do:
- Add `OpenCodeMcpJson`: list, read, upsert and remove entries only under `mcp.servers`, preserving
  `mcp.timeout` and every other root key.
- Render local entries as `type: local`, `command` as the executable followed by its arguments,
  and `environment`; render remote entries as `type: remote`, `url` and `headers`. Do not write
  `enabled` or `disabled`.
- Parse both shapes back into `McpServerConfig`; the command array maps to `command` plus `args`,
  and `environment` maps to `env`.
- Add `OpenCodeMcpInstaller`: global `<root>/opencode.json`, project `<project>/opencode.json`;
  add it to `shippedMcpInstallers()`.
- Add tests for local and remote round trips, `mcp.timeout` preservation, foreign server
  preservation, and install/remove at global and project scope.
Verify: `./gradlew -PwarningsAsErrors=true :mcp:allTests`
Traps: v2 place servers under `mcp.servers`; `timeout` must never appear as a server name. Keep the
existing JSON outside the edited entry intact. The entry is enabled by default, so omit the v1
`enabled` field.
Role: heavy
Commit: P007.2.1: install OpenCode MCP servers

### [ ] P007.2.2 Connect OpenCode and install its bundled skill
Read:
- mcp/src/jvmMain/kotlin/dev/ruleblend/mcp/McpConnector.kt:L1-140
- mcp/src/jvmMain/kotlin/dev/ruleblend/mcp/McpServersJsonConnector.kt:L1-120
- mcp/src/jvmMain/kotlin/dev/ruleblend/mcp/skill/ZCodeSkillInstaller.kt:L1-30
- mcp/src/jvmTest/kotlin/dev/ruleblend/mcp/McpConnectorTest.kt:L1-150
- mcp/src/jvmTest/kotlin/dev/ruleblend/mcp/skill/AdditionalSkillInstallersTest.kt:L1-120
Do:
- Add `OpenCodeConnector` using `OpenCodeMcpJson` at the global `<root>/opencode.json`, with
  register, unregister, status and installed-entry-version behavior modelled on `ZCodeConnector`.
- Add `OpenCodeSkillInstaller` writing `<root>/skills/ruleblend/SKILL.md`.
- Add tests for registration state transitions, foreign-entry refusal, preservation of foreign
  servers on unregister, and skill install/foreign/uninstall.
Verify: `./gradlew -PwarningsAsErrors=true :mcp:allTests`
Traps: ownership rides in `environment.RULEBLEND_ENTRY`; parsing the command array must yield
`args == ["--mcp"]` for the shared ownership check. Do not bump `MCP_ENTRY_VERSION`.
Role: cheap
Commit: P007.2.2: connect OpenCode

## Phase 3: App wiring and e2e sandbox

### [ ] P007.3.1 Wire OpenCode into the app and update the bundled skill
Read:
- app/src/jvmMain/kotlin/dev/ruleblend/app/Main.kt:L375-405
- app/src/jvmMain/kotlin/dev/ruleblend/app/library/LibraryDialogs.kt:L415-425
- app/src/jvmMain/kotlin/dev/ruleblend/app/i18n/Strings.kt:L1758-1766
- app/src/jvmMain/kotlin/dev/ruleblend/app/i18n/Strings.kt:L2785-2792
- mcp/src/jvmMain/resources/dev/ruleblend/mcp/skill/SKILL.md:L25-36
- app/build.gradle.kts:L14-17
Do:
- Add `OpenCodeConnector` and `OpenCodeSkillInstaller` to the connector and skill lists in `Main`.
- Add `opencode` to `gitAssistantName`.
- Add OpenCode to the `@import` hint in both languages.
- Add OpenCode to the supported-agent sentences in the bundled `SKILL.md` and raise its marker to
  v15.
- Raise `appBuild` from 299 to 300.
Verify: `./gradlew -PwarningsAsErrors=true :app:allTests :mcp:allTests`
Traps: changing `SKILL.md` requires the marker bump and makes connected assistants report a skill
update; that is expected. Do not raise `appVersion`. Do not bump the marker twice in later units.
Role: cheap
Commit: P007.3.1: wire OpenCode into the app

### [ ] P007.3.2 Cover OpenCode in UI e2e
Read:
- tools/ui_e2e.py:L80-120
- tools/ui_e2e.py:L285-300
- app/src/uiE2e/kotlin/dev/ruleblend/e2e/Phase1Test.kt:L305-335
- app/src/uiE2e/kotlin/dev/ruleblend/e2e/Phase1Test.kt:L590-625
- app/src/uiE2e/kotlin/dev/ruleblend/e2e/SandboxContractTest.kt:L45-60
Do:
- Create `<root>/home/.config/opencode` in the sandbox, add an `opencode` stub executable and add
  `.config/opencode` to the real-home fingerprint list.
- Extend the Phase 1 assertions to cover project `.opencode/skills/e2e-skill/SKILL.md`,
  `.opencode/agents/e2e-agent.md`, project `opencode.json`, global `.config/opencode/AGENTS.md`,
  global `opencode.json` and global `.config/opencode/skills` and `agents`.
- Keep the dynamic counts from `shippedAgentAdapters()` and `shippedMcpInstallers()`.
Verify: `./gradlew -PwarningsAsErrors=true :app:compileUiE2eKotlinJvm && python3 -B -m unittest discover -s tools -p 'test_*.py'`
Traps: `XDG_CONFIG_HOME` already points at `home/.config`; the `opencode` child must exist before
the app starts. The e2e gate must not require a real OpenCode installation.
Role: cheap
Commit: P007.3.2: cover OpenCode in UI e2e

## Phase 4: Documentation

### [ ] P007.4.1 Document OpenCode support
Read:
- docs/ARCHITECTURE.md:L714-840
- docs/ARCHITECTURE.md:L1460-1560
- README.md:L1-35
- README.ru.md:L1-35
- CHANGELOG.md:L1-20
Do:
- In both architecture files, add OpenCode to the terminology, the adapter table, the override
  table, the distribution snapshot, the subagent-format prose, the capability matrix, the skill
  roots and the MCP destination/helper lists.
- Record that the global config is `~/.config/opencode/opencode.json`, that MCP uses
  `mcp.servers`, and that a sibling `opencode.jsonc` is never rewritten.
- Add OpenCode to the supported-agent and Markdown-import lists in both README files.
- Add one user-facing `CHANGELOG.md` bullet under `[Unreleased] → Added`.
Verify: `grep -q 'OpenCode' docs/ARCHITECTURE.md && grep -q 'OpenCode' docs/ru/ARCHITECTURE.md && grep -q 'OpenCode' README.md && grep -q 'OpenCode' README.ru.md && grep -q 'OpenCode' CHANGELOG.md && docflow lint`
Traps: keep the English docs English and the `ru` copies Russian. Do not add a ROADMAP entry; the
ROADMAP holds open work only.
Role: cheap
Commit: P007.4.1: document OpenCode support

## Phase 5: Acceptance

### [ ] P007.5.1 Accept OpenCode end to end
Read:
- app/src/uiE2e/kotlin/dev/ruleblend/e2e/Phase1Test.kt:L300-340
- app/src/uiE2e/kotlin/dev/ruleblend/e2e/Phase1Test.kt:L580-625
- tools/ui_e2e.py:L80-120
- docs/UI_E2E.md:L100-170
Do:
- Run the complete unit suite.
- Run the Phase 1 UI e2e suite on a fresh sandbox and confirm that a rule, skill, subagent and MCP
  server reach every OpenCode global and project destination while foreign files stay untouched.
- From a temporary `XDG_CONFIG_HOME`, write the Ruleblend MCP entry and an OpenCode subagent
  through the fixtures, then run `opencode debug config` and `opencode agent list` with the
  installed OpenCode 2.2.3; confirm the `ruleblend` server and the agent are listed.
- Close every `Unverified:` item from earlier Handoffs by a test, an `Accepted:` note or a concrete
  failing scenario. Record the live CLI output in the Handoff.
Verify: `./gradlew -PwarningsAsErrors=true :core:allTests :mcp:allTests :app:allTests :app:compileUiE2eKotlinJvm && python3 -B tools/ui_e2e.py ui --tests '*Phase1Test*'`
Traps: the UI e2e runs only under its temporary root; never point it at the real home. The live
OpenCode check must use a temporary `XDG_CONFIG_HOME`, otherwise it reads the developer's own
config. If `opencode` is missing from PATH, block the unit as a human issue instead of skipping it
silently. The exact file shape is asserted by unit tests; the live run is a confirmation.
Depends: P007.1.1, P007.1.2, P007.1.3, P007.2.1, P007.2.2, P007.3.1, P007.3.2, P007.4.1
Role: acceptance
Commit: P007.5.1: accept OpenCode support
