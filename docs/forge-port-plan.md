# Porting luna to forge

Two ports, not one, because forge is two different things across the range luna
wants to run on.

## Why not a single jar for 1.12.2 - 1.20.1

Fabric ships one jar for 1.20 upward because a fabric mod is compiled against
**intermediary** names and remapped at load time. Forge has no equivalent: a
compiled mod carries the runtime names of exactly one era, so the same jar cannot
resolve on two. Four walls sit inside that range, any one of them decisive:

1. **Runtime names.** 1.12.2 - 1.16.5 mods reference SRG members (`func_71121_q`);
   1.17+ reference official class names with `m_`/`f_` ids. No load-time remap.
2. **Two loader eras.** 1.12.2 is legacy FML on LaunchWrapper (`mcmod.info`,
   `@Mod(modid=…)`, `@Mod.EventHandler`); 1.13+ is ModLauncher (`META-INF/mods.toml`,
   `@Mod("id")`, bus lifecycle). A jar may carry both descriptors, but that only
   helps if the classes inside could run on both, and per (1) they cannot.
3. **Bytecode floor.** A stock 1.12.2 server runs Java 8; 1.18+ requires 17. One
   jar would have to be entirely Java 8, and `luna-core-api` is Java 21 with
   records in 46 of its 173 files.
4. **The game changed shape.** 1.13 flattening (item ids and metadata, which
   LunaShop's payloads are made of), Brigadier from 1.13 (1.12.2 is `CommandBase`),
   the container rework, chat components renamed twice, RGB chat at 1.16.

Every mod advertising "1.12 - 1.20" ships one jar per line from one source tree.
That is the shape here too.

## Modern forge: 1.20.1 and 1.19.2

A trunk consumer, alongside fabric and neoforge. `core/luna-core-mc` is compiled
by source into each platform module; the game-line differences are compat sets,
listed in that module's README.

Build: `net.neoforged.moddev.legacyforge` - the same ModDevGradle the neoforge
modules use. Sources stay mojmap and are reobfuscated to SRG at packaging.
Verified against a scratch module: `getPlayerList()` compiles to `m_6846_` in the
deliverable. MDG's own tooling wants a JDK 17, auto-provisioned by the foojay
resolver already applied in `settings.gradle.kts`.

Measured, not estimated: compiling the trunk against 1.20.1 broke **two files** -
`LunaItems` (data components) and `LunaItemCodec` (item tag io and the read
accounter). Menus, chat, prompts, placeholders and the click bridge compiled
unchanged. Both were resolved by extracting the changed calls into `ItemDecor`
and `ItemIo` rather than duplicating either file.

What stays per loader, and so has to be written for forge:

- nine bootstraps (forge's bus is the static `MinecraftForge.EVENT_BUS`, and its
  event names are the pre-fork ones: `LivingDamageEvent`, `EntityItemPickupEvent`,
  no `UseItemOnBlockEvent`)
- the auth restriction controller, which is event-cage code and already exists
  once per platform
- a `SimpleChannel` message transport: the custom-payload API is 1.20.2+
- mixin declaration through the jar manifest's `MixinConfigs` rather than a
  `[[mixins]]` block
- the descriptor itself: `META-INF/mods.toml`, not `neoforge.mods.toml`, and a
  dependency's necessity is spelled `mandatory=true` where neoforge writes
  `type="required"`. Copying neoforge's spelling is not a warning - forge throws
  `InvalidModFileException: Missing required field mandatory in dependency` and
  logs only `File <jar> is not a valid mod file`, so the server starts happily
  without the mod. Check for the mod's own log line, never for a clean boot.

1.19.2 then follows the `-mc26` sibling pattern: same sources, its own
descriptor, its own compat sets where they differ.

### What a module port actually is

Measured on luna-core, which was the largest: of its 18 files only the bootstrap
imported a loader. The other 2,644 lines moved to `core/luna-core-mc/src/services`
under `dev.belikhun.luna.core.mc.*` and the forge module picked them up with one
`srcDir` line. So a port is: promote what is already loader-free, then write the
bootstrap. Two classes needed decoupling first - `ServerProbe` took its config
directory as an argument instead of reading `FMLPaths`, and
`ServerSelectorController` took a brigadier dispatcher instead of a
register-commands event.

### Deliberately not in scope

1.21+ on classic forge. NeoForge is what the 1.21 modded ecosystem moved to and
luna already ships it; adding a third 1.21 build buys nothing until a specific
forge 1.21 pack needs hosting.

## Legacy forge: 1.12.2

Not a trunk consumer - a sibling implementation, the way the pumpkin port is.

- **Its own mini-trunk** against MCP names (`EntityPlayerMP`, `ITextComponent`,
  `NBTTagCompound`, `Container`/`IInventory`, `CommandBase`). The designs port
  directly: 1.12.2 has the same click constants, so the `LunaClick` string bridge
  and the chest-menu base/override split carry over unchanged. The code is
  rewritten; the architecture is not.
- **A Java 8 `luna-legacy-api`**, because `luna-core-api` cannot be consumed:
  config loading, the heartbeat client, forwarding-secret resolution, the AMQP and
  plugin-message wire contracts, the economy protocol, jdbc. Every dependency
  involved (snakeyaml, amqp-client 5.x, adventure 4.x, the mariadb driver) still
  supports 8. **The wire protocols are the real shared trunk here**: a 1.12.2
  vault backend speaks the same frames to the same velocity plugins.
- **RetroFuturaGradle 2.x**, which runs on this repo's gradle, so it fits the
  multi-project build the way the loom-free 26.x sibling already does.
- **Adaptations with no modern counterpart**: adventure rendered to legacy `§`
  text with RGB downsampled to 16 colours, pre-flattening item ids with metadata
  in the shop store, non-namespaced plugin-message channels (namespaced is 1.13+).

Costs outside this repo, which is why it is scoped separately:

- **Forwarding.** Modern forwarding needs login plugin messages, which are 1.13+.
  1.12.2 is legacy or BungeeGuard, with PCF's legacy jar, MixinBooter on the
  backend and an Ambassador-style plugin on velocity.
- **Permissions.** There is no LuckPerms build for forge 1.12.2, and every luna
  bootstrap currently declares it a required dependency. The legacy port needs an
  OP-level fallback.
- **Provisioning.** luna's own forge provider refuses <= 1.16 today: that era is
  the runnable universal jar, with no `unix_args.txt` to launch through.
- Simple Voice Chat and spark integrations are likely absent; they degrade to the
  optional-dependency path.

Effort: modern forge is roughly a rerun of the neoforge port, with the trunk
handing over ~6k lines. Legacy is 2-3x that, dominated by the shop gui, the auth
cage and the command layer.
