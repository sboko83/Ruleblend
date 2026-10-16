# Embedded AI-assistant terminal — implementation plan

Created: 2026-10-10. Refined: 2026-10-11; phase 7 added 2026-10-16.
Status: **IN PROGRESS**; work resumed 2026-10-11.
Scope of the current session: the user asked to finish the whole plan, including phases 3–6.
Branch: `feat/ai-assistance`, appBuild 270. Next unfinished phase — **6**.

## Task after the user's clarification

What is needed is an ordinary embedded terminal, as in Orca: open a bottom panel, start
Claude Code or Codex in it with launch parameters, and then work directly in the CLI.
Ruleblend provides the terminal and the process launch. Conversation, authorization,
permissions and tools remain the standard features of the chosen CLI.

The previous plan was excessive. Its requirements for a separate AI session system, snapshot
schema, mutation preconditions, a change journal and a dedicated MCP protocol for this feature
are cancelled. Do not continue implementing them from old session context.

## Approved behaviour

- The existing app design stays. Added: a help button, a bottom panel and assistant selection
  in Settings. Library, editors, navigation and their styling are not reworked.
- The first version is Claude Code only, with native prefill; approved by the user 2026-10-11.
  Codex is deferred until a separate decision on editable input without automatic submission.
- The top button opens a terminal for the current object. The starting text names the object's
  type, name and ID and suggests working through the installed Ruleblend MCP and skill.
  For example: "Let's discuss the rule Commit (ID: commit). Use Ruleblend MCP and the ruleblend
  skill. Let's discuss the task first."
- The text appears in the CLI's own input, stays editable and is not submitted automatically.
  The user completes the message and presses Enter themselves.
- The bottom panel holds only an "object · assistant" header, a close button on the right and an
  interactive terminal. A divider changes its height; the rest of the app stays usable.
- One active session. Navigation does not restart it. Invoking it again for the same object
  returns focus; for another object the app offers to end the current process and open a new one.
- The close button ends the process and closes the panel. If the process is still running —
  confirmation; if it has already exited — close immediately. Process exit is tracked, not the
  `/exit` string. The exited CLI's output stays until the panel is closed. Closing the app also
  ends the process it owns; a live session is confirmed the same way.
- Settings allow choosing only an assistant with the CLI, Ruleblend MCP **and** skill installed.
  Use the existing connection status and Connect/Update actions. This choice is independent of
  assistant visibility and of the external terminal launch. Nothing is selected by default.
  A single selected available assistant launches immediately; several — through a compact picker
  at the top button; none — navigation to setup. No hidden assistant substitution.
- Changes and versions of library items are handled by the existing Ruleblend MCP.
  No extra Save/Apply, modes, task form, version reports or toolbar in the panel.
  Installing items into projects remains the existing separate action.

Mockup approved (the mockup files are not kept in the repository).

## Minimal implementation

- The terminal component and PTY belong to `app`; keep the `app → mcp → core` direction.
  Launch the chosen executable with argv/cwd/env, forward keyboard input and resize,
  render output, release the process and resources on close.
- Use the CLI's normal configuration and authorization, the already installed Ruleblend
  MCP/skill and the existing executable/PATH discovery. The launch button does not reinstall
  the integration and does not rewrite the assistant's global settings.
- For a saved item a short starting phrase with the ID is enough: the assistant reads the content
  through MCP. Do not create a separate context snapshot format, event journal,
  session-specific MCP server or a custom permission system.
- In the first step wire the invocation from Library. For an open unsaved editor, explicitly
  distinguish a draft from a saved object. Add further entry points after that;
  the table of possible scenarios from the previous plan is not mandatory first-release scope.
- Use the app's existing mechanisms for refreshing Library after an external write.
  If a concrete shortcoming shows up, fix it locally; do not start reworking MCP mutations
  or versioning in advance.
- A starting prompt argument is acceptable only if it leaves the text in the CLI editor.
  An argument that submits immediately does not meet the user's original requirement.
  Bracketed paste needs a ready input field and no Enter; a timer alone does not prove
  readiness. If a particular CLI cannot do this, state the limitation separately and agree on
  a minimal alternative, keeping the simple terminal scenario.

## Acceptance constraints

- Implementation and dependencies: [architecture decision](../ARCHITECTURE.md#ui).
  The local opt-in harness is described in [CONTRIBUTING](../../CONTRIBUTING.md).
- Phase 6 still has user input / system paste / focus return, resize, IME and accessibility.
  Terminal text is absent from the macOS AX tree; programmatic writes to the PTY do not count
  as user input. Native automation did not get AWT focus and did not deliver keys to an ordinary
  Compose field either; this does not count as successful acceptance.
- macOS arm64 is the first platform; macOS x64 and Windows are accepted separately.

## Phase 7: project profile selection

Picking a profile for a project from a large library is manual today: hundreds of items, extras
bloat the assistant's context, missing ones leave it without instructions. The approach was tried
outside Ruleblend in a course experiment (2026-10-16): a local model picked profiles from a
269-item library for three projects and was checked against a frozen reference. This phase moves
what it found into Ruleblend. Direction approved by the user 2026-10-16; the entry point and the
starting text below are a proposal and need approval before code.

Behaviour:

- A project in Place gets the same assistance button. Its starting text names the project and
  its absolute path and asks to pick a profile through Ruleblend MCP and the ruleblend skill,
  discussing first. Prefill, no automatic submission, one session — as for Library objects.
- The assistant in the terminal analyses and installs. Ruleblend adds no selection engine,
  model calls, ranking or profile wizard; the procedure lives in the bundled skill.
- The profile targets the project's enabled assistants (`set_project_agents`), not only the one
  running in the terminal.

Skill section "Picking a profile for a project", the procedure the experiment arrived at:

1. Project facts first, from the repository: languages split into product, tests and auxiliary
   files; build declarations, commands, CI, existing agent files. Only top-level `docs*`, `site`,
   `plugins` and hidden directories are auxiliary (a nested `plugins/` package is product code).
   A `package.json` alone does not make a Node project. Read only: no project commands, and
   agent files are counted, not followed.
2. Screening over every candidate, without a size limit. Clearly foreign items (PowerShell,
   Salesforce, Figma, …) are rejected by description; items touching the project's stack and all
   MCP configs are read in full through `get_*`. Descriptions alone mislead.
3. Selection over the shortlist as a whole. One of several overlapping items: a broad
   `frontend-developer` over narrow React/Vue agents, the `playwright` MCP over browser skills.
   Stack-agnostic generic agents stay out. Ordered by need, at most 10; the reference took 2–6.
   Why two stages: a single pass judged each item alone and took up to 31 items; a limit stated
   in the prompt was ignored. Measured on a local model; not yet tried with Claude Code.
4. Every picked item cites a project fact (file and line); an item without one is dropped.
5. Before any write: the composition with reasons and the exact MCP calls (`create_profile` or
   `update_profile`, `set_project_agents`, `install` with `profile_id`). Write after the user
   confirms. One profile per project; another profile already attached stops the flow and is
   reported. A repeat brings the profile to the agreed composition. Afterwards `target_status`
   shows every member `synced`.

Acceptance:

- Rebase the branch onto `main` first: project subagents for Codex and MCP installation with a
  working directory containing `.ruleblend` need `e2ca27f`. The branch is local, never pushed.
- Reference: course repository `AIAC9`, `week-7-task-5/reference/` — project facts and a decision
  with reasons for each of 269 candidates for alkaa, jrnl and number-flow, pinned to project and
  source SHAs; `week-7-task-5/tools/build_library.py` rebuilds that library. Compare the
  assistant's picks for alkaa at least: matches, extras, misses, with both sides' reasons.
- The live run uses a project clone and an isolated library, never the user's projects or
  library. Ruleblend takes every path from the JVM `user.home`, not `$HOME`: isolate with
  `-Duser.home` in `JAVA_TOOL_OPTIONS` plus `CODEX_HOME` and `CLAUDE_CONFIG_DIR`.
  With mods frozen nothing in the app runs `claude plugin` (it created `CLAUDE_CONFIG_DIR` and
  made Claude Code look available, checked 2026-10-16). Open: how the terminal's Claude Code stays
  authenticated while the library is isolated — decide before the live run.

## Next step

Phase 6: finish interactive acceptance in an active native window and the other platform checks.

## Phases and control

| Phase | Short result | Status |
|---|---|---|
| 0 | Simple terminal UX and mockup approved | DONE 2026-10-11 |
| 1 | Plain terminal/PTY and a minimal way to launch the CLI | DONE 2026-10-11 |
| 2 | Short starting text and selection of a ready Claude Code | DONE 2026-10-11 |
| 3 | Launch/close of a single CLI process in the app | DONE 2026-10-11 |
| 4 | Bottom panel and Settings in the existing design | DONE 2026-10-11 |
| 5 | Invocation from Library and consistent refresh of its data | DONE 2026-10-11 |
| 6 | Automated checks and macOS arm64 packaging done; interactive and platform gates open | IN PROGRESS |
| 7 | Project profile selection: Place entry point, skill procedure, reference check | TODO |

Keep the previously agreed control points after 0–1, 2–3, 4–5 and the final one in 6: check
against the task, review the diff and check the changed behaviour. Control for 0–1, 2–3 and 4–5
closed 2026-10-11. Additional assistants and more sessions are separate tasks.

## Conditions for the next session

- The user authorized implementation; no new mockup or re-approval of the already approved UX
  is needed. Clarification 2026-10-11: an ordinary terminal at the bottom with launch
  parameters, as in Orca.
- The working document is this file; `CLAUDE_CODE_MODS.md` belongs to another task.
- Do not commit an unfinished prototype. After the corresponding step and its checks are done —
  a normal commit per the repository rules; no push.
- Phase 7 does not depend on the open phase 6 gates; its UX needs approval first.
- No live AI requests were made; they need a separately agreed scenario. Launching the CLI
  without submitting and local fake checks are allowed. Do not change real profiles for tests.
- Report checks and their output in chat; do not accumulate a run log here. For a finished
  change run focused checks, then the standard profile from CONTRIBUTING/UI_E2E.
