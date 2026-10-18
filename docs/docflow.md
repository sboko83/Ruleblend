---
docflow: 1
read_budget: 40000
start_read: README.md
start_budget: 4000
---
## Queue
- P004
- P005

## Frozen
- P002 — building mods into the app is premature (user, 2026-10-16)

## Backlog
- Run the embedded AI help terminal on macOS x64 and Windows, including paths with Unicode and
  spaces (see docs/archive/P001-ai-assistance.md).
- Prewarm pty4j/JediTerm in the background at app start so the first AI help terminal opens without
  the ~2 s cold start (see docs/archive/P003-project-profile-selection.md).
- Re-run live profile picking against tools/profile_pick_fixture.py (9-item oracle, GitHub MCP case)
  and run the fixture script on Windows.
