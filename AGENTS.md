<!-- rb1 owned e7a8ef84 ruleblend@1:376 version@1:212 commit@2:104 docs@2:566 rules@2:722 — managed by Ruleblend; edits are overwritten -->
# Ruleblend

Desktop app (KMP + Compose Desktop) managing AI-agent instruction blocks and MCP server configs.

Before architectural changes read: docs/ARCHITECTURE.md

Modules & layering (app -> mcp -> core):
- core: business logic, parsing, persistence; must not depend on app or mcp
- mcp: MCP server and integrations, facade over core; must not depend on app
- app: UI only

## Version

`appVersion` / `appBuild` in app/build.gradle.kts are the single source of truth.
Raise `appBuild` by one in every change that touches shipped code or resources.
Never raise `appVersion` unless asked.

## Commit

Commit messages in this project are in English.
Code, comments and CHANGELOG stay in English.

## Docs

- All project docs are in English, unless the user asks for another language for a specific
  document. Exception: dedicated translations (README.ru.md, docs/ru/) — keep them in their language.
- CHANGELOG.md: one bullet per change under [Unreleased], English, worded from the user's side,
  no internal symbol names. No version bumps unless asked.
- docs/ARCHITECTURE.md: update when module layout, storage layout, file formats or invariants
  change. Skip for behaviour-neutral refactors.
- docs/ROADMAP.md: delete shipped items instead of ticking them.

## Rules:

- Use modern Kotlin Multiplatform conventions.
- In --mcp mode stdout is reserved for MCP protocol; use stderr for logs.
- Never modify content outside Ruleblend-managed regions.
  Exceptions: explicit user-invoked "Save to library" action with replace ticked; explicit confirmed deletion of a foreign skill directory or MCP config entry.
- All writes to user files must be atomic (temp + rename).
- Marker parser and import merge changes require unit tests.
- Raise the version in the bundled `SKILL.md` marker line whenever that file changes, and
  `MCP_ENTRY_VERSION` whenever the registration a connector writes changes. Neither follows
  `appBuild`: a bump reports every connected assistant as out of date.
