# Generate and watch contracts

This page records the current jcompose behavior for two operations in the ComposeSharp feature synchronization scope. Docker Compose remains the compatibility target. These operations have distinct purposes: `generateAsync` serializes configuration retained by the loader; `watchAsync` is intended to expose filesystem changes through the JDK Flow API. A ComposeSharp implementation is a porting reference, not compatibility evidence.

## `generateAsync`

`generateAsync` loads the local Compose project and returns a typed `ComposeProjectConfig` summary with a normalized YAML snapshot in `renderedYaml`. Its `environment` map contains the effective interpolation environment: process environment values override values from the project `.env`, and the project name from `ComposeProjectContext` is exposed as `COMPOSE_PROJECT_NAME`, overriding both. This includes variables that the YAML does not reference. It does not require a Docker connection and does not write a file. `loadProject` synchronously returns the same typed summary without generated YAML, matching the ComposeSharp 3.0.0 contract. `loadProjectAsync` provides the same configuration load through a `CompletableFuture`. Both honor the profile selection in `ComposeProjectContext`.

Service selection uses the explicit service list when provided, including services outside the active profiles. A null selection follows the profile selection in `ComposeProjectContext`; an empty list selects no services. Unknown service names fail. Selection does not add a service's dependencies. The project-name option changes only the returned document and summary, not Docker resource ownership. Nonempty legacy `containers` selections fail because generation serializes configuration rather than inspecting containers.

The output is a normalized snapshot of the loader's retained model, not a source-preserving round trip. It can include fields and resource names the model retains, but it cannot restore comments, original formatting/quoting, unknown fields, or syntax details lost by loading. Resource definitions whose options are not retained cannot be reconstructed from their names alone. `env_file` references are omitted when values have already been folded into the service environment. Resolved environment values may contain credentials or other secrets; handle generated YAML as sensitive data.

Paths in the snapshot remain relative to the original Compose project directory. Moving generated YAML to a different directory can therefore change how those paths resolve. Deploy values and other shapes preserved by the loader should not be interpreted as proof of full Compose CLI output compatibility. See the [field support matrix](compose-field-matrix.md) for loader limitations and retained fields.

Example:

```java
var generated = service.generateAsync(context, options).join();
System.out.println(generated.renderedYaml());
```

The rendered document is available from `ComposeProjectConfig.renderedYaml()`.

## `watchAsync`

`watchAsync` returns a JDK `Flow.Publisher<WatchEvent>`. For a selected service with `develop.watch` rules, it recursively watches each rule's local `path`, applies `include` and `ignore` glob filters, performs the configured `rebuild`, `restart`, `sync`, `sync+restart`, or `sync+exec` action, then publishes the event. Unless `noUp` is set, it first builds and starts the selected services. Services without rules retain the SDK behavior of watching their local build contexts and publishing `rebuild` notifications without performing a rebuild.

The publisher honors subscriber demand and cancellation. Projects with no selected local build contexts complete without notifications. A watcher registration or queue-overflow failure is delivered to the subscriber as `onError`. Events for the same service, action, and path are coalesced during a 500 ms quiet period; services sharing a context each receive their own event. Editor temporary files are filtered using the Docker Compose watch exclusions for GoLand, Emacs, Vim, Kate, and Go temporary files, including `.idea` directories. When `ComposeWatchOptions.prune` is enabled, a rebuild removes dangling images labeled for the current project after the service has been recreated. Images without that project label and images that still have a tag are preserved.

For sync actions, `initial_sync: true` copies matching regular files under the rule path before the file watcher starts. `.dockerignore` patterns from the service build context, `.git` directories, and the editor temporary-file patterns described above are excluded from initial sync and subsequent watch events. Matching file changes are copied into the corresponding target path; deleted files and directories are removed from the container with `rm -rf`. The container image therefore needs `rm` and write permission for the target. String `sync+exec` commands use `/bin/sh -c`. See [Compose Watch](https://docs.docker.com/compose/how-tos/file-watch/) for the compatibility target.

## Conformance evidence

When adding or changing either operation, keep this page aligned with the implementation and link only to verification that has actually run. Cover profile and explicit service selection, invalid service names, generated secrets handling, relative paths, subscriber cancellation/back-pressure, missing build contexts, repeated/shared-path notifications, and watcher failure behavior as applicable. Do not list planned checks as completed evidence.
