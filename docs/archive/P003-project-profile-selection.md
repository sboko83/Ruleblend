---
id: P003
title: Project profile selection
closed: 2026-10-17
---
## Outcome

A Place project has an AI help menu: "Ask a question" and "Pick a profile" open Claude Code in the
project folder with an unsubmitted starting text in the UI language. The bundled skill (v13) picks a
profile from the library, shows it with reasons and installs it after confirmation. The terminal
follows the app theme and its panel appears on the click. Profile picking was accepted live once on
the fixture (8 of 8 picked items match); the GitHub MCP case of the corrected fixture was not re-run.

## Decisions

- The selection procedure lives in the bundled skill, not in the app — the assistant already reads
  the library through MCP and the procedure changes faster than the app — rejected: a built-in
  ranking engine or profile wizard.
- Two-stage selection, screening over every candidate then choice over the shortlist, at most 10
  items, generic stack-agnostic items left out — a single pass with a local model judged items alone
  and took up to 31 — rejected: one-pass ranking.
- The skill leaves installed files uncommitted — in the live run the assistant committed them
  following the user's global commit rule — rejected: leaving commits to whatever instructions the
  assistant has.
- Place AI help is a menu with "Ask a question" and "Pick a profile" — a profile request behind a
  generic AI help label was wrong for the user — rejected: a second header button, a button in the
  palette's Profiles section.
- Project starting texts follow the UI language: RU help «Проект <name> (<path>). Нужна помощь с »,
  EN help "Project <name> (<path>). I need help with ", RU profile «Подберём профиль для проекта
  <name> (<path>). Используй Ruleblend MCP и skill ruleblend. Сначала обсудим.», EN profile "Let's
  pick a profile for the project <name> (<path>). Use Ruleblend MCP and the ruleblend skill. Let's
  discuss first." — approved by the user — rejected: English-only project texts.
- Project help and profile picking are separate target kinds with the absolute project path as id —
  the one-session rule keys on (kind, id), so switching purpose for the same project asks to replace
  — rejected: one kind with a purpose flag outside the identity.
- The Place button is a composable slot passed to PlaceScreen — keeps the place package independent
  of assistance sessions and settings — rejected: reading them inside PlaceHeader.
- Terminal colors are captured from the composition at launch as plain RGB data, light or dark by
  surface luminance — the PTY start runs off EDT and needs nothing from Compose — rejected: reading
  MaterialTheme inside the terminal start.
- The starting panel is set synchronously before the assistant refresh and survives release() until
  the terminal starts; a second click while starting is ignored — the slow part is the cold terminal
  start, which a mutex-serialised state would hide — rejected: setting it inside open().
- The Place header and the assistants summary wrap with FlowRow, guarded by an e2e bounds check — at
  the 1024 px window the summary row alone overflowed and Configure got zero width — rejected:
  moving the AI help button into the title row.
- The live acceptance used the user's real Claude Code config and login with the fixture library
  selected through `-Duser.home` in JAVA_TOOL_OPTIONS — approved by the user — rejected: a separate
  CLAUDE_CONFIG_DIR with its own login.
- Fixture oracle: 9 of 30 items with include/overlap/foreign/unneeded verdicts, the project has a
  GitHub remote — the first oracle broke the skill's own rules and cited a missing remote — rejected:
  loosening the skill to fit the fixture.

## Gotchas

- Ruleblend takes every path from the JVM `user.home`, Claude Code from `$HOME`/`CLAUDE_CONFIG_DIR`:
  an isolated library needs `-Duser.home` in JAVA_TOOL_OPTIONS, which does reach the MCP that Claude
  Code spawns through the shared launcher `~/.ruleblend/bin/ruleblend-mcp`.
- A live profile pick runs under the user's real global Claude instructions; they shape the choice
  (commit style, generic items).
- Claude Code has no per-launch theme option; on the themed terminal it draws with the user's own
  `/theme`, which can mismatch the app.
- The first terminal start in a JVM takes about 2 s (pty4j/JediTerm class loading); later starts
  take about 25 ms.
- JediTerm reads its settings provider when the widget is created; colors cannot change for a
  running session. Its palette covers ANSI 0..15 only and throws above that.
- `BundledSkillTest` pins the SKILL.md digest: any edit needs a marker version bump and the new
  digest from the failure message.
- AssistanceUiTest's Place scenario is the tail of A01 and needs A01's Claude connection.

## Follow-ups

- Prewarm pty4j/JediTerm in the background at app start (Backlog).
- Re-run live profile picking against the 9-item fixture, including the GitHub MCP case, and run
  the fixture script on Windows (Backlog).
