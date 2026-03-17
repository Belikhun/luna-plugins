# Luna Placeholders

Complete reference for placeholders currently provided by LunaCore and LunaVault.

## Paper

Luna Core Paper PlaceholderAPI expansion:

- Identifier: `luna`
- Format: `%luna_<key>%`

General rules:

- Width placeholders are optional and clamped to `1..120`.
- Default width is `25` when width is omitted.

Current server value placeholders:

- `%luna_current_server%`
- `%luna_status%`
- `%luna_online%`
- `%luna_max%`
- `%luna_tps%`
- `%luna_latency%`
- `%luna_uptime%`
- `%luna_uptime_ms%`
- `%luna_system_cpu%`
- `%luna_process_cpu%`
- `%luna_version%`
- `%luna_display%`
- `%luna_color%`
- `%luna_whitelist%`

Current player ping placeholders:

- `%luna_player_ping%`

Current server bar placeholders:

Standard bars:

- `%luna_tps_bar%`
- `%luna_tps_bar_<width>%`
- `%luna_player_ping_bar%`
- `%luna_player_ping_bar_<width>%`
- `%luna_latency_bar%`
- `%luna_latency_bar_<width>%`
- `%luna_system_cpu_bar%`
- `%luna_system_cpu_bar_<width>%`
- `%luna_process_cpu_bar%`
- `%luna_process_cpu_bar_<width>%`
- `%luna_ram_bar%`
- `%luna_ram_bar_<width>%`

Bar-only:

- `%luna_tps_bar_only%`
- `%luna_tps_bar_only_<width>%`
- `%luna_player_ping_bar_only%`
- `%luna_player_ping_bar_only_<width>%`
- `%luna_latency_bar_only%`
- `%luna_latency_bar_only_<width>%`
- `%luna_system_cpu_bar_only%`
- `%luna_system_cpu_bar_only_<width>%`
- `%luna_process_cpu_bar_only%`
- `%luna_process_cpu_bar_only_<width>%`
- `%luna_ram_bar_only%`
- `%luna_ram_bar_only_<width>%`

Value-only:

- `%luna_tps_bar_value_only%`
- `%luna_player_ping_bar_value_only%`
- `%luna_latency_bar_value_only%`
- `%luna_system_cpu_bar_value_only%`
- `%luna_process_cpu_bar_value_only%`
- `%luna_ram_bar_value_only%`

Return values:

- `status` returns `ONLINE`, `OFFLINE`, or `MAINT`.
- `whitelist` returns `true` or `false`.
- CPU placeholders return percent number strings.
- `uptime_ms` returns raw milliseconds.
- `uptime` returns formatted duration text.

LunaVault backend PlaceholderAPI expansion:

- Identifier: `lunavault`
- Format: `%lunavault_balance%`

## Velocity MiniPlaceholders

Luna Core Velocity MiniPlaceholders:

- Namespace: `lunav`
- Format: `<lunav:key>`

Available placeholders:

- `<lunav:online_servers>`
- `<lunav:registered_servers>`
- `<lunav:total_servers>`
- `<lunav:total_players>`
- `<lunav:player_name>`
- `<lunav:player_group_name>`
- `<lunav:player_group_display>`
- `<lunav:player_prefix>`
- `<lunav:player_suffix>`
- `<lunav:player_display>`
- `<lunav:server_status_<backend>>`
- `<lunav:server_online_<backend>>`
- `<lunav:server_max_<backend>>`
- `<lunav:server_tps_<backend>>`
- `<lunav:server_version_<backend>>`
- `<lunav:server_display_<backend>>`
- `<lunav:server_color_<backend>>`
- `<lunav:server_whitelist_<backend>>`

LunaVault Velocity MiniPlaceholders:

- Namespace: `lunavaultv`
- Format: `<lunavaultv:key>`
- Available placeholder: `<lunavaultv:balance>`

## Velocity TAB

Luna Core Velocity TAB placeholders:

- `%lunav-online-servers%`
- `%lunav-registered-servers%`
- `%lunav-total-servers%`
- `%lunav-total-players%`
- `%lunav-player-name%`
- `%lunav-player-group-name%`
- `%lunav-player-group-display%`
- `%lunav-player-prefix%`
- `%lunav-player-suffix%`
- `%lunav-player-display%`
- `%lunav-server-status-<backend>%`
- `%lunav-server-online-<backend>%`
- `%lunav-server-max-<backend>%`
- `%lunav-server-tps-<backend>%`
- `%lunav-server-version-<backend>%`
- `%lunav-server-display-<backend>%`
- `%lunav-server-color-<backend>%`
- `%lunav-server-whitelist-<backend>%`

LunaVault Velocity TAB placeholder:

- `%lunavaultv-balance%`

## Notes

- Replace `<backend>` with the normalized backend name from the Velocity server selector configuration, for example `survival`.
- LunaCore Velocity player display placeholders use `strings.user-display-format` from the LunaCore Velocity config.
- LunaVault balance placeholders use the shared Velocity `strings.money.*` formatting config.
- TAB integration on Velocity is optional and only registers when the `tab` plugin is installed.
- MiniPlaceholders integration on Velocity is optional and only registers when the `miniplaceholders` plugin is installed.
