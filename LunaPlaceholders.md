# Luna Placeholders

Reference for every placeholder LunaCore, LunaVault and LunaGlyph provide, on every
platform, after the 2026-09 rewrite (`docs/placeholder-rewrite-plan.md`).

## One model, three surfaces

A placeholder is `namespace_key[_arguments]`. The same placeholder can be written in
any of these forms and means the same thing:

| Form | Where it is read | Example |
|---|---|---|
| `%namespace_key_arg%` | TAB configs, PlaceholderAPI, the tab bridge, messenger formats | `%luna_tps_bar_10%` |
| `<namespace_key:arg>` | MiniMessage templates (MiniPlaceholders) | `<luna_tps_bar:10>` |
| `{namespace_key_arg}` | Inside another placeholder's argument | `%luna_mm2l_{luckperms_prefix}%` |

Arguments are the words after the key. In the MiniMessage form they ride after the
colon because a tag name cannot hold a glyph: `%luna_player_status_⏺%` is
`<luna_player_status:'⏺'>` there.

Two namespaces carry luna's own facts, and the split has a meaning:

- **`luna_*`** is the backend the player is on. Resolved by that backend and carried to
  the proxy by TAB's bridge, so it exists on Paper, Fabric, NeoForge and Forge alike.
- **`lunav_*`** is the network as the proxy sees it. Resolved on the proxy from the
  heartbeat registry, the server list and the proxy's own plugins.

On the proxy a `luna_*` key that no backend exported falls back to `lunav_*`, so a
messenger format written before the split still resolves.

### Rules that hold everywhere

- Unknown keys stay as written. A typo shows in the tab list instead of vanishing.
- `_safe` may end any placeholder in any namespace; the value's `%` signs become `％`
  so TAB does not read them as another placeholder. Use it for every bar in a TAB
  header.
- Bar widths are `1..120`, default `25`. `%luna_ram_bar_only_80%` is the bar alone at
  80 cells; `%luna_ram_bar_value_only%` is its value text; `%luna_ram_bar%` is both.
- Values are MiniMessage source. CPU values carry no `%` sign.

## `luna` (this backend)

Server:

| Key | Value |
|---|---|
| `current_server` | luna's name for this backend |
| `host_name`, `server_name` | the name the proxy routes to this backend by |
| `display`, `color` | display name and accent colour from the proxy's server list |
| `version` | server software version |
| `status` | `ONLINE`, or `MAINT` while the whitelist is on |
| `whitelist` | `true` or `false` |
| `online`, `max` | players and slots |
| `tps`, `tick_duration` | two decimals; tick duration in ms |
| `latency` | heartbeat round trip to the proxy in ms |
| `uptime`, `uptime_long`, `uptime_ms` | compact, long form, milliseconds |
| `system_cpu`, `process_cpu` | percent, one decimal, no sign |
| `total_entities`, `total_living_entities`, `total_chunks` | across all worlds |
| `world_<world>_weather`, `_weathericon`, `_weathercolor`, `_weatherduration` | one world's weather |

Player:

| Key | Value |
|---|---|
| `player_ping` | ping in ms, `0` when absent |
| `player_level` | styled level |
| `player_status[_<glyph>]` | glyph coloured by the player's dimension, `▋` by default |
| `player_dimension[_<glyph>]` | same colours, `◎` by default |
| `voicechat_status`, `voicechat_group` | Simple Voice Chat state |
| `stripcolor_legacy_<text>`, `stripcolor_mm_<text>`, `mm2l_<text>` | text transforms; `{…}` in the argument is expanded first |
| `replace_<from>_<to>_<text>` | every `<from>` in the text becomes `<to>`; `\uXXXX` escapes (`\u005F` for `_`, `\u0020` for a space), `""` for nothing; `{…}` expanded first, e.g. `%luna_replace_▋_\|_{luna_ram_bar_only_80}%` |

Bars, each as `X_bar[_<width>]`, `X_bar_only[_<width>]` and `X_bar_value_only`, plus
`X_bar_glyph_<glyph>[_<width>]` and `X_bar_only_glyph_<glyph>[_<width>]` drawing the cells
with another glyph than `▋` (`%luna_ram_bar_only_glyph_|_80%`; `\uXXXX` escapes work), for
a server whose resource pack replaces the default font:
`tps`, `player_ping`, `latency`, `system_cpu`, `process_cpu`, `ram`.

### Mod-loader stand-ins

Paper has PlaceholderAPI expansions for these; Fabric, NeoForge and Forge do not, so
LunaCore answers them there with the same shapes:

- `player_name`, `player_displayname`, `player_ping`, `player_world`, `player_biome`,
  `player_x`, `player_y`, `player_z`, `player_health`, `player_health_rounded`,
  `player_max_health`, `player_max_health_rounded`, `player_level`, `player_food_level`,
  `player_gamemode`
- `world`, `world_time`
- `server_name`, `server_online`, `server_max_players`, `server_tps`, `server_uptime`,
  `server_total_entities`, `server_total_living_entities`, `server_total_chunks`,
  `server_time_<pattern>`
- `luckperms_prefix`, `luckperms_suffix`, `luckperms_primary_group_name`,
  `luckperms_primary_group_display_name`; `vault_prefix`, `vault_suffix`,
  `vault_primary_group`
- `spark_<key>`: spark's own value, or luna's tick reading when spark is absent

### `lunavault` (this backend)

`lunavault_balance`, `lunavault_rank`: the viewer's balance and leaderboard rank, from
the backend's cached ledger snapshot.

## `lunav` (the network, proxy only)

Network: `online_servers`, `registered_servers`, `total_servers`, `total_players`.

Per backend, `server_<field>[_<backend>]`. Without the backend argument the value is
for the viewer's current server; `%lunav_server_tps_survival%` and
`<lunav_server_tps:survival>` name one. Fields: `status` (`ONLINE`/`MAINT`/`OFFLINE`),
`online`, `max`, `tps`, `tick_duration`, `version`, `software`, `motd`, `whitelist`,
`uptime`, `uptime_long`, `uptime_ms`, `system_cpu`, `process_cpu`, `ram_used_mb`,
`ram_max_mb`, `ram_percent`, `latency`, `last_heartbeat`, `display`, `color`.

Player: `player_name`, `player_server`, `player_group_name`, `player_group_display`,
`player_prefix`, `player_suffix`, `player_display` (the configured
`strings.user-display-format`), `player_status[_<glyph>]` (coloured by the current
server's accent).

Plugins on the proxy publish under `lunav` too:

- LunaVault: `vault_balance`, `vault_rank`.
- LunaGlyph: `glyph_<key>` for every key under `glyphs` in `glyphs.yml`.

TAB ids are the percent form of the same names, `%lunav_server_status_lobby%`. For
one release the ids from before the rename keep answering: `%lunav-…%` with hyphens,
`%lunavaultv-balance%`, `%lunavaultv-rank%`, `%lunaglyph-<key>%`, and the
MiniPlaceholders namespace `<luna_…>` on the proxy. The proxy names every config line
still using one at startup, and `/luna placeholder migrate apply` rewrites them.

## Server selector templates

`servers.yml` templates use their own variables, filled by the selector and nothing
else: `server_name`, `luna_host_name`, `luna_server_name`, `server_display`,
`server_accent_color`, `server_status`, `server_status_color`, `server_status_icon`,
`online`, `max`, `version`, `server_version`, `server_version_full`, `software`,
`server_software`, `motd`, `tps`, `uptime`, `uptime_long`, `cpu_usage`, `ram_used_mb`,
`ram_max_mb`, `ram_percent`, `latency_ms`, `line`, `player_name`. The validator checks
against the same list the renderer fills.

## Debugging

Every platform has the same three reports, and the output goes to the instance log so
`luna send` can drive them.

Backends, Paper `/lunacorepaper placeholder …` and mods `/lunacore placeholder …`:

- `parse <text>`: how each token is split and which spec it reaches.
- `resolve <player|-> <text>`: the rendered text, then each value with its spec, refresh
  class and time.
- `list [namespace]`: every registered spec with its owner.
- mods only: `bridge [player]` shows what TAB asked for, the last value sent and the
  queue; `trace <player> <seconds>` logs every frame sent.

Proxy, `/luna placeholder …`:

- `parse`, `resolve`, `list` as above, over the network registry.
- `render <player|-> <template>`: a messenger-style template through the proxy renderer.
- `tab render <player> [plain|mini|legacy]`: the tab list exactly as Velocity holds it
  for that player, header and footer included.
- `bridge stats [backend]`, `bridge last <player> [n]`: TAB bridge frames the proxy saw.
- `tabids`: every id luna registered with TAB.
- `migrate [apply]`: rewrite pre-rename ids in the proxy configs, with backups.

Over HTTP on LunaCore's proxy API (`X-Luna-Forwarding-Secret` required):
`GET /api/tab/<player>[?format=plain|mini|legacy]`, `GET /api/placeholders/bridge`,
`GET /api/placeholders/bridge/<player>`.
