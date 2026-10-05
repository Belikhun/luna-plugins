# Placeholder system: assessment and rewrite plan

Written 2026-09-20 against `9e23a961`. Companion to `docs/vault-ledger.md`, which did the
same exercise for LunaVault. Phases 0 to 7 shipped on 2026-09-21; section 7 records what
landed and what is still open.

## 1. What exists today

Four backend engines, three copies of the TAB bridge, three proxy plugins each carrying
their own TAB and MiniPlaceholders boilerplate, seven text syntaxes, and one shared helper
(`PlaceholderRouting`) with the string `"luna"` hardcoded inside it.

### 1.1 Engines

| Engine | Module | Lines | Syntax in | Providers | Refresh | Unknown key |
|---|---|---|---|---|---|---|
| `PaperLunaPlaceholderExpansion` | paper/luna-core | 587 | PAPI `%luna_x%` | none, one switch | none, re-polls spark and the status view per lookup | `""` |
| `BuiltInPlaceholderService` | core/luna-core-mc (neoforge, forge) | 786 + 500 | `%x%`, bare, `{x}` | hardcoded list of 4 | only when the tab bridge calls `refreshSharedSnapshot`; frozen forever without it | `null` |
| `BuiltInFabricPlaceholderService` | fabric/luna-core | 675 + 500 | same | same 4, forked | every 10 ticks | `null` |
| `LegacyPlaceholderService` | forge/luna-core-mc12 | 505 + 400 | same | 2 | every 20 ticks | `null` |

The trunk and Fabric services are the same file with about 250 lines diverged. The mc12
service is the same design re-typed for Java 8 on a byte-identical copy of the API
(`core/luna-legacy-api/placeholder`, 740 lines, one of whose classes has zero callers).

### 1.2 Transports

| Path | Wire | Version check | Naming |
|---|---|---|---|
| Heartbeat (backend to proxy, HTTP form-encoded) | flat `key=value`, index-prefixed arrays | proxy to backend only; the proxy accepts any heartbeat body | 22 fields travel, 7 become placeholders; the rest are only reachable through the connect command's private `%server_*%` scheme |
| TAB bridge (backend to TAB on the proxy) | TAB's own `tab:bridge-6`, positional `DataOutputStream` | the channel name is the version | TAB ids `%luna_*%`; `%rel_luna_*%` |
| Messenger export (backend to proxy, inside the command frame) | custom binary, `writeStringMap`, uncapped | `protocol=1` written, never compared | a runtime-discovered, never-evicted key set fed by chat content |

The TAB bridge runtime exists three times: 1240 lines (neoforge and forge), 1242 lines
(fabric, 93% identical), 1577 lines (1.12, Java 8). Nine satellite classes are also
duplicated three times each.

### 1.3 Proxy surfaces

| Plugin | MiniPlaceholders | TAB | Refresh | Reload hook |
|---|---|---|---|---|
| luna-core | `<lunav_*>` and a legacy `<luna_*>` copy | `%lunav-*%` (hyphens) | 500 ms flat, servers x 8 ids | own copy |
| luna-vault | `<lunavaultv_*>` | `%lunavaultv-*%` | 500 ms, blocks on a DB join inside the TAB refresh | own copy |
| luna-glyph | `<lunaglyph_glyph:key>` (colon is outside the tag charset) | `%lunaglyph-<key>%` | 10 s | own copy |

The TAB ids and the MiniPlaceholders names for the same value differ by separator because
the `luna` to `lunav` rename in March 2026 changed underscores to hyphens for TAB only.

### 1.4 Syntaxes in play

1. `%ns_key%` PlaceholderAPI form, on Paper, in TAB configs and in the mods' engines.
2. `<ns_key>` and `<ns_key:'arg'>` MiniMessage tags on the proxy.
3. `%lunav-key%` hyphenated TAB ids on the proxy.
4. `{key}` in `MessageFormatter` and the messenger backend resolver.
5. bare `%server_display%`, `%line%` template variables in the server selector.
6. `%player_prefix% %displayname%` in the display format, then a MiniPlaceholders pass.
7. `{luckperms_prefix}` bracket placeholders passed to PAPI from inside a luna value.

### 1.5 Bug history, by class

Thirty-five commits mention placeholders. Grouped:

- **Escaping** (5 commits): the escape token went `%%`, then `:percent:`, then `％`.
  The doc still says `%%`. Seven call sites implement their own copy.
- **Parsing** (5): bare vs wrapped identifiers rejected; `ram_bar_only_55` matched the
  `ram_bar_` prefix and rendered the wrong bar at the wrong width; MiniPlaceholders tags
  emitted as `<ns:key>` which matches nothing; `⏺` in a tag name.
- **Registration and lifecycle** (7): TAB drops every registration on `/tab reload`
  (hook copied into three plugins); a global duplicate-namespace check that threw at
  startup; the rename that broke every existing config and was patched with a second
  expansion.
- **Transport and timing** (4): frames sent before TAB said `PlayerJoin` were lost (fixed
  with an unbounded per-player queue); a 50 ms refresh strobing the client; reflection on
  the resolve path.
- **Naming** (3): `luna` to `lunav`; `server_name` and `host_name` swapped on 1.12;
  method-name reflection that silently returned blanks after an MC update.

Production TAB placeholder error log: about 98,000 lines, dominated by the old LunaVault
placeholders and by PlaceholderAPI's server expansion being called off the main thread.

### 1.6 Root causes

1. **No canonical model of a placeholder.** Each engine parses a string ad hoc with
   `startsWith` chains and regexes, so arity, argument boundaries and width suffixes are
   re-invented per key and per platform. Every parsing bug above is this.
2. **Resolution runs on whoever's thread called it.** Paper, the trunk and Fabric all
   read world, entity and player state from TAB's or the bridge's threads. The snapshots
   that exist are refreshed by consumers (the tab bridge), not by the engine.
3. **Values are substituted as text, then re-parsed.** `%` inside a value is re-read by
   TAB, MiniMessage tags inside a value are re-read by MiniMessage. `_safe` and the
   three-pass renderers are workarounds for this.
4. **Every surface is a fork.** Nothing is shared between Paper, the trunk, Fabric, 1.12,
   the proxy plugins or the three bridges except `PlaceholderRouting`, so behaviour drifts
   (CPU with or without `%`, `player_level` styled or plain, `server_name` meaning).
5. **Names encode history, not meaning.** `lunav`, `lunavaultv`, hyphens, a legacy
   `luna` expansion on the proxy that collides with the backend's `luna`.

## 2. Target design

### 2.1 Principles

1. **One engine, one grammar, one registry, thin adapters.** The engine lives in
   `luna-core-api` and knows nothing about Bukkit, Fabric or Velocity. A platform
   supplies a `HostBridge<P>`; a surface (PAPI, TAB bridge, MiniPlaceholders, TAB API,
   template rendering) is an adapter of at most a few hundred lines.
2. **A placeholder is data.** A `PlaceholderSpec` declares namespace, key pattern with
   typed parameters, scope, refresh class and a resolver. Matching, documentation, config
   validation, TAB refresh intervals, bridge cadence and the debug listing are all derived
   from the specs. Nothing is a `startsWith` chain.
3. **Resolution never touches the game thread.** Samplers run on the server tick and
   publish immutable snapshots; resolvers are pure functions over snapshots. A spec that
   truly needs a live read declares it and is hopped with a timeout.
4. **Values are never re-parsed.** The renderer inserts values as literal text nodes, or
   escapes them for the target syntax. Escaping is one function with one token.
5. **Two namespaces with a meaning.** `luna_*` is the backend the player is on;
   `lunav_*` is the network as the proxy sees it. Underscores everywhere. Plugin values on
   the proxy are segments under `lunav` (`lunav_vault_balance`, `lunav_glyph_logo`);
   plugin values on a backend keep their own PAPI-style namespace (`lunavault_balance`).
6. **Where behaviours differ today, Paper wins**, because production configs were written
   against it. Concretely: CPU values carry no `%`; unknown keys render raw; `player_level`
   is styled.
7. **Every hop is observable** without a client: parse, resolve, transport, render.

### 2.2 Core package (`core/luna-core-api`, `dev.belikhun.luna.core.api.placeholder`)

- `PlaceholderRef`: namespace, key segments, args, `safe` flag, source syntax, source text.
- `PlaceholderGrammar`: parses `%ns_key_args%`, `<ns_key:arg>`, `{ns_key}`, bare
  `ns_key`; formats a ref back into any of them. The only parser in the tree.
- `KeyPattern` and `PlaceholderSpec`: literal segments plus typed params (`INT`, `WIDTH`
  with clamp, `WORD`, `TEXT` for a trailing free argument such as the status dot),
  `Scope` (`SERVER`, `PLAYER`, `RELATIONAL`), `Refresh` (`STATIC`, `SERVER`, `PLAYER`,
  `LIVE`), an owner, and a resolver. Matching is longest literal prefix, so
  `tps_bar_only_40` cannot match `tps_bar_<width>`.
- `PlaceholderRegistry<P>`: namespaces to specs, register and unregister by owner,
  conflict detection with a logged reason, enumeration. One instance per process.
- `ServerSampler` and `ServerSample`: replaces `PlaceholderSnapshot`, `SharedSnapshot`
  and the per-engine CPU and TPS code. Runs on the tick at a configured cadence.
- `PlayerSampler` and `PlayerSample`: ping, dimension, position, health, level, permission
  prefix and suffix, voicechat state; refreshed per tick for online players.
- `Resolver` and `ResolutionContext`: layers (template-local values, registry, foreign
  resolver such as PAPI), a depth guard for nested `{…}` expansion, the escaping policy.
- `TemplateRenderer`: parses a template once into literal and ref segments; renders to a
  MiniMessage `Component` with values inserted as text nodes, or to a legacy string for
  TAB. Replaces `SimpleTemplateRenderer`, the router's `renderRaw`, the connect command's
  loop, `ServerSelectorEngine.applyTemplate`, `MessageFormatter` and both messenger
  backend resolvers' `applyInternal`.
- `Bars`: one table from metric to progress-bar preset that generates the `_bar`,
  `_bar_only`, `_bar_value_only` and `_<width>` specs. Replaces four copies.
- `PlaceholderDocs`: generates `LunaPlaceholders.md` from the registry so the doc cannot
  drift again.
- `HostBridge<P>`: what a platform supplies (server facts, player facts, permission facts,
  a server-thread executor, an identity of the backend).

Tests in `core/luna-core-api/src/test`: grammar golden tests over the production
compatibility set (section 4), spec matching including every bar variant, renderer
escaping, sampler cadence, registry conflicts. No Minecraft classes.

### 2.3 Backends

- `core/luna-core-mc` gets `MinecraftHostBridge` over `ServerPlayer`, keeping the two
  version compat sets for tick metrics. Fabric, NeoForge and Forge 1.20 share it; the
  `fabric/luna-core/placeholder` package is deleted.
- `paper/luna-core` gets `PaperHostBridge`, a `PapiAdapter` that registers one
  `PlaceholderExpansion` per registered namespace and delegates to the registry, and a
  `PapiForeignResolver` for non-luna keys inside templates. `MessageFormatter` leaves
  `core-api`, which currently imports Bukkit.
- Feature plugins register specs through one API on every platform:
  `PlaceholderService.register(owner, specs)`. LunaVault becomes one class over the
  already generic `VaultClient<P>`, replacing three copies. Voicechat status becomes a
  `PlayerSample` fact.
- The 1.12 line keeps its current engine frozen; see section 5.

### 2.4 TAB bridge

- One runtime in a new `core/luna-tab-bridge-mc` trunk, composed by the fabric, neoforge
  and forge modules the way `luna-core-mc` is. The `tab:bridge-6` wire is TAB's and is
  unchanged.
- Requested ids are parsed by the grammar and matched to specs; cadence per id comes from
  the spec's refresh class and TAB's requested interval, not a flat 50 ms loop.
- Bounded per-player queue, an in-flight guard on the refresh, every dropped or rejected
  id logged once with its reason (today the `BLOCK_ELEMENTS` filter drops silently).
- `%rel_luna_*%` come from `RELATIONAL` specs in the same registry. The display template
  is the proxy's `strings.user-display-format`, pushed to backends in the heartbeat
  response instead of being a second hardcoded literal.

### 2.5 Proxy

- `velocity/luna-core` hosts the `PlaceholderRegistry<Player>` and three adapters:
  `MiniPlaceholdersAdapter` (one expansion per namespace, arguments as tag arguments),
  `TabApiAdapter` (ids are the canonical `%lunav_…%` form, refresh from the spec class,
  one `TabLoadEvent` hook for everyone), and the `TemplateRenderer` wired for the
  messenger, the server selector, the display format and the connect command.
- Heartbeat specs are generated over `BackendHeartbeatStats`: every field that travels is
  reachable as `lunav_server_<field>_<backend>` and as `lunav_server_<field>` for the
  viewer's current server. The connect command's private scheme goes away.
- LunaVault and LunaGlyph register specs into the shared registry and delete their TAB and
  MiniPlaceholders classes and reload hooks. The vault resolver reads the client's cached
  snapshot; it never joins a future on the TAB thread.
- Deprecated ids (`%lunavaultv-*%`, `%lunaglyph-*%`, `%lunav-*%`, the legacy `luna`
  expansion on the proxy) stay registered as aliases for one release. At startup the
  plugin scans TAB's `config.yml` and `groups.yml` and logs each deprecated id still in
  use with its replacement.

### 2.6 Messenger and templates

- The proxy computes the set of foreign keys each configured template needs at config load
  and pushes it to backends in the heartbeat response. Backends export exactly those keys
  with a message; the runtime-discovered key set is removed.
- `MessengerCommandRequest` gets a protocol check and a cap on the exported map.
- `%luna_player_status_⏺%` keeps working in TAB (TAB forwards it to the backend); in
  MiniMessage templates the canonical form is `<luna_player_status:'⏺'>`, and the grammar
  converts between the two because the spec knows the key takes one `TEXT` argument.

## 3. Tooling

All commands write to the instance log so `luna send` can drive them, and the proxy
exposes the same data over LunaCore's HTTP API on `127.0.0.1:32452/api`.

### 3.1 Backend `/luna placeholder` (Paper and mods)

- `parse <text>`: one row per token with source syntax, namespace, key, args, safe flag,
  and the matched spec or `unmatched`.
- `resolve <player> <text>`: the rendered result, then per token the value, spec, refresh
  class, sample age and resolution time.
- `list [namespace]`: every registered spec with its owner and refresh class.
- `bridge <player>`: TAB-bridge state: ready flag, each requested id with its interval,
  last value, last sent time, queue depth, and the rejected ids with reasons.
- `trace <player> <seconds>`: log every `UpdatePlaceholder` frame sent for that player.

### 3.2 Proxy `/lunav placeholder`

- `parse`, `resolve`, `list` as above over the proxy registry.
- `tab render <player> [plain|ansi|json]`: the tab list exactly as Velocity holds it for
  that player: header, entries in order with display name, latency and game mode, footer.
  Entries come from the public `TabList` API; header and footer from the proxy's
  connected-player object by reflection, reported as unavailable if the accessor moves.
- `bridge stats [backend]`: a passive listener on `tab:bridge-6` counts frames per player
  and per backend on the proxy side, so a backend's `trace` and the proxy's count can be
  compared for lost frames.
- `migrate [dry-run|apply]`: rewrites deprecated ids in TAB's `config.yml` and
  `groups.yml` by a fixed table, backing the files up first, and prints every change.

### 3.3 HTTP

- `GET /api/placeholders` registry listing; `GET /api/placeholders/resolve?player=&text=`.
- `GET /api/tab/<player>` JSON and `?format=plain` for the render above.
- `GET /api/placeholders/bridge` proxy-side frame counters.

### 3.4 Tests

The JUnit suite in `core-api` carries a fixture of every luna placeholder found in
production configs (section 4) and asserts each one parses, matches a spec and resolves
against a fake `HostBridge` to a value of the expected shape. A new key is added to the
fixture when it is added to a config.

## 4. Compatibility set

Extracted from `<root>/proxy/plugins/tab/{config,groups}.yml`,
`<root>/proxy/plugins/lunamessenger/config.yml` and `<root>/proxy/plugins/lunacore/*.yml`
on 2026-09-20. Everything here must resolve to the same shape after the rewrite.

Backend `luna_*` through TAB and PlaceholderAPI (unchanged names):
`luna_host_name`, `luna_current_server`, `luna_uptime_safe`, `luna_tick_duration_safe`,
`luna_tps_bar_10_safe`, `luna_tps_bar_20_safe`, `luna_player_ping_bar_safe`,
`luna_player_ping_bar_10_safe`, `luna_system_cpu_bar_safe`, `luna_process_cpu_bar_safe`,
`luna_ram_bar_value_only_safe`, `luna_ram_bar_only_80_safe`, `luna_ram_bar_only_120_safe`,
`luna_total_entities_safe`, `luna_total_living_entities_safe`, `luna_total_chunks_safe`,
`luna_voicechat_status`, `luna_player_level`, `luna_player_dimension`,
`luna_player_status_⏺`, `luna_world_survival_weather`, `luna_world_survival_weathericon`,
`luna_world_survival_weathercolor`, `luna_world_survival_weatherduration`.

Proxy ids, renamed by the migration:

| Today | After |
|---|---|
| `%lunavaultv-balance%` | `%lunav_vault_balance%` |
| `%lunavaultv-rank%` | `%lunav_vault_rank%` |
| `%lunaglyph-logo%`, `%lunaglyph-createlogo%` | `%lunav_glyph_logo%`, `%lunav_glyph_createlogo%` |
| `%lunav-<key>%` (unused in production) | `%lunav_<key>%` |
| `<luna_player_status>` in messenger templates | `<lunav_player_status>` |

Server selector `servers.yml` keeps its template-local variables (`%server_display%`,
`%online%`, `%line%`); they become a local layer of the renderer and the validator's known
set is derived from that layer instead of a hand-kept list.

## 5. Phases

Each phase ships on its own; aliases make the order safe. Proxy and backends do not have
to deploy together except where noted.

| # | Scope | Deletes | Deploy | Verify with |
|---|---|---|---|---|
| 0 | Observability first: `tab render`, proxy `bridge stats`, the compat fixture. Baselines the current behaviour before anything changes. | nothing | proxy | `lunav placeholder tab render <player>` for an online player on lobby and survival |
| 1 | Core engine in `core-api` with tests. | `PlaceholderRouting` and friends once callers move | none | `./gradlew :luna-core-api:test` |
| 2 | Paper adapter and `PaperHostBridge`; LunaVault Paper on the new API. | `PaperLunaPlaceholderExpansion`, `PaperVaultPlaceholderExpansion` | lobby, survival | `luna placeholder resolve` for the whole fixture; `tab render` shows identical text |
| 3 | mc trunk adapter; Fabric switches to it; LunaVault mods on the new API. | `fabric/luna-core/placeholder`, the trunk service, `VaultPlaceholders` copies | create | same, on create |
| 4 | TAB bridge trunk. | three `RawChannel*` runtimes and satellites | create | `luna placeholder bridge`, `trace` vs proxy `bridge stats` |
| 5 | Proxy registry and adapters; vault and glyph register into it; aliases and the deprecation scan. | `Velocity*Placeholders` in three plugins, three reload hooks | proxy | `lunav placeholder list`, `tab render` |
| 6 | Renderer for messenger, selector, display format, connect command; pushed export keys. | `SimpleTemplateRenderer`, both `*BackendPlaceholderResolver`, private render loops | proxy, lobby, survival, create | chat through the messenger with a `%luckperms_prefix%` template; Discord bridge |
| 7 | Config migration on the production proxy (`migrate apply`), `LunaPlaceholders.md` regenerated, aliases removed one release later. | alias code | proxy | TAB `placeholder-errors.log` stays empty; `tab render` unchanged |
| 8 | Deferred: 1.12 line on the new core. Requires either a Java 8 build of the engine or leaving `luna-legacy-api` frozen. No 1.12 instance exists today. | `luna-legacy-api/placeholder`, `/tabbridge` | none | none |

Size: roughly 10,000 lines removed (four engines, three bridge runtimes with satellites,
three proxy adapter sets, the legacy API copies in phase 8), roughly 3,000 added.

## 6. Decisions taken and open

Taken in this plan:

- Two namespaces, `luna` (this backend) and `lunav` (the network), underscores only, plugin
  values as segments under `lunav` on the proxy.
- Paper behaviour is the reference where platforms disagree.
- The TAB bridge wire stays TAB's; every wire luna owns carries a version and is checked
  on both ends.
- Production configs are migrated by the tool in phase 7, with backups and a printed diff.

Open, to confirm before phase 5:

- Whether LunaGlyph's per-key MiniMessage form `<lunaglyph:glyph:key>` should survive as an
  alias at all; it never parsed as a tag, so nothing can depend on it.
- Whether the 1.12 line is worth phase 8 or should be retired with the module.

## 7. Delivered (2026-09-21)

What shipped, against the phases in section 5:

- **0**: `/luna placeholder tab render`, `bridge stats|last`, the HTTP routes, the
  production compat fixture at `core/luna-core-api/src/test/resources/placeholders/`.
- **1**: `dev.belikhun.luna.core.api.placeholder`: grammar, key patterns, specs,
  registry, engine, samples, host, template, runtime, inspector, the `luna` built-ins
  (`LunaPlaceholders`, `StatBar`) and the mod stand-ins (`CompatPlaceholders`).
  Tests cover the grammar, patterns, registry, template, compat namespaces and every
  production `%luna_*%`.
- **2**: Paper runs `PaperPlaceholderModule`: one PlaceholderAPI expansion per
  namespace, main-thread sampling every 10 ticks, `/lunacorepaper placeholder`.
  LunaVault's Paper expansion became `VaultPlaceholderSpecs`, shared with the mods.
- **3**: Fabric, NeoForge and Forge run `MinecraftPlaceholderModule` over a
  `GameReads` per loader; the Fabric engine copy and the trunk providers are gone.
  `PlaceholderService` stays as a thin face for other modules. The 26.x Fabric module
  compiles again as a side effect.
- **4**: One TAB bridge runtime in `core/luna-tab-bridge-mc`, reading world, group
  and permission facts from the engine's samples. Bounded queue, in-flight guard,
  refused ids kept with their reason, unmatched ids echoed back raw and logged once,
  `bridge` and `trace` in `/lunacore placeholder`.
- **5**: `VelocityPlaceholderModule` on the proxy: one MiniPlaceholders expansion and
  one TAB registration set per namespace, one `/tab reload` hook, the `lunav`
  built-ins with `server_<field>[_<backend>]` covering every heartbeat field, vault and
  glyph as `lunav_vault_*` and `lunav_glyph_<key>`. Legacy ids stay as aliases and are
  named at startup.
- **6**: `VelocityTemplateRenderer` renders messenger formats, the Discord presence text,
  the display format, the selector and the connect command in one pass. Backend exports
  go through `ExportingPlaceholderResolver` with a fixed key list and caps; the
  runtime-discovered key set is gone. The messenger command frame's protocol is checked.
- **7**: `/luna placeholder migrate apply` rewrote the production TAB configs
  (`%lunavaultv-*%`, `%lunaglyph-*%`, `<luna_player_status>`) with backups beside the
  files; TAB reloaded onto the new ids with no errors logged. `LunaPlaceholders.md`
  is rewritten to the new model.

Still open:

- Aliases (`%lunav-…%`, `%lunavaultv-*%`, `%lunaglyph-*%`, proxy `<luna_*>`) are still
  registered; remove them one release after this one.
- The display template used by the tab bridge's `%rel_luna_player_display%` is still the
  hardcoded default rather than the proxy's `strings.user-display-format`; pushing it down
  the heartbeat is the remaining piece of section 2.4.
- The required foreign keys for messenger templates are not yet pushed from the proxy;
  backends export the default list plus their own config.
- `MessageFormatter` in `luna-core-api` still imports Bukkit and does its own `{…}` pass;
  it is used by `HttpServerManager` in the same module, so moving it is its own change.
- The NeoForge `create` server received phases 3 and 4 while stopped; its first start
  is the first live run of the shared bridge runtime.
- Phase 8, the 1.12 line, is untouched.
