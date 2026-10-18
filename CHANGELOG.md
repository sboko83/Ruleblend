# Changelog

All notable changes to this project will be documented in this file.

Follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- app: Saving a found file with several sections to the library can keep it whole as one rule.
- app: Settings → General → Workspace can hide the Recent section of the project list.
- app: AI help (the robot button in the Library inspector and editor header) opens Claude Code in a
  resizable embedded terminal for saved Library objects,
  with an editable starting message, explicit assistant selection and confirmed process closure.
- app: The embedded terminal follows the app's light or dark theme; a theme change applies from the next session.
- app: a project in Place has an AI help menu next to its assistants: "Ask a question" or "Pick a
  profile" opens Claude Code in the project folder with an editable starting message in the interface language.
- app: AI help shows the terminal panel at once, with a progress indicator while Claude Code starts.
- skill: the bundled ruleblend skill can pick a profile for a project: it analyses the project, shows
  the composition with reasons and installs it after confirmation, leaving the files uncommitted.
  Connected assistants ask for a skill update.
- app: The Library list header shows how many objects are listed ("5 of 41") beside the search, and
  switching the grouping moved into one picker next to it.
- app: An empty Library list names the filter that emptied it and offers to lift it in one click
  ("Without the “Local” filter — 7 objects", Remove).
- app: Favorite rules show the same star in the Projects file preview as in the Library.

### Changed
- app: Assistant settings use a searchable list with detected assistants first, eight rows per page,
  independent visibility and AI help switches, and expandable capabilities, launch options and MCP/skill details.
- ZCode MCP servers use native user and workspace configs in `.zcode`; existing unchanged
  Ruleblend-managed entries move there on launch without overwriting other settings.
- app: Library facet counts answer for the filters and search already in play, and a combination
  that would list nothing shows a dimmed zero before it is clicked.
- app: The Library Source facet is shown only where it can answer (skills and subagents; the
  opening rules preset keeps it visible), Scope and Usage read as one-line segmented switches, and
  the Groups facet lost its "All" row — no group selected already means all.
- macOS, Linux: installed and development builds register the same MCP launcher, so switching
  between them no longer requires reconnecting assistants; Settings → Agents shows which build
  assistants launch and switches it. Assistants connected earlier ask for one reconnection.
- Skills are installed only into each assistant's own folder, and no longer into the shared
  `.agents/skills` folder: Pi now uses `~/.pi/agent/skills` and `.pi/skills`, Kimi Code and ZCode
  only their own `skills` folders. On launch, copies an earlier version left in `.agents/skills`
  move to the assistant's folder or are removed; copies edited by hand stay where they are.

### Fixed
- app: Updating skills imported from Git no longer shows the same updates again after a restart.
- app: A skill imported from Git without a declared version shows its commit (`@59f4649`) instead of
  a `v`-prefixed hash; a `version` in the skill's `SKILL.md` frontmatter now takes precedence.
- app: in a narrow window the Place project header wraps its buttons instead of squeezing the
  assistants summary into a column and hiding Configure.
- Codex subagents can be installed into projects (`.codex/agents/`), including through profiles;
  they were previously skipped as unsupported.
- Installing MCP servers and subagents no longer fails with "Is a directory" or risks
  touching unrelated files when Ruleblend runs from a folder such as the home directory.
- A rule edited by hand in a project file no longer marks every other rule in that file as
  modified; a file whose rules already match the library but whose Ruleblend header is out of date
  shows an update and is repaired by it.
- Tooltips appear above and to the right of the pointer, keeping controls clickable.
- The Library label in Settings → About stays on one line.
- Home reports missing assistant components and moved MCP paths; updating an existing connection
  restores its missing components.
