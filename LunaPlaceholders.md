# Luna Placeholder Reference

This document summarizes placeholders exported by Luna Core.

## Namespace

- Velocity MiniPlaceholders: `<luna:key>`
- Paper PlaceholderAPI: `%luna_key%`

## Global Keys

- `online_servers`
- `registered_servers`
- `total_servers`
- `total_players`

Examples:

- Velocity: `<luna:online_servers>`
- Paper: `%luna_online_servers%`

## Per-Server Keys

Use the backend key suffix (for example `survival`, `event2`, `iceboat`).

- `server_status_<server>`
- `server_online_<server>`
- `server_max_<server>`
- `server_tps_<server>`
- `server_version_<server>`
- `server_display_<server>`
- `server_color_<server>`
- `server_whitelist_<server>`

Examples for `survival`:

- Velocity:
  - `<luna:server_status_survival>`
  - `<luna:server_online_survival>`
  - `<luna:server_display_survival>`
- Paper:
  - `%luna_server_status_survival%`
  - `%luna_server_online_survival%`
  - `%luna_server_display_survival%`

## Status Values

`server_status_<server>` returns one of:

- `ONLINE`
- `OFFLINE`
- `MAINT`

`server_whitelist_<server>` returns `true` or `false`.

## Data Source Notes

- Values are driven by heartbeat snapshots from Luna Core.
- Per-server keys are dynamic and depend on known backend names.
- On Velocity, display and color are resolved from centralized selector config, with heartbeat fallback.
