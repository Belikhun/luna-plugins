package dev.belikhun.luna.smp.furniture

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Levelled
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.joml.Quaternionf
import org.joml.Vector3f
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockBreak
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockInteract
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.util.BlockUtils
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.block.tileentity.TileEntity
import xyz.xenondevs.nova.world.block.tileentity.menu.TileEntityMenuClass
import kotlin.math.cos
import kotlin.math.sin
import xyz.xenondevs.nova.world.fakeentity.FakeEntity
import xyz.xenondevs.nova.world.fakeentity.impl.FakeBlockDisplay
import xyz.xenondevs.nova.world.fakeentity.impl.FakeItemDisplay
import xyz.xenondevs.nova.world.format.WorldDataManager
import dev.belikhun.luna.smp.LunaSmp
import dev.belikhun.luna.smp.gauges.GaugeItems

/** The spec every furniture tile entity reads its own layout out of. */
private fun specOf(blockState: NovaBlockState): FurnitureCatalog.Spec =
	FurnitureCatalog.BY_ID.getValue(blockState.block.id.value())

/**
 * Turns a point authored for a north-facing piece to wherever the piece
 * actually faces, matching the turn Nova applies to the model itself:
 * counterclockwise seen from above, so (dx, dz) from the block centre maps
 * to (dz, -dx) for west and (-dz, dx) for east. Every copy of this function
 * once had east and west swapped; the gauges are what finally showed it.
 */
private fun turn(x: Double, z: Double, facing: BlockFace): Pair<Double, Double> = when (facing) {
	BlockFace.WEST -> z to 1.0 - x
	BlockFace.SOUTH -> 1.0 - x to 1.0 - z
	BlockFace.EAST -> 1.0 - z to x
	else -> x to z
}

private fun yawOf(facing: BlockFace): Float = when (facing) {
	BlockFace.NORTH -> 180f
	BlockFace.EAST -> -90f
	BlockFace.WEST -> 90f
	else -> 0f
}

/**
 * A piece that lights up: lamps switch by hand, flames need flint and steel,
 * and either way an empty hand puts one out.
 *
 * The glow itself is a vanilla light block beside the piece, because the
 * barrier or structure void the model sits in cannot emit any light of its own.
 * The model is told to render at full brightness while it burns, which is the
 * only way a lit lamp reads as lit in a dark room: a resource pack model has no
 * emissive faces.
 */
class FurnitureLamp(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data) {

	private val lamp = specOf(blockState).lamp!!

	private val lit: Boolean
		get() = blockState[FurnitureCatalog.LIT] == true

	override fun handlePlace(ctx: Context<BlockPlace>) {
		if (lit) {
			placeLight()
		}
	}

	override fun handleBreak(ctx: Context<BlockBreak>) {
		clearLight()
	}

	override fun handleEnable() {
		glow()
	}

	override fun handleRightClick(ctx: Context<BlockInteract>): Boolean {
		val player = ctx[DefaultContextParamTypes.SOURCE_ENTITY] as? Player ?: return false

		if (player.isSneaking) {
			return false
		}

		// nova offers the interaction once per hand: acting on both would
		// toggle twice and land back where it started
		val hand = ctx[DefaultContextParamTypes.INTERACTION_HAND] ?: EquipmentSlot.HAND

		if (hand != EquipmentSlot.HAND) {
			return false
		}

		val held = player.inventory.getItem(hand)
		val empty = held.type.isAir

		if (lit) {
			if (!empty) {
				return false
			}

			setLit(false)

			// a flame is snuffed; a lamp is switched, and its off-click is
			// the same lever as its on-click, a step lower
			if (lamp.flint) {
				play(Sound.BLOCK_FIRE_EXTINGUISH)
			} else {
				play(Sound.BLOCK_LEVER_CLICK, 0.9f)
			}

			return true
		}

		if (lamp.flint) {
			if (held.type != Material.FLINT_AND_STEEL) {
				return false
			}

			wear(held, player, hand)
			play(Sound.ITEM_FLINTANDSTEEL_USE)
		} else {
			if (!empty) {
				return false
			}

			play(Sound.BLOCK_LEVER_CLICK, 1.1f)
		}

		setLit(true)
		return true
	}

	private fun setLit(value: Boolean) {
		updateBlockState(blockState.with(FurnitureCatalog.LIT, value))

		if (value) {
			placeLight()
		} else {
			clearLight()
		}

		glow()
	}

	/** Renders the model at full brightness while it burns. */
	private fun glow() {
		val brightness = if (lit) Display.Brightness(15, 15) else null

		// the display entities are created with the block model, which may not
		// have happened yet when a chunk loads this tile entity
		runTask {
			displayEntities?.forEach { display ->
				display.updateEntityData(true) { this.brightness = brightness }
			}
		}
	}

	private fun placeLight() {
		val target = pos.add(0, lamp.offset, 0).block

		if (!target.type.isAir) {
			return
		}

		target.type = Material.LIGHT

		val data = target.blockData as Levelled
		data.level = lamp.level
		target.blockData = data
	}

	private fun clearLight() {
		val target = pos.add(0, lamp.offset, 0).block

		if (target.type == Material.LIGHT) {
			target.type = Material.AIR
		}
	}

	private fun wear(item: ItemStack, player: Player, hand: EquipmentSlot) {
		if (player.gameMode == GameMode.CREATIVE) {
			return
		}

		val meta = item.itemMeta

		if (meta !is Damageable) {
			return
		}

		meta.damage += 1
		item.itemMeta = meta

		if (meta.damage >= item.type.maxDurability) {
			item.amount = 0
		}

		player.inventory.setItem(hand, item)
	}

	private fun play(sound: Sound, pitch: Float = 1f) {
		pos.world.playSound(pos.location.add(0.5, 0.5, 0.5), sound, 1f, pitch)
	}
}

/**
 * A piece you can set things down on: a table, a shelf, or the display case,
 * which is the same thing with one slot and a glass box around it.
 *
 * Right-click with something in hand puts one of it down, right-click with an
 * empty hand picks the last one back up. The items are shown by display
 * entities parked at the slots the generator measured off the model, so a
 * shelf's items sit on its board and a table's on its top.
 */
class FurnitureDisplay(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data) {

	private val spec = specOf(blockState)

	private val slots: List<FurnitureCatalog.Slot> =
		if (spec.showcase) listOf(FurnitureCatalog.Slot(0.5, 0.3125, 0.5)) else spec.surface

	private val inventory = storedInventory(
		"items",
		slots.size,
		maxStackSizes = IntArray(slots.size) { 1 },
		postUpdateHandler = { refresh() },
	)

	private val displays = arrayOfNulls<FakeItemDisplay>(slots.size)

	override fun handleEnable() {
		refresh()
	}

	/**
	 * The angle the item in a slot was set down at, in degrees.
	 *
	 * Kept per slot rather than taken from the piece, because most of the
	 * pieces that hold things do not turn at all: a table has no facing, so
	 * everything ever put on one used to point north. What is stored is the
	 * placer's own heading turned back on itself, so the thing looks at
	 * whoever set it down, at whatever angle they were standing.
	 */
	private fun angleOf(slot: Int): Float =
		retrieveDataOrNull<Int>(ANGLE + slot)?.toFloat() ?: yawOf(blockState[DefaultBlockStateProperties.FACING] ?: BlockFace.NORTH)

	private fun rememberAngle(slot: Int, player: Player) {
		storeData(ANGLE + slot, Math.round(player.location.yaw + 180f))
	}

	override fun handleDisable() {
		super.handleDisable()
		clear()
	}

	override fun handleRightClick(ctx: Context<BlockInteract>): Boolean {
		val player = ctx[DefaultContextParamTypes.SOURCE_ENTITY] as? Player ?: return false

		if (player.isSneaking) {
			return false
		}

		// see FurnitureLamp: the off-hand call would take back what the main
		// hand just put down
		val hand = ctx[DefaultContextParamTypes.INTERACTION_HAND] ?: EquipmentSlot.HAND

		if (hand != EquipmentSlot.HAND) {
			return false
		}

		val held = player.inventory.getItem(hand)

		if (held.type.isAir) {
			return takeOne(player)
		}

		// nothing free and a full hand: leave the click alone, so a block still
		// goes against the piece rather than knocking something off it
		val free = slots.indices.firstOrNull { inventory.getItem(it) == null } ?: return false
		val one = held.clone()
		one.amount = 1

		// the angle goes in before the item does: setting the item is what
		// triggers the refresh that spawns the display
		rememberAngle(free, player)
		inventory.setItem(SELF_UPDATE_REASON, free, one)

		if (player.gameMode != GameMode.CREATIVE) {
			held.amount -= 1
			player.inventory.setItem(hand, held)
		}

		play(Sound.ENTITY_ITEM_FRAME_ADD_ITEM)
		return true
	}

	private fun takeOne(player: Player): Boolean {
		val filled = slots.indices.lastOrNull { inventory.getItem(it) != null } ?: return false
		val item = inventory.getItem(filled)!!
		inventory.setItem(SELF_UPDATE_REASON, filled, null)

		for (leftover in player.inventory.addItem(item).values) {
			player.world.dropItem(player.location, leftover)
		}

		play(Sound.ENTITY_ITEM_FRAME_REMOVE_ITEM)
		return true
	}

	/** Brings the shown items back in line with what the piece holds. */
	private fun refresh() {
		for (index in slots.indices) {
			val item = inventory.getItem(index)
			val display = displays[index]

			if (item == null) {
				display?.remove()
				displays[index] = null
				continue
			}

			if (display == null) {
				displays[index] = spawn(index, item)
			} else {
				display.updateEntityData(true) { itemStack = item }
			}
		}
	}

	private fun clear() {
		for (index in displays.indices) {
			displays[index]?.remove()
			displays[index] = null
		}
	}

	private fun spawn(index: Int, item: ItemStack): FakeItemDisplay {
		val slot = slots[index]
		val facing = blockState[DefaultBlockStateProperties.FACING] ?: BlockFace.NORTH
		val (x, z) = turn(slot.x, slot.z, facing)
		val location = pos.location.add(x, slot.y, z)
		location.yaw = angleOf(index)

		// a block reads as a small block standing on the surface. A flat item
		// lies down on a table (the -90 degree tip), but stands upright on a
		// shelf: the board is too shallow for it to lie on, and upright is how
		// a shelf presents things anyway. The case shows everything upright.
		val standing = item.type.isBlock
		val upright = spec.showcase || spec.surfaceUpright

		val size = when {
			spec.showcase -> 1.2f
			spec.surfaceUpright -> 1.0f
			else -> 1.0f
		}

		return FakeItemDisplay(location) { _, meta ->
			meta.itemStack = item
			meta.itemDisplay = ItemDisplayTransform.GROUND
			meta.scale = Vector3f(size, size, size)

			if (!standing && !upright) {
				meta.leftRotation = Quaternionf().rotateX((-Math.PI / 2).toFloat())
			}

			// an upright flat item's ground transform dips 2px below its
			// anchor; lift it back so it stands on the board, not in it
			if (!standing && spec.surfaceUpright) {
				meta.translation = Vector3f(0f, 0.125f * size, 0f)
			}
		}
	}

	private fun play(sound: Sound) {
		pos.world.playSound(pos.location.add(0.5, 0.5, 0.5), sound, 1f, 1f)
	}

	private companion object {

		/** Key prefix the per-slot angles are stored under. */
		const val ANGLE = "angle"
	}
}

/**
 * A pot: it holds plants, and each one stands in it as its own block.
 *
 * That is the whole idea ported from Gardener's Dream. Vanilla pots one plant
 * per `potted_*` block and stops at about thirty; a pot that simply draws the
 * plant beside itself takes anything that grows, and the pot does not have to
 * know what went in it.
 *
 * Most pots hold one. The long planter and the flower basket hold two and
 * three, and their slots are filled front to back and emptied back to front:
 * the click carries which block was hit and not where on it, so asking the
 * player to aim at a particular slot is not a thing this can honour.
 *
 * Right-click with a plant to put it in, with an empty hand to take one back.
 * Sneak with an empty hand to turn them: a plant that is drawn the same way in
 * every pot on a windowsill looks stamped, and an eighth of a turn is enough
 * to break that up. The turn is the display entity's own yaw rather than a
 * rotation inside its transform, because the transform turns the model about
 * the corner it was authored from and would swing the plant out of the pot.
 */
class FurniturePot(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data) {

	private var spin: Int by storedValue("spin") { 0 }

	private val slots = specOf(blockState).plantSlots

	private val facing = blockState[DefaultBlockStateProperties.FACING] ?: BlockFace.SOUTH

	private val inventory = storedInventory(
		"plant",
		slots.size,
		maxStackSizes = IntArray(slots.size) { 1 },
		postUpdateHandler = { refresh() },
	)

	private val displays = arrayOfNulls<FakeEntity<*>>(slots.size)

	override fun handleEnable() {
		refresh()
	}

	override fun handleDisable() {
		super.handleDisable()
		clear()
	}

	override fun handleRightClick(ctx: Context<BlockInteract>): Boolean {
		val player = ctx[DefaultContextParamTypes.SOURCE_ENTITY] as? Player ?: return false

		// see FurnitureLamp: nova offers the interaction once per hand, and the
		// off-hand call would undo what the main hand just did
		val hand = ctx[DefaultContextParamTypes.INTERACTION_HAND] ?: EquipmentSlot.HAND

		if (hand != EquipmentSlot.HAND) {
			return false
		}

		val held = player.inventory.getItem(hand)
		val filled = lastFilled()

		if (player.isSneaking) {
			// sneaking with something in hand is how a block is placed against
			// the pot, and is left alone
			if (!held.type.isAir || filled < 0) {
				return false
			}

			turn()
			return true
		}

		if (!held.type.isAir) {
			val free = firstEmpty()

			// a full pot and a full hand: leave the click alone, so a block
			// still goes against the pot rather than being planted into nothing
			if (free < 0 || PotPlants.of(held) == null) {
				return false
			}

			plant(free, held, player, hand)
			return true
		}

		if (filled < 0) {
			return false
		}

		return take(filled, player)
	}

	/** The first slot with nothing in it, or -1 when the pot is full. */
	private fun firstEmpty(): Int = slots.indices.firstOrNull { inventory.getItem(it) == null } ?: -1

	/** The last slot with something in it, or -1 when the pot is empty. */
	private fun lastFilled(): Int = slots.indices.lastOrNull { inventory.getItem(it) != null } ?: -1

	private fun plant(slot: Int, held: ItemStack, player: Player, hand: EquipmentSlot) {
		val one = held.clone()
		one.amount = 1
		inventory.setItem(SELF_UPDATE_REASON, slot, one)

		if (player.gameMode != GameMode.CREATIVE) {
			held.amount -= 1
			player.inventory.setItem(hand, held)
		}

		pos.world.spawnParticle(Particle.BLOCK, pos.location.add(0.5, 0.6, 0.5), 3, 0.05, 0.0, 0.05, 0.0, ROOTED_DIRT)
		play(Sound.ITEM_HOE_TILL, 1.5f)
	}

	private fun take(slot: Int, player: Player): Boolean {
		val item = inventory.getItem(slot) ?: return false
		inventory.setItem(SELF_UPDATE_REASON, slot, null)

		for (leftover in player.inventory.addItem(item).values) {
			player.world.dropItem(player.location, leftover)
		}

		play(Sound.ITEM_HOE_TILL, 1.0f)
		return true
	}

	private fun turn() {
		spin = (spin + 1) % TURNS
		val angle = yaw()

		for (display in displays) {
			display?.teleport { yaw = angle }
		}

		play(Sound.BLOCK_AZALEA_LEAVES_PLACE, 1.5f)
	}

	private fun clear() {
		for (index in displays.indices) {
			displays[index]?.remove()
			displays[index] = null
		}
	}

	/** Brings the plants that are shown back in line with what the pot holds. */
	private fun refresh() {
		clear()

		for (index in slots.indices) {
			val item = inventory.getItem(index) ?: continue
			val plant = PotPlants.of(item) ?: continue

			displays[index] = spawn(plant, slots[index])
		}
	}

	private fun spawn(plant: PotPlants.Plant, slot: FurnitureCatalog.Slot): FakeEntity<*> {
		val (x, z) = turn(slot.x, slot.z, facing)
		val location = pos.location.add(x, slot.y, z)
		location.yaw = yaw()

		val scale = plant.scale

		// a plant with no block state of its own is one of ours
		val block = plant.block ?: return ours(plant, location, scale)

		// the display draws its block from the corner it was authored from, so
		// half of it has to be taken back off to stand it in the middle of the
		// pot; a flipped plant hangs the other way and is pushed the other way
		val translation = Vector3f(
			-0.5f * scale.x + plant.offset.x,
			(if (plant.flipped) scale.y else 0f) + plant.offset.y,
			(if (plant.flipped) 0.5f * scale.z else -0.5f * scale.z) + plant.offset.z,
		)

		return FakeBlockDisplay(location) { _, meta ->
			meta.blockState = block
			meta.scale = scale
			meta.translation = translation

			if (plant.flipped) {
				meta.leftRotation = Quaternionf().rotateX(Math.PI.toFloat())
			}
		}
	}

	/**
	 * One of our own flowers standing in the pot, drawn from its item.
	 *
	 * The transform is NONE on purpose: it is the one display context the
	 * flora items do not answer with their flat menu sprite, so what is drawn
	 * is the block model itself, at the size it was authored. An item display
	 * hangs its model about its middle rather than off a corner, so half a
	 * block of lift is the whole difference from the block-state path above.
	 */
	private fun ours(plant: PotPlants.Plant, location: Location, scale: Vector3f): FakeItemDisplay =
		FakeItemDisplay(location) { _, meta ->
			meta.itemStack = plant.item
			meta.itemDisplay = ItemDisplayTransform.NONE
			meta.scale = scale
			meta.translation = Vector3f(0f, 0.5f * scale.y, 0f)
		}

	private fun yaw(): Float = spin * (360f / TURNS)

	private fun play(sound: Sound, pitch: Float) {
		pos.world.playSound(pos.location.add(0.5, 0.5, 0.5), sound, 1f, pitch)
	}

	private companion object {

		/** Turning steps around the full circle. */
		const val TURNS = 16

		val ROOTED_DIRT: BlockData = Material.ROOTED_DIRT.createBlockData()
	}
}

/**
 * A tank with fish in it.
 *
 * The fish are display entities carrying a vanilla fish item, walked around a
 * slow ellipse inside the glass. They are told to interpolate over a whole
 * second, which is the rate this ticks at: the client draws the movement
 * smoothly and the server does twenty times less work than an entity would.
 *
 * They are drawn, not simulated. Nothing here is a real fish: they cannot be
 * caught, they do not breed, and breaking the tank does not drop them.
 */
class FurnitureAquarium(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data) {

	private val fish = ArrayList<FakeItemDisplay>()

	private var phase = 0.0

	override fun handleEnable() {
		stock()
	}

	override fun handleDisable() {
		super.handleDisable()
		empty()
	}

	override fun handleBreak(ctx: Context<BlockBreak>) {
		empty()
	}

	override fun handleTick() {
		phase += STEP

		for (index in fish.indices) {
			fish[index].teleport(placeOf(index))
		}
	}

	/** Where fish [index] is this tick, and which way it is pointing. */
	private fun placeOf(index: Int): Location {
		val swim = phase * SPEEDS[index % SPEEDS.size] + index * (Math.PI * 2 / SCHOOL)
		val radius = RADII[index % RADII.size]

		val location = pos.location.add(
			0.5 + cos(swim) * radius,
			DEPTHS[index % DEPTHS.size],
			0.5 + sin(swim) * radius,
		)

		// side-on to the way it is going, which is how a flat sprite reads as
		// a fish rather than as a card
		location.yaw = Math.toDegrees(-swim).toFloat()

		return location
	}

	private fun stock() {
		empty()

		for (index in 0 until SCHOOL) {
			val stack = ItemStack(SPECIES[index % SPECIES.size])
			val display = FakeItemDisplay(placeOf(index)) { _, meta ->
				meta.itemStack = stack
				meta.itemDisplay = ItemDisplayTransform.GROUND
				meta.scale = Vector3f(SIZE, SIZE, SIZE)

				// the tick rate, in ticks: the client fills in the whole
				// second between one position and the next
				meta.posRotInterpolationDuration = 20
			}

			fish.add(display)
		}
	}

	private fun empty() {
		for (display in fish) {
			display.remove()
		}

		fish.clear()
	}

	private companion object {

		/** How many fish a tank holds. */
		const val SCHOOL = 3

		/** How far the school swims each second, in radians. */
		const val STEP = 0.55

		const val SIZE = 0.7f

		val SPECIES = listOf(Material.COD, Material.TROPICAL_FISH, Material.SALMON)

		/** Each fish keeps its own ring, depth and pace, so they never line up. */
		val RADII = listOf(0.24, 0.17, 0.21)
		val DEPTHS = listOf(0.45, 0.62, 0.33)
		val SPEEDS = listOf(1.0, -0.8, 1.3)
	}
}

/**
 * A piece that opens: blinds roll up off a window, a door swings out of its
 * frame.
 *
 * It is a tile entity for one reason - changing your own block state is
 * something only a tile entity may do - and it does nothing at all until
 * somebody clicks it. The shape follows the state, so an open door is walked
 * through and a closed one is not; that part is the catalog's, not this
 * class's.
 */
class FurnitureOpen(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data) {

	private val spec = specOf(blockState)

	private val sound = spec.openable!!.sound

	/** A piece you can also sit on opens on a sneak-click; see below. */
	private val seats = spec.seatHeight != null

	/** A tap pours while it is open; everything else just holds its shape. */
	private val running = sound == FurnitureCatalog.OpenSound.TAP

	private val open: Boolean
		get() = blockState[FurnitureCatalog.OPEN] == true

	/** Set while this half breaks its partner, so the partner does not break back. */
	private var breakingPartner = false

	/** Whether this piece is one half of a two-block door. */
	private val doorHalf: Boolean
		get() = spec.box == FurnitureCatalog.Box.DOOR

	override fun handlePlace(ctx: Context<BlockPlace>) {
		super.handlePlace(ctx)

		if (!doorHalf || spec.upper) {
			return
		}

		// a door is two blocks and the player only places the lower one; the
		// upper half is built here, the way vanilla doors do it. No headroom
		// means no door: the piece pops straight back off as its item. Both
		// run a tick later, because placing or breaking a block from inside
		// the placement that is still in flight is asking for re-entrancy.
		val topBlock = FurnitureCatalog.BLOCKS[spec.id + TOP] ?: return
		val above = pos.add(0, 1, 0)
		val facing = blockState[DefaultBlockStateProperties.FACING]

		runTask {
			if (!isEnabled) {
				return@runTask
			}

			if (!above.block.type.isAir) {
				// breakBlockNaturally, not breakBlock: breakBlock only
				// RETURNS the drops as a list, it never spawns them - the
				// popped-off door was silently eaten
				BlockUtils.breakBlockNaturally(
					Context.intention(BlockBreak)
						.param(DefaultContextParamTypes.BLOCK_POS, pos)
						// drops default to false without a player source,
						// and popping off must give the door back
						.param(DefaultContextParamTypes.BLOCK_DROPS, true)
						.build(),
				)

				return@runTask
			}

			var topState = topBlock.defaultBlockState

			if (facing != null) {
				topState = topState.with(DefaultBlockStateProperties.FACING, facing)
			}

			// the upper leaf mirrors with its lower half, or a double door's
			// second leaf would flip at the waist
			topState = topState.with(FurnitureCatalog.MIRROR, blockState[FurnitureCatalog.MIRROR] == true)

			BlockUtils.placeBlock(
				Context.intention(BlockPlace)
					.param(DefaultContextParamTypes.BLOCK_POS, above)
					.param(DefaultContextParamTypes.BLOCK_STATE_NOVA, topState)
					.param(DefaultContextParamTypes.BLOCK_PLACE_EFFECTS, false)
					.build(),
			)
		}
	}

	override fun handleBreak(ctx: Context<BlockBreak>) {
		super.handleBreak(ctx)

		if (!doorHalf || breakingPartner) {
			return
		}

		// the two halves live and die together; the flag keeps the partner's
		// own break from bouncing back into this one
		val other = partner() ?: return

		other.breakingPartner = true

		// the item lives on the lower half's drop list, so when the upper is
		// the one hit, the partner break has to carry a drop verdict. The
		// carried BLOCK_DROPS flag proved unreliable in game (a door broken
		// at the top kept vanishing without its item), so the verdict is
		// taken from the breaker directly: any non-creative player drop
		val breaker = ctx[DefaultContextParamTypes.SOURCE_ENTITY] as? Player
		val drops = if (breaker != null) {
			breaker.gameMode != GameMode.CREATIVE
		} else {
			ctx[DefaultContextParamTypes.BLOCK_DROPS] == true
		}

		// a tick later, like the upper half's placement: breaking the partner
		// from inside this break's own still-running pipeline re-enters Nova
		// mid-flight. And breakBlockNaturally, not breakBlock: breakBlock
		// only RETURNS the drops, it never spawns them - which is where the
		// door item was actually vanishing all along
		runTask {
			if (other.isEnabled) {
				BlockUtils.breakBlockNaturally(
					Context.intention(BlockBreak)
						.param(DefaultContextParamTypes.BLOCK_POS, other.pos)
						.param(DefaultContextParamTypes.BLOCK_DROPS, drops)
						.param(DefaultContextParamTypes.BLOCK_BREAK_EFFECTS, false)
						.build(),
				)
			}
		}
	}

	override fun handleRightClick(ctx: Context<BlockInteract>): Boolean {
		val player = ctx[DefaultContextParamTypes.SOURCE_ENTITY] as? Player ?: return false

		val hand = ctx[DefaultContextParamTypes.INTERACTION_HAND] ?: EquipmentSlot.HAND

		if (doorHalf) {
			// the CLIENT already swung its door: the backing is a real (if
			// invisible) warped door, and vanilla clients toggle doors
			// predictively for every non-sneak click and for a sneaking pair
			// of empty hands. The server must toggle for exactly that same
			// set - Nova redirects the vanilla use away from the backing, so
			// a refusal here leaves the client's door open and the server's
			// shut, and the mismatch reads as a broken door. Sneaking with
			// anything in either hand is the one case vanilla suppresses,
			// which keeps it free for placing blocks against the door.
			val mainEmpty = player.inventory.itemInMainHand.type.isAir
			val offEmpty = player.inventory.itemInOffHand.type.isAir

			if (player.isSneaking && !(mainEmpty && offEmpty)) {
				return false
			}

			// nova offers the interaction once per hand; the second offer is
			// consumed, or it would swing the door straight back
			if (hand != EquipmentSlot.HAND) {
				return true
			}
		} else {
			// On anything else, sneaking is how a block is placed against a
			// piece, so a plain click opens it. A toilet is both a seat and a
			// lid, and the plain click is already taken by sitting down, so
			// there the two swap over: sneak to lift the lid.
			if (player.isSneaking != seats) {
				return false
			}

			if (hand != EquipmentSlot.HAND) {
				return true
			}

			if (!player.inventory.getItem(hand).type.isAir) {
				return false
			}
		}

		val opening = !open

		setOpen(opening)
		partner()?.setOpen(opening)

		// by name, because the catalog holds names: see OpenSound
		pos.world.playSound(pos.location.add(0.5, 0.5, 0.5), if (opening) sound.open else sound.close, 1f, 1f)

		return true
	}

	override fun handleTick() {
		// only a tap ticks, and only while it is running: the model turns its
		// handle and nothing else, so the water itself is particles
		if (!running || !open) {
			return
		}

		val spout = pos.location.add(0.5, 0.95, 0.5)

		pos.world.spawnParticle(Particle.FALLING_WATER, spout, 4, 0.12, 0.05, 0.12, 0.0)
		pos.world.spawnParticle(Particle.SPLASH, pos.location.add(0.5, 0.6, 0.5), 2, 0.1, 0.02, 0.1, 0.0)
	}

	private fun setOpen(value: Boolean) {
		if (open != value) {
			updateBlockState(blockState.with(FurnitureCatalog.OPEN, value))
		}
	}

	/**
	 * The other half of a two-block piece, above or below.
	 *
	 * A door is two blocks and swings as one, so a click on either half moves
	 * both. The halves are told apart by the `_top` suffix on the upper one's
	 * id, the same way the beds and the fridges are named.
	 */
	private fun partner(): FurnitureOpen? {
		val stacked = if (spec.upper) spec.id.removeSuffix(TOP) else spec.id + TOP

		for (offset in intArrayOf(1, -1)) {
			val other = WorldDataManager.getTileEntity(pos.add(0, offset, 0)) as? FurnitureOpen ?: continue

			if (other.spec.id == stacked) {
				return other
			}
		}

		// a tap and the tub it fills stand side by side rather than stacked
		val beside = spec.openPartner ?: return null

		for (face in AROUND) {
			val other = WorldDataManager.getTileEntity(pos.add(face.modX, 0, face.modZ)) as? FurnitureOpen ?: continue

			if (other.spec.id == beside) {
				return other
			}
		}

		return null
	}

	private companion object {

		/** How the upper half of a two-block piece is named. */
		const val TOP = "_top"

		/** Where a piece looks for the one beside it that opens with it. */
		val AROUND = listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)
	}
}

/**
 * A drawer: a right-click opens the rows of storage the spec asks for, and
 * whatever is inside drops when it is broken.
 */
class FurnitureStorage(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data) {

	private val rows = specOf(blockState).storageRows

	private val inventory = storedInventory("items", rows * 9)

	@TileEntityMenuClass
	inner class DrawerMenu : GlobalTileEntityMenu() {

		override val gui = Gui.builder()
			.setStructure(*Array(rows) { "i i i i i i i i i" })
			.addIngredient('i', inventory)
			.build()

		// a drawer sounds like the barrel it is: the lid on both ends of the
		// visit, played at the block so the room hears the drawer, not the ear
		init {
			windowBuilder.addOpenHandler { play(Sound.BLOCK_BARREL_OPEN) }
			windowBuilder.addCloseHandler { play(Sound.BLOCK_BARREL_CLOSE) }
		}
	}

	private fun play(sound: Sound) {
		pos.world.playSound(pos.location.add(0.5, 0.5, 0.5), sound, 0.5f, 1f)
	}
}

/**
 * The wall clock: the painted face is static art, and the two hands are
 * display entities the tile turns with the world's own time - the same
 * machinery as a gauge needle, authored pointing at twelve. The hour hand
 * makes two laps a day, the minute hand one lap a game-hour, each nudged
 * once a second with client interpolation carrying the sweep between.
 */
class FurnitureClock(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data) {

	private val facing: BlockFace
		get() = blockState[DefaultBlockStateProperties.FACING] ?: BlockFace.NORTH

	private var hour: FakeItemDisplay? = null
	private var minute: FakeItemDisplay? = null

	override fun handleEnable() {
		super.handleEnable()

		hour = spawn("clock_hand_hour", HOUR_LIFT)
		minute = spawn("clock_hand_minute", MINUTE_LIFT)

		handleTick()
	}

	override fun handleDisable() {
		super.handleDisable()
		clear()
	}

	override fun handleBreak(ctx: Context<BlockBreak>) {
		clear()
	}

	private fun clear() {
		hour?.remove()
		minute?.remove()
		hour = null
		minute = null
	}

	override fun handleTick() {
		// world time 0 is 06:00; the half-day fraction drives the hour hand,
		// the game-hour fraction the minute hand
		val hours = (pos.world.time % 24000L) / 1000.0 + 6.0

		swing(hour, (hours % 12.0) / 12.0 * 360.0)
		swing(minute, (hours % 1.0) * 360.0)
	}

	private fun swing(hand: FakeItemDisplay?, degrees: Double) {
		hand?.updateEntityData(true) {
			leftRotation = Quaternionf().rotationZ(Math.toRadians(SPIN * degrees).toFloat())
			transformationInterpolationDelay = 0
		}
	}

	private fun spawn(item: String, lift: Double): FakeItemDisplay? {
		val stack = GaugeItems.CLOCK_HANDS[item]?.createItemStack() ?: return null
		val (x, z) = turn(0.5, FACE_DEPTH - lift, facing)
		val location = Location(pos.world, pos.x + x, pos.y + 0.5, pos.z + z, yawOf(facing), 0f)

		return FakeItemDisplay(location) { _, meta ->
			meta.itemStack = stack
			meta.itemDisplay = ItemDisplayTransform.NONE
			meta.transformationInterpolationDelay = 0
			meta.transformationInterpolationDuration = 25
		}
	}

	private companion object {

		/** The ported face's glass plane, in blocks from the north edge. */
		const val FACE_DEPTH = 14.0 / 16.0

		/** How far each hand floats in front of the face; minute over hour. */
		const val HOUR_LIFT = 0.02
		const val MINUTE_LIFT = 0.035

		/** The dial convention the gauges verified: clockwise is a -z spin. */
		const val SPIN = -1.0
	}
}
