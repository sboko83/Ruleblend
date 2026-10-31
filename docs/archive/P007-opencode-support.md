---
id: P007
title: OpenCode support
closed: 2026-10-31
---
## Outcome           <!-- 2–3 строки: что теперь есть -->
OpenCode v2 is a first-class assistant: detected by its config directory, and rules, MCP servers,
skills and subagents install into its global and project locations in its own v2 formats, its
Ruleblend MCP server connects from Settings, and Git import reads `.opencode/agents`.
Settings, Place and Coverage list it like every other shipped assistant, and the bundled skill
names it under marker v15. Nothing was left unverified: every earlier gap ended as a test or an
`Accepted:` note.

## Decisions         <!-- решение — почему — отвергнутая альтернатива -->
- Target native OpenCode v2 (`mcp.servers`) — the installed 2.2.3 and the current v2 docs use it —
  rejected: the v1-compatible flat `mcp.<name>`, which is deprecated and collides with the
  `servers` and `timeout` keys.
- Write `opencode.json`, never edit a sibling `opencode.jsonc` — OpenCode merges both and the JSON
  writer cannot preserve JSONC comments — rejected: detecting and rewriting the JSONC file.
- Resolve the global root in OpenCode's own order, `OPENCODE_CONFIG_DIR` verbatim, else
  `XDG_CONFIG_HOME/opencode`, else `~/.config/opencode`, through the shared `override()` validation
  — one rule shared by detection, rules, MCP, skills and subagents keeps the WSL and relative-path
  rejection and its one-time report — rejected: a separate silent parse of `XDG_CONFIG_HOME` that
  could leave a second, inconsistent rejection rule.
- A dedicated OpenCode Markdown format, out of the shared `MarkdownSubagentFormat` and out of the
  ambiguous generic Markdown import map, while `.opencode/agents` is still recognized by Git import
  — parity with the other agents without making every foreign Markdown file ambiguous — rejected:
  adding OpenCode to the generic Markdown map.
- The OpenCode parser drops both `name` and `mode`: `mode` is a format-owned constant, and a kept
  `name` would surface as a phantom tunable in the variant form that OpenCode never reads and the
  render never writes — rejected: holding `name` as an undeclared flat field.
- Git import always takes the OpenCode subagent name from the file stem — `parse` strips `name`
  unconditionally, so a null check would be dead code — rejected: a conditional assignment.
- `OpenCodeMcpJson` stays standalone instead of generalizing the `mcp.servers` envelope out of
  `ZCodeMcpJson` — the entry shapes already differ and touching ZCode would widen the change —
  rejected: a shared `McpSectionJson` helper.
- `parseEntry` reads only the v2 local and remote shapes, inferring the type when the discriminator
  is absent; a local entry needs a non-blank executable, so an empty `command` array parses as
  unparsable — a v1 flat string command shows up as an unknown entry instead of being reinterpreted
  as stdio, and an empty command never reads as a permanently modified entry — rejected: accepting
  the v1 string command and mapping an empty array to an empty command.
- No legacy-address cleanup in `OpenCodeConnector`, unlike `ZCodeConnector` — OpenCode shipped only
  the v2 native address, so a legacy branch would be untestable dead code — rejected: copying
  ZCode's kitbash retirement.
- Clear the pre-existing `-Werror` terminal failure inside P007.1.1 rather than in a unit of its own
  — a fresh unit gets the next id but runs last in its phase while the runner always resumes the
  `[~]` unit first, so a separate prerequisite would never run before the unit that needs it.
- Fix the terminal by overriding `JediTermWidget.createDefaultStyle()` and dropping the deprecated
  `getDefaultStyle()` override — `createDefaultStyle()` is the supported hook and carries the default
  style `JediTerminal` falls back to after an SGR reset — rejected: `@Suppress`/`@Deprecated`, which
  keeps the deprecated call and breaks when jediterm removes the method.
- Read the theme colors back through the superclass `mySettingsProvider` inside that hook — the
  superclass constructor calls the hook before subclass fields are assigned, so a captured field
  would be null — rejected: mutating `StyleState.setDefaultStyle()` after construction.
- OpenCode went into the existing shared connector tables (version stamp, Windows path and
  foreign-preservation, foreign same-name) instead of dedicated tests only — those tables are the
  regression net every format gets and the OpenCode shape fits them without special cases —
  rejected: dedicated-only coverage that would miss cross-format invariants.
- No `MCP_ENTRY_VERSION` bump, because the registration every connector writes does not change; the
  bundled skill marker goes from v14 to v15 because its text does, and `EXPECTED_DIGEST` was
  updated rather than relaxed — the digest is the guard that a changed `SKILL.md` ships under a new
  marker — rejected: stripping the digest assertion.
- The acceptance unit was replanned by hand: `Verify:` became `python3 -B tools/ui_e2e.py projects`
  and the live check became `opencode debug agents`, which a replan otherwise leaves alone on a
  `Role: acceptance` unit — the original command could never pass, because `Phase1Test` runs one
  method per sandbox and the broad `ui --tests '*Phase1Test*'` pattern piles all 12 into one home
  (3 passed, 9 failed on state left behind by other methods), while the live check named a command
  OpenCode 2.2.3 does not have — rejected: changing `tools/ui_e2e.py` so a broad pattern is split
  into per-scenario sandboxes, which would break the one-method-per-sandbox contract every suite is
  built on, and skipping the e2e step, which would drop the only check of the Goal.

## Gotchas           <!-- ловушки, актуальные после закрытия -->
- `OPENCODE_CONFIG_DIR` names the OpenCode config directory itself, so nothing is appended to it;
  `XDG_CONFIG_HOME` is a base directory, so `opencode` is appended. One resolver serves detection,
  rules, MCP, skills and subagents — never re-derive the order anywhere else.
- OpenCode v2 keeps MCP servers under `mcp.servers` with `mcp.timeout` as a sibling, and a sibling
  `opencode.jsonc` is merged by OpenCode but never read or written by Ruleblend: a server defined
  only there stays invisible in Place and Settings, and hand-editing it is the user's business.
- The OpenCode subagent id is the filename. `name` and `mode` are not writable fields, and nested
  values such as `permissions` are outside the flat variant model and survive untouched.
- OpenCode 2.2.3 has no `opencode agent` command: the live checks are
  `opencode debug config --standalone` and `opencode debug agents --standalone`, and `--standalone`
  is required when the developer's own background service holds the port. Point `HOME` and
  `XDG_CONFIG_HOME` at a temporary root, or the check reads the real configuration.
- The UI e2e sandbox must pre-create `home/.config/opencode` before the app starts, because
  `XDG_CONFIG_HOME` points at `home/.config`; without the directory neither the adapter nor any
  OpenCode destination exists.
- `Phase1Test` runs one method per sandbox and the Phase-1 suite is `python3 -B tools/ui_e2e.py
  projects` (P-01…P-12, cold restarts on P-03, P-07 and P-10). A broad `ui --tests '*Phase1Test*'`
  puts all 12 into one home and most of them fail on state another method left behind.
- jediterm's default style — what `JediTerminal` falls back to after an SGR 0 reset — comes from
  `JediTermWidget.createDefaultStyle()`, called from the superclass constructor; read the theme back
  through the superclass `mySettingsProvider`, and `AssistanceTerminalWidgetTest` fails if the
  override disappears.

## Follow-ups        <!-- несделанное; в Backlog — всё, кроме `Waits:` и `Accepted:` -->
- Accepted: `opencode --continue` resume — the CLI descriptor is shared adapter data this plan did
  not change, launching is outside the Goal, and a live session cannot be scripted without a real
  model call.
- Accepted: the bundled-skill v14 to v15 update prompt in a live assistant session — assistant-side
  UI; the marker and the `SKILL.md` digest are pinned by `BundledSkillTest`.
- Accepted: OpenCode global files after a cold restart — the writes are the atomic path P-12
  asserts, and cold restarts are exercised by P-03, P-07 and P-10; only re-reading the same files is
  uncovered.
- Accepted: the live PTY visual reset — the default-style hook is now covered by
  `AssistanceTerminalWidgetTest`, so what remains is the pixels of a real session.
- Waits: packaged-build probe of the real home (`python3 -B tools/ui_e2e.py desktop --bundle
  app/build/compose/binaries/main/app/Ruleblend.app`) — needs a distributable build and Accessibility
  permission; run it when a packaged build is at hand.
