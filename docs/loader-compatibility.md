# Loader compatibility

This page documents behavior in the current jcompose loader. Docker Compose is the compatibility target; ComposeSharp is the feature synchronization source. Similar behavior in ComposeSharp does not by itself establish Docker Compose conformance.

## File discovery and project validation

The loader uses the requested file when it exists under the supplied working directory. When the requested name is one of the conventional defaults, it searches `compose.yaml`, `compose.yml`, `docker-compose.yaml`, and `docker-compose.yml` in that order in the working directory and then each parent directory. `loadProject` also merges the matching optional conventional override file beside the resolved base file (for example, `compose.yaml` with `compose.override.yaml`). A missing custom filename is treated as an explicit missing path and does not fall back to another file. Paths are normalized to absolute paths for loading. The YAML root must be a mapping with a nonempty `services` mapping. Each service must be a mapping and currently must declare either `image` or `build`.

YAML is loaded with SnakeYAML's safe constructor and duplicate mapping keys are rejected. Service values are exposed through `ServiceDefinition` as normalized mappings; this should not be mistaken for comprehensive schema validation. Consult the [field support matrix](compose-field-matrix.md) before using a field at runtime.

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

Process environment values take precedence over values in the project `.env` file. Defaults and replacement expressions, including nested forms such as `${MISSING:-${FALLBACK:-value}}`, are recursively interpolated, with a guard against excessive nesting. This is the current implementation contract; verify edge cases against Docker Compose before expanding compatibility claims.

`ComposeFileLoader.load`, `loadProject`, and `loadMerged` accept per-call environment overrides. For that call, the precedence is explicit overrides, then the process environment, then the project `.env`. For a multi-file load, the project directory defaults to the directory of the first Compose file, so the same `.env` supplies interpolation values to every merged file. Overrides are never written to the process environment, so separate or concurrent loads remain isolated. On Windows, override names are matched without regard to case; on other platforms they are case-sensitive.

The parser is intentionally smaller than the full Compose interpolation and dotenv grammars. The `.env` reader does not implement all quoting, escape, multiline, or format variants. Unsupported shell expressions are not evaluated. An unset variable without an operator becomes empty text, without Docker Compose's warning.

## Service `extends`

Services can extend another service in the same Compose file or in a separate file. The loader resolves nested references, merges maps recursively, appends ordinary sequences, merges `volumes` and `devices` by container target, and merges block-I/O device limits by device path. Values declared by the extending service override inherited mapping entries. Missing references and circular inheritance fail with service/property context. The external file path is resolved relative to the main Compose file; inherited build contexts, `env_file` paths, and relative bind sources are rebased so they continue to point to their original locations. Referenced services' dependencies and top-level resources are not imported automatically and must be declared by the project using the extended service. `include` remains unsupported.

## `.env` and service `env_file`

The project `.env` is read from the first Compose file's directory in a multi-file load and is used for YAML interpolation only. The reader accepts `=` or `:` delimiters, optional leading `export`, blank lines, full-line comments, unquoted and double-quoted interpolation, literal single-quoted values (including multiline values), escaped quotes, common double-quoted escapes, and inline comments following Docker Compose rules. Unquoted and double-quoted `.env` values can reference other `.env` values; process values take precedence. The parser does not implement every Docker Compose dotenv rule.

Service `env_file` values are container environment input and are interpolated separately from the Compose YAML. Scalar and list entries are read in declaration order; later files override earlier files, and inline `environment` values override file values. Mapping-shaped entries support `path`, `required: false`, and `format: raw`; a missing optional file is skipped, while a missing required file fails loading with service/path context. In normal format, `=` and `:` delimit key/value pairs, a bare key is an empty string, unquoted and double-quoted values interpolate against the process environment and the Compose file's `.env`, and single-quoted values are literal and may span lines. Common double-quoted escapes and escaped quotes are handled. By contrast, key-only `environment` entries and null map values pass through a matching host value, or are omitted if the host has no value. Raw format preserves each value after `=` (including whitespace, quotes, and `$`) without normal dotenv parsing; raw entries without `=` remain ignored. Other Compose dotenv edge cases are not implemented.

## Profiles and multi-file loading

The loader retains service profile values; operation-level selection is handled by the engine. A null service selection uses the active profiles in `ComposeProjectContext`; explicit selection and profile semantics are documented per operation as those operations are implemented.

`loadProject` applies the matching optional conventional override file. `loadMerged` accepts an explicit ordered list of Compose files, interpolates each file as it is read, then applies the incremental mapping/list merge described in [multi-file merge semantics](merge-semantics.md). These paths are not full Docker Compose merge compatibility. The `!reset` and `!override` overlay tags are recognized: `!reset` clears to the inherited value's empty collection shape or `null`, and `!override` replaces the inherited value. Other custom YAML tags and unsupported constructs are not treated as Compose directives. Service `extends` uses its own resolution and merge rules, described above; it is not an overlay operation.

## Compatibility changes

When changing loader behavior, record the exact ComposeSharp version/commit synchronized, check the behavior against Docker Compose documentation and observable behavior, and update this page, the [field matrix](compose-field-matrix.md), relevant merge documentation, and conformance evidence together. The ComposeSharp source is a convenient feature port reference, not the oracle for Docker Compose semantics.
