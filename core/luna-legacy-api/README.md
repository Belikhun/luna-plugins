# luna-legacy-api

`luna-core-api` at Java 8, for the 1.12.2 line.

A stock 1.12.2 server runs Java 8. `luna-core-api` is Java 17 bytecode and uses records in
45 of its files, so a 1.12.2 mod can neither load it nor compile against it. This module is
the half of it that a legacy backend actually needs, rewritten to that floor.

## What it carries, and what it does not

It carries the **platform-free** half: the wire formats, the config loader, the heartbeat
client, the permission contract. Those are the real shared trunk - a 1.12.2 backend speaks
the same frames, on the same channels, to the same velocity plugins as every other backend.

It deliberately does **not** carry anything Bukkit-coupled (`gui/`, `help/`, `ConfigStore`,
`DatabaseManager`, `HttpServerManager`, `MessageFormatter`, `LunaUi`), because none of it
could compile here and none of it is reachable from a mod.

## Why the packages differ

Everything here lives under `dev.belikhun.luna.legacy.*`, not `dev.belikhun.luna.core.api.*`.

Two jars carrying the same packages with different bytecode versions is a runtime surprise
waiting for whoever puts both in one `mods/` folder. luna's game-line variant mechanism
means that should never happen, but "should never" is not the same as "cannot", and a
distinct package makes a mistake a compile error instead. It also makes it greppable which
line a given call site belongs to.

## Downgrades, and the ones that bite

Java 8 has neither the syntax nor the library surface the modern api assumes:

| | Modern api | Here |
|---|---|---|
| `String.isBlank()` | 42 files | `Strings.isBlank` |
| `List.of` / `Map.of` / `Set.of` | ~20 files | `Arrays.asList`, `Collections.unmodifiable*` |
| `record` | 45 files | final classes with explicit `equals`/`hashCode`/`toString` |
| switch arrow, pattern `instanceof` | throughout | statement switch, explicit casts |
| `java.net.http.HttpClient` | `BackendRegistryClient` | `HttpURLConnection` |

There are no sealed types and no text blocks anywhere in the modern api, so nothing to
unwind there.

## The contract that must not drift

The byte formats are shared with a cluster running Java 21, and nothing in the type system
enforces that. `src/test/` pins them instead: fixtures captured from the modern build, which
this module's output has to reproduce byte for byte. `HeartbeatFormCodec.PROTOCOL_VERSION`
and its field order are read by the proxy, and a silent divergence there is a backend that
registers wrong rather than one that fails loudly.

That approach is taken from the pumpkin port, which has the same problem in Rust and solved
it the same way (`pumpkin/luna-core-api/tests/java_compat.rs`).
