# Loader compatibility

This page documents behavior in the current jcompose loader. Docker Compose is the compatibility target; ComposeSharp is the feature synchronization source. Similar behavior in ComposeSharp does not by itself establish Docker Compose conformance.

## File discovery and project validation

The loader uses the requested file when it exists under the supplied working directory. When the requested name is one of the conventional defaults, it searches `compose.yaml`, `compose.yml`, `docker-compose.yaml`, and `docker-compose.yml` in that order in the working directory and then each parent directory. `loadProject` also merges the matching optional conventional override file beside the resolved base file (for example, `compose.yaml` with `compose.override.yaml`). A missing custom filename is treated as an explicit missing path and does not fall back to another file. Paths are normalized to absolute paths for loading. The YAML root must be a mapping with a nonempty `services` mapping. Each service must be a mapping. A service without `image` or `build` is retained during loading so inactive profile services can be loaded; runtime operations that select it reject it with an image/build diagnostic.

YAML is loaded with SnakeYAML's safe constructor and duplicate mapping keys are rejected. Service values are exposed through `ServiceDefinition` as normalized mappings. The loader validates supported shape and scalar conversions for service environment/build/deploy/healthcheck/logging fields, durations, byte values, booleans, and selected integers/numbers; this is still not comprehensive Compose schema validation. Healthcheck durations accept fractional and compound units such as `250ms`, `1.5s`, and `1m30s`, including `start_interval`. Consult the [field support matrix](compose-field-matrix.md) before using a field at runtime.

Invalid input raises `ComposeValidationException`. The exception exposes `sourceFile`, `propertyPath`, optional `serviceName`, and optional one-based parser `line`/`column` values, and retains the cause when available. Parser and validation messages are contextualized with the source path; the exact wording is not a stable compatibility interface.

## Variable interpolation

Interpolation runs on each Compose file before multi-file merging. It applies to unquoted and double-quoted YAML scalar values; mapping keys, comments, single-quoted values, and literal/folded block scalars are preserved without interpolation. The loader currently recognizes:

| Form | Current behavior |
| --- | --- |
| `$NAME`, `${NAME}` | Replaced with the resolved value, or an empty string if unset. |
| `$$` | Emits a literal `$`. |
| `${NAME:-default}` | Uses the default when the variable is unset or empty. |
| `${NAME-default}` | Uses the default only when the variable is unset. |
| `${NAME:?message}` | Fails when unset or empty, including the variable and message. |
| `${NAME?message}` | Fails only when unset. |
| `${NAME:+replacement}` | Uses replacement when set and nonempty; otherwise emits empty text. |
| `${NAME+replacement}` | Uses replacement when set, including an empty value; otherwise emits empty text. |

The loader module exposes `VariableInterpolator.expand(text, dotenv)` and `VariableInterpolator.expand(text, dotenv, strict)` for callers that need the same interpolation utility independently of loading a Compose project. Process environment values take precedence over the supplied dotenv map. The `strict` argument is reserved and currently has no effect, matching the ComposeSharp 3.0.0 contract.

Process environment values take precedence over values in the project `.env` file. Defaults and replacement expressions, including nested forms such as `${MISSING:-${FALLBACK:-value}}`, are recursively interpolated, with a guard against excessive nesting. This is the current implementation contract; verify edge cases against Docker Compose before expanding compatibility claims.

Malformed or unclosed braced expressions fail loading with the Compose property path and source location when they occur in a YAML value. A bare unset variable still becomes empty without Docker Compose's warning.

`ComposeFileLoader.load`, `loadProject`, and `loadMerged` accept per-call environment overrides. For that call, the precedence is explicit overrides, then the process environment, then the project `.env`. For a multi-file load, the project directory defaults to the directory of the first Compose file, so the same `.env` supplies interpolation values to every merged file. Overrides are never written to the process environment, so separate or concurrent loads remain isolated. On Windows, override names are matched without regard to case; on other platforms they are case-sensitive.

The parser is intentionally smaller than the full Compose interpolation and dotenv grammars. It supports the quoting, escaping, multiline, and comment forms listed above, but does not implement every dotenv edge case or the CLI's `--env-file` selection and project-directory discovery behavior. Unsupported shell expressions are not evaluated. An unset variable without an operator becomes empty text, without Docker Compose's warning.

## Multi-file overlays

The loader merges mappings recursively and appends ordinary service sequences. Docker Compose's duplicate-removal sequence fields (`cap_add`, `cap_drop`, placement constraints/preferences, generic resources, `device_cgroup_rules`, `expose`, `external_links`, and `security_opt`) preserve first-seen order and omit exact duplicate entries. Ports and target-keyed mounts/resources use their dedicated merge keys. See [merge semantics](merge-semantics.md) for the supported rules and gaps.

## Service `extends`

Services can extend another service in the same Compose file or in a separate file. The loader resolves nested references, merges maps recursively, appends ordinary sequences, removes duplicate entries from Compose's unique `extends` sequences, merges `volumes` and `devices` by container target, and merges block-I/O device limits by device path. Values declared by the extending service override inherited mapping entries. Enabling `healthcheck.disable` in the extending service is rejected unless the base service also disables its healthcheck. Missing references and circular inheritance fail with service/property context. The external file path is resolved relative to the main Compose file; inherited build contexts, `env_file` paths, and relative bind sources are rebased so they continue to point to their original locations. Referenced services' dependencies and top-level resources are not imported automatically and must be declared by the project using the extended service.

## `include`

Local Compose files can be included with a path string, a list of paths, or a mapping with `path`, optional `project_directory`, and optional `env_file`. A mapping's `path` may itself be a string or a list of Compose files; listed files are interpolated and merged in order into one included project before import, so an override can modify a service or resource from an earlier path. Includes are expanded recursively after the main project's explicit multi-file overlays have been merged. Their services, networks, volumes, secrets, and configs are copied into the project; a duplicate name between the resulting included project and the parent or another include is an error rather than a merge. Relative build contexts, service `env_file` paths and bind mounts, plus config/secret `file` paths, are rebased to the main project directory while preserving their included-project locations. The included project's `.env` is read from its project directory for interpolation. An explicit include `env_file` path or list of paths is read relative to the including file; later files override earlier ones, and the parent project's resolved environment takes precedence. Remote OCI/Git sources and other parts of the full Docker Compose include schema are not supported and fail rather than being treated as local files.

## `.env` and service `env_file`

The project `.env` is read from the first Compose file's directory in a multi-file load and is used for YAML interpolation only. The reader accepts `=` or `:` delimiters, optional leading `export`, blank lines, full-line comments, unquoted and double-quoted interpolation, literal single-quoted values (including multiline values), escaped quotes, common double-quoted escapes, and inline comments following Docker Compose rules. Unquoted and double-quoted `.env` values can reference other `.env` values; process values take precedence. Engine operations pass the project name from `ComposeProjectContext` as an explicit per-load `COMPOSE_PROJECT_NAME` override, which takes precedence over both. Direct `ComposeFileLoader` calls do not infer a project name; callers can pass an explicit environment override. The parser does not implement every Docker Compose dotenv rule.

Service `env_file` values are container environment input and are interpolated separately from the Compose YAML. Scalar and list entries are read in declaration order; later files override earlier files, and inline `environment` values override file values. Mapping-shaped entries support `path`, `required: false`, and `format: raw`; a missing optional file is skipped, while a missing required file fails loading with service/path context. In normal format, `=` and `:` delimit key/value pairs, a bare key passes through a matching process environment value or is omitted if unset, unquoted and double-quoted values interpolate against the process environment and the Compose file's `.env`, and single-quoted values are literal and may span lines. Common double-quoted escapes and escaped quotes are handled. Key-only `environment` entries and null map values pass through a matching host value; if unset, they remain null so they override a same-named `env_file` value and are omitted when creating the container. Raw format preserves each value after `=` (including whitespace, quotes, and `$`) without normal dotenv parsing; raw entries without `=` remain ignored. Other Compose dotenv edge cases are not implemented.

Service `label_file` accepts one path or a list of paths relative to the Compose project directory. Files use the service env-file key/value syntax and are interpolated with the process environment and project `.env`; files are read in order, later files override earlier ones, and inline `labels` override all file values. Loaded labels are normalized into the service's `labels` mapping for rendering and engine operations. Missing files fail loading with service/property context. Docker Compose introduced this field in version 2.32.2; consult the [Docker Compose services reference](https://docs.docker.com/reference/compose-file/services/) for its documented ordering and precedence.

## Profiles and multi-file loading

The loader retains service profile values and validates that `profiles` is an array of strings whose names match `[a-zA-Z0-9][a-zA-Z0-9_.-]+`; operation-level selection is handled by the engine. A null service selection uses the active profiles in `ComposeProjectContext`; explicit selection and profile semantics are documented per operation as those operations are implemented. Docker Compose CLI v5.5.1 accepts a profile-gated service without `image` or `build` when that profile is inactive; selecting it reports that the service has neither an image nor a build context. jcompose follows this load-versus-runtime validation boundary for `up`, `create`, `run`, and `scale`.

When present, `depends_on` must be an array of service names or a long-form mapping. Long-form entries require a valid `condition` (`service_started`, `service_healthy`, or `service_completed_successfully`); `restart` and `required`, when present, must be booleans. Dependency names must refer to declared services. Docker Compose CLI v5.5.1 rejects scalar/null forms, non-string list entries, non-mapping long-form values, and unknown conditions; `ComposeLoaderConformanceTest` covers these schema cases and a valid long form.

`links` must be a list of service names or `service:alias` entries, and each linked service must be declared in the project. The engine selects linked services as dependencies and starts them before the referencing service. An explicit alias is added to the linked service's endpoint on each shared project network, and changing that effective alias causes the linked service container to be recreated. `external_links` accepts existing container names with optional aliases and passes them to Docker Engine for service and one-off `run` container creation; the external container must already share a network with the consuming container. This follows Docker Compose's documented rule that links add hostnames but do not override network attachment. Link aliases with non-Compose `network_mode` values remain unsupported.

Service `networks` must be a list of network names or a mapping from network names to null or mapping configuration. List entries must be strings, and every referenced network must be declared or be the implicit `default` network. Network configuration options are retained, but their complete schema is not validated here.

`loadProject` applies the matching optional conventional override file. `loadMerged` accepts an explicit ordered list of Compose files, interpolates each file as it is read, then applies the incremental mapping/list merge described in [multi-file merge semantics](merge-semantics.md). These paths are not full Docker Compose merge compatibility. The `!reset` and `!override` overlay tags are recognized: `!reset` clears to the inherited value's empty collection shape or `null`, and `!override` replaces the inherited value. Other custom YAML tags and unsupported constructs are not treated as Compose directives. Service `extends` uses its own resolution and merge rules, described above; it is not an overlay operation.

For validation errors after a multi-file load, `sourceFile` points to the Compose file that contributed the invalid value, including values nested in maps and merged list items. When a later file replaces a value, its source takes ownership of that value; untouched base-file values retain their original source.

## Compatibility changes

When changing loader behavior, record the exact ComposeSharp version/commit synchronized, check the behavior against Docker Compose documentation and observable behavior, and update this page, the [field matrix](compose-field-matrix.md), relevant merge documentation, and conformance evidence together. The ComposeSharp source is a convenient feature port reference, not the oracle for Docker Compose semantics.

## Observed Docker Compose behavior

- Docker Compose CLI v5.5.1 accepts service names made of ASCII letters, digits, `_`, `-`, and `.`, and rejects spaces, `:`, `@`, `/`, and non-ASCII characters. `ComposeLoaderConformanceTest` covers accepted and rejected examples with service context in validation errors.
- Docker Compose service `container_name` values must match `[a-zA-Z0-9][a-zA-Z0-9_.-]+`; `ComposeLoaderConformanceTest` covers accepted and rejected names with a service property path.
- Docker Compose CLI v5.5.1 `config --format json`: an `env_file` bare key is omitted when the host variable is unset and resolves from the host when set. A key-only or null `environment` entry resolves from the host when set and still overrides a same-named `env_file` entry when unset. `ComposeFileLoaderTest` covers these cases in jcompose.
- Docker Compose CLI v5.5.1 `config --format json`: include `env_file` lists are applied in order, with the parent project's environment taking precedence over included defaults. `ComposeFileLoaderTest` covers list ordering and parent `.env` precedence.
- Docker Compose CLI v5.5.1 `config --format json`: `services.<name>.profiles` must be an array of strings, and each name must match `[a-zA-Z0-9][a-zA-Z0-9_.-]+`. `ComposeLoaderConformanceTest` covers valid and invalid profile names in jcompose.
- Docker Compose CLI v5.5.1 rejects malformed `depends_on` shape and values, including unknown long-form conditions/options and a missing long-form `condition`. `ComposeLoaderConformanceTest` covers these forms and valid long-form dependencies.
