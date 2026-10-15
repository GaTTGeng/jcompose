# Loader compatibility

The loader's interpolation runs over raw Compose YAML before YAML parsing. For parity with ComposeSharp 3.0.0, it supports `$NAME`, `${NAME}`, `$$`, and the `:-`, `-`, `:?`, `?`, `:+`, and `+` operators.

The process environment takes precedence over the project's `.env` values. For `:-`, `:?`, and `:+`, an unset or empty value activates the operator; the non-colon forms treat only an unset value as missing. Required-variable operators fail loading and include the variable name and optional message. An unresolved variable without an operator becomes an empty string. `$$` produces a literal dollar sign.

Service `env_file` entries are container environment input. They do not participate in interpolation of the Compose YAML. During service environment resolution, files are applied in declaration order and inline `environment` entries take precedence over them.

The implementation is a targeted port of the ComposeSharp 3.0.0 loader contract, not a claim of complete Docker Compose Specification coverage. Changes to these rules must update the conformance tests and this page together.
