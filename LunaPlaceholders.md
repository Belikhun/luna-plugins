# Luna Placeholders

Complete reference for placeholders provided by Luna Core Paper PlaceholderAPI expansion.

## Namespace

- PlaceholderAPI identifier: `luna`
- Usage format: `%luna_<key>%`

## General Rules

- Width placeholders are optional and clamped to `1..120`.
- Default width is `25` when width is omitted.

## Current Server Value Placeholders

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

## Current Player Ping Placeholders

- `%luna_player_ping%`

## Current Server Bar Placeholders

Standard bars (label + value):

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

Bar-only (no label, no value):

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

## Return Values

- `status` returns `ONLINE`, `OFFLINE`, or `MAINT`.
- `whitelist` returns `true` or `false`.
- CPU placeholders return percent number strings.
- `uptime_ms` returns raw milliseconds.
- `uptime` returns formatted duration text.
