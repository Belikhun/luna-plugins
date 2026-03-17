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

- Namespace: `luna`
- Format: `<luna:key>`

Available placeholders:

- `<luna:online_servers>`
- `<luna:registered_servers>`
- `<luna:total_servers>`
- `<luna:total_players>`
- `<luna:player_name>`
- `<luna:player_group_name>`
- `<luna:player_group_display>`
- `<luna:player_prefix>`
- `<luna:player_suffix>`
- `<luna:player_display>`
- `<luna:server_status_<backend>>`
- `<luna:server_online_<backend>>`
- `<luna:server_max_<backend>>`
- `<luna:server_tps_<backend>>`
- `<luna:server_version_<backend>>`
- `<luna:server_display_<backend>>`
- `<luna:server_color_<backend>>`
- `<luna:server_whitelist_<backend>>`

LunaVault Velocity MiniPlaceholders:

- Namespace: `lunavault`
- Format: `<lunavault:key>`
- Available placeholder: `<lunavault:balance>`

## Velocity TAB

Luna Core Velocity TAB placeholders:

- `%luna_online_servers%`
- `%luna_registered_servers%`
- `%luna_total_servers%`
- `%luna_total_players%`
- `%luna_player_name%`
- `%luna_player_group_name%`
- `%luna_player_group_display%`
- `%luna_player_prefix%`
- `%luna_player_suffix%`
- `%luna_player_display%`
- `%luna_server_status_<backend>%`
- `%luna_server_online_<backend>%`
- `%luna_server_max_<backend>%`
- `%luna_server_tps_<backend>%`
- `%luna_server_version_<backend>%`
- `%luna_server_display_<backend>%`
- `%luna_server_color_<backend>%`
- `%luna_server_whitelist_<backend>%`

LunaVault Velocity TAB placeholder:

- `%lunavault_balance%`

## Notes

- Replace `<backend>` with the normalized backend name from the Velocity server selector configuration, for example `survival`.
- LunaCore Velocity player display placeholders use `strings.user-display-format` from the LunaCore Velocity config.
- LunaVault balance placeholders use the shared Velocity `strings.money.*` formatting config.
- TAB integration on Velocity is optional and only registers when the `tab` plugin is installed.
- MiniPlaceholders integration on Velocity is optional and only registers when the `miniplaceholders` plugin is installed.
