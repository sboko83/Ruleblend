# Ruleblend

Library and composer for AI agent instruction blocks.

## What

Ruleblend organizes a local library of instruction rules, MCP server configs, subagents and skills
for coding agents (Claude Code, Codex, Pi, Kimi Code, ZCode). It installs rules and MCP configs into
agent configs or project folders: rules as managed regions in `CLAUDE.md` / `AGENTS.md`, MCP servers
as entries in each agent's own MCP config. It also runs as an MCP server itself, so agents can search
the library and install rules — into a project or into an agent's global file — without hand-editing
instruction files. Connecting an agent also installs a built-in skill teaching it that workflow;
Pi 1.0+ supports MCP natively; no extension is required.

## Why

- Keep agents clean: only relevant instructions per project → fewer tokens, better context.
- Single UI for all agents: no per-agent CLI commands or config formats.
- Constructor workflow: build block groups, swap them per project on the fly.

## Core concepts

- **Block** — named, versioned rule or MCP config with a description. A rule has a mutable global or
  project scope, so project-only rules stay out of unrelated lists. Types: `rule` (markdown
  instruction) and `mcp` (MCP server config, edited as a form).
- **Skill** — a complete `SKILL.md` directory created locally or imported from a Codex or Claude
  plugin Git repository, including scripts, references and assets. Git-backed skills update in place;
  editing starts from a separate `changed` copy.
- **Subagent** — instructions with per-assistant fields, created locally or imported from Git as
  Codex TOML or Claude Code/Kimi Code Markdown. Git imports offer a preview and source-assistant
  choice; updates preserve identity, while editing starts from a separate changed copy.
- **Group** — named set of rules, MCP servers, subagents and skills (e.g. "iOS projects").
- **Profile** — portable project mode containing objects and flat groups; attach it to a project
  and enable or disable it there. Local project bindings are separate from the portable definition.
- **Target** — agent global config or a project folder. Rules are written as managed regions:

```markdown
<!-- Ruleblend-managed. Do not edit below; changes are overwritten. -->
<!-- rb1 a1b2c3d4 git-no-commit@3:23:g=ios-projects swift-style@1:20 -->
...instruction text...
...instruction text...
<!-- rb:end -->
```

One manifest line describes the whole managed run. State is derived by parsing markers — no separate install database. Ordinary rule updates preserve content outside markers. MCP entries, complete installed skill directories
and standalone subagent definitions carry no markers, so ownership is tracked in sidecar state files.

Legacy `kb` and `kb1` files remain readable and are rewritten as `rb1` on their next managed-content
change. An `rb1` run uses `partial` ownership beside hand-written text or `owned` ownership when the
whole file is rendered by Ruleblend. The file remains the source of truth; a changed wrapped run is
flagged before Ruleblend writes it. Per-file controls also toggle the warning notice or disown the
file while leaving its content intact.

## Stack

- Kotlin Multiplatform, Gradle 9.7 (modern KMP module structure; JVM targets)
- `core/` — pure Kotlin/JVM: models, storage (files + JGit), marker parser, agent adapters
- `mcp/` — facade over `core`: stdio MCP server, per-agent MCP-config connectors, MCP-block installers, bundled agent skill
- `app/` — Compose Multiplatform Desktop (macOS and Windows); `--mcp` runs the server
- Library storage: `~/.ruleblend/library/` — markdown files in a git repo; every save is a commit

## MCP tools

Run `Ruleblend --mcp` to manage the library:

- Rules: `list_rules`, `get_rule`, `create_rule`, `update_rule`, `delete_rule`.
- MCP server configs: `list_mcp_servers`, `get_mcp_server`, `create_mcp_server`,
  `update_mcp_server`, `delete_mcp_server`.
- Groups: `list_groups`, `get_group`, `create_group`, `update_group`, `delete_group`, `reorder_group`.
- Profiles: `list_profiles`, `get_profile`, `create_profile`, `update_profile`, `delete_profile`.
- Subagents: `list_subagents`, `get_subagent`, `create_subagent`, `update_subagent`,
  `delete_subagent`, `fork_subagent`.
- Skills: `list_skills`, `get_skill`, `create_skill`, `update_skill`, `delete_skill`, `fork_skill`.
- Skill files: `list_skill_files`, `read_skill_file`, `write_skill_file`, `delete_skill_file`.
- Git imports: `preview_git_import`, `get_git_import_entry`, `apply_git_import`.
- Git sources: `list_sources`, `check_sources`, `update_from_sources`.
- Targets: `list_targets`, `install`, `uninstall`, `target_status`.
- Project profiles: `get_project_profiles`, `attach_profile`, `set_profile_active`.
- Projects and assistants: `register_project`, `unregister_project`, `set_project_agents`,
  `list_assistants`, `set_assistant_visibility`.
- Installed copies: `list_target_entries`, `read_target_entry`, `save_target_entry`,
  `accept_local_change`, `manage_target_entry`, `reorder_target_rules`, `check_target_conflicts`.
- ZIP exchange: `export_library`, `preview_archive_import`, `get_archive_import_entry`, `apply_archive_import`.
- History and Git: `library_history`, `library_diff`, `library_sync_status`, `sync_library`.

Group and profile updates use `add` and
`remove` maps with typed id arrays (`rule_ids`, `mcp_ids`, `subagent_ids`, `skill_ids`, and, for
profiles, `group_ids`). MCP config reads mask environment and header values; updates accept new
values. Use `--library /absolute/path` or `RULEBLEND_LIBRARY` in MCP mode to work in a separate
library. Installed copies remain in targets until uninstalled or reconciled.

## License

Ruleblend's original code and artwork use [PolyForm Noncommercial 1.0.0](LICENSE).
Commercial licensing: [s.bokonyaev@yandex.ru](mailto:s.bokonyaev@yandex.ru).
Third-party components retain their own licenses: see [NOTICE](NOTICE) and
[THIRD-PARTY.md](THIRD-PARTY.md). Native packages include these files in the app's
`resources/legal/` directory; the Java runtime retains its own `legal/` directory.

## Status

Alpha: `0.5.0` is the first public build (`v0.5.0-alpha.1`), unsigned; expect rough edges.

Working macOS app — library, local and Git-backed skills, integration into five agents, adoption of
existing rules, MCP blocks, export/import, two-way Git synchronization, and the MCP server all ship.
Windows MSI packaging is available.
Linux packaging is planned.

## Unsigned alpha installation

Download the DMG or MSI matching your operating system and architecture from the release assets.
Java is included. Close Ruleblend and its active MCP sessions before updating.
The release also provides corresponding Java sources and `SHA256SUMS`; checksums detect damaged
downloads and are not digital signatures.

- **macOS (Apple Silicon):** open the DMG, drag Ruleblend into Applications, then launch it. The alpha is unsigned
  and not notarized. If macOS blocks it, open System Settings → Privacy & Security → Open Anyway
  for Ruleblend and confirm. Use this exception only for a download you trust; see
  [Apple's instructions](https://support.apple.com/en-us/102445).
- **Windows x64:** run the MSI to install for the current user. If SmartScreen reports an
  unrecognized app and offers an override, choose More info → Run anyway for a download you trust.
  Smart App Control or an organization policy may block unsigned packages without an override;
  see [Microsoft's explanation](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/smartscreen-reputation).
  Installation, updates and removal are described below.

## Windows installer

Offline translation requires macOS 26.4+; its controls are hidden on Windows and Linux.
The window needs a usable desktop of at least 900 × 600 logical pixels after display scaling.

Build on Windows x64 with JDK 21 selected for Gradle:

```powershell
.\gradlew.bat :app:packageMsi
```

The MSI is in `app/build/compose/binaries/main/msi/`. Gradle downloads WiX on the first build.
Run it to install for the current user; no separate Java installation is needed. Close Ruleblend
and active Ruleblend MCP sessions before running a newer MSI. Remove the app through Windows
Installed apps. Installer identity and data retention are defined in
[Architecture](docs/ARCHITECTURE.md#windows-distribution).

Packages are unsigned. Windows ARM64 and WSL are not validated distribution targets.

## Windows development in Android Studio

1. Open the repository root and let Gradle sync finish. Use JDK 21 for Gradle
   (**Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JVM**).
   The project uses a JDK 21 Gradle daemon and a JDK 17
   application toolchain; Gradle downloads JDK 17 automatically if it is missing.
2. Select **Ruleblend (Windows)** in the run configuration selector. If it does not appear,
   reopen the project. The shared configuration is in `.run/Ruleblend (Windows).run.xml`.
3. Set a breakpoint in Kotlin code, then choose **Debug** (Shift+F9). Choose **Run** (Shift+F10)
   for a normal launch. The configuration runs `:app:run` and attaches the debugger to the app.

The first sync/build needs internet access to download dependencies and the Java toolchain.
This launches the desktop window directly; an Android emulator and a Windows installer are not needed.

For a terminal launch, set `JAVA_HOME` to a JDK 21 installation and run:

```powershell
.\gradlew.bat :app:run
```

## Opt-in UI checks

Run `tools/verify_e2e.sh --list` to see the available suites. Use
`tools/verify_e2e.sh --scenario P08` for one scenario or `tools/verify_e2e.sh --all-local`
for all deterministic UI suites. Native window and live translation checks require a packaged
macOS app and separate selection. See [docs/UI_E2E.md](docs/UI_E2E.md) for setup and reports.

Contributing: [CONTRIBUTING.md](CONTRIBUTING.md). Security reports: [SECURITY.md](SECURITY.md).

Documentation index: [docs/README.md](docs/README.md).

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how it works, [docs/ROADMAP.md](docs/ROADMAP.md) for what is open, and [CHANGELOG.md](CHANGELOG.md) for release history.

Русская версия: [README.ru.md](README.ru.md)
