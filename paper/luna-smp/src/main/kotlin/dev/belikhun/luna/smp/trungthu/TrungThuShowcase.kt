package dev.belikhun.luna.smp.trungthu

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Chest
import org.bukkit.block.data.type.Chest as ChestData
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitFun
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.util.BlockUtils
import xyz.xenondevs.nova.util.runTaskLater
import xyz.xenondevs.nova.util.runTaskTimer
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.item.NovaItem
import xyz.xenondevs.nova.world.pos
import java.io.File
import java.util.logging.Logger

/**
 * A gallery of the whole Mid-Autumn set built round one player, for looking
 * the art over in game on a test server.
 *
 * Armed by a marker file holding a player name - write it to
 * `plugins/LunaSmp/.trungthu-showcase` and restart. Once that player is
 * online it clears a room in front of them and fills it: every mooncake on a
 * plate and two of them at each eaten stage, the drum and the pomelo dog,
 * every lantern stood on the floor, hung on the wall and hung from a beam,
 * armor stands carrying each lantern and each sword and wearing each mask,
 * item frames showing every item, and chests holding one stack of each. The
 * player is made creative and turned to face it all. The marker is consumed.
 *
 * It polls for the player instead of listening for the join, because on a
 * test server without the economy the plugin half disables, and a disabled
 * plugin may not register listeners.
 */
@Init(stage = InitStage.POST_WORLD)
object TrungThuShowcase {

	private val log: Logger = Logger.getLogger("LunaSmp")

	private const val MARKER = "plugins/LunaSmp/.trungthu-showcase"

	/** Half the room's width, in blocks either side of the player. */
	private const val HALF = 24

	private var poll: BukkitTask? = null

	@InitFun
	private fun arm() {
		val marker = File(MARKER)

		if (!marker.exists()) {
			return
		}

		val name = marker.readText().trim()
		marker.delete()

		if (name.isEmpty()) {
			log.warning("[TrungThu showcase] the marker names no player")

			return
		}

		log.info("[TrungThu showcase] waiting for $name")

		poll = runTaskTimer(20, 20) {
			val player = Bukkit.getPlayerExact(name) ?: return@runTaskTimer
			poll?.cancel()

			// long enough for the join to settle and the resource pack to load
			runTaskLater(100) {
				try {
					build(player)
				} catch (error: Throwable) {
					log.warning("[TrungThu showcase] could not build: $error")
				}
			}
		}
	}

	private fun build(player: Player) {
		if (!player.isOnline) {
			return
		}

		val world = player.world
		val here = player.location.block
		val ox = here.x
		val oy = here.y
		// the room starts a few blocks south of the player, who looks into it
		val oz = here.z + 6

		clear(world, ox, oy, oz)

		var placed = 0
		var refused = 0

		fun place(target: Block, state: NovaBlockState) {
			val ok = BlockUtils.placeBlock(
				Context.intention(BlockPlace)
					.param(DefaultContextParamTypes.BLOCK_POS, target.pos)
					.param(DefaultContextParamTypes.BLOCK_STATE_NOVA, state)
					.param(DefaultContextParamTypes.BLOCK_PLACE_EFFECTS, false)
					.build(),
			)

			if (ok) {
				placed++
			} else {
				refused++
			}
		}

		// row 1: every mooncake whole on its plate
		for (i in TrungThuTable.MOONCAKES.indices) {
			place(world.getBlockAt(ox - 20 + i * 2, oy, oz), plate(i, 0))
		}

		// row 2: the first baked cake and the first heart cake at each stage,
		// then the drum and the dog
		val heart = TrungThuTable.MOONCAKES.indexOfFirst { it.id.contains("_tim_") }.coerceAtLeast(0)

		for (eaten in 0 until MooncakeCatalog.QUARTERS) {
			place(world.getBlockAt(ox - 10 + eaten * 2, oy, oz + 2), plate(0, eaten))
			place(world.getBlockAt(ox + 2 + eaten * 2, oy, oz + 2), plate(heart, eaten))
		}

		place(world.getBlockAt(ox - 16, oy, oz + 2), facingNorth(TrungThuCatalog.LION_DRUM.defaultBlockState))
		place(world.getBlockAt(ox + 16, oy, oz + 2), facingNorth(TrungThuCatalog.POMELO_DOG.defaultBlockState))

		// rows 3 to 5: the lanterns on the floor, from the beam, on the wall
		for ((i, lantern) in TrungThuTable.LANTERNS.withIndex()) {
			val block = TrungThuCatalog.LANTERNS.getValue(lantern.id)
			val x = ox - 20 + i * 2

			fun mounted(mount: Int): NovaBlockState =
				facingNorth(block.defaultBlockState.with(TrungThuCatalog.MOUNT, mount))

			place(world.getBlockAt(x, oy, oz + 4), mounted(TrungThuCatalog.FLOOR))
			place(world.getBlockAt(x, oy + 4, oz + 7), mounted(TrungThuCatalog.CEILING))
			place(world.getBlockAt(x, oy + 2, oz + 9), mounted(TrungThuCatalog.WALL))
		}

		standsAndFrames(world, ox, oy, oz)
		chests(world, ox, oy, oz)

		player.gameMode = GameMode.CREATIVE
		player.teleport(Location(world, ox + 0.5, oy.toDouble(), oz - 7.5, 0f, 10f))

		log.info("[TrungThu showcase] built for ${player.name} at $ox,$oy,$oz: $placed blocks placed, $refused refused")
		player.sendMessage("Trung Thu showcase: $placed blocks placed, $refused refused.")
	}

	private fun facingNorth(state: NovaBlockState): NovaBlockState =
		state.with(DefaultBlockStateProperties.FACING, BlockFace.NORTH)

	private fun plate(index: Int, eaten: Int): NovaBlockState =
		facingNorth(
			MooncakeCatalog.MOONCAKE_PLATE.defaultBlockState
				.with(MooncakeCatalog.VARIANT, index)
				.with(MooncakeCatalog.EATEN, eaten),
		)

	/**
	 * Empties the room, lays a smooth stone floor, raises the back wall the
	 * lanterns hang on and the beam the ceiling ones hang from.
	 */
	private fun clear(world: World, ox: Int, oy: Int, oz: Int) {
		for (x in ox - HALF - 8..ox + HALF + 8) {
			for (z in oz - 14..oz + 11) {
				world.getBlockAt(x, oy - 1, z).setType(Material.SMOOTH_STONE, false)

				for (y in oy..oy + 9) {
					world.getBlockAt(x, y, z).setType(Material.AIR, false)
				}
			}
		}

		for (x in ox - HALF - 8..ox + HALF + 8) {
			for (y in oy..oy + 8) {
				world.getBlockAt(x, y, oz + 10).setType(Material.STONE_BRICKS, false)
			}

			world.getBlockAt(x, oy + 5, oz + 7).setType(Material.DARK_OAK_PLANKS, false)
		}
	}

	private fun stack(item: NovaItem): ItemStack = item.createItemStack()

	/**
	 * Armor stands in front of the room, facing the player: one carrying each
	 * lantern, then the masks worn and the swords held, lit and not. Above
	 * the wall lanterns, item frames show every item the way a frame does.
	 */
	private fun standsAndFrames(world: World, ox: Int, oy: Int, oz: Int) {
		fun stand(x: Int, z: Int, dress: (ArmorStand) -> Unit) {
			world.spawn(Location(world, x + 0.5, oy.toDouble(), z + 0.5, 180f, 0f), ArmorStand::class.java) { armorStand ->
				armorStand.setArms(true)
				armorStand.setBasePlate(false)
				armorStand.setGravity(false)
				armorStand.isInvulnerable = true
				dress(armorStand)
			}
		}

		for ((i, lantern) in TrungThuTable.LANTERNS.withIndex()) {
			stand(ox - 20 + i * 2, oz - 2) {
				it.equipment.setItem(EquipmentSlot.HAND, stack(TrungThuItems.LANTERNS.getValue(lantern.id)))
			}
		}

		val masks = TrungThuTable.MASKS.map { TrungThuItems.MASKS.getValue(it.id) }
		val swords = TrungThuItems.SWORDS.values.flatMap { listOf(it.first, it.second) }
		var x = ox - (masks.size + swords.size) + 1

		for (mask in masks) {
			stand(x, oz - 5) {
				it.equipment.setItem(EquipmentSlot.HEAD, stack(mask))
			}

			x += 2
		}

		for (sword in swords) {
			stand(x, oz - 5) {
				it.equipment.setItem(EquipmentSlot.HAND, stack(sword))
			}

			x += 2
		}

		fun frame(fx: Int, fy: Int, item: NovaItem) {
			val at = Location(world, fx.toDouble(), fy.toDouble(), (oz + 9).toDouble())

			world.spawn(at, ItemFrame::class.java) { itemFrame ->
				itemFrame.setFacingDirection(BlockFace.NORTH, true)
				itemFrame.setItem(stack(item), false)
				itemFrame.isFixed = true
			}
		}

		for ((i, cake) in TrungThuTable.MOONCAKES.withIndex()) {
			frame(ox - 20 + i * 2, oy + 4, MooncakeItems.MOONCAKES.getValue(cake.id))
		}

		for ((i, lantern) in TrungThuTable.LANTERNS.withIndex()) {
			frame(ox - 20 + i * 2, oy + 6, TrungThuItems.LANTERNS.getValue(lantern.id))
		}

		val rest = masks + swords + TrungThuItems.FRUITS.values + listOf(TrungThuItems.LION_DRUM, TrungThuItems.POMELO_DOG)

		for ((i, item) in rest.withIndex()) {
			frame(ox - rest.size + 1 + i * 2, oy + 7, item)
		}
	}

	/** Two double chests beside the player's spot, a full stack of every item between them. */
	private fun chests(world: World, ox: Int, oy: Int, oz: Int) {
		val items = TrungThuItems.ITEMS.values.map { item ->
			item.createItemStack().also { it.amount = it.maxStackSize }
		}

		val half = (items.size + 1) / 2

		doubleChest(world, ox - 3, oy, oz - 9, items.take(half))
		doubleChest(world, ox + 2, oy, oz - 9, items.drop(half))
	}

	private fun doubleChest(world: World, x: Int, y: Int, z: Int, items: List<ItemStack>) {
		val left = world.getBlockAt(x, y, z)
		val right = world.getBlockAt(x + 1, y, z)

		// a chest's halves are named from its own front: facing south, the
		// left half is the one to the east
		for ((block, type) in listOf(left to ChestData.Type.RIGHT, right to ChestData.Type.LEFT)) {
			block.setType(Material.CHEST, false)
			val data = block.blockData as ChestData
			data.facing = BlockFace.SOUTH
			data.type = type
			block.setBlockData(data, false)
		}

		val inventory = (left.state as Chest).inventory

		for (item in items) {
			inventory.addItem(item)
		}
	}
}
