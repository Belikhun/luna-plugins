package dev.belikhun.luna.smp.gauges

import dev.belikhun.luna.smp.LunaSmp
import net.kyori.adventure.key.Key
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockBreak
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockInteract
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.block.tileentity.TileEntity
import xyz.xenondevs.nova.world.block.tileentity.network.NetworkManager
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkBridge
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkNode
import xyz.xenondevs.nova.world.block.tileentity.network.type.DefaultNetworkTypes
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkType
import xyz.xenondevs.nova.world.block.tileentity.network.type.energy.EnergyBridge
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.FluidBridge
import xyz.xenondevs.nova.world.block.tileentity.network.type.item.ItemBridge
import xyz.xenondevs.nova.world.format.NetworkState
import xyz.xenondevs.nova.world.format.WorldDataManager
import xyz.xenondevs.nova.world.model.FixedMultiModel

/**
 * The switch family: every device that is a piece of cable with a will.
 *
 * While a device conducts, it is a network bridge exactly the way a Logistics
 * cable is one - what it carries passes along its own axis, at rates far past
 * anything a survival network moves. Opening it removes the toggled types
 * from its bridge, which is precisely what breaking the cable would do for
 * those networks: Nova rebuilds them and the two sides come apart. Closing
 * re-adds them and the sides knit together. Nothing here reimplements any of
 * that machinery; the devices only drive the same two queue calls that
 * placing and breaking a cable drive.
 *
 * One tile serves the whole family, told apart by its catalog spec:
 * - the plain isolator and the knife switch cut everything and hold;
 * - the pulse button closes everything for a few seconds and lets go;
 * - the contactor follows redstone power at its own block instead of clicks;
 * - the fluid valve and the energy breaker cut only their own network type
 *   and keep bridging the rest, so a valve in a combined line stops the
 *   liquid without blacking out the machines behind it.
 *
 * Every device conducts only front to back. A switch is a device in a line,
 * and a junction that can also be severed is what a switch is FOR - so the
 * sides never connect, and a run of cable through it stays one clean line.
 */
class NetworkSwitchTile(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data), EnergyBridge, ItemBridge, FluidBridge {

	private val spec = GaugeCatalog.SWITCH_BY_ID.getValue(blockState.block.id.value())

	/** What the handle cuts, and what keeps flowing regardless. */
	private val toggled: Set<NetworkType<*>> = when (spec.toggles) {
		GaugeCatalog.Toggles.ALL -> ALL_TYPES
		GaugeCatalog.Toggles.FLUID -> setOf(DefaultNetworkTypes.FLUID)
		GaugeCatalog.Toggles.ENERGY -> setOf(DefaultNetworkTypes.ENERGY)
	}

	private val passive: Set<NetworkType<*>> = ALL_TYPES - toggled

	private var valid = false

	/** Ticks left before a pulse device lets go; meaningless on the others. */
	private var remaining = 0

	/** Slows the once-a-second work down on the devices that tick every tick. */
	private var tickCount = 0

	/** The periodic are-we-actually-linked check; see [auditWire]. */
	private var nextAudit = 0L
	private var auditStrikes = 0

	/** The couplings shown toward the line; see [refreshDeadJoints]. */
	private val joints = FixedMultiModel()

	override val isValid: Boolean
		get() = valid

	/**
	 * Starts as our own id and immediately impersonates the cable line it is
	 * spliced into - see [Wiring] for why a bridge with an honest id of its
	 * own would cut the line instead of joining it.
	 */
	override var typeId: Key = Wiring.ownId(blockState.block.id.value())
		private set

	override val linkedNodes: Set<NetworkNode>
		get() = emptySet()

	// generous enough that the device never becomes the bottleneck of the
	// line it protects; the cables around it throttle first
	override val energyTransferRate: Long
		get() = 1_000_000_000L

	override val itemTransferRate: Int
		get() = 1_024

	override val fluidTransferRate: Long
		get() = 100_000_000L

	private val facing: BlockFace
		get() = blockState[DefaultBlockStateProperties.FACING] ?: BlockFace.NORTH

	private val on: Boolean
		get() = blockState[GaugeCatalog.ON] != false

	/** The two faces the line runs through; the other four never connect. */
	private fun lineFaces(): Set<BlockFace> = setOf(facing, facing.oppositeFace)

	/** The types the bridge currently carries: all of them, or the keepers. */
	private fun carriedTypes(): Set<NetworkType<*>> = if (on) ALL_TYPES else passive

	/**
	 * Re-registers the bridge for the current state. Remove first, always: an
	 * add for a node already registered in the network state is silently
	 * ignored, so this is the only way to converge a bridge an older build
	 * registered under a stale type id, and the only way to change the type
	 * set of a live one.
	 */
	private fun applyBridge() {
		NetworkManager.queueRemoveBridge(this)

		val types = carriedTypes()

		if (types.isNotEmpty()) {
			NetworkManager.queueAddBridge(this, types, lineFaces())
		}
	}

	override fun handleEnable() {
		super.handleEnable()

		// a pulse held across a restart would be a pulse that never lets go
		if (spec.momentaryTicks > 0 && on) {
			updateBlockState(blockState.with(GaugeCatalog.ON, false))
		}

		typeId = Wiring.lineIdAt(pos, lineFaces()) ?: typeId
		applyBridge()
		valid = true
	}

	override suspend fun handleNetworkLoaded(state: NetworkState) {
		refreshLinkedJoints(state)
	}

	override suspend fun handleNetworkUpdate(state: NetworkState) {
		refreshLinkedJoints(state)
	}

	/** While bridging: a short coupling per face the state says is linked. */
	private suspend fun refreshLinkedJoints(state: NetworkState) {
		if (carriedTypes().isEmpty()) {
			return
		}

		val faces = state.getConnectedNodes(this).columnKeySet()
		val models = Wiring.jointModels(pos, faces, GaugeItems.JOINTS)

		runTask {
			if (isEnabled) {
				joints.replaceModels(models)
			}
		}
	}

	/**
	 * While fully open the device is not in the network graph at all and the
	 * cables have retracted their arms, so the housing carries the whole span
	 * itself: a dark dead-line sleeve reaching to each neighbouring cable's
	 * hub. The run keeps reading as one line, and the missing live arm is the
	 * tell that nothing flows through it. A per-type device never needs this:
	 * its passive bridge keeps the cables' own arms alive.
	 */
	private fun refreshDeadJoints() {
		val faces = lineFaces().filter { face ->
			WorldDataManager.getTileEntity(pos.advance(face, 1)) is NetworkBridge
		}

		joints.replaceModels(Wiring.jointModels(pos, faces, GaugeItems.JOINTS_LONG))
	}

	override fun handleTick() {
		tickCount++

		// the pulse lets go on its own; nothing else counts down
		if (spec.momentaryTicks > 0 && on && --remaining <= 0) {
			setConducting(false)
		}

		// the contactor answers its coil, not hands
		if (spec.redstone) {
			val powered = pos.block.isBlockIndirectlyPowered || pos.block.blockPower > 0

			if (powered != on) {
				setConducting(powered)
			}
		}

		// the once-a-second housekeeping, however fast this device ticks
		if (spec.momentaryTicks > 0 || spec.redstone) {
			if (tickCount % 20 != 0) {
				return
			}
		}

		if (carriedTypes().isEmpty()) {
			refreshDeadJoints()
			return
		}

		// a cable laid after the device, or the line re-tiered around it:
		// adopt the new id and rejoin, which is a remove and an add exactly
		// like flicking the handle twice
		val sampled = Wiring.lineIdAt(pos, lineFaces())

		if (sampled != null && sampled != typeId) {
			typeId = sampled
			applyBridge()
		}

		auditWire()
	}

	/**
	 * The same are-we-actually-linked repair the gauges run: a same-id bridge
	 * on the line with no recorded connection means this device's
	 * registration went stale, and remove-and-re-add is the repair. Capped so
	 * a genuinely unlinkable line cannot churn the graph forever.
	 */
	private fun auditWire() {
		val now = System.currentTimeMillis()

		if (now < nextAudit || auditStrikes >= MAX_AUDIT_STRIKES) {
			return
		}

		nextAudit = now + AUDIT_MS

		NetworkManager.queueRead(pos.chunkPos) { state ->
			if (carriedTypes().isEmpty()) {
				return@queueRead
			}

			val missing = Wiring.unlinkedFaces(state, this, pos, lineFaces())

			if (missing.isEmpty()) {
				auditStrikes = 0
				return@queueRead
			}

			auditStrikes++
			LunaSmp.logger.warn(
				"switch {} at {} has unlinked line faces {} (typeId {}, strike {}); re-adding its bridge",
				spec.id, pos, missing, typeId, auditStrikes,
			)

			applyBridge()
		}
	}

	override fun handleDisable() {
		super.handleDisable()
		valid = false
		joints.clear()
	}

	override fun handleBreak(ctx: Context<BlockBreak>) {
		if (carriedTypes().isNotEmpty()) {
			NetworkManager.queueRemoveBridge(this)
		}

		valid = false
		joints.clear()
	}

	/** Throws the device into a state: block state, bridge, joints, noise. */
	private fun setConducting(value: Boolean) {
		updateBlockState(blockState.with(GaugeCatalog.ON, value))

		if (value) {
			typeId = Wiring.lineIdAt(pos, lineFaces()) ?: typeId
		}

		applyBridge()

		if (value) {
			joints.clear()
		} else if (passive.isEmpty()) {
			refreshDeadJoints()
		}

		sound(value)
	}

	private fun sound(value: Boolean) {
		when (spec.sound) {
			"knife" -> {
				pos.playSound("block.lever.click", 0.8f, if (value) 0.6f else 0.5f)
				pos.playSound("block.chain.place", 0.5f, if (value) 1.2f else 0.9f)
			}

			"button" -> {
				pos.playSound(if (value) "block.stone_button.click_on" else "block.stone_button.click_off", 0.9f, 0.7f)

				if (value) {
					pos.playSound("block.iron_trapdoor.close", 0.3f, 1.8f)
				}
			}

			"relay" -> {
				pos.playSound("block.iron_trapdoor.close", 0.7f, if (value) 1.8f else 1.5f)
			}

			"wheel" -> {
				pos.playSound("block.chain.step", 0.8f, if (value) 0.8f else 0.7f)
				pos.playSound("block.lantern.place", 0.4f, if (value) 1.4f else 1.1f)
			}

			"breaker" -> {
				pos.playSound("block.lever.click", 0.9f, if (value) 0.5f else 1.4f)
			}

			else -> {
				pos.playSound("block.lever.click", 0.8f, if (value) 1.1f else 0.7f)
				pos.playSound(if (value) "block.iron_trapdoor.open" else "block.iron_trapdoor.close", 0.4f, if (value) 1.6f else 1.4f)
			}
		}
	}

	override fun handleRightClick(ctx: Context<BlockInteract>): Boolean {
		val player = ctx[DefaultContextParamTypes.SOURCE_ENTITY] as? Player ?: return false
		val hand = ctx[DefaultContextParamTypes.INTERACTION_HAND] ?: EquipmentSlot.HAND

		// a switch throws for any hand and any held thing, like a lever; only
		// the duplicate off-hand offer is turned away
		if (hand != EquipmentSlot.HAND) {
			return false
		}

		if (player.isSneaking) {
			return false
		}

		// nothing on a contactor is for hands
		if (spec.redstone) {
			pos.playSound("block.stone_button.click_off", 0.5f, 1.8f)

			return true
		}

		if (spec.momentaryTicks > 0) {
			remaining = spec.momentaryTicks

			if (!on) {
				setConducting(true)
			} else {
				// already held: the press re-arms the timer, quietly
				pos.playSound("block.stone_button.click_on", 0.4f, 1.2f)
			}

			return true
		}

		setConducting(!on)

		return true
	}

	private companion object {

		/** How often and how stubbornly the wire audit repairs a dead link. */
		const val AUDIT_MS = 5_000L
		const val MAX_AUDIT_STRIKES = 3

		/** Everything a cable carries. */
		val ALL_TYPES: Set<NetworkType<*>> = setOf(
			DefaultNetworkTypes.ENERGY,
			DefaultNetworkTypes.ITEM,
			DefaultNetworkTypes.FLUID,
		)
	}
}
