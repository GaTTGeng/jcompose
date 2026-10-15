# Multi-file merge semantics

`ComposeFileLoader.loadMerged` accepts an ordered list of Compose files. The first file is the base and each following file is an overlay. The merged project resolves relative paths from the first file's directory, matching Docker Compose's base-file path rule.

The current implementation is an incremental subset, not full Docker Compose merge compatibility. These rules describe current jcompose behavior; ComposeSharp is the feature synchronization source, while Docker Compose defines the compatibility behavior:

| YAML shape or field | Current jcompose behavior |
| --- | --- |
| Scalars, `null`, or a change between YAML shapes | The later value replaces the earlier value. |
| Mappings | Recursively merges keys; later values replace matching scalar leaves. |
| Standard lists | Appends overlay entries not already present. |
| Service `ports` lists | Replaces entries with the same parsed `ip`, `target`, `published`, and `protocol` key for short and long entries; other entries are retained. Port ranges and every short/long syntax combination are not yet verified. |
| `environment` and `labels` list form | Replaces an earlier entry with the same key (the text before `=` or `:`). |
| Service `volumes`, `secrets`, and `configs` lists | Replaces an earlier entry with the same target when the short or long form can be identified. |
| `command`, `entrypoint`, and `healthcheck.test` | The overlay list replaces the base list. |
| Top-level `volumes`, `networks`, `secrets`, `configs` | Resource mappings merge recursively by name. |

The loader resolves project-relative paths from the first Compose file. The merge implementation also interpolates each file while it is read, before overlaying its parsed mapping.

Docker Compose defines unique-resource merge keys, special handling for several fields, and the `!reset` / `!override` tags. jcompose recognizes both tags on overlay values. `!override` uses the supplied value as a full replacement, bypassing recursive/list merging. `!reset` ignores its supplied value and clears the inherited value to an empty mapping/list when the inherited value has that shape, or to `null` otherwise. Other custom YAML tags still fail loading. jcompose has not verified port ranges and every short/long syntax combination, volume-like entries keyed by container target, and other Compose-specific edge cases. `extends` and `include` resolution are outside this overlay contract.

See Docker's [merge reference](https://docs.docker.com/reference/compose-file/merge/) and [multiple-file guide](https://docs.docker.com/compose/how-tos/multiple-compose-files/merge/) for the compatibility target. When porting merge behavior from ComposeSharp, verify it independently against those references and Docker Compose itself, then update this table and its conformance evidence.

Interpolation currently runs while each file is read, before the merged model is built. That matches Docker Compose's per-file interpolation ordering, but the interpolation grammar and merge rules remain incomplete. See [loader compatibility](loader-compatibility.md).
