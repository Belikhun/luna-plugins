# NeoForge Parity Closure Plan

## Goal

Close the remaining gap between the Paper backend plugins and the NeoForge backend mods so that:

- `luna-core-neoforge`, `luna-core-messaging`, `luna-countdown-neoforge`, `luna-messenger-neoforge`, and `luna-tab-bridge-neoforge` boot on a real NeoForge 1.21.1 server.
- Backend behavior matches the current Paper-side user-facing and proxy-facing behavior closely enough to be treated as a production replacement, not just a transport prototype.
- The attached NeoForge server can start successfully with the Luna jars and required external dependencies installed.
- Validation includes both standalone NeoForge startup and end-to-end behavior with the Velocity-side stack.

## Current Checkpoint Verdict

Parity is **not** complete yet.

The highest-impact gaps at the checkpoint are:

1. Packaging/runtime boot is broken for NeoForge deployables.
2. `luna-core-neoforge` is still only a bootstrap shell and does not mirror the Paper backend service surface.
3. `luna-countdown-neoforge` has command parity, but not Paper countdown UX parity.
4. `luna-messenger-neoforge` has the command transport path, but misses important Paper UX and integration behavior.
5. `luna-tab-bridge-neoforge` is much closer, but still needs final protocol-completeness review after the platform/runtime issues are fixed.

## Non-Negotiable Exit Criteria

Do not mark parity complete until all of the following are true:

- NeoForge server boots with Luna jars present and no module-layer or duplicate-package failures.
- Required external dependencies are documented and installed for the runtime under test.
- `luna-core-neoforge` exposes the backend services that NeoForge feature mods need, at parity with the Paper-side contract where applicable.
- Countdown behavior matches the Paper operational experience closely enough for admins and players.
- Messenger behavior matches the Paper operational experience closely enough for players, including result delivery and presence-side UX.
- TAB bridge behavior matches the expected `tab:bridge-6` runtime behavior for the placeholders and state flags used by Luna.
- Validation has been performed on the attached NeoForge server and, for cross-server features, against the Velocity-side runtime.

## Phase 0: Fix NeoForge Packaging First

### Problem

The current NeoForge artifacts are produced as `*-all.jar` outputs via root Shadow configuration. A real server start on the attached NeoForge instance failed with a module-layer resolution error caused by duplicate packaged libraries (`com.google.gson` collision inside `luna-core-messaging-neoforge-all.jar`).

### Required Work

1. Rework NeoForge packaging so runtime jars do not bundle libraries that NeoForge already provides or loads as named modules.
2. Audit every NeoForge module dependency and decide whether each dependency should be:
   - provided by NeoForge/runtime,
   - kept as `compileOnly`,
   - embedded safely, or
   - loaded through a NeoForge-compatible runtime mechanism.
3. Revisit the root `shadowJar` convention for NeoForge modules.
4. Produce deployable NeoForge jars that can be copied directly into `mods/` without module conflicts.

### Exit Criteria

- The attached NeoForge server starts past mod discovery and into normal server startup.
- No duplicate-package, module-layer, or jar-shadowing resolution failures remain.

## Phase 1: Bring `luna-core-neoforge` Up to Real Backend Runtime Parity

### Problem

`luna-core-paper` currently owns substantial backend runtime responsibilities, while `luna-core-neoforge` only registers a logger, dependency manager, AMQP config, and permission service.

### Required Work

1. Inventory the Paper-side core services and classify them into:
   - must port to NeoForge now,
   - can be replaced with NeoForge-native equivalents,
   - not applicable on NeoForge.
2. Port or replace the Paper-side backend essentials:
   - config loading and migration support,
   - database connection and migrations when still required by downstream modules,
   - HTTP management if used by downstream Luna features,
   - backend status/heartbeat publication,
   - server selector sync support if still part of backend expectations,
   - shared dependency registration for downstream NeoForge mods.
3. Expand `LunaCoreNeoForgeServices` so NeoForge feature modules are no longer building their own ad hoc substitutes for missing core services.
4. Define the NeoForge-side equivalent of Paper placeholder/bootstrap surfaces where feature modules rely on them indirectly.

### Exit Criteria

- NeoForge feature modules consume stable shared services from `luna-core-neoforge` rather than local stopgaps.
- The service surface needed by countdown, messenger, and TAB bridge is centralized and documented.

## Phase 2: Close Countdown UX Parity

### Problem

`luna-countdown-neoforge` has command-level behavior, but the current notifier only pushes action-bar updates. Paper countdowns use boss bars and player lifecycle rebinding.

### Required Work

1. Compare Paper `CountInstance` behavior against NeoForge runtime snapshots tick-by-tick.
2. Implement the NeoForge equivalent of persistent countdown HUD behavior.
3. Ensure players joining mid-countdown receive the current visible state.
4. Ensure players leaving are cleaned up safely.
5. Verify shutdown countdown UX separately from generic countdown UX.

### Exit Criteria

- Countdown and shutdown timers present persistent state comparable to the Paper experience.
- Join/leave lifecycle behavior does not regress visibility or cleanup.

## Phase 3: Close Messenger Behavioral Parity

### Problem

The NeoForge messenger runtime handles command dispatch and presence tracking, but Paper still provides richer behavior around placeholder exporting, result delivery, chat UX, join/quit UX, mentions, and integrations.

### Required Work

1. Compare `PaperMessengerGateway` feature-by-feature against `PresenceTrackingNeoForgeMessengerRuntime`.
2. Decide which Paper features are parity-critical and must exist on NeoForge:
   - placeholder export coverage,
   - mention alert handling,
   - poke alert handling,
   - timeout behavior and configurability,
   - presence-side join/quit UX expectations,
   - rich formatted result delivery instead of plain-text stripping where supported.
3. Replace hard-coded NeoForge messenger timeouts and behavior toggles with configuration where parity requires it.
4. Determine the acceptable NeoForge-side equivalent for InteractiveChat-dependent behavior.
5. Verify direct message, reply, poke, network chat, server chat, and context switching against the Velocity-side authority.

### Exit Criteria

- All Paper messenger commands have matching observable behavior on NeoForge unless explicitly declared not applicable.
- Message rendering and alerts are not downgraded silently.

## Phase 4: Finish TAB Bridge Runtime Completeness

### Problem

The raw NeoForge TAB bridge is much more complete now, but it should not be declared done until the remaining protocol slices and real-provider integrations are verified against expected upstream behavior.

### Required Work

1. Re-audit `RawChannelNeoForgeTabBridgeRuntime` against upstream `tab:bridge-6` behavior after the platform/runtime blockers are fixed.
2. Confirm all currently used outbound numeric packets behave exactly as the proxy expects.
3. Review whether any still-unhandled proxy actions matter for Luna’s actual deployment surface.
4. Decide whether vanished/disguised integrations need real providers now for parity.
5. Verify `%rel_*` behavior with real requested placeholders instead of only synthetic runtime inspection.

### Exit Criteria

- The bridge is validated with actual TAB runtime behavior, not just compile-time or local packet reasoning.
- All Luna-used placeholders and state flags hydrate correctly on join, refresh, late registration, unload, and shutdown.

## Phase 5: Runtime Validation on the Attached NeoForge Server

### Required Work

1. Install the required external runtime dependencies into `d:\Servers\neoforge\mods`.
2. Copy the newly fixed NeoForge Luna jars into that server.
3. Run a clean server startup.
4. Verify mod initialization logs for each Luna module.
5. Exercise in-game command paths for countdown and messenger.
6. Verify TAB bridge behavior with the matching proxy-side environment.

### Exit Criteria

- Server reaches a stable started state.
- No Luna module crashes during startup or shutdown.
- Core commands and behaviors work in a live server, not only in isolated module compilation.

## Phase 6: Velocity/Proxy End-to-End Verification

### Required Work

1. Run the Velocity-side Luna stack with the NeoForge backend connected.
2. Verify plugin messaging and RabbitMQ paths separately when both are intended to be supported.
3. Validate messenger command flows end-to-end.
4. Validate TAB bridge join, placeholder registration, permission checks, state flags, and relational updates end-to-end.
5. Validate countdown and backend status data that the proxy stack consumes.

### Exit Criteria

- NeoForge backend and Velocity proxy agree on the same observable behavior currently delivered by the Paper backend path.

## Execution Order

Follow this order strictly to avoid chasing false parity failures:

1. Fix NeoForge packaging and boot.
2. Install required external runtime dependencies and get the attached server to start.
3. Expand `luna-core-neoforge` to a real backend service host.
4. Close countdown UX parity.
5. Close messenger parity.
6. Re-run TAB bridge audit under live runtime conditions.
7. Perform full end-to-end validation with the Velocity-side stack.

## Acceptance Checklist

### `luna-core-neoforge`

- Boots cleanly.
- Provides the shared services needed by dependent NeoForge Luna modules.
- No longer acts only as a minimal bootstrap shell.

### `luna-countdown-neoforge`

- Commands behave like Paper.
- Countdown presentation is parity-grade, not action-bar-only stopgap behavior.
- Join/leave handling preserves active countdown visibility and cleanup.

### `luna-messenger-neoforge`

- `/nw`, `/sv`, `/msg`, `/r`, `/poke` work end-to-end.
- Presence tracking is accurate.
- Results and alerts preserve expected formatting/UX.
- Timeout behavior is correct and configurable where parity requires it.

### `luna-tab-bridge-neoforge`

- Join response is correct.
- Placeholder registration and update flow is correct.
- `%rel_*` values behave correctly under real proxy usage.
- Invisible, vanished, disguised, world, group, and gamemode sync behave correctly.

## Definition of Done

The gap is closed only when:

- the NeoForge Luna backend boots on the attached server,
- the NeoForge backend behaves like the Paper backend for the supported Luna feature set,
- the Velocity-side stack works with it without backend-specific regressions,
- and the remaining parity checklist above is fully satisfied.