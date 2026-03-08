# LunaPackLoader Implementation Plan

## 1. Goal
Implement a new Velocity plugin named `LunaPackLoader` in module `luna-pack` with package root `dev.belikhun.luna.pack`.

The plugin manages stacked resource packs based on the player current backend server, supports admin commands for reload/resend/force load/unload/debug, and enforces required pack policy.

Implementation is inspired by PackStacker behavior, but all code here should be original and adapted to this repository architecture.

## 2. Scope and Requirements Mapping

### Functional requirements
- Packs are served from an external web server using a configurable `base-url` plus pack `filename`.
- Packs are defined by YAML files and support:
  - `name`
  - `filename`
  - `priority` (default `0`, lower = lower layer)
  - `required`
  - `enabled`
  - `servers` (`*`/`all` means all servers)
- Main config includes:
  - `base-url`
  - `pack-path` (local path to zip files for hash and size calculation)
- Admin commands:
  - reload (config + pack configs + recompute hashes)
  - resend (reapply computed pack set for current server)
  - forceload (load specific pack on target player even if disabled or not mapped to server)
  - forceunload (remove a loaded pack from player)
  - debug toggle for player chat stream
  - debug state view for specified player
- Runtime behavior:
  - Correct pack load/unload on server transitions
  - Avoid unnecessary unload/reload when already correct
  - On required pack failure: kick or return player to previous server
  - Notify player before pack request with count and total download size

### Non-functional requirements
- Java 21 and existing Gradle conventions.
- Velocity plugin metadata via `@Plugin` and generated `BuildConstants` template.
- Player-facing text must be Vietnamese with full accents.
- Build verification with `./gradlew shadowJar`.

## 3. Architecture Overview

### Core components
- `LunaPackLoaderPlugin`: Velocity entrypoint, bootstrap, command/event registration.
- `LoaderConfigService`: read and validate `config.yml`.
- `PackRepository`: load and validate pack YAML files, maintain indexed pack metadata.
- `PackHashService`: compute SHA-1 and file size from `pack-path`.
- `PackSelectionService`: determine desired packs for a server and diff with current player state.
- `PlayerPackSessionStore`: per-player runtime state (loaded/pending/debug/previous server).
- `PackDispatchService`: build and send resource pack requests/unloads.
- `PackStatusListener`: handle `PlayerResourcePackStatusEvent` outcomes.
- `PackCommand`: admin command dispatcher with subcommands.
- `MessageService`: centralized Vietnamese user/admin messages.

### Per-player runtime state model
For each player UUID, track:
- `loadedPackNames`: packs confirmed as loaded.
- `pendingPackIds`: request UUID -> pending pack context.
- `lastKnownServer`: current backend server name.
- `previousServer`: last backend server before transition.
- `lastTransitionAt`: timestamp for rollback safeguards.
- `debugEnabled`: whether to mirror debug info to player chat.
- `lastFailure`: optional latest error status for diagnostics.

## 4. Build and Module Plan

## 4.1 Add module
- Add `include("luna-pack")` to `settings.gradle.kts`.
- Create `luna-pack/build.gradle.kts`:
  - `implementation(project(":luna-core-api"))`
  - `compileOnly(libs.velocity.api)`
  - `annotationProcessor(libs.velocity.api)`
  - generated `BuildConstants` task copied from `luna-core-velocity` pattern.

## 4.2 Resolve platform routing conflict
Current root logic treats modules not ending in `-velocity` as Paper.

Because module name is requested as `luna-pack`, implement one of:
- Option A (recommended if name can change): rename module to `luna-pack-velocity`.
- Option B (keep requested name): update root `build.gradle.kts` platform detection to mark `luna-pack` as Velocity explicitly.

Plan assumes Option B to honor requested module name.

## 5. Configuration and Data Files

## 5.1 `config.yml`
File: `luna-pack/src/main/resources/config.yml`

```yaml
base-url: "https://mc.belikhun.dev/mcds/packs/"
pack-path: "plugins/lunapackloader/packs"
```

Validation rules:
- `base-url` must be a valid absolute URL (http/https).
- Normalize trailing slash (always exactly one trailing slash in runtime).
- `pack-path` may be absolute or relative to Velocity working directory.
- Log warnings for invalid paths; keep plugin running with zero available packs.

## 5.2 Pack definition files
Directory: `plugins/lunapackloader/packs/`
Extension: `*.yml`

Example:
```yaml
name: "example-pack"
filename: "example-pack.zip"
priority: 0
required: false
enabled: true
servers:
  - lobby
  - survival
```

Validation rules:
- `name` unique (case-insensitive key).
- `filename` required, must not include path traversal segments.
- `servers` required list; `*` or `all` means global.
- `priority` default `0`.
- `required` default `false`.
- `enabled` default `true`.
- Invalid files are skipped with clear console diagnostics.

## 5.3 Message templates
Add `messages.yml` for Vietnamese chat output and admin feedback.

Message groups:
- reload success/failure
- player pack summary before request
- forced load/unload feedback
- required pack failed actions
- debug lines (queue/load/unload/status)

## 6. Pack Loading and Diff Algorithm

## 6.1 Desired packs for server
- Resolve backend server name from player current server.
- Select packs where:
  - `enabled = true`
  - server match (`servers` contains current server, or wildcard/all)
- Sort by `priority` ascending, tie-breaker by `name` ascending.

## 6.2 Delta computation
Given `desired` and `loaded` sets:
- `toLoad = desired - loaded`
- `toUnload = loaded - desired`, excluding packs marked sticky by current in-flight required checks (if any)

Optimization rule:
- If `toLoad` and `toUnload` are both empty, do nothing.
- Never unload and immediately reload the same pack during one transition.

## 6.3 Dispatch strategy
- Unload packs in `toUnload` first.
- Send load request for `toLoad` as one stacked request (preserving order by priority).
- Record pending request pack ids and timestamps.
- Before sending, notify player in Vietnamese:
  - number of packs
  - human-readable total size

## 7. Hash and Size Calculation
- For each pack file `pack-path/<filename>`:
  - compute SHA-1 hash (required by client resource pack protocol)
  - compute file size bytes
- Build final URL as `base-url + filename`.
- Cache hash/size in memory for runtime use.
- On `reload`, fully recompute and replace cache atomically.

Failure policy:
- If file missing or unreadable, mark pack unavailable and skip from load selection.
- `forceload` on unavailable pack returns explicit error.

## 8. Events and Runtime Flow

## 8.1 Startup
- Load config.
- Load pack definitions.
- Compute hashes/sizes.
- Register commands and listeners.

## 8.2 Player connect/disconnect
- `PostLoginEvent`: initialize player state entry.
- `DisconnectEvent`: cleanup state entry.

## 8.3 Server transition handling
- `ServerPostConnectEvent`:
  - capture previous and current server names
  - compute and apply delta
  - emit debug lines if enabled

## 8.4 Pack status handling
- `PlayerResourcePackStatusEvent`:
  - map event to pending pack(s) by pack id or hash
  - on `SUCCESSFUL`: mark pack loaded
  - on `ACCEPTED`: keep pending and debug-log
  - on failure (`DECLINED`, `FAILED_DOWNLOAD`, etc.):
    - mark failed
    - if pack required:
      - attempt return to previous server if available and connectable
      - if return impossible, disconnect with Vietnamese reason

Important note:
- If using forced flag from Velocity resource pack info, coordinate with manual rollback logic to avoid duplicate kicks.

## 9. Command Design
Primary command alias proposal: `/lunapack` (aliases `/lpack`, `/packloader`).

Command parser style requirement (match existing Luna plugins):
- Use one top-level command class that manually parses `String[] args` with `if`/`switch` blocks (same style as `ShopCommand`, `CountdownCommand`, `ToolRepairCommand`).
- Keep parsing explicit and readable per subcommand instead of introducing a new command framework.
- Centralize usage/syntax text with `CommandStrings`.
- Implement completion with `CommandCompletions.filterPrefix(...)`.
- Keep permission checks directly in command handlers for each action.

Velocity binding note:
- The parser style above must be preserved, but the command adapter should use Velocity `SimpleCommand` for registration.
- `execute(Invocation)` should delegate to an internal `handle(CommandSource source, String[] args)` method.
- `suggest(Invocation)` should delegate to an internal `suggest(CommandSource source, String[] args)` method.

Subcommands:
- `/lunapack reload`
  - permission: `lunapack.admin.reload`
  - behavior: reload config + packs + hashes; report loaded/invalid counts
- `/lunapack resend <player>`
  - permission: `lunapack.admin.resend`
  - behavior: recompute based on player current server and resend delta
- `/lunapack forceload <player> <pack>`
  - permission: `lunapack.admin.forceload`
  - behavior: request specified pack ignoring `enabled` and server mapping
- `/lunapack forceunload <player> <pack>`
  - permission: `lunapack.admin.forceunload`
  - behavior: remove specified pack from client and runtime state
- `/lunapack debug <player> [on|off|toggle]`
  - permission: `lunapack.admin.debug`
  - behavior: control debug chat stream for player
- `/lunapack debug state <player>`
  - permission: `lunapack.admin.debug.state`
  - behavior: print loaded/pending/current server/last failure

Tab completion:
- online player names
- loaded pack names
- all pack names
- static subcommand tokens

## 10. Suggested File Layout

```text
luna-pack/
  build.gradle.kts
  src/main/java/dev/belikhun/luna/pack/
    LunaPackLoaderPlugin.java
    BuildConstants.java (generated)
    command/
      PackAdminCommand.java
    config/
      LoaderConfig.java
      LoaderConfigService.java
      PackDefinition.java
      PackRepository.java
    model/
      ResolvedPack.java
      PlayerPackSession.java
      PackStatusSnapshot.java
    service/
      PackHashService.java
      PackSelectionService.java
      PackDispatchService.java
      PlayerPackSessionStore.java
      MessageService.java
    listener/
      PlayerConnectionListener.java
      PlayerPackStatusListener.java
    util/
      SizeFormat.java
      ValidationUtil.java
  src/main/resources/
    config.yml
    messages.yml
```

## 11. Phased Delivery Plan

### Phase 1: Project scaffold
- Add module and Gradle wiring.
- Add plugin main class + initialization event + command registration skeleton.
- Add default `config.yml` and `messages.yml` resources.
- Exit criteria: plugin loads on Velocity and logs startup line.

### Phase 2: Config + pack repository
- Implement `LoaderConfigService` and `PackRepository` with validation.
- Implement hash+size calculation and resolved pack cache.
- Exit criteria: `/lunapack reload` reports valid/invalid packs with hash availability.

### Phase 3: Runtime session + transition logic
- Implement per-player session store.
- Implement server transition listener and delta-based load/unload.
- Add player summary message (count + total size) before sending request.
- Exit criteria: moving between servers applies only delta changes.

### Phase 4: Status handling and required failure policy
- Implement `PlayerResourcePackStatusEvent` processing.
- Add rollback to previous server, fallback disconnect path.
- Ensure required failures are handled once per transition.
- Exit criteria: required pack decline/fail reliably returns/kicks with clear message.

### Phase 5: Admin commands + debug tools
- Implement resend/forceload/forceunload/debug/state.
- Add tab completions and permission checks.
- Keep parser implementation aligned with Luna command conventions (`args[]` parser + `CommandStrings` + `CommandCompletions.filterPrefix`).
- Exit criteria: all command paths behave as specified.

### Phase 6: Hardening and verification
- Add input/path sanitization and robust logging.
- Manual test matrix (below).
- Run `./gradlew shadowJar`.
- Exit criteria: no build errors; manual checks pass.

## 12. Manual Test Matrix
- Player joins server with no matching packs.
- Player joins server with 1 enabled optional pack.
- Player joins server with multiple packs mixed priority.
- Transition where only one additional pack should load.
- Transition where one pack unloads and one stays.
- Pack file missing from `pack-path`.
- `required=true` pack declined by client.
- `required=true` pack download failure.
- `forceload` for disabled pack.
- `forceunload` for required pack (admin override path).
- Debug on/off and state output correctness.
- Reload while players online (cache swap safety).

## 13. Risks and Mitigations
- Velocity API differences across versions for resource pack request fields.
  - Mitigation: code against currently cataloged `velocity-api` and verify with compile.
- Ambiguous mapping between status events and stacked packs.
  - Mitigation: maintain pack-id mapping and hash fallback map.
- Required pack kick race with manual rollback.
  - Mitigation: centralize failure handling and guard against duplicate actions.
- Broken URL normalization causing invalid links.
  - Mitigation: strict URL validator and startup diagnostics.

## 14. Open Decisions for Review
- Confirm module naming strategy:
  - Keep `luna-pack` and patch root platform routing (planned), or
  - rename to `luna-pack-velocity`.
- Confirm primary admin command label and aliases.
- Confirm required-pack failure preference order:
  - return to previous server first, then kick (planned), or always kick.
- Confirm whether `resend` should be admin-only for any player, or include self shortcut.
- Confirm if `forceload` should bypass `required` enforcement semantics entirely.

## 15. Definition of Done
- New Velocity plugin module compiles and packages to `output/luna-pack-velocity-all.jar` (or agreed artifact name).
- All required commands implemented with permissions and tab completion.
- Pack selection and delta behavior correct on server changes.
- Required pack failure path implemented with return/kick logic.
- Player messaging (Vietnamese) and debug mode implemented.
- `./gradlew shadowJar` passes.
