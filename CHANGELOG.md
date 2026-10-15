# Changelog

All notable changes to jcompose are documented in this file.

This project follows [Semantic Versioning](https://semver.org/). JDK artifacts have not been published; `3.0.0-SNAPSHOT` is a development version and makes no claim of feature parity with ComposeSharp or compatibility with Docker Compose.

## [Unreleased]

### Added

- Applied Compose `blkio_config`, GPU and deploy device reservations to service and one-off containers, and deploy restart policies to service containers.
- Parsed fractional and composite Compose duration values such as `1.5s` and `1m30s`.
- Parsed fractional Compose byte sizes such as `1.5gb` for memory and block I/O settings.
- Matched Docker Compose image pull behavior for the `if_not_present` alias, cached `latest` tags, and explicit `pull` policy handling (`always`, `missing`, `never`, `build`, and refresh windows).
- Applied service-level `platform` as the fallback platform for explicit image pulls.
- Deduplicated successful pulls when multiple selected services share the same image reference.
- Honored offline project contexts by disabling automatic image pulls and rejecting explicit pull, push, and publish operations.
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

### Changed

- Clarified that ongoing ComposeSharp development is the feature synchronization source while Docker Compose remains the compatibility target.
- Expanded the field matrix to distinguish loader retention, engine behavior, and operations that are still declarations only.
