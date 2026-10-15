# jcompose

**jcompose** is a Java/JDK implementation of the managed Docker Compose SDK contract in [ComposeSharp 3.0.0](https://github.com/GaTTGeng/ComposeSharp/tree/3.0.0). The target is API and behavior parity for that release; ComposeSharp is the reference project, not this project's name.

> Current status: early implementation (`3.0.0-SNAPSHOT`). The project has API, Compose file loader, Docker Engine integration, and Spring Boot starter modules. Compose operation implementations and full parity are still in progress. Do not treat this snapshot as the completed 3.0.0 release.

## Modules

| Module | Responsibility |
| --- | --- |
| `jcompose-api` | Java project models, operation options, results, and asynchronous service contract |
| `jcompose-loader` | Compose YAML loading, interpolation, dotenv/env files, validation, and overlay merging |
| `jcompose-engine` | Docker Engine client integration and Compose operations |
| `jcompose-spring-boot-starter` | Spring Boot integration |

## Requirements

- JDK 21 or newer
- Maven 3.9 or newer
- Docker Engine for integration/runtime operations

## Build

```powershell
mvn verify
```

## Compatibility target

jcompose aims to reproduce the documented contract and supported field subset of ComposeSharp 3.0.0, including project labels, Compose loading, profiles, build/up/down and container operations, archive copy/export, watch/events, publishing outcomes, and diagnostics. The compatibility matrix and operation-level behavior will be documented as implementation is completed. Similar names or versions do not imply source or binary compatibility with the .NET library.

Loader interpolation and environment precedence are documented in [docs/loader-compatibility.md](docs/loader-compatibility.md).

## License

MIT. See [LICENSE](LICENSE).
