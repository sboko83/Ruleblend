# End-to-end UI checks

[Русская версия](ru/UI_E2E.md)

Suites run manually. They are excluded from `test`, `jvmTest`, `allTests`, `check`,
packaging, CI and `tools/verify_ui.sh --all`. List available suites with `tools/verify_e2e.sh --list`.

Run all commands from the repository root.

Unified suite or scenario selection:

Python 3.12+; Gradle builds need JDK 21, while test JVMs use toolchain 17.
On Windows, use `python tools/verify_e2e.py` instead of `tools/verify_e2e.sh`, with the same
arguments. Symlink checks require Developer Mode or permission to create symbolic links;
missing permission is a failed prerequisite, not a reason to exclude tests from the suite.
`desktop`, `translation-live` and N-11 use the macOS sandbox and return `blocked` on Windows.
Runner contracts without UI run in CI; graphical scenarios run only manually.

```sh
tools/verify_e2e.sh --list

tools/verify_e2e.sh smoke

tools/verify_e2e.sh library ownership profiles coverage

tools/verify_e2e.sh exchange sources sync

tools/verify_e2e.sh translation settings recovery

tools/verify_e2e.sh --all-local
tools/verify_e2e.sh --scenario R03
RULEBLEND_E2E_BUNDLE=/absolute/path/Ruleblend.app tools/verify_e2e.sh desktop
```

`--all-local` selects only deterministic UI suites. `desktop` and `translation-live`
require a prebuilt `.app` and a separate explicit invocation.
Each suite saves its own report; a failure does not cancel the remaining requested suites,
and the overall exit code retains failure or blocked/skipped status. Rerunning an ID creates
a new root and preserves the previous report.

```sh
# Root UI and cold restart in a new JVM, sharing one temporary root.
python3 -B tools/ui_e2e.py ui
python3 -B tools/ui_e2e.py ui --tests '*RootSmokeTest*' --timeout 240

# Phase 1: each P-01…P-12 scenario has its own temporary root.
python3 -B tools/ui_e2e.py projects
python3 -B tools/ui_e2e.py projects --tests P08

# Phase 2: each L-01…L-12 scenario has its own temporary root.
python3 -B tools/ui_e2e.py library
python3 -B tools/ui_e2e.py library --tests L08

# Phase 3: each O-01…O-12 scenario has its own temporary root.
python3 -B tools/ui_e2e.py conflicts
python3 -B tools/ui_e2e.py conflicts --tests O01

# Phase 4: each G-01…G-09 scenario has its own temporary root.
python3 -B tools/ui_e2e.py groups
python3 -B tools/ui_e2e.py groups --tests G07

# Phase 5: each F-01…F-09 scenario has its own temporary root.
python3 -B tools/ui_e2e.py overview
python3 -B tools/ui_e2e.py overview --tests F07

# Phase 6: X-01…X-05 archives and S-01…S-05 Git skills.
python3 -B tools/ui_e2e.py archives
python3 -B tools/ui_e2e.py archives --tests X01
python3 -B tools/ui_e2e.py git-skills
python3 -B tools/ui_e2e.py git-skills --tests S03

# Phase 7: Y-01…Y-10 two-way library Git synchronization.
python3 -B tools/ui_e2e.py git-sync
python3 -B tools/ui_e2e.py git-sync --tests Y03

# Phase 8: T-01…T-07 translation through root UI with controlled responses.
python3 -B tools/ui_e2e.py translation
python3 -B tools/ui_e2e.py translation --tests T05

# Phase 9: N-01…N-07 and N-11 settings/connections without a native window.
python3 -B tools/ui_e2e.py settings
python3 -B tools/ui_e2e.py settings --tests N05

# Phase 10: R-01…R-06 recovery, including the R-05 report contract.
python3 -B tools/ui_e2e.py recovery
python3 -B tools/ui_e2e.py recovery --tests R01

# Phase 8: T-08…T-09 packaged window and macOS helper.
./gradlew :app:createDistributable
python3 -B tools/ui_e2e.py translation-live --bundle app/build/compose/binaries/main/app/Ruleblend.app
python3 -B tools/ui_e2e.py translation-live --tests T09 --bundle app/build/compose/binaries/main/app/Ruleblend.app

# Shared smoke: P-07 main flow with a cold restart.
python3 -B tools/ui_e2e.py smoke

# Remove all completed temporary fixtures; leave incomplete ones.
python3 -B tools/ui_e2e.py cleanup

# Report, timeout and cleanup contracts without UI.
python3 -B -m unittest discover -s tools -p test_ui_e2e.py

# Real macOS window: ffmpeg, Accessibility, System Events Automation and screen recording.
./gradlew :app:createDistributable
python3 -B tools/ui_e2e.py desktop --bundle app/build/compose/binaries/main/app/Ruleblend.app
python3 -B tools/ui_e2e.py desktop --tests N09 --bundle app/build/compose/binaries/main/app/Ruleblend.app
```

The UI suite uses Compose JUnit v2, the application's root UI and real filesystem services.
Folder/archive/editor selection, CLI discovery, external applications and translation are substituted.
This does not check system dialogs or real translation. Every start uses a new JVM 17:
home and environment are set before classes load; JVM writes stay within the canonical root,
and external processes and networking are denied. Normal application startup does not install this guard.
`ui` defaults to the phase 0 fixture suite; `smoke` runs P-07, while `projects` runs
P-01…P-12 separately on independent roots. P-03, P-07 and P-10 check cold restarts.
`conflicts` runs O-01…O-12 separately; only the fixture creates external edits and malformed files,
inside that root.
`library` runs L-01…L-12 separately; the local L-10 Git source is prepared within the fixture
before the application starts.
`groups` runs G-01…G-09 separately; G-07 also checks config and bindings after a cold restart.
`overview` runs F-01…F-09 separately, checking Home, Coverage and Resolve through the root UI
and real files. F-09 also checks settings after a cold restart.
`archives` runs X-01…X-05 separately; X-01 and X-02 transfer ZIPs to an empty library and
the next process's configuration. `git-skills` runs S-01…S-05 with a local Git source;
S-03…S-05 change the fixture between processes, and S-05 checks manual and automatic modes
in separate cold starts.
`git-sync` runs Y-01…Y-10 separately with a local bare remote and a second client inside
the fixture; Y-09 and Y-10 check state after a cold start.
`translation` runs T-01…T-07 on independent root UI fixtures with a controlled translator;
T-02 checks an installed rule in the project file, and T-05 uses a translator that corrupts
anything not masked. `translation-live` drives the packaged application through Accessibility
and its bundled helper, preflights the EN→RU and RU→EN language packs and does not install them.
T-09 opens the application twice in separate processes. A missing pack returns `blocked`.
`settings` runs N-01…N-07 and N-11 separately; N-11 first builds the current distribution
for a second `--mcp` process. N-01, N-02, N-04 and N-05 use cold restarts.
`desktop` runs H-08 and N-08…N-11 separately in a real window.
N-08 selects an exported ZIP after a cold application restart.
`recovery` runs R-01…R-06; R-01 and R-06 check cold restarts, and R-01 also checks a missing
project directory. R-03 runs smoke P-07 twice on fresh roots and checks the real home fingerprint.
R-02 forcibly terminates a second JVM during an atomic rule rewrite, holds the lock in another
process and checks write refusal through the UI. R-05 checks report semantics on synthetic
outcomes without Compose; its initial failing fixture is retained for inspection until `cleanup`.

The desktop suite launches the packaged binary under a macOS sandbox limiting writes and child
processes to the same root. Before launch it checks that outside writes are denied; afterwards
it fingerprints real assistant configurations and installed files. Other sessions' logs and caches
are excluded. The driver uses Accessibility through `osascript`, automating launch, native folder
selection, keyboard input, a window screenshot and clean shutdown.
N-08…N-11 separately check native dialogs, window size and restoration, About and refresh after
external MCP writes. `ffmpeg` captures through AVFoundation without audio and crops to window
bounds accounting for Retina scale. The window must fit entirely on the primary display;
multiple monitors are outside this suite's coverage.
Missing permissions or `ffmpeg` return `blocked`; System Events needs Automation permission,
and screenshots need Screen Recording permission for the launching application.
Ambiguous elements, driver errors and timeouts return `failed`.
Element references retain the PID: multiple Ruleblend instances can have the same name.

Exit codes: `0` — all selected scenarios passed; `1` — failure/timeout/empty suite;
`3` — skipped or blocked outcomes. Successful XML does not override a Gradle failure.
Reports and screenshots are saved in `build/ui-e2e/<run>/`. Failures also retain a temporary
sandbox with logs, XML, real files and a manifest; the terminal prints its path.
A successful sandbox is removed after processes exit. To clean up a retained fixture:

```sh
python3 -B tools/ui_e2e.py cleanup /private/tmp/ruleblend-e2e-<run>
```

Cleanup requires the runner's own marker and does not follow symbolic links.
The runner invokes `:app:uiE2e` directly; that task requires a prepared `-Pe2eRoot=...`,
and `-Pe2eStage=...` selects the stage. To compile without running:
`./gradlew :app:compileUiE2eKotlinJvm`.

## Maintaining scenarios

- Perform user actions through the root UI; a direct model call does not replace a click.
  Fixtures prepare environment, external edits and failures only within the isolated root.
- Check visible results and independently read files/config/sidecars;
  include a cold restart when persisted state needs verification.
- Selectors use stable ids, kind, place and action; native selectors use accessible names and roles.
  Waits have bounded timeouts and check the required state: Compose-idle does not mean I/O has finished.
- Git scenarios use local repositories. Ordinary suites substitute CLI and translation;
  real dialogs, distributions and translation have separate native suites.
- After a run, `python3 -B tools/ui_e2e.py cleanup` removes completed temporary fixtures,
  including failed ones. Incomplete and foreign directories remain; reports in `build/ui-e2e`
  are retained. Save any artifacts needed for investigation before cleanup.
