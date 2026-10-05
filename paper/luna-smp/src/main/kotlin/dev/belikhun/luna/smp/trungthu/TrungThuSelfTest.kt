package dev.belikhun.luna.smp.trungthu

import io.papermc.paper.datacomponent.DataComponentTypes
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Levelled
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockBreak
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitFun
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.util.BlockUtils
import xyz.xenondevs.nova.util.item.novaItem
import xyz.xenondevs.nova.util.runTaskLater
import xyz.xenondevs.nova.world.block.NovaBlock
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.format.WorldDataManager
import xyz.xenondevs.nova.world.item.recipe.RecipeRegistry
import xyz.xenondevs.nova.world.pos
import java.io.File
import java.util.UUID
import java.util.logging.Logger

/**
 * An end-to-end check of the Mid-Autumn set that needs nobody in game.
 *
 * Armed by a marker file - touch `plugins/LunaSmp/.trungthu-selftest` and
 * restart - it forceloads a patch of sky, places every lantern in all three
 * mounts, every kind of mooncake on its plate, the drum and the pomelo dog
 * through Nova's own placement path, and checks what a player would see: the
 * state, the tile entity, the backing block, the companion light, the tune
 * a click selects, the plate eaten a quarter at a time, the drops. It walks
 * a pretend carrier through the air to check that a held lantern's light
 * follows and is put away, checks every recipe and tune, then breaks
 * everything it placed and removes the forceload.
 *
 * The report goes to `plugins/LunaSmp/trungthu-selftest.txt` and the log,
 * one PASS or FAIL line per check and a total. The marker is consumed.
 */
@Init(stage = InitStage.POST_WORLD)
object TrungThuSelfTest {

	private val log: Logger = Logger.getLogger("LunaSmp")

	private val lines = ArrayList<String>()
	private var passed = 0
	private var failed = 0

	/** The patch of sky the test builds in: high enough to be empty in any world. */
	private const val Y = 250
	private const val X0 = 0
	private const val Z0 = 0

	@InitFun
	private fun run() {
		val marker = File("plugins/LunaSmp/.trungthu-selftest")

		if (!marker.exists()) {
			return
		}

		marker.delete()

		// an addon init that throws sinks the whole server; a test never may
		try {
			start()
		} catch (error: Exception) {
			log.warning("[TrungThu self-test] could not start: $error")
		}
	}

	private fun check(name: String, ok: Boolean, detail: String = "") {
		val line = (if (ok) "PASS " else "FAIL ") + name + (if (detail.isEmpty()) "" else " ($detail)")

		if (ok) {
			passed++
		} else {
			failed++
		}

		lines.add(line)
		log.info("[TrungThu self-test] $line")
	}

	/** Runs [action] [delay] ticks from now, turning an exception into a failed check. */
	private fun later(delay: Long, name: String, action: () -> Unit) {
		runTaskLater(delay) {
			try {
				action()
			} catch (error: Throwable) {
				check(name, false, error.toString())
			}
		}
	}

	private fun start() {
		val world = Bukkit.getWorlds().first()

		for (cx in 0..3) {
			for (cz in 0..1) {
				world.setChunkForceLoaded(X0 / 16 + cx, Z0 / 16 + cz, true)
			}
		}

		checkStatic()

		// Nova indexes its recipes after the world loads, later than this init
		later(60, "recipes") { checkRecipes() }

		// Nova refuses to place into a region it has not loaded yet
		later(60, "placement") { place(world) }
		later(80, "placed state") { verifyPlaced(world) }
		later(81, "tunes on a placed lantern") { startTune(world) }
		later(125, "tunes on a placed lantern") { verifyTune(world) }
		later(126, "eating") { eat(world, 0) }
		later(128, "eating") { eat(world, 1) }
		later(130, "eating") { eat(world, 2) }
		later(132, "eating") { eat(world, 3) }
		later(136, "eaten") { verifyEaten(world) }
		later(137, "drum") { drumStart(world) }
		later(137 + DrumTile.BEAT.length * DrumTile.STEP + 10L, "drum") { drumStopped(world) }
		later(140, "held lantern") { carry(world, 0) }

		for (t in 1..60) {
			later(140L + t, "held lantern") { carry(world, t) }
		}

		later(205, "held lantern") { verifyPutAway() }
		later(420, "breaking") { breakAll(world) }
		later(425, "cleanup") { finish(world) }
	}

	// ---- checks that need no world ---------------------------------------------

	private fun checkStatic() {
		val tunes = ToyTunes.SONGS
		check("tune count matches the lantern table", tunes.size == TrungThuTable.SONG_COUNT, "${tunes.size}")

		for ((i, tune) in tunes.withIndex()) {
			val pitches = tune.notes.map { it.pitch }
			val inRange = pitches.all { it in 0.5f..2.0f }
			check("tune $i '${tune.name}' has notes in the note block's range", tune.notes.isNotEmpty() && inRange && tune.period > 0, "${tune.notes.size} notes, period ${tune.period}")
		}

		val cycle = generateSequence(ToyTunes.OFF) { ToyTunes.next(it) }.take(tunes.size + 2).toList()
		check("a click cycles every tune and then switches off", cycle == listOf(-1) + tunes.indices.toList() + listOf(-1), cycle.toString())

		for (lantern in TrungThuTable.LANTERNS) {
			check("lantern ${lantern.id} starts on a real tune", lantern.song in tunes.indices)
		}

		// every item registers and makes a stack
		for ((id, item) in TrungThuItems.ITEMS) {
			val stack = item.createItemStack()
			check("item $id makes a stack", stack.novaItem == item)
		}

		for (cake in TrungThuTable.MOONCAKES) {
			val stack = MooncakeItems.MOONCAKES.getValue(cake.id).createItemStack()
			val food = stack.getData(DataComponentTypes.FOOD)
			check("mooncake ${cake.id} is food worth ${cake.nutrition}", food != null && food.nutrition() == cake.nutrition, "${food?.nutrition()}")
		}

		for (mask in TrungThuTable.MASKS) {
			val stack = TrungThuItems.MASKS.getValue(mask.id).createItemStack()
			check("mask ${mask.id} is worn on the head", stack.hasData(DataComponentTypes.EQUIPPABLE))
		}

		// a sword's button swaps it between off and lit, keeping the stack size
		for (sword in TrungThuTable.SWORDS) {
			val (off, on) = TrungThuItems.SWORDS.getValue(sword.id)
			check("sword ${sword.id} switches on", SwordBehavior.toggled(sword.id, false).novaItem == on)
			check("sword ${sword.id} switches off", SwordBehavior.toggled(sword.id, true).novaItem == off)
		}

		// a lantern stack remembers the tune a click set
		val lantern = TrungThuTable.LANTERNS.first()
		val behaviour = LanternBehavior(lantern)
		val plain = TrungThuItems.LANTERNS.getValue(lantern.id).createItemStack()
		check("a fresh lantern plays its own first tune", behaviour.songOf(plain) == lantern.song)
		val set = LanternBehavior.withSong(plain, 5)
		check("a lantern stack keeps the tune a click set", behaviour.songOf(set) == 5)

		// the placement rule: ceiling hangs, wall hangs facing out, floor stands
		val block = TrungThuCatalog.LANTERNS.values.first()
		val world = Bukkit.getWorlds().first()
		val at = world.getBlockAt(X0, Y, Z0 + 20)

		for ((face, mount) in listOf(BlockFace.DOWN to TrungThuCatalog.CEILING, BlockFace.NORTH to TrungThuCatalog.WALL, BlockFace.UP to TrungThuCatalog.FLOOR)) {
			val state = block.chooseBlockState(
				Context.intention(BlockPlace)
					.param(DefaultContextParamTypes.BLOCK_POS, at.pos)
					.param(DefaultContextParamTypes.BLOCK_TYPE_NOVA, block)
					.param(DefaultContextParamTypes.CLICKED_BLOCK_FACE, face)
					.param(DefaultContextParamTypes.SOURCE_DIRECTION, Vector(0.0, 0.0, 1.0))
					.build(),
			)

			check("clicking the $face face mounts it as $mount", state[TrungThuCatalog.MOUNT] == mount, "${state[TrungThuCatalog.MOUNT]}")

			if (face == BlockFace.NORTH) {
				check("a wall lantern looks out of the wall", state[DefaultBlockStateProperties.FACING] == BlockFace.NORTH)
			}
		}
	}

	/**
	 * Every crafted item has a recipe the server knows, counted from both
	 * Nova's explorer index and the server's own recipe list; the fruit is
	 * the shop's alone.
	 */
	private fun checkRecipes() {
		// the fruit is the shop's alone, and a lit sword is only ever made by switching one on
		val shopOnly = setOf("durian", "coconut", "pomelo") + TrungThuTable.SWORDS.map { "${it.id}_on" }
		val made = HashSet<String>()
		val recipes = Bukkit.recipeIterator()

		while (recipes.hasNext()) {
			val item = recipes.next().result.novaItem ?: continue

			if (item.id.namespace() == "lunasmp") {
				made.add(item.id.value())
			}
		}

		val indexed = RecipeRegistry.CREATION_RECIPES.keys

		for (id in TrungThuItems.ITEMS.keys) {
			if (id in shopOnly) {
				continue
			}

			val known = id in made || indexed.any { it.endsWith(":$id") || it == id }
			check("recipe makes $id", known)
		}

		if (made.none { it in TrungThuItems.ITEMS.keys }) {
			log.info("[TrungThu self-test] recipe index sample: ${indexed.take(5)}")
		}
	}

	// ---- placed blocks ---------------------------------------------------------------

	private fun lanternAt(world: World, index: Int, mount: Int): Block = world.getBlockAt(X0 + index * 3, Y, Z0 + mount * 3)

	private fun plateAt(world: World, index: Int): Block = world.getBlockAt(X0 + index * 2, Y, Z0 + 12)

	private fun drumAt(world: World): Block = world.getBlockAt(X0, Y, Z0 + 15)

	private fun dogAt(world: World): Block = world.getBlockAt(X0 + 4, Y, Z0 + 15)

	private fun lightFaceOf(mount: Int): BlockFace = when (mount) {
		TrungThuCatalog.WALL -> BlockFace.NORTH
		TrungThuCatalog.CEILING -> BlockFace.DOWN
		else -> BlockFace.UP
	}

	private fun placeState(target: Block, block: NovaBlock, state: xyz.xenondevs.nova.world.block.state.NovaBlockState): Boolean =
		BlockUtils.placeBlock(
			Context.intention(BlockPlace)
				.param(DefaultContextParamTypes.BLOCK_POS, target.pos)
				.param(DefaultContextParamTypes.BLOCK_STATE_NOVA, state)
				.param(DefaultContextParamTypes.BLOCK_PLACE_EFFECTS, false)
				.build(),
		).also { check("placed ${block.id.value()} at ${target.x},${target.z}", it) }

	private fun place(world: World) {
		for ((i, lantern) in TrungThuTable.LANTERNS.withIndex()) {
			val block = TrungThuCatalog.LANTERNS.getValue(lantern.id)

			for (mount in TrungThuCatalog.FLOOR..TrungThuCatalog.CEILING) {
				val state = block.defaultBlockState
					.with(TrungThuCatalog.MOUNT, mount)
					.with(DefaultBlockStateProperties.FACING, BlockFace.NORTH)

				placeState(lanternAt(world, i, mount), block, state)
			}
		}

		for (i in TrungThuTable.MOONCAKES.indices) {
			val ok = MooncakeBehavior.placeOn(plateAt(world, i), BlockFace.NORTH, i)
			check("mooncake ${TrungThuTable.MOONCAKES[i].id} set down on a plate", ok)
		}

		placeState(drumAt(world), TrungThuCatalog.LION_DRUM, TrungThuCatalog.LION_DRUM.defaultBlockState)
		placeState(dogAt(world), TrungThuCatalog.POMELO_DOG, TrungThuCatalog.POMELO_DOG.defaultBlockState)
	}

	private fun verifyPlaced(world: World) {
		for ((i, lantern) in TrungThuTable.LANTERNS.withIndex()) {
			for (mount in TrungThuCatalog.FLOOR..TrungThuCatalog.CEILING) {
				val at = lanternAt(world, i, mount)
				val state = WorldDataManager.getBlockState(at.pos)
				val tile = WorldDataManager.getTileEntity(at.pos)
				val light = at.getRelative(lightFaceOf(mount))
				val level = (light.blockData as? Levelled)?.level
				val name = "${lantern.id} mount $mount"

				check("$name holds its state", state?.block?.id?.value() == lantern.id && state[TrungThuCatalog.MOUNT] == mount)
				check("$name has its tile", tile is LanternTile)
				check("$name stands in a structure void", at.type == Material.STRUCTURE_VOID, at.type.name)
				check("$name lights the space it opens onto", light.type == Material.LIGHT && level == 14, "${light.type} $level")
				check("$name is drawn", tile?.displayEntities?.isNotEmpty() == true)
			}
		}

		for ((i, cake) in TrungThuTable.MOONCAKES.withIndex()) {
			val at = plateAt(world, i)
			val state = WorldDataManager.getBlockState(at.pos)

			check("plate of ${cake.id} holds its kind", state?.get(MooncakeCatalog.VARIANT) == i && state[MooncakeCatalog.EATEN] == 0)
			check("plate of ${cake.id} has its tile", WorldDataManager.getTileEntity(at.pos) is MooncakePlateTile)
			check("plate of ${cake.id} stands in a flower pot", at.type == Material.FLOWER_POT, at.type.name)

			val drops = BlockUtils.getDrops(breakContext(at, true))
			check("a whole ${cake.id} gives itself back", drops.any { it.novaItem == MooncakeItems.MOONCAKES[cake.id] }, drops.map { it.novaItem?.id }.toString())
		}

		check("drum has its tile", WorldDataManager.getTileEntity(drumAt(world).pos) is DrumTile)
		check("drum stands in a barrier", drumAt(world).type == Material.BARRIER)
		check("pomelo dog has its tile", WorldDataManager.getTileEntity(dogAt(world).pos) is PomeloDogTile)
		check("pomelo dog stands in a heavy core", dogAt(world).type == Material.HEAVY_CORE)
	}

	private fun startTune(world: World) {
		val tile = WorldDataManager.getTileEntity(lanternAt(world, 0, TrungThuCatalog.FLOOR).pos) as LanternTile
		check("a placed lantern starts silent", tile.currentSong == ToyTunes.OFF)

		// the fire-engine siren, which sounds every other tick
		val siren = ToyTunes.SONGS.indexOfFirst { it.name == "Còi Cứu Hỏa" }

		while (tile.currentSong != siren) {
			tile.cycleSong()
		}

		check("a click steps a placed lantern to the siren", tile.currentSong == siren)
	}

	private fun verifyTune(world: World) {
		val tile = WorldDataManager.getTileEntity(lanternAt(world, 0, TrungThuCatalog.FLOOR).pos) as LanternTile
		check("a placed lantern plays its tune", tile.notesPlayed >= 15, "${tile.notesPlayed} sounds in 44 ticks")

		// the rest of its cycle ends in silence
		while (tile.currentSong != ToyTunes.OFF) {
			tile.cycleSong()
		}

		check("the last click switches it off", tile.currentSong == ToyTunes.OFF)
	}

	private fun eat(world: World, bite: Int) {
		val at = plateAt(world, 0)
		val tile = WorldDataManager.getTileEntity(at.pos) as? MooncakePlateTile

		check("quarter ${bite + 1} is there to eat", tile != null)
		tile?.eatQuarter(null)

		if (bite < 3) {
			check("after quarter ${bite + 1} the model shows ${bite + 1} gone", WorldDataManager.getBlockState(at.pos)?.get(MooncakeCatalog.EATEN) == bite + 1)
		}
	}

	private fun verifyEaten(world: World) {
		val at = plateAt(world, 0)
		check("the last quarter takes the plate", WorldDataManager.getBlockState(at.pos) == null && at.type == Material.AIR, at.type.name)
	}

	private fun drumStart(world: World) {
		val tile = WorldDataManager.getTileEntity(drumAt(world).pos) as DrumTile
		tile.toggle()
		check("a click starts the drum", tile.playing)
	}

	private fun drumStopped(world: World) {
		val tile = WorldDataManager.getTileEntity(drumAt(world).pos) as DrumTile
		check("the drum stops at the end of its beat", !tile.playing)
	}

	// ---- a lantern carried through the air -------------------------------------------

	private val carrier: UUID = UUID.nameUUIDFromBytes("trungthu-selftest-carrier".toByteArray())
	private val bystander: UUID = UUID.nameUUIDFromBytes("trungthu-selftest-bystander".toByteArray())
	private var carriedSounds = 0
	private var lastLight: Block? = null

	/** Walks the carrier one block every ten ticks for sixty ticks, playing the siren. */
	private fun carry(world: World, t: Int) {
		val siren = ToyTunes.SONGS.indexOfFirst { it.name == "Còi Cứu Hỏa" }
		val eye = world.getBlockAt(X0 + 40 + t / 10, Y + 1, Z0 + 20)
		val at = Location(world, eye.x + 0.5, eye.y.toDouble(), eye.z + 0.5)

		if (t == 60) {
			check("a carried lantern plays as it walks", carriedSounds >= 25, "$carriedSounds sounds in 60 ticks")
			check("its light never stays behind", lastLight?.type == Material.LIGHT && world.getBlockAt(X0 + 40, Y + 1, Z0 + 20).type == Material.AIR)

			// a second carrier standing where the first one's light is does not take it over
			val taken = lastLight!!
			HeldLanterns.step(bystander, taken, Location(world, taken.x + 0.5, taken.y.toDouble(), taken.z + 0.5), null)
			check("a carrier never takes over another's light", HeldLanterns.lightOf(bystander) == null && HeldLanterns.lightOf(carrier) == taken)

			return
		}

		carriedSounds += HeldLanterns.step(carrier, eye, at, siren)

		val light = HeldLanterns.lightOf(carrier)

		if (t % 10 == 1) {
			val level = (eye.blockData as? Levelled)?.level
			check("the light is at the carrier's head at step ${t / 10}", light == eye && eye.type == Material.LIGHT && level == 13, "${eye.type} $level")
		}

		if (light != null && lastLight != null && light != lastLight) {
			check("the light it left behind is gone (step ${t / 10})", lastLight!!.type == Material.AIR, lastLight!!.type.name)
		}

		lastLight = light
	}

	private fun verifyPutAway() {
		check("a lantern put away takes its light with it", lastLight?.type == Material.AIR && !HeldLanterns.isCarrying(carrier), "${lastLight?.type}")
		check("the bystander was put away too", !HeldLanterns.isCarrying(bystander))
	}

	// ---- teardown --------------------------------------------------------------------

	private fun breakContext(at: Block, drops: Boolean): Context<BlockBreak> =
		Context.intention(BlockBreak)
			.param(DefaultContextParamTypes.BLOCK_POS, at.pos)
			.param(DefaultContextParamTypes.BLOCK_DROPS, drops)
			.param(DefaultContextParamTypes.BLOCK_BREAK_EFFECTS, false)
			.build()

	private fun breakAll(world: World) {
		for ((i, lantern) in TrungThuTable.LANTERNS.withIndex()) {
			for (mount in TrungThuCatalog.FLOOR..TrungThuCatalog.CEILING) {
				val at = lanternAt(world, i, mount)
				val drops: List<ItemStack> = BlockUtils.breakBlock(breakContext(at, true))
				val light = at.getRelative(lightFaceOf(mount))
				val name = "${lantern.id} mount $mount"

				check("$name drops itself", drops.any { it.novaItem == TrungThuItems.LANTERNS[lantern.id] })
				check("$name takes its light away", light.type == Material.AIR, light.type.name)
				check("$name leaves nothing behind", at.type == Material.AIR, at.type.name)
			}
		}

		for (i in 1 until TrungThuTable.MOONCAKES.size) {
			BlockUtils.breakBlock(breakContext(plateAt(world, i), false))
		}

		val drum = BlockUtils.breakBlock(breakContext(drumAt(world), true))
		check("the drum drops itself", drum.any { it.novaItem == TrungThuItems.LION_DRUM })

		val dog = BlockUtils.breakBlock(breakContext(dogAt(world), true))
		check("the pomelo dog drops itself", dog.any { it.novaItem == TrungThuItems.POMELO_DOG })
	}

	private fun finish(world: World) {
		for (cx in 0..3) {
			for (cz in 0..1) {
				world.setChunkForceLoaded(X0 / 16 + cx, Z0 / 16 + cz, false)
			}
		}

		val total = "RESULT: $passed passed, $failed failed"
		lines.add(total)
		log.info("[TrungThu self-test] $total")

		File("plugins/LunaSmp/trungthu-selftest.txt").writeText(lines.joinToString("\n") + "\n")
	}
}
