# jcompose roadmap

This roadmap describes work required to complete the ComposeSharp 3.0.0 release scope in Java while treating Docker Compose as the behavior and file-format compatibility target. As ComposeSharp continues development, its new work is also the ongoing feature synchronization source. Record the exact source version or commit for each sync. Docker Compose documentation and observable behavior decide compatibility.

## Synchronization checkpoint

The ComposeSharp source checkpoint is `3.0.0-32-g648a430` (`648a430`). Since `3.0.0-28-gd8d8992`, the remote adds four test-dependency update commits and no loader or engine changes. jcompose has verified the per-call merge environment override behavior from `d8d8992`. This sync adds Docker-backed coverage for the default project visibility behavior from `bf0dca7`: Docker's non-`All` container listing keeps paused/restarting projects visible while excluding stopped-only projects. The checkpoint does not claim that every feature in the source snapshot has been ported. For each later sync, record the ComposeSharp tag/commit in the change summary and update this checkpoint when the repository intentionally moves to a new source snapshot. Keep the Docker Compose compatibility target unchanged.

## Current milestone: 3.0.0 parity foundation

ComposeSharp 3.0.0 is the initial sibling feature baseline maintained by the same author. Newer ComposeSharp development is the ongoing feature source to sync. These sources define requested feature scope, while Docker Compose documentation and observed behavior define compatibility. This is a target scope, not a claim that the current jcompose snapshot has parity.

### Repository and API

- Keep the public repository, Maven reactor, module names, Java package names, and documentation consistently branded as jcompose.
- Define Java-idiomatic immutable option types and typed results for the complete operation surface.
- Define cancellation, asynchronous completion, streaming, error, and resource ownership contracts.
- Keep `AGENTS.md` as local maintainer guidance and excluded from Git.
- Keep GitHub repository metadata, Maven coordinates, CI, issue links, license, and both README files branded consistently as `jcompose`.
- Keep GitHub synchronized with reviewed and committed source; a local IDE project window or Git initialization alone does not establish remote repository readiness.

### Compose loading

- Validate interpolation, `.env`, `env_file`, profiles, common service fields, and useful diagnostics against Docker Compose.
- Implement Docker Compose multi-file merge rules, including unique resources and supported merge tags, or explicitly reject unsupported constructs with clear diagnostics.
- Track parsed fields separately from fields applied to Docker Engine in the [support matrix](compose-field-matrix.md).
- Keep English and Simplified Chinese documentation aligned as loader behavior changes.

### Docker Engine operations

Implement and verify the synchronized operation surface: build/up/down, create/start/stop/restart, pull/push, kill/run/exec/attach, remove/copy, pause/unpause, ps/list/top/images/port/logs/events, scale/wait/watch, export/commit/viz/generate/volumes/publish. Each operation needs documented Docker Engine behavior, structured results, cancellation, and meaningful failure reporting.

The API exposes the synchronized operation surface, while each engine method currently implements only the subset documented in the [field support matrix](compose-field-matrix.md). Do not present an operation as fully Docker Compose-compatible until its options, cancellation, results, and failure behavior are established.

### Java integration

- Implement the Spring Boot starter with predictable configuration and lifecycle behavior. The starter auto-configures `IComposeService`, binds `jcompose.*` defaults, and provides `JComposeContextFactory`; focused tests cover property binding, context defaults, and back-off for a custom service. Lifecycle review found no service-level resources to close: operations own and close their Docker clients, callers cancel operation futures, and Flow subscriptions release stream resources when cancelled, so the bean needs no shutdown hook.
- Build a usable Java API example and document supported JDK, Maven, and Docker Engine versions.
- Configure cross-platform CI and Docker-backed integration verification.
- Decide and document Maven artifact hosting, namespace ownership, source/Javadoc packaging, signing, and release automation before the first publication.

## Completion criteria

3.0.0 is ready for release only when the synchronized public feature scope is implemented, the supported Docker Compose subset and gaps are explicit, loader and operation behavior have conformance evidence, all modules pass the full verification workflow, and release docs/configuration are reviewable. Publishing the JDK artifacts is the last step; see [maintainer release guide](maintainer-release.md).

## Compatibility references

- [Docker Compose file reference](https://docs.docker.com/reference/compose-file/)
- [Interpolation](https://docs.docker.com/reference/compose-file/interpolation/)
- [Merge](https://docs.docker.com/reference/compose-file/merge/)
- [Compose Watch](https://docs.docker.com/compose/how-tos/file-watch/)

Feature-sync reports should include the ComposeSharp version/commit, a minimal Compose file or Java example, expected Docker Compose behavior, observed jcompose behavior, and relevant Docker Engine/JDK versions.
