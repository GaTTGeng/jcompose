# Changelog

All notable changes to jcompose are documented in this file.

This project follows [Semantic Versioning](https://semver.org/). JDK artifacts have not been published; `3.0.0-SNAPSHOT` is a development version and makes no claim of feature parity with ComposeSharp or compatibility with Docker Compose.

## [Unreleased]

### Added

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

### Changed

- Clarified that ongoing ComposeSharp development is the feature synchronization source while Docker Compose remains the compatibility target.
- Expanded the field matrix to distinguish loader retention, engine behavior, and operations that are still declarations only.
