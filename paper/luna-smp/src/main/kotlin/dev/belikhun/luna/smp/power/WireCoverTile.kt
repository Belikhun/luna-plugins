package dev.belikhun.luna.smp.power

import net.kyori.adventure.key.Key
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.commons.collections.firstInstanceOfOrNull
import xyz.xenondevs.commons.provider.combinedProvider
import xyz.xenondevs.nova.addon.logistics.gui.cable.CableConfigMenu
import xyz.xenondevs.nova.config.Configs
import xyz.xenondevs.nova.config.entry
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockBreak
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockInteract
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.util.item.novaItem
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.TileEntity
import xyz.xenondevs.nova.world.block.tileentity.network.NetworkManager
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkEndPoint
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkNode
import xyz.xenondevs.nova.world.block.tileentity.network.type.DefaultNetworkTypes
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkType
import xyz.xenondevs.nova.world.block.tileentity.network.type.energy.EnergyBridge
import xyz.xenondevs.nova.world.block.tileentity.network.type.energy.EnergyNetwork
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.FluidBridge
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.FluidNetwork
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.holder.FluidHolder
import xyz.xenondevs.nova.world.block.tileentity.network.type.item.ItemBridge
import xyz.xenondevs.nova.world.block.tileentity.network.type.item.ItemNetwork
import xyz.xenondevs.nova.world.block.tileentity.network.type.item.holder.ItemHolder
import xyz.xenondevs.nova.world.format.NetworkState
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * A wire cover: a Logistics cable of one tier, poured into a solid block.
 *
 * It bridges all three networks at exactly the cable's own rates, read off
 * the cable's config so the two can never disagree, and it answers with the
 * cable's own block id as its type id. That id is the whole of how Nova
 * decides whether two bridges join, so a cover chains with the cables and
 * the covers of its tier precisely as another cable would, and cuts a run
 * of any other tier precisely as another cable would. Nothing here samples
 * or impersonates: the tier is known, so the id is fixed.
 *
 * Unlike a cable, a cover conducts each network type on its own set of
 * faces: energy may leave to the north while items do not. Nova's bridge
 * model has one face set for every type, so the cover registers with Nova
 * on the union of its masks and lets [WireCoverNetworking] cut and join the
 * individual links the masks call for; a neighbour placed later that Nova
 * links across a masked face is cut again on the next network update.
 *
 * A wrench click, or a click with an empty hand, opens the cover's side menu
 * ([WireCoverMenu]): an energy, an item and a fluid tab, each with the six
 * faces laid out as every Nova machine lays out its side configuration. The
 * item and fluid tabs also reach the same insert/extract menu a cable's
 * attachment opens for the machine on a face, because a buried line still
 * has to be told which way its goods flow.
 */
class WireCoverTile(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data), EnergyBridge, ItemBridge, FluidBridge {

	private val tier: String = PowerCatalog.COVER_BY_ID.getValue(blockState.block.id.value()).tier

	/** The cable this cover conducts as, which is also the id it answers with. */
	private val cableId: Key = Key.key("logistics", "${tier}_cable")

	@Volatile
	override var isValid: Boolean = false

	override val typeId: Key
		get() = cableId

	override val linkedNodes: Set<NetworkNode> = emptySet()

	// the cable's own numbers, per tick in its config and per network tick
	// here, combined the way the cable combines them itself
	override val energyTransferRate: Long by combinedProvider(
		Configs[cableId].entry<Double>("energy_transfer_rate"),
		EnergyNetwork.TICK_DELAY_PROVIDER,
	).map { (rate, delay) -> (rate * delay).roundToLong() }

	override val itemTransferRate: Int by combinedProvider(
		Configs[cableId].entry<Double>("item_transfer_rate"),
		ItemNetwork.TICK_DELAY_PROVIDER,
	).map { (rate, delay) -> (rate * delay).roundToInt() }

	override val fluidTransferRate: Long by combinedProvider(
		Configs[cableId].entry<Double>("fluid_transfer_rate"),
		FluidNetwork.TICK_DELAY_PROVIDER,
	).map { (rate, delay) -> (rate * delay).roundToLong() }

	// one six-bit mask per network type, a bit per face in FACES order; all
	// on until somebody says otherwise, the way a cable conducts everywhere
	private var energyMask: Int by storedValue("mask.energy") { ALL }
	private var itemMask: Int by storedValue("mask.item") { ALL }
	private var fluidMask: Int by storedValue("mask.fluid") { ALL }

	/** The open machine menus, one per face, kept in step with the network. */
	private val configMenus = ConcurrentHashMap<BlockFace, CableConfigMenu>()

	/** The cover's own side menu; lazy, since it holds a reference back to this tile. */
	private val sideMenu: WireCoverMenu by lazy { WireCoverMenu(this) }

	override fun handleEnable() {
		super.handleEnable()

		// the network state restored from disk is authoritative on load, as
		// it is for a cable: only a fresh placement registers
		isValid = true
	}

	override fun handleDisable() {
		super.handleDisable()

		isValid = false
		configMenus.values.forEach { it.closeForAllViewers() }
		configMenus.clear()
		sideMenu.closeAll()
	}

	override fun handlePlace(ctx: Context<BlockPlace>) {
		super.handlePlace(ctx)

		register()
		isValid = true
	}

	override fun handleBreak(ctx: Context<BlockBreak>) {
		super.handleBreak(ctx)

		NetworkManager.queueRemoveBridge(this)
		isValid = false
	}

	/** Whether this cover carries [type] out through [face]. */
	fun conducts(type: NetworkType<*>, face: BlockFace): Boolean =
		maskOf(type) and bitOf(face) != 0

	/** The faces this cover carries [type] through. */
	fun facesOf(type: NetworkType<*>): Set<BlockFace> =
		FACES.filterTo(HashSet()) { conducts(type, it) }

	/**
	 * Turns [face] on or off for [type], then runs [then] once the change has
	 * reached the network state, so a menu can redraw off it.
	 *
	 * Only a change to the union of the masks re-registers the whole bridge
	 * with Nova, since that union is what Nova stores; a change inside it is
	 * a single link cut or joined, which is far cheaper than a remove and add.
	 */
	fun setFace(type: NetworkType<*>, face: BlockFace, conducting: Boolean, then: () -> Unit) {
		val before = union()
		val bit = bitOf(face)
		val mask = if (conducting) maskOf(type) or bit else maskOf(type) and bit.inv()

		setMask(type, mask)

		if (union() != before) {
			register()
		}

		NetworkManager.queueWrite(pos.chunkPos) { state ->
			WireCoverNetworking.apply(state, this)
			then()
		}
	}

	/**
	 * Conducts every type on exactly [faces] from now on: what the cable this
	 * cover took the place of conducted on, so covering a wire changes nothing
	 * about the line it was part of.
	 */
	fun adoptFaces(faces: Set<BlockFace>) {
		val mask = faces.fold(0) { acc, face -> acc or bitOf(face) }

		energyMask = mask
		itemMask = mask
		fluidMask = mask
		register()

		NetworkManager.queueWrite(pos.chunkPos) { state ->
			WireCoverNetworking.apply(state, this)
		}
	}

	/**
	 * Registers the bridge with Nova on the union of its masks. Remove first,
	 * always: an add for a node the state already holds is a silent no-op,
	 * and this is also how a cable changes its own faces.
	 */
	private fun register() {
		NetworkManager.queueRemoveBridge(this)
		NetworkManager.queueAddBridge(this, TYPES.toSet(), FACES.filterTo(HashSet()) { union() and bitOf(it) != 0 })
	}

	private fun union(): Int =
		energyMask or itemMask or fluidMask

	private fun maskOf(type: NetworkType<*>): Int =
		when (type) {
			DefaultNetworkTypes.ENERGY -> energyMask
			DefaultNetworkTypes.ITEM -> itemMask
			DefaultNetworkTypes.FLUID -> fluidMask
			else -> 0
		}

	private fun setMask(type: NetworkType<*>, mask: Int) {
		when (type) {
			DefaultNetworkTypes.ENERGY -> energyMask = mask
			DefaultNetworkTypes.ITEM -> itemMask = mask
			DefaultNetworkTypes.FLUID -> fluidMask = mask
		}
	}

	override suspend fun handleNetworkUpdate(state: NetworkState) {
		val connected = state.getConnectedNodes(this)

		// a machine menu shows one machine's holders; when that machine is
		// gone or has changed, the menu is stale and closes rather than lying
		for ((face, menu) in configMenus) {
			val neighbour = connected[DefaultNetworkTypes.ITEM, face]
				?: connected[DefaultNetworkTypes.FLUID, face]

			val endPoint = neighbour as? NetworkEndPoint
			val itemHolder = endPoint?.holders?.firstInstanceOfOrNull<ItemHolder>()
			val fluidHolder = endPoint?.holders?.firstInstanceOfOrNull<FluidHolder>()

			if (endPoint != null && menu.itemHolder == itemHolder && menu.fluidHolder == fluidHolder) {
				menu.updateValues()
				runTask { menu.updateGui() }
				continue
			}

			configMenus.remove(face)
			runTask { menu.closeForAllViewers() }
		}

		// a neighbour Nova linked across a masked face, or a neighbour that
		// now accepts a link a mask wants: put it right on the next write,
		// not inside this update, which may sit in the middle of Nova's own.
		// At most once a second, so a link that cannot be made for a reason
		// this cannot see never becomes a loop on the network thread
		val now = System.currentTimeMillis()

		if (now >= nextHeal && WireCoverNetworking.needsApply(state, this)) {
			nextHeal = now + HEAL_COOLDOWN_MS

			NetworkManager.queueWrite(pos.chunkPos) { later ->
				WireCoverNetworking.apply(later, this)
			}
		}

		sideMenu.refresh(state)
	}

	/** When the next self-heal may be queued; see [handleNetworkUpdate]. */
	@Volatile
	private var nextHeal: Long = 0L

	override fun handleRightClick(ctx: Context<BlockInteract>): Boolean {
		val player = ctx[DefaultContextParamTypes.SOURCE_PLAYER] ?: return false
		val hand = ctx[DefaultContextParamTypes.INTERACTION_HAND] ?: EquipmentSlot.HAND

		// the click is offered once per hand; the off-hand one would repeat it
		if (hand != EquipmentSlot.HAND) {
			return false
		}

		val held = ctx[DefaultContextParamTypes.INTERACTION_ITEM_STACK]

		// the wrench opens the side menu, as it does nothing else useful on a
		// block with no arms to click; so does an empty hand or a tool
		if (held?.novaItem?.id == WRENCH) {
			sideMenu.openWindow(player)
			return true
		}

		// a block in hand is a build gesture, and sneaking is the bypass
		// gesture everywhere else here: both go through to vanilla
		if (player.isSneaking) {
			return false
		}

		if (held != null && (held.type.isBlock || held.novaItem?.block != null)) {
			return false
		}

		sideMenu.openWindow(player)
		return true
	}

	/**
	 * Opens the insert/extract menu for the machine on [face], when there is
	 * one with anything to configure.
	 */
	fun openFaceMenu(player: Player, face: BlockFace) {
		val open = configMenus[face]

		if (open != null) {
			open.openWindow(player)
			return
		}

		NetworkManager.queueRead(pos.chunkPos) { state ->
			val endPoint = state.getConnectedNode(this, face) as? NetworkEndPoint
			val itemHolder = endPoint?.holders?.firstInstanceOfOrNull<ItemHolder>()
			val fluidHolder = endPoint?.holders?.firstInstanceOfOrNull<FluidHolder>()

			if (endPoint == null || (itemHolder == null && fluidHolder == null)) {
				return@queueRead
			}

			val menu = configMenus.computeIfAbsent(face) {
				CableConfigMenu(this, endPoint, itemHolder, fluidHolder, face.oppositeFace)
			}

			runTask {
				menu.openWindow(player)
			}
		}
	}

	companion object {

		/** The Logistics wrench: the one tool that reconfigures a wire. */
		private val WRENCH: Key = Key.key("logistics", "wrench")

		/** The three networks a cover carries, in the order the menu shows them. */
		val TYPES: List<NetworkType<*>> = listOf(
			DefaultNetworkTypes.ENERGY,
			DefaultNetworkTypes.ITEM,
			DefaultNetworkTypes.FLUID,
		)

		/** The six faces, in mask bit order. */
		val FACES: List<BlockFace> = listOf(
			BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH,
			BlockFace.WEST, BlockFace.UP, BlockFace.DOWN,
		)

		private const val ALL = 0b111111

		private const val HEAL_COOLDOWN_MS = 1_000L

		private fun bitOf(face: BlockFace): Int =
			1 shl FACES.indexOf(face)
	}
}
