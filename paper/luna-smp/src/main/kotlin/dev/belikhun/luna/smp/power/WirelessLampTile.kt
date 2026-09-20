package dev.belikhun.luna.smp.power

import dev.belikhun.luna.smp.LightSource
import dev.belikhun.luna.smp.furniture.FurnitureCatalog
import dev.belikhun.luna.smp.gauges.GaugeCatalog
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Levelled
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.item.Item
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockBreak
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockInteract
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.util.serverTick
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.block.tileentity.TileEntity
import xyz.xenondevs.nova.world.block.tileentity.menu.TileEntityMenuClass
import xyz.xenondevs.nova.world.format.WorldDataManager

/**
 * A wireless fixture: a bulb, a flat panel or a full block that lights up
 * whenever a node within reach is paying for it.
 *
 * The fixture holds nothing and knows nothing about networks. A node that
 * has it inside its region calls [feed] every tick and takes the price out
 * of its own store; the fixture is lit as long as the last payment is
 * recent. Two nodes covering the same fixture do not both pay: [feed]
 * refuses a second payment on a tick already paid for, which is why the
 * bookkeeping is in server ticks rather than in milliseconds - the tick is
 * a clock every node shares exactly, and a wall clock is not.
 *
 * Every fitting - the thin ones the furniture catalog registers and the full
 * block alike - lights the room with a vanilla light block placed in the
 * block it throws into, and the block it hangs on stays whatever it was: one
 * on a wall lights the room, one under a ceiling lights the floor below it,
 * and the full block sunk into a floor lights the room above it. See
 * [LightSource] and [hasOnState].
 */
class WirelessLampTile(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data) {

	private val spec = LampCatalog.LAMP_BY_ID.getValue(blockState.block.id.value())

	/** What lighting this fixture costs a node, in joules a second. */
	val draw: Long
		get() = spec.draw

	/** The point a node tests against its region. */
	val centre: Location
		get() = pos.location.add(0.5, 0.5, 0.5)

	private val facing: BlockFace
		get() = blockState[DefaultBlockStateProperties.FACING] ?: BlockFace.UP

	/**
	 * The block-state property that mirrors [lit], if the block has one.
	 *
	 * The fixtures this catalog owns carry ON and swap between a lit and a
	 * dark model through it; the furniture lamps that could already be
	 * switched carry the furniture's own LIT and swap their models through
	 * that. The furniture lights that never had a state carry NONE: adding a
	 * property to a placed block orphans every instance of it, so their lit
	 * state is [lit] alone, shown as display brightness and the light block.
	 */
	private val stateProperty = when (spec.state) {
		LampCatalog.State.ON -> GaugeCatalog.ON
		LampCatalog.State.LIT -> FurnitureCatalog.LIT
		LampCatalog.State.NONE -> null
	}

	/** Whether the fixture is lit right now; mirrored into [stateProperty] where there is one. */
	private var lit = false

	/** The channel this fixture listens on; a switch on it turns the fixture off. */
	var channel = 0
		private set

	/** The tick a node last paid for this fixture on, and which node it was. */
	private var fedTick = -GRACE_TICKS - 1
	private var feeder: BlockPos? = null

	// ---- being lit -------------------------------------------------------------

	/** Whether this fixture has already been paid for on [tick]. */
	fun isFresh(tick: Int): Boolean = fedTick >= tick

	/**
	 * Records that [node] paid for [tick]. Returns false, and records
	 * nothing, when another node already paid for this tick - the caller
	 * keeps its joules.
	 */
	fun feed(node: BlockPos, tick: Int): Boolean {
		if (isFresh(tick)) {
			return false
		}

		fedTick = tick
		feeder = node

		return true
	}

	// ---- lifecycle -------------------------------------------------------------

	override fun handleEnable() {
		super.handleEnable()

		val property = stateProperty

		LightSource.retireBulbBacking(pos.block)

		lit = property != null && blockState[property] == true
		channel = (retrieveDataOrNull<Int>(CHANNEL) ?: 0).coerceIn(0, LightChannels.COUNT - 1)
		WirelessLamps.register(this)

		// a fixture that arrives dark takes any light block it left behind
		// with it: a lantern lit for free under the old build, or a lamp
		// that was on when the chunk unloaded
		if (!lit) {
			clearLight()
		}

		glow()
	}

	override fun handleDisable() {
		super.handleDisable()

		WirelessLamps.unregister(this)
	}

	override fun handleBreak(ctx: Context<BlockBreak>) {
		super.handleBreak(ctx)

		WirelessLamps.unregister(this)
		clearLight()
	}

	override fun handleTick() {
		LightSource.retireBulbBacking(pos.block)

		val paid = serverTick - fedTick <= GRACE_TICKS

		if (paid != lit) {
			lit = paid

			val property = stateProperty

			if (property != null) {
				updateBlockState(blockState.with(property, paid))
			}

			if (paid) {
				placeLight()
			} else {
				clearLight()
			}

			glow()

			return
		}

		// the light block is replaceable, so anything built into that space
		// took it; once the space is clear again the light comes back
		if (lit) {
			placeLight()
		}
	}

	// ---- the menu ----------------------------------------------------------------

	override fun handleRightClick(ctx: Context<BlockInteract>): Boolean {
		val player = ctx[DefaultContextParamTypes.SOURCE_ENTITY] as? Player ?: return false
		val hand = ctx[DefaultContextParamTypes.INTERACTION_HAND] ?: EquipmentSlot.HAND

		// sneaking is the build gesture everywhere in this addon
		if (hand != EquipmentSlot.HAND || player.isSneaking) {
			return false
		}

		menuContainer.openWindow(player)

		return true
	}

	/**
	 * What the fixture is doing, who pays for it, and its channel: Nova's
	 * own cycling colour button, so a lamp is put on the red channel here
	 * and switched with the red switch by the door.
	 */
	@TileEntityMenuClass
	inner class LampMenu : GlobalTileEntityMenu() {

		private val status: Item = Item.builder()
			.updatePeriodically(20)
			.setItemProvider {
				val node = feeder?.let { WorldDataManager.getTileEntity(it) as? WirelessNodeTile }
				val builder = ItemBuilder(if (lit) Material.LANTERN else Material.SOUL_LANTERN)
					.setName(if (lit) "<green>Đang sáng</green>" else "<dark_red>MẤT ĐIỆN</dark_red>")
					.addLoreLines("<gray>Tiêu thụ: <white>${spec.draw}</white> J/s")

				if (lit && node != null) {
					builder.addLoreLines(
						"<gray>Cấp bởi trạm tại <white>${node.pos.x} ${node.pos.y} ${node.pos.z}</white>",
						"<gray>Điện trong trạm: <white>${node.storedEnergy()}</white> / ${node.storedCapacity()} J",
					)
				} else {
					builder.addLoreLines("<gray>Không có trạm nào trong tầm cấp đủ điện cho kênh này.</gray>")
				}

				builder
			}
			.build()

		override val gui: Gui = Gui.builder()
			.setStructure(
				"1 - - - - - - - 2",
				"| s # # c # # # |",
				"3 - - - - - - - 4",
			)
			.addIngredient('s', status)
			.addIngredient('c', LightChannels.button({ channel }, { chosen ->
				channel = chosen
				storeData(CHANNEL, chosen)
			}))
			.build()
	}

	// ---- the light -------------------------------------------------------------

	/**
	 * The block the fixture throws its light into: the one it faces for a
	 * wall piece, the one above for a standing lamp or a lantern, the one
	 * below for a hanging lantern or a ceiling lamp.
	 */
	private fun target() = when (spec.target) {
		LampCatalog.Target.UP -> pos.add(0, 1, 0).block
		LampCatalog.Target.DOWN -> pos.add(0, -1, 0).block
		else -> pos.advance(facing, 1).block
	}

	private fun placeLight() {
		LightSource.place(target(), spec.level)
	}

	private fun clearLight() {
		LightSource.clear(target())
	}

	/** Draws the model at full brightness while lit, like every lamp here. */
	private fun glow() {
		val brightness = if (lit) Display.Brightness(15, 15) else null

		// the display entities are created with the block model, which may
		// not have happened yet when a chunk loads this tile entity
		runTask {
			if (!isEnabled) {
				return@runTask
			}

			displayEntities?.forEach { display ->
				display.updateEntityData(true) { this.brightness = brightness }
			}
		}
	}

	private companion object {

		const val CHANNEL = "channel"

		/**
		 * How long a fixture stays lit after its last payment, in ticks.
		 *
		 * A node pays every tick, but a fixture only looks at its own books
		 * once a second, so the grace has to clear that gap comfortably or a
		 * lamp would blink between its own ticks. Two seconds is a missed
		 * beat's worth of slack without a dead node keeping a room lit, and
		 * it is what [fedTick] starts one past, so a fixture loading into the
		 * world is dark until something actually pays for it.
		 */
		const val GRACE_TICKS = 40
	}
}
