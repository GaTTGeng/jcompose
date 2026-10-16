# jcompose

[![License](https://img.shields.io/github/license/GaTTGeng/jcompose.svg)](LICENSE)

[简体中文](README.zh-CN.md)

**jcompose** is a JDK library for applications that need to load a Docker Compose project and manage its resources through the Docker Engine API. It keeps the project context explicit, reads Compose configuration in process, and scopes owned resources with the familiar `com.docker.compose.*` labels.

Docker Compose is jcompose's compatibility target. [ComposeSharp](https://github.com/GaTTGeng/ComposeSharp) is a sibling project maintained by the same author and the feature/documentation synchronization source: work developed there is subsequently ported here. Its API and implementation do not define compatibility; Compose's documentation and observable behavior do. jcompose is not a wrapper around the `docker compose` executable and does not claim full Docker Compose CLI or Compose Specification compatibility.

## Project shape

```text
compose.yml + .env + env_file
              │
              ▼
       jcompose-loader
       Java project model and validation
              │
              ▼
       jcompose-engine
       Docker Engine resources and Compose labels
              │
              ▼
       IComposeService in your application
```

Project ownership is label-based. The project context carries the project name, working directory, Compose files, profiles, registry credentials, and Docker connection settings. Operations use these boundaries to find resources belonging to one project instead of scanning or removing unrelated Docker resources.

## Modules

| Module | Responsibility |
| --- | --- |
| `jcompose-api` | Java project models, operation options and results, callbacks, and the asynchronous service contract |
| `jcompose-loader` | Compose YAML loading, interpolation, dotenv and `env_file` handling, validation, and multi-file merging |
| `jcompose-engine` | Docker Engine integration and project-scoped operations |
| `jcompose-spring-boot-starter` | Spring Boot auto-configuration for `IComposeService`, `JComposeContextFactory`, and `jcompose.*` defaults |

The public Java API uses `CompletableFuture` for finite operations and `Flow.Publisher` for streaming events and watch notifications. It targets JDK 21 or newer.

The Maven compiler targets Java 21 bytecode. The current local compile check uses JDK 21.0.12 and Maven 3.9.9; a minimum Maven version and a supported Docker Engine version have not yet been established through a compatibility matrix.

## Start here

Build the Maven reactor from a checkout:

```powershell
mvn verify
```

An application can construct the engine service directly and pass an explicit project context:

```java
import io.github.gattgeng.jcompose.api.ComposeProjectContext;
import io.github.gattgeng.jcompose.engine.ComposeService;
import java.nio.file.Path;

var compose = new ComposeService();
var project = new ComposeProjectContext("inventory-dev", Path.of("C:/src/inventory"));

var config = compose.loadProject(project).join();
System.out.println(config.services().keySet());
```

The API and engine modules are currently in active development. The sample shows the loading boundary; consult the operation documentation and support matrix before relying on a runtime operation or field.

### Spring Boot

Add `jcompose-spring-boot-starter` to a Spring Boot 3.5 application. The starter registers an `IComposeService` bean and a `JComposeContextFactory`:

```java
@Service
class InventoryCompose {
    private final IComposeService compose;
    private final JComposeContextFactory contexts;

    InventoryCompose(IComposeService compose, JComposeContextFactory contexts) {
        this.compose = compose;
        this.contexts = contexts;
    }

    void load(Path workingDirectory) {
        var project = contexts.create("inventory-dev", workingDirectory);
        compose.loadProject(project).join();
    }
}
```

Configure optional defaults with `jcompose.default-project-name` and `jcompose.default-socket-path`. The socket path accepts the Docker endpoint syntax supported by docker-java; leave it unset to use docker-java discovery. The project factory prefers Docker Compose's canonical `compose.yaml` filename and falls back to other conventional Compose filenames when it is absent. Each caller can provide profiles or a different Compose file.

## What the loader understands

The loader reads Compose YAML, `.env`, and service `env_file` inputs, performs variable interpolation, and creates project/service models. The current implementation is an incremental subset, not a complete Compose Specification parser. It retains raw service mappings for fields that do not yet have a fully typed Java representation or Docker Engine behavior.

### Variable interpolation and environment files

The loader supports `$NAME`, `${NAME}`, `$$`, and the `:-`, `-`, `:?`, `?`, `:+`, and `+` interpolation operators. The process environment takes precedence over project `.env` values. Service `env_file` values contribute to that service's container environment; they are not an interpolation source. Inline environment values take precedence over values from those files.

See [loader compatibility](docs/loader-compatibility.md) for precedence, operator behavior, and current limits. For multi-file input, see [merge semantics](docs/merge-semantics.md). These rules are documented as jcompose behavior and must be checked against Docker Compose; they are not inherited as compatible merely because ComposeSharp implements similar logic.

Service `extends` supports same-file and external-file references, recursive inheritance, and Compose-specific merge behavior. External inherited build, env-file, and bind paths are rebased to the project file. Dependencies and top-level resources still need to be declared by the project; see [loader compatibility](docs/loader-compatibility.md) for the current boundary.

## What the engine does today

The engine is being ported from the ComposeSharp feature scope, one documented and reviewable slice at a time. The current snapshot implements a subset of image builds and publishing, basic `up`/`create` reconciliation, one-off `run`, host/container `copy`, `scale` and `wait`, `exec`/`attach`, local build-context watch events, container filesystem export and image commit, selected label-scoped operations for existing Docker resources, project container queries, and a normalized configuration snapshot. These operations have explicit limitations and are not complete Docker Compose equivalents; see the compatibility matrix for current gaps.

| Area | Current boundary |
| --- | --- |
| Project lifecycle | Selected start, stop, restart, pause, unpause, kill, remove, and down operations act on existing resources carrying the project label. |
| Inspection | `ps` and `list` query project-labeled containers; `top`, `images`, `port`, and `volumes` expose limited daemon-backed inspection. `logsAsync` streams container output to an `ILogConsumer`. A declared API method or options record alone does not imply complete implementation. |
| Generation | `generateAsync` renders a normalized YAML snapshot from configuration retained by the loader; it does not connect to Docker or write files. |
| Orchestration | Basic service reconciliation, one-off `run`, host/container `copy`, replica scaling, exit waiting, selected dependency conditions, health checks, declared network/volume resource definitions, project network attachments, DNS settings, CPU/memory limits, image builds/publishing, `exec`/`attach`, build-context watch events, container filesystem export, and image commit are implemented as subsets; full Compose lifecycle behavior is in progress. |

The [Docker Compose field support matrix](docs/compose-field-matrix.md) is the source of truth for field-level and operation status. For example, `volumesAsync` lists project-labeled Docker volumes and applies an explicit service selection across running and stopped project containers. The [roadmap](docs/roadmap.md) records the complete 3.0.0 synchronization scope and release criteria.

## Docker Compose compatibility

The compatibility target is Docker Compose file loading and project behavior within the subset explicitly documented in this repository. The ComposeSharp 3.0.0 baseline and later ComposeSharp development determine which feature work is synchronized; Docker Compose documentation and observable Docker Compose behavior determine whether jcompose's implementation is compatible.

For a compatibility report, include a minimal Compose file, expected behavior from Docker Compose, observed jcompose behavior, the jcompose commit, and Docker Engine/JDK versions. Update the field matrix and relevant operation contract alongside any behavior change. Do not treat a matching method name, model field, or ComposeSharp implementation as conformance evidence.

## Documentation

- [Docker Compose field support matrix](docs/compose-field-matrix.md)
- [Loader compatibility](docs/loader-compatibility.md)
- [Multi-file merge semantics](docs/merge-semantics.md)
- [Generate and watch contract](docs/generate-watch.md)
- [Roadmap and completion criteria](docs/roadmap.md)
- [Maintainer release guide](docs/maintainer-release.md)
- [Contributing](CONTRIBUTING.md)
- [Code of Conduct](CODE_OF_CONDUCT.md)
- [Support](SUPPORT.md)
- [Security policy](SECURITY.md)
- [Changelog](CHANGELOG.md)

## Release status

The repository is currently on `3.0.0-SNAPSHOT`. This is not the completed 3.0.0 port and no JDK artifact has been published. The first JDK release is intentionally the last step, after the feature scope, compatibility evidence, repository configuration, CI, and release contents are complete and reviewed. See the [maintainer release guide](docs/maintainer-release.md).

## License

MIT. See [LICENSE](LICENSE).
