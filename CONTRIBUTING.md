# Contributing

[Русская версия](docs/ru/CONTRIBUTING.md)

Use the issue templates for reproducible bugs and feature proposals. Discuss changes to file
formats, storage or assistant integrations before implementing them. Follow the
[Code of Conduct](CODE_OF_CONDUCT.md); report vulnerabilities through [SECURITY](SECURITY.md).

## Build and test

Run commands from the repository root. Install Git, JDK 21 for the Gradle daemon and Python 3.12+.
Gradle downloads the JDK 17 application/test toolchain when it is missing. The first build needs
internet access. macOS builds also need the Xcode command-line tools and a Swift toolchain capable
of building [the translation helper](tools/translate-helper/Package.swift).

On macOS:

```sh
./gradlew :app:run
./gradlew -PwarningsAsErrors=true :core:allTests :mcp:allTests :app:allTests :app:compileUiE2eKotlinJvm
./gradlew :app:packageDistributionForCurrentOS
```

On Windows, select JDK 21 through `JAVA_HOME` or the IDE Gradle JVM:

```powershell
.\gradlew.bat :app:run
.\gradlew.bat -PwarningsAsErrors=true :core:allTests :mcp:allTests :app:allTests :app:compileUiE2eKotlinJvm
.\gradlew.bat :app:packageDistributionForCurrentOS
```

Use Python 3 for the non-UI contracts on either system (`python3` on macOS, `python` on Windows):

```sh
python3 -B -m unittest discover -s tools -p 'test_*.py'
python3 -B tools/mcp_contract.py
```

The CI gate is defined in [.github/workflows/ci.yml](.github/workflows/ci.yml), including dependency
license coverage and packaged-app checks. Unit tests do not run the graphical suites or native
macOS translation checks. Run the relevant opt-in scenarios from [UI_E2E](docs/UI_E2E.md) for UI
changes, and state explicitly which checks were not run.

The terminal feasibility harness is opt-in on macOS. `./gradlew :app:checkAssistantTerminalSpike` checks PTY/emulator behavior without AI;
`:app:assistantTerminalSpike` opens its offline native window. Keyboard, clipboard and focus
acceptance require that window. After `:app:createDistributable`,
`:app:packageAssistantTerminalSpike` creates a separate app image. For another build, select a
fresh output directory with `-PspikePackageDirectory=/path/to/new-directory`.
`python3 -B tools/assistant_cli_prefill_spike.py <claude|codex> --dry-run` describes a separate
macOS experiment with temporary profiles and denied network/credential access. Removing
`--dry-run` checks draft input and fake MCP without submitting a prompt or changing real profiles.
`:app:checkAssistanceLifecycle` exercises the application's session owner and terminal backend with
an offline Claude-shaped executable, including native prefill, retained output and forced shutdown.
`:app:assistanceNativeCheck` opens the real app with isolated profiles and an offline CLI for
keyboard, clipboard and focus acceptance. `:app:packageAssistanceNativeCheck` builds the same check
as a separate app image using the packaged Java runtime. Neither invokes a model.
`python3 -B tools/ui_e2e.py ui --tests '*AssistanceUiTest*'` runs the deterministic first-launch and
cold-restart flow with a fake terminal backend.

## File-write guarantees

- Preserve all content outside Ruleblend-managed regions. Exceptions require a user-invoked
  **Save to library** with replacement enabled, or explicitly confirmed deletion of a foreign
  skill directory or MCP entry; see [Architecture](docs/ARCHITECTURE.md).
- Write user files atomically with a temporary file and rename. Preserve local edits and report
  conflicts instead of overwriting them silently; atomic replacement alone does not prevent lost updates.
- Add unit tests for marker parsing and import merge changes. Test destructive or conflicting
  operations against temporary fixtures, never real assistant configurations.
- Keep stdout exclusively for the protocol in `--mcp` mode; send diagnostics to stderr.

## Pull requests

Keep a change focused. Explain the user-visible problem, resulting behavior and relevant validation.
For UI changes, include a screenshot with synthetic data. Do not attach real libraries, credentials,
private repository URLs or unredacted assistant configurations.

Keep `core` independent of `mcp` and `app`, and `mcp` independent of `app`.
Read [Architecture](docs/ARCHITECTURE.md) before architectural changes and update affected decisions.
Update English documentation and its Russian counterpart together. User-visible changes need one
English entry under `[Unreleased]` in [CHANGELOG](CHANGELOG.md); behavior-neutral refactors do not.

Code, comments and configuration use English. Public-repository commit messages use English:
start the subject with an imperative verb and explain what changed and why in the body.
For shipped code or resource changes, increment `appBuild` once in `app/build.gradle.kts`.
Do not change `appVersion` without maintainer agreement. Assistant guide and connector registration
versions change only when their installed content changes; see [Architecture](docs/ARCHITECTURE.md#mcp-server).

Contributions are distributed under the project's [license](LICENSE); third-party material must
retain its license and attribution. See [THIRD-PARTY](THIRD-PARTY.md) before adding dependencies or assets.
