# Changelog

All notable changes to this project will be documented in this file.

Follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Library Git sync and ZIP archives preserve complete Claude Code mod packages, including executable
  files, group membership and whole-package conflict alternatives.
- core: Complete Claude Code mods can be previewed and imported from Git or a selected local/installed
  folder; explicit source updates preserve references and report incompatible packages.
- core: Claude Code mods support explicit user-scope installation, updates, enable/disable and
  keep-data uninstall, with drift protection and recovery after interrupted CLI operations.
- app: Library and Global → Claude Code offer complete mod import, group installation, explicit
  updates and removal previews, with separate library/runtime revisions and reload notices.
- app: Saving a found file with several sections to the library can keep it whole as one rule.

### Fixed
- Tooltips appear above and to the right of the pointer, keeping controls clickable.
- The Library label in Settings → About stays on one line.
- Home reports missing assistant components and moved MCP paths; updating an existing connection
  restores its missing components.
- core: Claude Code mods reinstall cleanly after removal, detect foreign plugins with aliased names,
  and use user settings for global actions. Installed-package imports prefer the active source folder
  when Claude reports it instead of an older cache copy.
