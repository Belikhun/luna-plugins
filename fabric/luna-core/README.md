# luna-core-fabric

The Fabric runtime foundation for the luna backend modules: the counterpart of
`luna-core-paper` and `luna-core-neoforge`.

It publishes this backend's heartbeat to the velocity proxy (which is what makes
the server appear in the luna console), serves the network's server list, and
holds the service container the other luna fabric modules resolve through
`LunaCoreFabric.services()`.

Build: `./gradlew :luna-core-fabric:remapJar` → `output/fabric/luna-core-fabric-all.jar`.
Plain `shadowJar` works too; it is wired to finalise with `remapJar`, because an
unremapped jar is not something a Fabric server can load.

## One build, many game versions

The jar supports **Minecraft 1.20 through 1.21.x** without a per-version build.
That is a deliberate constraint on how the mod is written, not a build trick:

- **No mixins and no access wideners.** Both bind to a specific version's
  internals; neither is used anywhere in this module.
- **Nothing in the game is subclassed.** The mod registers callbacks and reads
  values; it never extends a game class, so a changed constructor or a new
  abstract method cannot break it.
- **Only long-lived API.** Fabric API is used through its versioned packages
  (`-v1`, `-v2`), which are kept binary compatible on purpose, and the game is
  touched in about 40 places in total - few enough to verify (see below).
- **Anything that did change is gated.** `compat/GameVersion` answers what is
  running, and `compat/Guarded` contains a call a later version removed so the
  feature behind it degrades on its own. Two things currently rely on this: the
  clickable/hoverable parts of the server list (both event types were reshaped in
  1.21.5) and per-value stat reads on the server object.
- **Tick rate is measured, not asked for.** The server's tick-time accessor was
  renamed in 1.20.5, so `TickRateMonitor` counts ticks instead; spark still takes
  precedence wherever it is installed.

### Verifying it

`tools/check-versions.py` reads every `net.minecraft` member the built jar links
against and checks it against Fabric's intermediary mappings for each supported
version. Run it after any change that touches game API:

```
./gradlew :luna-core-fabric:remapJar
python3 luna-core-fabric/tools/check-versions.py
```

It exits non-zero the moment a reference stops existing on a version in the list,
which turns "works on the one version I compiled against" into an answer at build
time rather than a `NoSuchMethodError` in production.

### Why the range stops below 26.x

From the date-based versions (26.1, 26.2, …) Mojang ships the server
unobfuscated, so Fabric publishes an **empty** intermediary mapping and mods are
expected to link the real names. A jar remapped to intermediary - which this one
is, and must be, to run on 1.20-1.21 - cannot also run there. `fabric.mod.json`
therefore declares `"minecraft": ">=1.20 <2.0"` so the loader refuses the mod
rather than loading it into a crash.

That line is served by **`luna-core-mc26-fabric`**, which compiles these same
sources against the real names and ships them unremapped. It is a sibling module
rather than a fork: `src/main/java` here is its source tree too, by reference, so
a fix lands on both. The one file that cannot be shared is
`src/mc21/java/…/compat/ChatEvents.java`, because 1.21.5 turned the chat click
and hover events into sealed interfaces and no expression compiles against both
shapes; that module carries the other half under the same class name.

luna deploys the right one on its own. Each jar declares its game range in
`luna-plugin.json`, and `luna luna sync` pools this build as the plugin's primary
and the 26.x build as a variant, which is the same mechanism that already picks a
plugin build per instance by MC version.

## Differences from the NeoForge core

- **The server list is chat, not an inventory.** An inventory is built out of item
  display data, and that is exactly what the 1.20.5 component rewrite replaced, so
  a single jar cannot fill both shapes without carrying two item layers. The same
  `ServerSelectorEngine` produces the same titles and lore; they are rendered as
  clickable chat lines instead of slots.
- **Missing LuckPerms disables the selector instead of refusing to boot.** The
  heartbeat still runs, because a backend the console cannot see is a worse
  failure than a server list nobody can open.
- **Commands register against a lookup.** The dispatcher is built before there is
  a server, so the tree is registered once and each executor resolves the
  controller when the player runs it.

## What the instance needs

luna provisions these automatically when it creates a fabric instance; they are
listed here because the mod will not load without the first one.

- **Fabric API** - required. Fabric loader ships no game API of its own, and both
  this mod and `fabricproxy-lite` hard-depend on it, so a server without it fails
  mod resolution before it starts. `SoftwareTraits.requiredAddons` on the control
  side is what installs it.
- **FabricProxy-Lite** - required behind a velocity proxy. luna writes its
  `config/FabricProxy-Lite.toml` with the cluster's forwarding secret, which is
  also where this mod reads that secret from.
- **LuckPerms** - optional. Without it the server list is disabled and the
  heartbeat still runs.
- **spark** - optional. Where it is installed its TPS and CPU readings are used
  in preference to the mod's own.

## Not here yet

- `PluginMessageBus` (the AMQP transport `luna-core-messaging` provides on
  NeoForge) has no Fabric build, so `/lunacoreconnect` reports that transfers are
  unavailable. Listing servers works, because that payload arrives over the
  heartbeat's own HTTP channel.
- The placeholder service (`BuiltInNeoForgePlaceholderService` and its providers)
  has not been ported.
