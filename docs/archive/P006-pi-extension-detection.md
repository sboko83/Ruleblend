---
id: P006
title: Pi subagent extension detection
closed: 2026-10-18
---
## Outcome

Settings reads the packages declared in Pi's `settings.json` and shows the `◐ Subagents*` marker,
the row tag and the install-command footnote only while no pi-subagents package is declared; the
footnote has a copy button for the command.

## Decisions

- Probe the extension for display only, installs stay ungated — P005 rejected probing because
  support that flickers would make installs unstable, and that still holds; a footnote shown to a
  user who already has the extension is just wrong — rejected: a static footnote for every Pi user.
- Match a declaration by the last segment of its source with the version or ref dropped — npm, git,
  URL and local sources all end in the package name, and it accepts the scoped
  `@tintinweb/pi-subagents` fork — rejected: a fixed list of npm names, which misses git and local
  installs.
- An object declaration with `"extensions": []` does not count — the package is present but loads
  no extension, so the files stay inert.

## Gotchas

- Only the personal `$PI_CODING_AGENT_DIR/settings.json` is read; a package declared just in a
  project's `.pi/settings.json` still shows the footnote.
- A differently named package that also reads `agents/*.md` is not recognised.
- The marker is refreshed with the other agent state (Settings load and connection refresh), not by
  watching the file.

## Follow-ups

None.
