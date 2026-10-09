# Preparing a public release

[Русская версия](ru/RELEASING.md)

Decisions for the first alpha.
This document describes the intended release setup, not a completed publication.

## License

- Original code and artwork use [PolyForm Noncommercial 1.0.0](../LICENSE).
- Commercial terms are arranged separately; the contact is published in README.
- The project is not presented as OSI open source.
- `NOTICE` and `THIRD-PARTY.md` attribute shipped dependencies and assets according to verified
  licenses. A preliminary list in a plan does not replace checking the actual distribution.

## Repository and history

- Public history starts in a separate repository with one `Public alpha` commit made from the
  audited export. This separates published source from private history and working materials.
- The private repository keeps the full history and is never added as a remote of the public one.
  Commits already published to the private remote are not rewritten.
- Public commits are authored with the GitHub noreply address; the published contact address stays.
- The public CHANGELOG starts with an empty `[Unreleased]`; earlier entries remain private.
- New commit messages use English after publication; the current project rule applies until then.

## Public tree

- Working plans `docs/PLAN.md` and `docs/ru/PLAN.md`, and helper scripts `tools/run_phases.sh`
  and `tools/split_kt.py`, remain private. Shipped application assets belong in the public build.
- Remaining artwork in `docs/art/` stays private; its intended location is ignored `private/`.
- Root `AGENTS.md`, `CLAUDE.md` and local tool settings are excluded from the public tree
  but retained locally. Ignore rules cover `/AGENTS.md`, `/CLAUDE.md`, `private/`, `.claude/`,
  `.vscode/`, `*.log`, `.swiftpm/` and `.build/`.
- Public-document links must point only to published files: when preparing the tree, replace
  links to private plans and artwork with public documents or remove them.

### Export and audit

Use a committed snapshot with `git archive`, not a copy of the working directory or its `.git`.
Export exclusions are defined in `.gitattributes`; `.gitignore` alone does not exclude tracked files.
Private originals remain in this repository. Create `build/` if it does not exist, then run:

```sh
git archive --format=tar --output=build/public-alpha.tar HEAD
python -B tools/check_public_tree.py build/public-alpha.tar
```

The audit checks excluded paths, local Markdown link destinations, common credential signatures
and absolute user-home paths in UTF-8 text. Findings show locations without printing matched values.
Synthetic paths in test fixtures must be reviewed explicitly; a clean scan is not a guarantee that
all secrets were detected. Repeat the export and audit from the final commit before publication.
After reviewing every reported value, rerun with `--reviewed <digest>` using the printed digest.
This accepts credential/path findings for those exact archive bytes; structural failures cannot
be accepted. A changed archive requires a new review.

## Alpha artifacts

- macOS DMG and Windows MSI; signing is outside the first alpha's scope.
- Ship license texts in both packages and offer the exact Java runtime's complete corresponding
  source archive beside the installers, with its vendor checksum. Keep the vendor notices and
  runtime metadata so a package can be matched to its source; see [THIRD-PARTY](../THIRD-PARTY.md).
- A pushed `v*` tag runs [CI](../.github/workflows/ci.yml) with release steps and creates a draft
  prerelease with all assets; it is published by hand after review. A manual run with a tag input
  rehearses the same steps and uploads the assets as an artifact without publishing.
  The intended tag is `v<ver>-alpha.1`; it must match `appVersion`.
- Java source assets are selected from the packaged Temurin 21 metadata, verified against the
  vendor SHA-256 and bundled with original notices and runtime metadata. Each platform's
  provenance JSON links its installer and source ZIP; `SHA256SUMS` covers all uploaded assets.
- Unsigned installation steps are in [README](../README.md#unsigned-alpha-installation).
- `appVersion` / `appBuild` in `app/build.gradle.kts` are the version source;
  `appVersion` changes only at Sergey's explicit instruction.
- Windows installation design and user-data retention are in
  [ARCHITECTURE](ARCHITECTURE.md#windows-distribution).
