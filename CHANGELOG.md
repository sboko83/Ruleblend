# Changelog

All notable changes to this project will be documented in this file.

Follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- app: Saving a found file with several sections to the library can keep it whole as one rule.
- app: Settings → General → Workspace can hide the Recent section of the project list.
- app: AI help opens Claude Code in a resizable embedded terminal for saved Library objects,
  with an editable starting message, explicit assistant selection and confirmed process closure.
- app: a project in Place has an AI help button next to its assistants; it opens Claude Code in
  the project folder with an editable starting message for picking a profile.
- skill: the bundled ruleblend skill can pick a profile for a project: it analyses the project, shows
  the composition with reasons and installs it after confirmation. Connected assistants ask for
  a skill update.

### Changed
- macOS, Linux: installed and development builds register the same MCP launcher, so switching
  between them no longer requires reconnecting assistants; Settings → Agents shows which build
  assistants launch and switches it. Assistants connected earlier ask for one reconnection.

### Fixed
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
