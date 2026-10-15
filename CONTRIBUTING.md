# Contributing

Thanks for considering a contribution to jcompose.

## Development setup

Install JDK 21 or newer and Maven 3.9 or newer. From the repository root, run:

```powershell
mvn verify
```

Tests that require a running Docker Engine should identify that requirement and skip cleanly when the daemon is unavailable. Do not report Docker-backed behavior as verified unless it was exercised against Docker.

## Pull request guidelines

- Keep changes focused and include tests for behavior changes.
- Do not commit `target/`, IDE metadata, generated packages, local Compose overrides, or secrets.
- Keep Docker resource ownership scoped to the Compose project labels.
- Validate compatibility changes against Docker Compose, not merely against ComposeSharp.
- Update public API documentation, both README files, the relevant `docs/*` pages, and `CHANGELOG.md` in the same pull request when behavior or compatibility claims change.
- Include a minimal Compose file or Docker reproduction when fixing compatibility behavior.

## Feature synchronization

ComposeSharp is the sibling implementation maintained by the same author and the source of features to synchronize. Start from the current ComposeSharp development state when the task requests a sync, and record the exact ComposeSharp version or commit in the change summary so the source is reproducible. Translate its public contract and documentation to Java/JDK rather than copying .NET-specific APIs or claims. For behavior and file-format compatibility, independently check Docker Compose documentation and observable behavior; ComposeSharp is not the compatibility oracle. If the source implementation differs from Docker Compose, preserve the Docker Compose target and document the decision and any supported subset.

## Documentation and changelog

Documentation is part of the change. Keep `README.md` and `README.zh-CN.md` aligned, and distinguish implemented, partial, parsed-only, planned, and unsupported behavior.

| Change | Update in the same pull request |
| --- | --- |
| Public API, options, results, or call semantics | API documentation and both README files |
| Parsed versus applied Compose fields | `docs/compose-field-matrix.md` |
| Multi-file loading or merge behavior | `docs/merge-semantics.md` and loader documentation |
| Generate/Watch contract | `docs/generate-watch.md` |
| Planned work or completed milestone | `docs/roadmap.md` |
| Release, tag, or publishing process | `docs/maintainer-release.md` |
| User-visible feature, fix, or breaking change | `CHANGELOG.md` under `[Unreleased]` |

Changelog entries describe user-visible outcomes, not tests or review steps. Move `[Unreleased]` entries into a dated section when preparing a release.

## Release process

JDK artifact publication is the final step after implementation, compatibility evidence, repository checks, and release review are complete. Do not create a release tag as a side effect of feature development. See [the maintainer release guide](docs/maintainer-release.md).
