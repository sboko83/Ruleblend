# Roadmap

[Русская версия](ru/ROADMAP.md)

Open work only. Shipped items are deleted from this file, not ticked — what shipped is in
[CHANGELOG.md](../CHANGELOG.md), how it works is in [ARCHITECTURE.md](ARCHITECTURE.md). Work
for public distribution is described in [RELEASING.md](RELEASING.md); this file holds work
after the first alpha.

## Recursive discovery of unmanaged instruction files

The *Found in project* pane scans the target-owned files and files reached through Claude Code
imports, but does not find instruction files elsewhere in a monorepo.

- Recursively find `AGENTS.md` and `CLAUDE.md` to a depth cap, skipping generated and
  dependency directories. Do not scan formats belonging to unsupported agents.
- Treat files outside `Target.ownedFiles()` as copy-only: they can be saved to the library, but
  cannot be replaced with a managed region that Ruleblend would never update.
- Leave agent globals unchanged; each config directory has one known instruction file.

## Element kinds beyond rules, MCP servers, skills and agents

Hooks, `settings.json` permissions, Codex custom prompts, ignore files, the `config.toml`
sections other than `mcp_servers`, plugins and marketplaces, and commands (`.agents/commands`) are
read by the assistants but not modelled. Each needs its own install/drift semantics before it can
join the library.

## Imported skills edited in place

Extend the current [fork model](ARCHITECTURE.md#git-repository-imports) to one skill with a pristine
`upstream/` copy beside `files/` and a three-way merge on update, with conflicts resolved in Compare.

## Offline translation on Windows / Linux

Add a translation backend for Windows / Linux. Options include an online provider with a user key
or an offline helper over Bergamot / Marian NMT, built like `rb-translate`.

## Later

- Linux packaging.
- CLI facade over `core`.
