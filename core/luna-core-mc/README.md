# luna-core-mc

The platform-free half of the luna UI toolkit: chest menus, item decoration, chat
prompts, the MiniMessage bridge. It is written against `net.minecraft` and nothing
else, so no fabric, neoforge or forge type appears anywhere in it.

It is **not** a gradle project and never becomes a jar. `net.minecraft` is not a
publishable dependency, so the code is shared by *source*: every platform module
adds these directories with `java.srcDir(...)` and compiles them against its own
game. That is what lets one screen render on fabric, neoforge and forge alike.

## The directories

`src/main/java` is the trunk: everything whose Minecraft API is spelled the same
on every line luna supports. Nothing version-sensitive belongs here.

The rest are **compat sets**, one per (concern, API era). A module adds the trunk
plus exactly the set matching its game line, so a file is never copied and the
trunk never learns to branch:

| Directory | Provides | Lines |
|---|---|---|
| `src/menu-clicktype/java` | `LunaChestMenu` overriding `clicked(…, ClickType, …)` | 1.19 - 1.21.x |
| `src/menu-containerinput/java` | the same, with `ContainerInput` | 26.x |
| `src/registry-namespaced/java` | `ItemLookup` via `ResourceLocation.fromNamespaceAndPath` | 1.20.5 - 1.21.x |
| `src/registry-ctor/java` | the same, via `new ResourceLocation(ns, path)` | 1.19 - 1.20.4 |
| `src/registry-identifier/java` | the same, via `Identifier` + `registry.getValue` | 26.x |
| `src/decor-components/java` | `ItemDecor` (name, lore, glint) via data components | 1.20.5 - 26.x |
| `src/decor-nbt/java` | the same, via the `display` tag | 1.19 - 1.20.4 |
| `src/itemio-codec/java` | `ItemIo` (item to and from a tag) via `ItemStack.CODEC` | 1.20.5 - 26.x |
| `src/itemio-save/java` | the same, via `save`/`ItemStack.of` | 1.19 - 1.20.4 |
| `src/text-codec/java` | `ComponentJson` (chat json to a component) via `ComponentSerialization` | 1.20.3 - 26.x |
| `src/text-serializer/java` | the same, via the static `Component.Serializer` | 1.19 - 1.20.2 |

A set is kept as small as the change is. `ItemDecor` is three writes rather than
the whole of `LunaItems`, and `ItemIo` is two calls rather than the whole of
`LunaItemCodec`, so the MiniMessage rendering, the barrier fallback, the gzip and
base64 envelope and the size cap are all written once.

Splitting by concern rather than by game line is deliberate. A line-shaped layout
(`src/mc20`, `src/mc21`, …) would hold a copy of `LunaChestMenu` in each of the
three lines that spell it identically, and the copies would drift. Here each file
exists exactly once, and a new line is a set of *choices* rather than a new pile
of sources.

## Mixins are per line too

`fabric/luna-hat/src/mixin-armorslot` and `src/mixin-inventoryslot` are the same
idea applied to a mixin, and they exist because the target class itself moved:
1.21 and 26.x have a named `ArmorSlot`, while 1.19 - 1.20.4 build the armor slots
as anonymous `Slot` subclasses inside `InventoryMenu` and so have nothing to name.
The older line widens `Slot.mayPlace` and narrows the effect back down by checking
the container and slot index.

Two things this cost, both worth knowing before adding another mixin:

- **Mixin `compatibilityLevel` must match the JVM the line runs on.** The 1.21
  config says `JAVA_21`; forge 1.20.1 runs Java 17 and rejects it.
- **A missing target fails silently.** A mixin only errors when its target class
  loads, so a config pointing at a class this line does not have lets the server
  boot, the mod report ready, and the feature quietly do nothing. Believe the
  compiler's `Mixin target ... could not be fully resolved` warning; it is the
  only warning you get.

## `src/services/java`

Not a compat set: the loader-free *implementations* of luna-core's services -
placeholders, the server selector, the heartbeat probe, logging, the voicechat
bridge - as opposed to the toolkit in the trunk. It is added by the neoforge and
forge core modules, which is what lets one implementation serve both loaders.

Nothing in here may import a loader. The two places that did were fixed rather
than tolerated: `ServerProbe` takes its config directory as a constructor
argument instead of reading `FMLPaths`, and `ServerSelectorController` takes a
brigadier `CommandDispatcher` instead of a register-commands event. Each
bootstrap unwraps its own loader's types and calls in.

Fabric still carries its own parallel copies of these services under
`core.fabric.*`; converging them onto this directory is worth doing and has not
been done, so a fix here does not reach fabric yet.

## Adding a game line

Work out which existing set each concern falls in and compose them; only write a
new set when nothing fits. When you do, add a row above and name the directory
after the API it uses, never after the version that happens to need it first -
the next line along usually reuses it.

## Keeping the trunk honest

If a trunk file needs `if (version …)`, that is the signal it holds two eras'
worth of code and wants splitting into a compat set instead. `LunaChestMenuBase`
shows the cheaper alternative where it works: it takes the click as a *string*
(`LunaClick` maps it once), so the 193-line base never names the enum whose type
changed and stays in the trunk.
