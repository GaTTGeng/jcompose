# Changelog

All notable changes to jcompose are documented in this file.

This project follows [Semantic Versioning](https://semver.org/). JDK artifacts have not been published; `3.0.0-SNAPSHOT` is a development version and makes no claim of feature parity with ComposeSharp or compatibility with Docker Compose.

## [Unreleased]

### Added

- Ran Docker Compose `pre_start` hooks in ordered ephemeral containers with service environment, network, and volume access before service containers start; hook failures now block service startup.
- Rejected malformed or unclosed Compose interpolation expressions with the YAML property path and source location.
- Loaded recursive local Docker Compose `include` files, merging each mapping's ordered `path` list into one included project before import, rebasing included file paths, and rejecting cross-project name conflicts; remote OCI/Git includes remain unsupported.
- Implemented the supported Docker Compose `develop.watch` actions (`rebuild`, `restart`, `sync`, `sync+restart`, and `sync+exec`) with path filters, initial sync, and sync targets.
- Rejected non-string service `stop_signal` values with the Compose property path in the validation error.
- Added per-call environment overrides for multi-file merges without changing process-wide environment variables.
- Passed the primary build image and all distinct `build.tags` values to Docker Engine in one build request.
- Skipped `top` results when a container exits or is removed between project listing and process inspection; other Docker errors remain visible.
- Matched ComposeSharp's Docker event mapping by preserving missing actor attributes as `null` and ensuring event action and ID fields fall back to empty strings.
- Mapped both list and mapping forms of Compose build `extra_hosts` to Docker Engine.
- Preserved Unix FIFOs in build-context archives, skipped Unix sockets, and rejected Unix device nodes to match ComposeSharp's archive behavior.
- Aligned Docker ignore parsing and glob matching with ComposeSharp for indented `#` patterns, escaped character-class members, Unicode scalar `?`/ranges, and descending ranges.
- Applied Compose `blkio_config`, GPU and deploy device reservations to service and one-off containers, and deploy restart policies to service containers.
- Parsed fractional and composite Compose duration values such as `1.5s` and `1m30s`.
- Parsed fractional Compose byte sizes such as `1.5gb` for memory and block I/O settings.
- Matched Docker Compose image pull behavior for the `if_not_present` alias, cached `latest` tags, and explicit `pull` policy handling (`always`, `missing`, `never`, `build`, and refresh windows).
- Applied service-level `platform` as the fallback platform for explicit image pulls.
- Deduplicated successful pulls when multiple selected services share the same image reference.
- Honored offline project contexts by disabling automatic image pulls and rejecting explicit pull, push, and publish operations.
- Aligned `wait` service selection with project profiles and included selected running one-off containers; Docker API wait errors yield `-1` for that container.
- Applied shared profile-aware existing-service selection to lifecycle operations, `down`, `ps`, `top`, `images`, and `logs`, with label-only fallback when the Compose file is absent and no profiles are active.
- Aligned `stop` completion with ComposeSharp by polling container state for up to five seconds after a successful stop request; removed containers count as stopped.
- Applied service `stop_grace_period` to stop, restart, down, and remove-with-stop operations when no operation timeout overrides it.
- Matched ComposeSharp's per-container Docker API error handling for lifecycle batches so a failed action does not prevent eligible remaining containers from being processed.
- Made `remove` with `stop` enabled finish the stop phase for the selected containers before beginning removal.
- Implemented `wait`'s `downProjectOnContainerExit` option: the first watched exit tears down all project containers and project networks before wait results are returned.
- Applied `logs` `since` and `until` filters for Unix timestamps, ISO-8601 dates/timestamps, and relative durations.
- Applied `ps` status filtering through the `status` option and Docker Compose's supported `filter=status=...` expression.
- Implemented `down.removeImages` for `all` and `local` image removal policies.
- Implemented `down.removeOrphans` for project containers whose services are absent from the Compose file.
- Applied `ps.quiet` by returning container IDs without summary fields.
- Applied `up.alwaysRecreateDeps` to dependency services and rejected incompatible recreate options.
- Reused explicitly configured and image-declared anonymous volumes by replica during `up` recreation; `renewAnonVolumes` now creates new volumes.
- Matched volume merge targets with Windows drive-letter colons in short syntax, including target replacement across overlay files.
- Honored `up.detach=false` by following selected service logs through `ILogConsumer` until their streams end.
- Applied `images` quiet mode by returning image IDs without summary fields.
- Filtered listed project volumes by the mounts used by explicitly selected running services.
- Established the Maven multi-module repository for the API, loader, Docker Engine integration, and Spring Boot starter.
- Added Compose loading, interpolation, environment-file handling, and an incremental multi-file merge implementation.
- Added the initial Docker Engine project-label-scoped container lifecycle implementation.
- Added initial project-scoped container listing and Compose project discovery operations.
- Added initial image pull/push operations and project-scoped volume discovery, graph generation, and configuration generation.
- Added daemon-backed `top`, `images`, and `port` inspection subsets for project containers.
- Added project-scoped container log streaming with service filtering, tail, timestamps, and ANSI stripping options.
- Implemented `generateAsync` to return a profile-filtered normalized Compose YAML snapshot without Docker daemon access.
- Expanded the documentation set to match ComposeSharp's project-documentation categories, rewritten for jcompose's JDK API and actual implementation status.
- Applied multiple project-network attachments, network aliases and endpoint addresses, DNS settings, and additional CPU, memory, OOM, and UTS options to service and one-off containers.
- Preserved top-level network and volume definitions through loading and configuration rendering; applied supported custom names, external-resource lookup, drivers, network flags, IPAM ranges, and driver options.
- Preserved top-level secret and config definitions through loading and configuration rendering; mounted granted file-backed resources read-only for service and one-off containers, with explicit errors for unsupported source types and mount attributes.
- Reported the optional conventional override file in generated project configuration metadata when it contributes to the loaded project.

### Fixed

- Honored `start.attach` by following selected service logs, with `attachTo` filtering and validation against the start selection.
- Created Docker-managed anonymous volume mounts for target-only service volumes in one-off `run` containers.
- Made `wait` aggregate exit codes deterministically by container ID, regardless of Docker wait completion order.
- Matched Docker Compose's profile handling by loading inactive services without `image` or `build` and rejecting them with a clear diagnostic when a runtime operation selects them.
- Validated service `profiles` as an array of strings, matching Docker Compose's loader behavior for malformed profile declarations.
- Validated `depends_on` short and long forms, including dependency names, supported conditions, and boolean `restart`/`required` options against Docker Compose CLI behavior.
- Applied profile activation to explicitly selected services and dependency resolution; inactive required dependencies fail clearly, while inactive optional dependencies are skipped for create/up/start and `pull --include-deps`.
- Rejected `volumes_from` service references whose profiles are inactive, matching Docker Compose's invalid-profile dependency behavior.
- Matched multi-file port entries by Docker Compose's unique key when an unbracketed IPv6 short entry overlays a long-form port.
- Resolved key-only and null `environment` entries from host values so they override same-named `env_file` values as Docker Compose does.
- Matched Docker Compose `env_file` behavior for bare keys by passing through a host value when set and omitting the key when unset.

### Changed

- Clarified that ongoing ComposeSharp development is the feature synchronization source while Docker Compose remains the compatibility target.
- Expanded the field matrix to distinguish loader retention, engine behavior, and operations that are still declarations only.
