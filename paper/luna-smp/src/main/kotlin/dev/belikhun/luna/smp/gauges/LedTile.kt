package dev.belikhun.luna.smp.gauges

import dev.belikhun.luna.smp.LunaSmp
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.joml.Vector3f
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockBreak
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockInteract
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.block.tileentity.TileEntity
import xyz.xenondevs.nova.world.block.tileentity.network.NetworkManager
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkNode
import xyz.xenondevs.nova.world.block.tileentity.network.type.DefaultNetworkTypes
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkType
import xyz.xenondevs.nova.world.block.tileentity.network.type.energy.EnergyBridge
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.FluidBridge
import xyz.xenondevs.nova.world.block.tileentity.network.type.item.ItemBridge
import xyz.xenondevs.nova.world.fakeentity.impl.FakeItemDisplay

/**
 * The activity indicator: the port lights of a network.
 *
 * A small plate screwed flat onto a cable, a machine or a wall, wearing one
 * LED per network type - energy, items, fluid, in that order. Each follows
 * the etiquette of a LAN port: dark when no network of its type reaches the
 * host block, steady when one is linked and idle, blinking while anything
 * moves. The reading is sampled once a second, exactly like a gauge's; the
 * blink itself is local theatre between samples.
 */
class LedTile(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data), EnergyBridge, ItemBridge, FluidBridge {

	private enum class Mode { DARK, STEADY, BLINK }

	/**
	 * The full-block form: the same three lights across a whole face, and it
	 * watches every block touching it instead of just the one behind it.
	 */
	private val cube = blockState.block.id.value() == "network_led_block"

	private val facing: BlockFace
		get() = blockState[DefaultBlockStateProperties.FACING] ?: BlockFace.NORTH

	// ---- the wire side -------------------------------------------------------
	//
	// Both forms are transparent pieces of the cable line, exactly like the
	// gauges: real bridges with the neighbouring line's own type id, so
	// cables visibly connect and the run passes through instead of being
	// cut. Neither form grows couplings of its own: the plate hugs a wall
	// (a centre-anchored coupling would float in front of its face - it did,
	// and covered the panel), and the cube fills its block; the linked
	// cable's own connector arm shows the connection.

	private var valid = false

	override val isValid: Boolean
		get() = valid

	override var typeId: Key = Wiring.ownId(blockState.block.id.value())
		private set

	override val linkedNodes: Set<NetworkNode>
		get() = emptySet()

	override val energyTransferRate: Long
		get() = 1_000_000_000L

	override val itemTransferRate: Int
		get() = 1_024

	override val fluidTransferRate: Long
		get() = 100_000_000L

	/** Every face but the one wearing the lights. */
	private fun wireFaces(): Set<BlockFace> = FACES.toSet() - facing

	/** The periodic are-we-actually-linked check, the gauges' own. */
	private var nextAudit = 0L
	private var auditStrikes = 0

	private val chips = arrayOfNulls<FakeItemDisplay>(3)
	private val modes = Array(3) { Mode.DARK }
	private val shownBright = BooleanArray(3) { false }

	/** The previous stocks, so items and fluid can say "something moved". */
	private var lastItems = Long.MIN_VALUE
	private var lastFluid = Long.MIN_VALUE

	private var tickCount = 0

	/** Whether the last second's draw was met; a dead panel shows nothing. */
	private var powered = true

	override fun handleEnable() {
		super.handleEnable()

		typeId = Wiring.lineIdAt(pos, wireFaces()) ?: typeId

		// remove first, always: an add for a known node is a silent no-op,
		// so a stale registration can only be repaired this way
		NetworkManager.queueRemoveBridge(this)
		NetworkManager.queueAddBridge(this, WIRE_TYPES, wireFaces())

		valid = true

		for (index in 0..2) {
			spawn(index)
		}
	}

	override fun handleDisable() {
		super.handleDisable()
		valid = false
		clear()
	}

	override fun handleBreak(ctx: Context<BlockBreak>) {
		NetworkManager.queueRemoveBridge(this)
		valid = false
		clear()
	}

	private fun clear() {
		for (index in 0..2) {
			chips[index]?.remove()
			chips[index] = null
		}
	}

	override fun handleTick() {
		tickCount++

		// once a second, re-check which line the indicator sits in and
		// audit the registration, exactly as the gauges do
		if (tickCount % 20 == 0) {
			val sampled = Wiring.lineIdAt(pos, wireFaces())

			if (sampled != null && sampled != typeId) {
				NetworkManager.queueRemoveBridge(this)
				typeId = sampled
				NetworkManager.queueAddBridge(this, WIRE_TYPES, wireFaces())
			}

			auditWire()
		}

		// the indicator runs on power too: a dead panel is three dark
		// wells, not a report - port lights on a powerless switch mean lies.
		// A plate on a machine taps the machine's own charge first, then any
		// powered network reachable from here or from the machine; only a
		// wall or wire mount depends on the wire alone.
		if (tickCount % 20 == 0) {
			val need = if (cube) CUBE_DRAIN else PLATE_DRAIN
			val hostPos = if (cube) null else pos.advance(facing.oppositeFace, 1)

			var paid = 0L

			if (hostPos != null) {
				paid = GaugeSources.drainEnergyAt(hostPos, need)
			}

			if (paid < need) {
				paid += GaugeSources.drainEnergy(pos, need - paid)
			}

			if (paid < need && hostPos != null) {
				paid += GaugeSources.drainEnergy(hostPos, need - paid)
			}

			powered = paid >= need

			if (powered) {
				sample()
			} else {
				for (index in 0..2) {
					modes[index] = Mode.DARK
				}
			}
		}

		// the blink: a 4-tick flip only for the LEDs that have traffic
		if (tickCount % 4 == 0) {
			for (index in 0..2) {
				val want = when (modes[index]) {
					Mode.DARK -> false
					Mode.STEADY -> true
					Mode.BLINK -> (tickCount / 4) % 2 == 0
				}

				if (want != shownBright[index]) {
					shownBright[index] = want
					light(index, want)
				}
			}
		}
	}

	/**
	 * Every few seconds, repairs a bridge registration the network state no
	 * longer reflects; three fruitless repairs stop the attempts.
	 */
	private fun auditWire() {
		val now = System.currentTimeMillis()

		if (now < nextAudit || auditStrikes >= MAX_AUDIT_STRIKES) {
			return
		}

		nextAudit = now + AUDIT_MS

		NetworkManager.queueRead(pos.chunkPos) { state ->
			val missing = Wiring.unlinkedFaces(state, this, pos, wireFaces())

			if (missing.isEmpty()) {
				auditStrikes = 0
				return@queueRead
			}

			auditStrikes++
			LunaSmp.logger.warn(
				"indicator block at {} has unlinked line faces {} (typeId {}, strike {}); re-adding its bridge",
				pos, missing, typeId, auditStrikes,
			)

			NetworkManager.queueRemoveBridge(this)
			NetworkManager.queueAddBridge(this, WIRE_TYPES, wireFaces())
		}
	}

	/**
	 * What each type is doing, once a second. The plate is strict about its
	 * mount, by request: screwed onto a readable block (a machine, chest,
	 * tank), it monitors THAT BLOCK ONLY - a chest gives no energy light,
	 * whatever wires run nearby. Screwed onto a wall or a wire, it monitors
	 * the whole network it is spliced into. The cube form merges the
	 * networks through it, probing its neighbours when no wire is in reach.
	 */
	private fun sample() {
		val host = if (cube) null else GaugeSources.host(pos.advance(facing.oppositeFace, 1))

		if (host != null) {
			val hostEnergy = host.energy
			modes[0] = when {
				hostEnergy == null -> Mode.DARK
				hostEnergy.energyPlus != 0L || hostEnergy.energyMinus != 0L -> Mode.BLINK
				else -> Mode.STEADY
			}

			val items = host.itemCount
			modes[1] = when {
				items == null -> Mode.DARK
				lastItems != Long.MIN_VALUE && items != lastItems -> Mode.BLINK
				else -> Mode.STEADY
			}
			lastItems = items ?: Long.MIN_VALUE

			val fluid = host.fluid?.amount
			modes[2] = when {
				fluid == null -> Mode.DARK
				lastFluid != Long.MIN_VALUE && fluid != lastFluid -> Mode.BLINK
				else -> Mode.STEADY
			}
			lastFluid = fluid ?: Long.MIN_VALUE

			return
		}

		val anchors = if (cube) {
			// the block is itself a node in the line, so its own position
			// reads the networks directly; the neighbours stay as fallback
			listOf(pos) + FACES.map { face -> pos.advance(face, 1) }
		} else {
			listOf(pos)
		}

		val probe = if (cube) GaugeSources.probe(FACES.map { face -> pos.advance(face, 1) }) else null

		val energy = GaugeSources.energy(anchors)
		modes[0] = when {
			energy != null ->
				if (energy.flow != 0L || energy.load != 0L || energy.gen != 0L) Mode.BLINK else Mode.STEADY

			probe?.hasEnergy == true ->
				if (probe.energyFlow != 0L) Mode.BLINK else Mode.STEADY

			else -> Mode.DARK
		}

		val items = GaugeSources.items(anchors) ?: probe?.itemCount
		modes[1] = when {
			items == null -> Mode.DARK
			lastItems != Long.MIN_VALUE && items != lastItems -> Mode.BLINK
			else -> Mode.STEADY
		}
		lastItems = items ?: Long.MIN_VALUE

		val fluid = GaugeSources.fluid(anchors)?.amount ?: probe?.fluid?.amount
		modes[2] = when {
			fluid == null -> Mode.DARK
			lastFluid != Long.MIN_VALUE && fluid != lastFluid -> Mode.BLINK
			else -> Mode.STEADY
		}
		lastFluid = fluid ?: Long.MIN_VALUE
	}

	private fun light(index: Int, bright: Boolean) {
		chips[index]?.updateEntityData(true) {
			brightness = Display.Brightness(if (bright) 15 else 0, if (bright) 15 else 0)
		}
	}

	/**
	 * Parks one chip in its well. The face is the plate's own 32-pixel
	 * texture over an eight-pixel span; the wells sit at a third, a half and
	 * two thirds of the way down its left side.
	 */
	private fun spawn(index: Int) {
		val item = GaugeItems.LED_CHIPS[CHIP_IDS[index]]?.createItemStack() ?: return
		val scale = if (cube) CHIP_SCALE * 2f else CHIP_SCALE

		chips[index] = FakeItemDisplay(chipPoint(10.0, 7.0 + index * 9.0)) { _, meta ->
			meta.itemStack = item
			meta.itemDisplay = ItemDisplayTransform.NONE
			meta.scale = Vector3f(scale, scale, scale)
			meta.brightness = Display.Brightness(0, 0)
		}
	}

	/** A point on the face, in the 32-pixel coordinates of its art. */
	private fun chipPoint(px: Double, py: Double): Location {
		val span = if (cube) 1.0 else SPAN
		val depth = if (cube) 0.0 else DEPTH
		val dx = -(px - 16.0) / 32.0 * span
		val dy = (16.0 - py) / 32.0 * span
		val (x, z) = turn(0.5 + dx, depth - LIFT, facing)

		return Location(pos.world, pos.x + x, pos.y + 0.5 + dy, pos.z + z, yawOf(facing), 0f)
	}

	override fun handleRightClick(ctx: Context<BlockInteract>): Boolean {
		val player = ctx[DefaultContextParamTypes.SOURCE_ENTITY] as? Player ?: return false
		val hand = ctx[DefaultContextParamTypes.INTERACTION_HAND] ?: EquipmentSlot.HAND

		if (hand != EquipmentSlot.HAND || player.isSneaking) {
			return false
		}

		if (!powered) {
			val need = if (cube) CUBE_DRAIN else PLATE_DRAIN

			player.sendMessage(MM.deserialize("<dark_red>MẤT ĐIỆN</dark_red> <gray>- đèn báo cần $need J/s từ máy được gắn hoặc dây điện.</gray>"))

			return true
		}

		val lines = listOf("điện", "vật phẩm", "chất lỏng").mapIndexed { index, name ->
			val state = when (modes[index]) {
				Mode.DARK -> "<dark_gray>không có mạng</dark_gray>"
				Mode.STEADY -> "<green>nối, đứng yên</green>"
				Mode.BLINK -> "<yellow>đang truyền</yellow>"
			}

			"<gray>● $name:</gray> $state"
		}

		player.sendMessage(MM.deserialize(lines.joinToString(" <dark_gray>·</dark_gray> ")))

		return true
	}

	private companion object {

		val CHIP_IDS = listOf("gauge_led_amber", "gauge_led_green", "gauge_led_blue")

		val FACES = listOf(
			BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH,
			BlockFace.WEST, BlockFace.UP, BlockFace.DOWN,
		)

		/** The plate's face geometry, mirrored from the generator. */
		const val SPAN = 8.0 / 16.0
		const val DEPTH = 14.5 / 16.0
		const val LIFT = 0.02

		/**
		 * A chip is authored two block-pixels wide; the well is eight pixels
		 * of a 32-pixel face that spans half a block, so the lens wants to be
		 * six of those face pixels: 6/64 blocks over the authored 2/16.
		 */
		const val CHIP_SCALE = 0.75f

		/** What watching the wire costs it, per second. */
		const val PLATE_DRAIN = 5L
		const val CUBE_DRAIN = 10L

		/** How often and how stubbornly the wire audit repairs a dead link. */
		const val AUDIT_MS = 5_000L
		const val MAX_AUDIT_STRIKES = 3

		/** Everything a cable carries: the block form passes all of it. */
		val WIRE_TYPES: Set<NetworkType<*>> = setOf(
			DefaultNetworkTypes.ENERGY,
			DefaultNetworkTypes.ITEM,
			DefaultNetworkTypes.FLUID,
		)

		val MM = MiniMessage.miniMessage()

		/** The same north-authored frame every instrument uses. */
		fun turn(x: Double, z: Double, facing: BlockFace): Pair<Double, Double> = when (facing) {
			BlockFace.WEST -> z to 1.0 - x
			BlockFace.SOUTH -> 1.0 - x to 1.0 - z
			BlockFace.EAST -> 1.0 - z to x
			else -> x to z
		}

		fun yawOf(facing: BlockFace): Float = when (facing) {
			BlockFace.NORTH -> 180f
			BlockFace.EAST -> -90f
			BlockFace.WEST -> 90f
			else -> 0f
		}
	}
}
