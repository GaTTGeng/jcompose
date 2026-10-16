# Multi-file merge semantics

`ComposeFileLoader.loadProject` automatically merges the matching optional conventional override file when present. `ComposeFileLoader.loadMerged` accepts an explicit ordered list of Compose files. In either case, the first file is the base and each following file is an overlay. The merged project resolves relative paths from the first file's directory, matching Docker Compose's base-file path rule.

The current implementation is an incremental subset, not full Docker Compose merge compatibility. These rules describe current jcompose behavior; ComposeSharp is the feature synchronization source, while Docker Compose defines the compatibility behavior:

| YAML shape or field | Current jcompose behavior |
| --- | --- |
| Scalars, `null`, or a change between YAML shapes | The later value replaces the earlier value. |
| Mappings | Recursively merges keys; later values replace matching scalar leaves. |
| Standard lists | Appends every overlay entry, preserving duplicates and order. |
| Service `ports` lists | Replaces entries with the same parsed `ip`, `target`, `published`, and `protocol` key for short and long entries, including ranges and Docker Compose's unbracketed IPv6 short form; other entries are retained. |
| `environment` and `labels` list form | Replaces an earlier entry with the same key (the text before `=` or `:`). |
| Service `volumes`, `secrets`, and `configs` lists | Replaces an earlier entry with the same target when the short or long form can be identified. Short volume syntax accounts for drive-letter colons in the covered Windows source and target forms; all path and syntax combinations are not verified. |
| `command`, `entrypoint`, and `healthcheck.test` | The overlay list replaces the base list. |
| Top-level `volumes`, `networks`, `secrets`, `configs` | Resource mappings merge recursively by name. |

The loader resolves project-relative paths from the first Compose file. The merge implementation also interpolates each file while it is read, before overlaying its parsed mapping.

Service `extends` is resolved separately from multi-file overlays. It accepts same-file and external service references, follows nested references, and merges inherited maps and sequences using the Compose service-extension rules: mappings merge recursively, ordinary sequences append, `cap_add`, `cap_drop`, `configs`, `deploy.placement.constraints`, `deploy.placement.preferences`, `deploy.reservations.generic_resources`, `device_cgroup_rules`, `expose`, `external_links`, `ports`, `secrets`, and `security_opt` remove exact duplicates, and `volumes`, `devices`, and block-I/O device limits replace entries by target/path. Inherited relative build contexts, `env_file` paths, and bind sources are rebased to the main Compose file so they continue to refer to their original locations. The loader rejects missing and circular service references. It does not automatically import dependencies or top-level resources referenced by an extended service; `include` is not implemented.

Docker Compose defines unique-resource merge keys, special handling for several fields, and the `!reset` / `!override` tags. jcompose recognizes both tags on overlay values. `!override` uses the supplied value as a full replacement, bypassing recursive/list merging. `!reset` ignores its supplied value and clears the inherited value to an empty mapping/list when the inherited value has that shape, or to `null` otherwise. Other custom YAML tags still fail loading. Other Compose-specific edge cases remain unverified. `include` resolution is not implemented.

See Docker's [merge reference](https://docs.docker.com/reference/compose-file/merge/), [service sequence rules](https://docs.docker.com/reference/compose-file/services/#sequences), and [multiple-file guide](https://docs.docker.com/compose/how-tos/multiple-compose-files/merge/) for the compatibility target. When porting merge behavior from ComposeSharp, verify it independently against those references and Docker Compose itself, then update this table and its conformance evidence.

Interpolation currently runs while each file is read, before the merged model is built. That matches Docker Compose's per-file interpolation ordering, but the interpolation grammar and merge rules remain incomplete. See [loader compatibility](loader-compatibility.md).
