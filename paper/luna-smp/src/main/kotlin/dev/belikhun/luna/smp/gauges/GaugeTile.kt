package dev.belikhun.luna.smp.gauges

import dev.belikhun.luna.smp.LunaSmp
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.joml.Quaternionf
import org.joml.Vector3f
import xyz.xenondevs.cbf.Compound
import org.bukkit.event.inventory.ClickType
import xyz.xenondevs.invui.Click
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.item.AbstractItem
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.invui.item.ItemProvider
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockBreak
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockInteract
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.ui.menu.Canvas
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.block.tileentity.TileEntity
import xyz.xenondevs.nova.world.block.tileentity.menu.TileEntityMenuClass
import xyz.xenondevs.nova.world.block.tileentity.network.NetworkManager
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkNode
import xyz.xenondevs.nova.world.block.tileentity.network.type.DefaultNetworkTypes
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkType
import xyz.xenondevs.nova.world.block.tileentity.network.type.energy.EnergyBridge
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.FluidBridge
import xyz.xenondevs.nova.world.block.tileentity.network.type.item.ItemBridge
import xyz.xenondevs.nova.util.pitch
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.util.yaw
import xyz.xenondevs.nova.world.fakeentity.impl.FakeItemDisplay
import xyz.xenondevs.nova.world.fakeentity.impl.FakeTextDisplay
import xyz.xenondevs.nova.world.format.NetworkState
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.FluidType
import xyz.xenondevs.nova.world.format.WorldDataManager
import xyz.xenondevs.nova.world.model.FixedMultiModel
import xyz.xenondevs.nova.world.model.Model
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * One instrument in the world: a painted face, and the live parts in front of
 * it - a needle that swings, a range plate that keeps the needle honest, and
 * on the totaliser a row of rolling digits.
 *
 * The gauge samples once a second. Stock readings go straight onto the dial
 * as a fraction; rate readings auto-range the way a multimeter does, because
 * one base's network moves a hundred joules a second and another's a million:
 * the needle shows the fraction of the current full scale, and the little
 * plate under the hub says what full scale currently is. The plate steps
 * along the 1-2-5 ladder and decays as the peak fades, so the needle spends
 * its life in the readable middle of the arc.
 */
class GaugeTile(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data), EnergyBridge, ItemBridge, FluidBridge {

	private val spec = GaugeCatalog.BY_ID.getValue(blockState.block.id.value())

	// ---- the wire side -----------------------------------------------------
	//
	// The free-standing unit is a transparent piece of the cable line it is
	// wired into: a real network bridge, so the cable draws its connector to
	// it, it can sit inline in a run, and its own position is a network node
	// it can read. The panel form is screwed to a machine and the dashboard
	// block is read through its wall, so neither of those bridges anything.
	// See [Wiring] for the borrowed type id.

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

	/**
	 * Whether this instrument takes part in the wire at all. The free-standing
	 * unit and the full-block dashboard both do - a dashboard wall is meant to
	 * pass the line through itself, so a run of them stays gapless. Only the
	 * panel stuck flat on a machine stays a pure observer.
	 */
	private val bridging: Boolean
		get() = blockState[GaugeCatalog.ATTACHED] != true && !probing

	/**
	 * The block form of a multipurpose meter is the probe: it watches the
	 * blocks touching it instead of any wire, and it must not bridge - a
	 * bridge here would quietly fuse the networks of the machines it only
	 * means to read.
	 */
	private val probing: Boolean
		get() = spec.dashboard
			&& (spec.metric == GaugeCatalog.Metric.MULTI || spec.metric == GaugeCatalog.Metric.MULTI_FLOW)

	/** Every face but the dial's own: a cable in front of the glass is no wire. */
	private fun wireFaces(): Set<BlockFace> = FACES.toSet() - facing

	private val facing: BlockFace
		get() = blockState[DefaultBlockStateProperties.FACING] ?: BlockFace.NORTH

	private val form: GaugeCatalog.Form
		get() = when {
			spec.dashboard -> GaugeCatalog.Form.BLOCK
			blockState[GaugeCatalog.ATTACHED] == true -> GaugeCatalog.Form.PANEL
			else -> GaugeCatalog.Form.UNIT
		}

	private var needle: FakeItemDisplay? = null
	private var bar: FakeItemDisplay? = null
	private var plate: FakeTextDisplay? = null
	private var digits: FakeTextDisplay? = null

	/** What the needle last showed, so a still network sends no packets. */
	private var shownFrac = Double.NaN
	private var shownPlate: String? = null
	private var shownDigits: String? = null

	/** The auto-range state of a rate dial: the fading peak and the scale. */
	private var peak = 0.0
	private var range = 0.0

	/** A hand-set full scale; zero means the auto-range runs the dial. */
	private var fixedRange = 0.0

	/** The previous stock sample, for the flows that must be differentiated. */
	private var lastStock = Double.NaN
	private var lastStockAt = 0L
	private var smoothedRate = 0.0

	/** The per-container books behind the intake and output meters. */
	private val ledger = GaugeSources.StockLedger()

	/** Whether the dial reads what arrives (true) or what leaves (false); null for other dials. */
	private val intakeDial: Boolean?
		get() = when (spec.metric) {
			GaugeCatalog.Metric.ITEM_IN, GaugeCatalog.Metric.FLUID_IN -> true
			GaugeCatalog.Metric.ITEM_OUT, GaugeCatalog.Metric.FLUID_OUT -> false
			else -> null
		}

	/** Picks the half of the ledger this dial shows. */
	private fun side(rates: GaugeSources.StockLedger.Rates?): Double? {
		if (rates == null) {
			return null
		}

		return if (intakeDial == true) rates.intake else rates.output
	}

	/**
	 * The totaliser's count in the metric's base unit - joules, items or
	 * millibuckets; persisted so the meter never forgets. The drums show it
	 * divided by [meterScale].
	 */
	private var total = 0.0

	/**
	 * The second moving part every fluid dial has: a red needle (or bar) for
	 * lava beside the blue one for water. Both read on one range.
	 */
	private var needle2: FakeItemDisplay? = null
	private var bar2: FakeItemDisplay? = null
	private var shownSecond = Double.NaN

	/** The lava side of a fluid flow dial; [rateOf] and [smoothedRate] are the water side. */
	private val lavaFlow = Rate()

	/** The lava side of a fluid intake/output dial; [ledger] is the water side. */
	private val lavaLedger = GaugeSources.StockLedger()

	/** One dial fraction per second, a chart column each; a ring, newest last. */
	private val history = DoubleArray(HISTORY)
	private var historyAt = 0
	private var historySize = 0

	/** The periodic are-we-actually-linked check; see [auditWire]. */
	private var nextAudit = 0L
	private var auditStrikes = 0

	/** The couplings grown toward connected wires; see [updateJoints]. */
	private val joints = FixedMultiModel()

	// ---- lifecycle ---------------------------------------------------------

	override fun handleEnable() {
		total = retrieveDataOrNull<Double>(TOTAL) ?: 0.0
		fixedRange = retrieveDataOrNull<Double>(FIXED_RANGE) ?: 0.0

		if (bridging) {
			typeId = Wiring.lineIdAt(pos, wireFaces()) ?: typeId

			// remove first, always: an add for a node already in the network
			// state is silently ignored (AddNodeTask bails on a known node),
			// so a re-add alone can never fix a bridge registered under a
			// stale type id by an older build. The remove is a no-op when the
			// node is unknown, and remove-then-add is exactly what Nova's own
			// cable does when its faces change.
			NetworkManager.queueRemoveBridge(this)
			NetworkManager.queueAddBridge(this, WIRE_TYPES, wireFaces())
		}

		valid = true
		handleTick()
	}

	override fun handleDisable() {
		super.handleDisable()
		valid = false
		storeData(TOTAL, total)
		storeData(FIXED_RANGE, fixedRange)
		joints.clear()
		clear()
	}

	override fun handleBreak(ctx: Context<BlockBreak>) {
		if (bridging) {
			NetworkManager.queueRemoveBridge(this)
		}

		valid = false
		joints.clear()
		clear()
	}

	override suspend fun handleNetworkLoaded(state: NetworkState) {
		updateJoints(state)
	}

	override suspend fun handleNetworkUpdate(state: NetworkState) {
		updateJoints(state)
	}

	/**
	 * Grows a coupling toward every wire the network state records a
	 * connection on, cable-fashion. The housing is not a full block, so
	 * without these a cable visibly stops at the block border a few pixels
	 * short of the box; the dashboard fills its block and needs none.
	 */
	private suspend fun updateJoints(state: NetworkState) {
		if (form != GaugeCatalog.Form.UNIT) {
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

	override fun handleRightClick(ctx: Context<BlockInteract>): Boolean {
		val player = ctx[DefaultContextParamTypes.SOURCE_ENTITY] as? Player ?: return false
		val hand = ctx[DefaultContextParamTypes.INTERACTION_HAND] ?: EquipmentSlot.HAND

		if (hand != EquipmentSlot.HAND) {
			return false
		}

		// sneaking is the build gesture; every other click opens the meter
		if (player.isSneaking) {
			return false
		}

		menuContainer.openWindow(player)

		return true
	}

	// ---- sampling ----------------------------------------------------------

	override fun handleTick() {
		if (bridging) {
			val sampled = Wiring.lineIdAt(pos, wireFaces())

			if (sampled != null && sampled != typeId) {
				NetworkManager.queueRemoveBridge(this)
				typeId = sampled
				NetworkManager.queueAddBridge(this, WIRE_TYPES, wireFaces())
			}

			auditWire()
		}

		val sample = sample()

		history[historyAt] = sample.frac
		historyAt = (historyAt + 1) % HISTORY

		if (historySize < HISTORY) {
			historySize++
		}

		show(sample)

		menuContainer.forEachMenu<GaugeMenu> { menu ->
			menu.redraw()
		}
	}

	/**
	 * Every few seconds, asks the network state whether the links that should
	 * exist actually do: a same-id bridge next door with no recorded
	 * connection means this device's registration went stale (an older
	 * build's type id survives in the persisted state, or a load-order race
	 * swallowed the add), and a remove-and-re-add is the repair. Three
	 * fruitless repairs stop the attempts, so a genuinely unlinkable line
	 * cannot churn the network graph forever.
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
				"gauge {} at {} has unlinked line faces {} (typeId {}, strike {}); re-adding its bridge",
				spec.id, pos, missing, typeId, auditStrikes,
			)

			NetworkManager.queueRemoveBridge(this)
			NetworkManager.queueAddBridge(this, WIRE_TYPES, wireFaces())
		}
	}

	private class Sample(
		val frac: Double,
		val plate: String? = null,
		val digits: String? = null,
		/** The second needle's fraction: the lava on a fluid dial, on the same range. */
		val second: Double? = null,
	)

	private fun sample(): Sample {
		val now = System.currentTimeMillis()

		// a panel screwed onto a one-way bridge reads the bridge itself: its
		// outlet counters are the one honest throughput in Nova, where every
		// network-level item or fluid "flow" is a net stock delta that reads
		// zero on anything passing straight through
		val diode = attachedDiode()

		if (diode != null) {
			return decorate(diodeSample(diode, now))
		}

		// a panel screwed straight onto a battery, tank, chest or machine
		// reads that block itself, the way the multipurpose meter always
		// has; the network stays the fallback for a panel on a bare wall
		if (!spec.dashboard && blockState[GaugeCatalog.ATTACHED] == true) {
			val direct = hostSample(now)

			if (direct != null) {
				return decorate(direct)
			}
		}

		return decorate(when (spec.metric) {
			GaugeCatalog.Metric.ENERGY_STORED -> {
				val stats = GaugeSources.energy(anchors())

				Sample(level(stats?.stored, stats?.capacity))
			}

			GaugeCatalog.Metric.ENERGY_FLOW -> {
				val stats = GaugeSources.energy(anchors())

				centred(stats?.flow?.toDouble(), FLOOR_ENERGY)
			}

			GaugeCatalog.Metric.ENERGY_LOAD -> {
				val stats = GaugeSources.energy(anchors())

				scaled(stats?.load?.toDouble(), FLOOR_ENERGY)
			}

			GaugeCatalog.Metric.ENERGY_GEN -> {
				val stats = GaugeSources.energy(anchors())

				scaled(stats?.gen?.toDouble(), FLOOR_ENERGY)
			}

			GaugeCatalog.Metric.ENERGY_TOTAL -> {
				val stats = GaugeSources.energy(anchors())

				if (stats != null) {
					total += stats.load.toDouble()
				}

				// the meter has no needle, but its chart plots the draw the
				// drums are counting up; the plate stays with the digits
				val load = scaled(stats?.load?.toDouble(), FLOOR_ENERGY)

				Sample(load.frac, digits = meterDigits())
			}

			GaugeCatalog.Metric.ITEM_FLOW ->
				centred(rateOf(GaugeSources.items(anchors())?.toDouble(), now), FLOOR_ITEMS)

			GaugeCatalog.Metric.ITEM_STORED -> {
				val stats = GaugeSources.itemsStored(anchors())

				Sample(level(stats?.count, stats?.capacity))
			}

			GaugeCatalog.Metric.FLUID_STORED -> {
				val stats = GaugeSources.fluid(anchors())

				Sample(level(stats?.water, stats?.capacity), second = level(stats?.lava, stats?.capacity))
			}

			GaugeCatalog.Metric.FLUID_FLOW -> {
				val stats = GaugeSources.fluid(anchors())

				centredPair(
					rateOf(stats?.water?.toDouble(), now),
					lavaFlow.update(stats?.lava?.toDouble(), now),
					FLOOR_FLUID,
				)
			}

			GaugeCatalog.Metric.ITEM_IN, GaugeCatalog.Metric.ITEM_OUT ->
				scaled(side(ledger.update(GaugeSources.itemStocks(anchors()), now)), FLOOR_ITEMS)

			GaugeCatalog.Metric.FLUID_IN, GaugeCatalog.Metric.FLUID_OUT ->
				scaledPair(
					side(ledger.update(GaugeSources.fluidStocks(anchors(), FluidType.WATER), now)),
					side(lavaLedger.update(GaugeSources.fluidStocks(anchors(), FluidType.LAVA), now)),
					FLOOR_FLUID,
				)

			GaugeCatalog.Metric.ITEM_TOTAL -> {
				val rates = ledger.update(GaugeSources.itemStocks(anchors()), now)

				// one sample a second: what left the stores since the last
				// one is what the drums count, like the electrical meter
				if (rates != null) {
					total += rates.output
				}

				Sample(scaled(rates?.output, FLOOR_ITEMS).frac, digits = meterDigits())
			}

			GaugeCatalog.Metric.FLUID_TOTAL -> {
				val rates = ledger.update(GaugeSources.fluidStocks(anchors()), now)

				if (rates != null) {
					total += rates.output
				}

				Sample(scaled(rates?.output, FLOOR_FLUID).frac, digits = meterDigits())
			}

			GaugeCatalog.Metric.MULTI -> multiSample(now, flow = false)

			GaugeCatalog.Metric.MULTI_FLOW -> multiSample(now, flow = true)
		})
	}

	/** The one-way bridge a panel instrument is screwed onto, if any. */
	private fun attachedDiode(): NetworkDiodeTile? {
		if (spec.dashboard || blockState[GaugeCatalog.ATTACHED] != true) {
			return null
		}

		return WorldDataManager.getTileEntity(hostPos()) as? NetworkDiodeTile
	}

	/** What each dial says when it is reading a one-way bridge. */
	private fun diodeSample(diode: NetworkDiodeTile, now: Long): Sample {
		return when (spec.metric) {
			// the stored dials read the crossing's own buffer: a filling
			// buffer is a congested outlet, which is exactly what somebody
			// bolting a level gauge to a valve wants to see
			GaugeCatalog.Metric.ENERGY_STORED, GaugeCatalog.Metric.MULTI -> {
				val (stored, capacity) = diode.energyLevel()

				Sample(level(stored, capacity))
			}

			GaugeCatalog.Metric.FLUID_STORED -> {
				val (stored, capacity) = diode.fluidLevel()

				Sample(level(stored, capacity))
			}

			GaugeCatalog.Metric.ITEM_STORED -> {
				val (stored, capacity) = diode.itemLevel()

				Sample(level(stored, capacity))
			}

			GaugeCatalog.Metric.ENERGY_FLOW, GaugeCatalog.Metric.MULTI_FLOW ->
				centred(rateOf(diode.movedEnergy, now), FLOOR_ENERGY)

			GaugeCatalog.Metric.ENERGY_LOAD, GaugeCatalog.Metric.ENERGY_GEN ->
				scaled(rateOf(diode.movedEnergy, now), FLOOR_ENERGY)

			GaugeCatalog.Metric.ENERGY_TOTAL -> {
				val rate = rateOf(diode.movedEnergy, now)

				if (rate != null) {
					// one sample a second: the per-second rate is the joules
					// since the last one, which is what a meter counts
					total += rate
				}

				val load = scaled(rate, FLOOR_ENERGY)

				Sample(load.frac, digits = meterDigits())
			}

			GaugeCatalog.Metric.ITEM_FLOW ->
				centred(rateOf(diode.movedItems, now), FLOOR_ITEMS)

			GaugeCatalog.Metric.FLUID_FLOW ->
				centred(rateOf(diode.movedFluid, now), FLOOR_FLUID)

			// what crosses a one-way bridge arrives on one side exactly as it
			// leaves the other, so both halves of the pair read the crossing
			GaugeCatalog.Metric.ITEM_IN, GaugeCatalog.Metric.ITEM_OUT ->
				scaled(rateOf(diode.movedItems, now), FLOOR_ITEMS)

			GaugeCatalog.Metric.FLUID_IN, GaugeCatalog.Metric.FLUID_OUT ->
				scaled(rateOf(diode.movedFluid, now), FLOOR_FLUID)

			GaugeCatalog.Metric.ITEM_TOTAL -> {
				val rate = rateOf(diode.movedItems, now)

				if (rate != null) {
					total += rate
				}

				Sample(scaled(rate, FLOOR_ITEMS).frac, digits = meterDigits())
			}

			GaugeCatalog.Metric.FLUID_TOTAL -> {
				val rate = rateOf(diode.movedFluid, now)

				if (rate != null) {
					total += rate
				}

				Sample(scaled(rate, FLOOR_FLUID).frac, digits = meterDigits())
			}
		}
	}

	/**
	 * The LCD instruments show the number itself. The value is rebuilt from
	 * the dial fraction and the current range - the same two figures the
	 * needle instruments live on, so the digits and a chart column can never
	 * disagree.
	 */
	private fun decorate(sample: Sample): Sample {
		if (spec.style != GaugeCatalog.Style.DIGITAL || sample.digits != null) {
			return sample
		}

		val digits = when {
			// no source in reach: the LCD rests instead of asserting a zero
			sample.plate == IDLE_PLATE -> "---"

			signedDial -> signed((sample.frac - 0.5) * 2.0 * range)

			else -> "${(sample.frac * 100).toInt()}%"
		}

		return Sample(sample.frac, sample.plate, digits)
	}

	/**
	 * The chameleon reading: whatever the host block offers, in priority
	 * order - energy, then tanks, then inventory - and the wire behind it
	 * when the host offers nothing at all.
	 */
	private fun multiSample(now: Long, flow: Boolean): Sample {
		if (probing) {
			return probeSample(now, flow)
		}

		val host = GaugeSources.host(hostPos())

		if (host == null) {
			// nothing readable behind it: read the network instead, so a
			// multipurpose meter on a bare cable still says something useful
			val stats = GaugeSources.energy(anchors()) ?: return if (flow) {
				centred(null, FLOOR_ENERGY)
			} else {
				Sample(level(null, null))
			}

			return if (flow) {
				centred(stats.flow.toDouble(), FLOOR_ENERGY)
			} else {
				Sample(level(stats.stored, stats.capacity))
			}
		}

		val energy = host.energy

		if (energy != null) {
			if (flow) {
				return centred((energy.energyPlus - energy.energyMinus) * 20.0, FLOOR_ENERGY)
			}

			// a battery reads as its level; anything else as its draw or its
			// output, which is what somebody bolting a meter to it wants
			return when {
				energy.maxEnergy > 0 && energy.allowedConnectionType.insert && energy.allowedConnectionType.extract ->
					Sample(level(energy.energy, energy.maxEnergy))

				energy.allowedConnectionType.extract ->
					scaled(energy.energyPlus * 20.0, FLOOR_ENERGY)

				else ->
					scaled(energy.energyMinus * 20.0, FLOOR_ENERGY)
			}
		}

		if (host.fluid != null) {
			return if (flow) {
				centred(rateOf(host.fluid.amount.toDouble(), now), FLOOR_FLUID)
			} else {
				Sample(level(host.fluid.amount, host.fluid.capacity))
			}
		}

		return if (flow) {
			centred(rateOf(host.itemCount?.toDouble(), now), FLOOR_ITEMS)
		} else {
			Sample(level(host.itemCount, host.itemCapacity))
		}
	}

	/**
	 * What a typed panel reads off the block it is screwed onto, or null when
	 * that block does not carry the panel's own quantity - an energy dial on
	 * a plain chest falls back to the network behind it.
	 */
	private fun hostSample(now: Long): Sample? {
		val host = GaugeSources.host(hostPos()) ?: return null
		val energy = host.energy

		return when (spec.metric) {
			GaugeCatalog.Metric.ENERGY_STORED ->
				energy?.let { Sample(level(it.energy, it.maxEnergy)) }

			GaugeCatalog.Metric.ENERGY_FLOW ->
				energy?.let { centred((it.energyPlus - it.energyMinus) * 20.0, FLOOR_ENERGY) }

			GaugeCatalog.Metric.ENERGY_LOAD ->
				energy?.let { scaled(it.energyMinus * 20.0, FLOOR_ENERGY) }

			GaugeCatalog.Metric.ENERGY_GEN ->
				energy?.let { scaled(it.energyPlus * 20.0, FLOOR_ENERGY) }

			GaugeCatalog.Metric.ENERGY_TOTAL ->
				energy?.let {
					total += it.energyMinus.toDouble()

					val load = scaled(it.energyMinus * 20.0, FLOOR_ENERGY)

					Sample(load.frac, digits = meterDigits())
				}

			GaugeCatalog.Metric.ITEM_STORED ->
				host.itemCount?.let { Sample(level(it, host.itemCapacity ?: 0L)) }

			GaugeCatalog.Metric.ITEM_FLOW ->
				host.itemCount?.let { centred(rateOf(it.toDouble(), now), FLOOR_ITEMS) }

			GaugeCatalog.Metric.FLUID_STORED ->
				host.fluid?.let { Sample(level(it.water, it.capacity), second = level(it.lava, it.capacity)) }

			GaugeCatalog.Metric.FLUID_FLOW ->
				host.fluid?.let {
					centredPair(
						rateOf(it.water.toDouble(), now),
						lavaFlow.update(it.lava.toDouble(), now),
						FLOOR_FLUID,
					)
				}

			GaugeCatalog.Metric.ITEM_IN, GaugeCatalog.Metric.ITEM_OUT ->
				host.itemCount?.let { scaled(side(ledger.update(mapOf(HOST_STOCK to it), now)), FLOOR_ITEMS) }

			GaugeCatalog.Metric.FLUID_IN, GaugeCatalog.Metric.FLUID_OUT ->
				host.fluid?.let {
					scaledPair(
						side(ledger.update(mapOf(HOST_STOCK to it.water), now)),
						side(lavaLedger.update(mapOf(HOST_STOCK to it.lava), now)),
						FLOOR_FLUID,
					)
				}

			GaugeCatalog.Metric.ITEM_TOTAL ->
				host.itemCount?.let {
					val rates = ledger.update(mapOf(HOST_STOCK to it), now)

					if (rates != null) {
						total += rates.output
					}

					Sample(scaled(rates?.output, FLOOR_ITEMS).frac, digits = meterDigits())
				}

			GaugeCatalog.Metric.FLUID_TOTAL ->
				host.fluid?.let {
					val rates = ledger.update(mapOf(HOST_STOCK to it.amount), now)

					if (rates != null) {
						total += rates.output
					}

					Sample(scaled(rates?.output, FLOOR_FLUID).frac, digits = meterDigits())
				}

			// the multipurpose meters run their own host logic
			GaugeCatalog.Metric.MULTI, GaugeCatalog.Metric.MULTI_FLOW -> null
		}
	}

	/**
	 * The probe block's reading: every block touching it, merged, with the
	 * same energy-tanks-inventory priority the panel form holds one host to.
	 */
	private fun probeSample(now: Long, flow: Boolean): Sample {
		val stats = GaugeSources.probe(neighboursOf(pos))
			?: return if (flow) {
				centred(null, FLOOR_ENERGY)
			} else {
				Sample(level(null, null))
			}

		if (stats.hasEnergy) {
			return if (flow) {
				centred(stats.energyFlow.toDouble(), FLOOR_ENERGY)
			} else {
				Sample(level(stats.energyStored, stats.energyCapacity))
			}
		}

		if (stats.fluid != null) {
			return if (flow) {
				centred(rateOf(stats.fluid.amount.toDouble(), now), FLOOR_FLUID)
			} else {
				Sample(level(stats.fluid.amount, stats.fluid.capacity))
			}
		}

		return if (flow) {
			centred(rateOf(stats.itemCount?.toDouble(), now), FLOOR_ITEMS)
		} else {
			Sample(level(stats.itemCount, stats.itemCapacity))
		}
	}

	/** A stock as a dial fraction; an absent source parks the needle at zero. */
	private fun level(current: Long?, capacity: Long?): Double {
		if (current == null || capacity == null || capacity <= 0L) {
			return 0.0
		}

		return (current.toDouble() / capacity.toDouble()).coerceIn(0.0, 1.0)
	}

	/** A one-sided rate on the auto-range: zero at rest, full at the plate. */
	private fun scaled(value: Double?, floor: Double): Sample {
		if (value == null) {
			return Sample(0.0, plate = IDLE_PLATE)
		}

		retune(abs(value), floor)

		return Sample((value / range).coerceIn(0.0, 1.0), plate = fmt(range))
	}

	/** A signed rate on a centre-zero dial. */
	private fun centred(value: Double?, floor: Double): Sample {
		if (value == null) {
			return Sample(0.5, plate = IDLE_PLATE)
		}

		retune(abs(value), floor)

		return Sample((0.5 + 0.5 * value / range).coerceIn(0.0, 1.0), plate = PLUS_MINUS + fmt(range))
	}

	/**
	 * [rateOf]'s bookkeeping as an object, for a dial that differentiates two
	 * stocks at once: the lava beside the water.
	 */
	private class Rate {

		private var last = Double.NaN
		private var lastAt = 0L

		var smoothed = 0.0
			private set

		fun update(stock: Double?, now: Long): Double? {
			if (stock == null) {
				last = Double.NaN

				return null
			}

			if (last.isNaN() || now <= lastAt) {
				last = stock
				lastAt = now

				return smoothed
			}

			val rate = (stock - last) / ((now - lastAt) / 1000.0)

			last = stock
			lastAt = now
			smoothed = smoothed * 0.5 + rate * 0.5

			return smoothed
		}
	}

	/** Two one-sided rates on one range: the water and the lava. */
	private fun scaledPair(water: Double?, lava: Double?, floor: Double): Sample {
		if (water == null && lava == null) {
			return Sample(0.0, plate = IDLE_PLATE, second = 0.0)
		}

		retune(max(abs(water ?: 0.0), abs(lava ?: 0.0)), floor)

		return Sample(
			((water ?: 0.0) / range).coerceIn(0.0, 1.0),
			plate = fmt(range),
			second = ((lava ?: 0.0) / range).coerceIn(0.0, 1.0),
		)
	}

	/** Two signed rates on one centre-zero dial: the water and the lava. */
	private fun centredPair(water: Double?, lava: Double?, floor: Double): Sample {
		if (water == null && lava == null) {
			return Sample(0.5, plate = IDLE_PLATE, second = 0.5)
		}

		retune(max(abs(water ?: 0.0), abs(lava ?: 0.0)), floor)

		return Sample(
			(0.5 + 0.5 * (water ?: 0.0) / range).coerceIn(0.0, 1.0),
			plate = PLUS_MINUS + fmt(range),
			second = (0.5 + 0.5 * (lava ?: 0.0) / range).coerceIn(0.0, 1.0),
		)
	}

	/**
	 * Differentiates a sampled stock into a rate, smoothed just enough that a
	 * batchy machine reads as a flow instead of a flicker.
	 */
	private fun rateOf(stock: Double?, now: Long): Double? {
		if (stock == null) {
			lastStock = Double.NaN
			return null
		}

		if (lastStock.isNaN() || now <= lastStockAt) {
			lastStock = stock
			lastStockAt = now
			return smoothedRate
		}

		val rate = (stock - lastStock) / ((now - lastStockAt) / 1000.0)

		lastStock = stock
		lastStockAt = now
		smoothedRate = smoothedRate * 0.5 + rate * 0.5

		return smoothedRate
	}

	/** Walks the 1-2-5 range ladder after the fading peak, unless pinned. */
	private fun retune(magnitude: Double, floor: Double) {
		if (fixedRange > 0.0) {
			range = fixedRange
			return
		}

		peak = max(magnitude, peak * 0.96)
		range = niceCeil(max(peak, floor))
	}

	/** What one drum unit is worth: a kilojoule, an item, or a bucket. */
	private fun meterScale(): Double =
		when (spec.metric) {
			GaugeCatalog.Metric.ITEM_TOTAL -> 1.0
			else -> 1000.0
		}

	private fun meterDigits(): String {
		val shown = (total / meterScale()).toLong()

		return shown.coerceIn(0, 9_999_999).toString().padStart(7, '0')
	}

	/** The red tenths drum; the item meter counts whole things and has none. */
	private fun meterFraction(): String =
		if (spec.metric == GaugeCatalog.Metric.ITEM_TOTAL) {
			""
		} else {
			((total / (meterScale() / 10.0)) % 10).toInt().toString()
		}

	// ---- anchors -----------------------------------------------------------

	/** The block a panel instrument is screwed to. */
	private fun hostPos(): BlockPos = pos.advance(facing.oppositeFace, 1)

	/**
	 * Where this instrument listens. A panel reads through its host; a field
	 * unit reads everything it touches; a dashboard block reads through the
	 * whole slab of dashboard blocks it is part of, so only one block of a
	 * gauge wall needs to actually touch the wire.
	 */
	private fun anchors(): Collection<BlockPos> {
		if (!spec.dashboard) {
			if (blockState[GaugeCatalog.ATTACHED] == true) {
				return listOf(hostPos())
			}

			// the unit is a bridge, so once linked its own position is in the
			// node map, and that alone is the honest anchor: keeping the
			// neighbours would fold in the networks BEYOND an adjacent
			// one-way bridge and double-count its throughput. They remain
			// only as the fallback for the moment before the queued add lands.
			if (NetworkManager.networks.any { network -> network.nodes.containsKey(pos) }) {
				return listOf(pos)
			}

			return listOf(pos) + neighboursOf(pos)
		}

		val members = LinkedHashSet<BlockPos>()
		val queue = ArrayDeque<BlockPos>()

		members += pos
		queue += pos

		while (queue.isNotEmpty() && members.size < MAX_DASHBOARD) {
			val at = queue.removeFirst()

			for (face in FACES) {
				val next = at.advance(face, 1)

				if (next in members) {
					continue
				}

				val tile = WorldDataManager.getTileEntity(next) as? GaugeTile ?: continue

				if (!tile.spec.dashboard) {
					continue
				}

				members += next
				queue += next
			}
		}

		val anchors = LinkedHashSet<BlockPos>()

		for (member in members) {
			for (face in FACES) {
				val next = member.advance(face, 1)

				if (next !in members) {
					anchors += next
				}
			}
		}

		return anchors
	}

	private fun neighboursOf(at: BlockPos): List<BlockPos> = FACES.map { face -> at.advance(face, 1) }

	// ---- the moving parts ----------------------------------------------------

	private fun show(sample: Sample) {
		val second = sample.second

		if (spec.needle != null) {
			if (shownFrac.isNaN() || abs(sample.frac - shownFrac) > 0.004) {
				shownFrac = sample.frac
				swing(sample.frac, lava = false)
			}

			if (second != null && (shownSecond.isNaN() || abs(second - shownSecond) > 0.004)) {
				shownSecond = second
				swing(second, lava = true)
			}
		}

		if (spec.style == GaugeCatalog.Style.BAR) {
			if (shownFrac.isNaN() || abs(sample.frac - shownFrac) > 0.004) {
				shownFrac = sample.frac
				climb(sample.frac, lava = false)
			}

			if (second != null && (shownSecond.isNaN() || abs(second - shownSecond) > 0.004)) {
				shownSecond = second
				climb(second, lava = true)
			}
		}

		// the LCD's window belongs to its digits; the range lives inside them
		if (sample.plate != shownPlate && spec.windowX != null && spec.style != GaugeCatalog.Style.DIGITAL) {
			shownPlate = sample.plate
			label(sample.plate ?: IDLE_PLATE)
		}

		if (sample.digits != null && sample.digits != shownDigits) {
			shownDigits = sample.digits
			roll(sample.digits)
		}
	}

	/** Whether this dial reads a fluid, and so swings a water needle and a lava needle. */
	private val fluidDial: Boolean
		get() = when (spec.metric) {
			GaugeCatalog.Metric.FLUID_STORED, GaugeCatalog.Metric.FLUID_FLOW,
			GaugeCatalog.Metric.FLUID_IN, GaugeCatalog.Metric.FLUID_OUT,
			-> true

			else -> false
		}

	/**
	 * The needle item a dial swings. A fluid dial ignores the catalog's
	 * colour and wears blue for water and red for lava, keeping only whether
	 * the blade is the corner kind; every other dial wears what it was given.
	 */
	private fun needleItem(lava: Boolean): ItemStack? {
		val given = spec.needle ?: return null
		val id = if (fluidDial) {
			val corner = if (given.contains("corner")) "corner_" else ""

			"gauge_needle_$corner${if (lava) "red" else "blue"}"
		} else {
			given
		}

		return GaugeItems.NEEDLES[id]?.createItemStack()
	}

	/**
	 * Swings a needle to a dial fraction, interpolated on the client. On a
	 * fluid dial the two blades are LAYERED, the blue water needle outermost
	 * and the red lava needle beneath it, a fifth of a pixel apart: the first
	 * cut put them two thousandths of a block apart, which the client's depth
	 * buffer could not tell apart, and the blades flickered through each
	 * other whenever the readings agreed.
	 */
	private fun swing(frac: Double, lava: Boolean) {
		val degrees = spec.startDeg + (spec.endDeg - spec.startDeg) * frac

		// no baked offset: the client renders the blade exactly where the
		// rotation says. A half-circle was briefly added here after the
		// mirrored-frame era made healthy needles look flipped; with turn()
		// fixed, that offset itself became the flip the players saw
		val rotation = Quaternionf().rotationZ(Math.toRadians(SPIN * degrees).toFloat())
		val existing = if (lava) needle2 else needle

		if (existing != null) {
			existing.updateEntityData(true) {
				leftRotation = rotation
				transformationInterpolationDelay = 0
			}

			return
		}

		val item = needleItem(lava) ?: return
		val span = form.faceSpan
		val scale = (spec.needleLen / 64.0 * span) / BLADE
		val lift = if (fluidDial && !lava) NEEDLE_LIFT + WATER_LAYER else NEEDLE_LIFT

		val display = FakeItemDisplay(facePoint(spec.pivotX.toDouble(), spec.pivotY.toDouble(), lift)) { _, meta ->
			meta.itemStack = item
			meta.itemDisplay = ItemDisplayTransform.NONE
			meta.scale = Vector3f(scale.toFloat(), scale.toFloat(), scale.toFloat())
			meta.leftRotation = rotation
			meta.transformationInterpolationDelay = 0
			meta.transformationInterpolationDuration = 16

			// an instrument that cannot be read in the dark is furniture
			meta.brightness = Display.Brightness(15, 15)
		}

		if (lava) {
			needle2 = display
		} else {
			needle = display
		}
	}

	/**
	 * Grows the LED bar to a dial fraction, interpolated on the client. The
	 * bar model's blade stands on the model's own middle and the display is
	 * anchored at the slot's floor, so scaling it moves only the top edge -
	 * the foot of the bar never leaves the bottom of the slot.
	 */
	private fun climb(frac: Double, lava: Boolean) {
		val span = form.faceSpan
		val width = (BAR_WIDTH_PX / 64.0 * span / BAR_AUTHORED_W).toFloat()
		val height = ((spec.needleLen / 64.0 * span / BAR_AUTHORED_H) * frac)
			.coerceAtLeast(0.001)
			.toFloat()
		val existing = if (lava) bar2 else bar

		if (existing != null) {
			existing.updateEntityData(true) {
				scale = Vector3f(width, height, 1f)
				transformationInterpolationDelay = 0
			}

			return
		}

		// a fluid column is two bars side by side in the one slot, water on
		// the left and lava on the right; every other column is one amber bar
		val id = when {
			!fluidDial -> "gauge_bar_amber"
			lava -> "gauge_bar_red"
			else -> "gauge_bar_blue"
		}
		val item = GaugeItems.BARS[id]?.createItemStack() ?: return
		val shift = when {
			!fluidDial -> 0.0
			lava -> TWIN_BAR_SHIFT
			else -> -TWIN_BAR_SHIFT
		}

		val display = FakeItemDisplay(facePoint(spec.pivotX + shift, spec.pivotY.toDouble(), NEEDLE_LIFT)) { _, meta ->
			meta.itemStack = item
			meta.itemDisplay = ItemDisplayTransform.NONE
			meta.scale = Vector3f(width, height, 1f)
			meta.transformationInterpolationDelay = 0
			meta.transformationInterpolationDuration = 16
			meta.brightness = Display.Brightness(15, 15)
		}

		if (lava) {
			bar2 = display
		} else {
			bar = display
		}
	}

	/** The little plate that says what full scale currently is. */
	private fun label(text: String) {
		val component = Component.text(text, NamedTextColor.WHITE)
		val existing = plate

		if (existing != null) {
			existing.updateEntityData(true) { this.text = component }

			return
		}

		if (spec.windowX == null || spec.windowY == null) {
			return
		}

		plate = FakeTextDisplay(textPoint(spec.windowX.toDouble(), spec.windowY.toDouble(), 6.0)) { _, meta ->
			meta.text = component
			meta.scale = plateScale(6.0)
			meta.alignment = TextDisplay.TextAlignment.CENTER
			meta.billboardConstraints = Display.Billboard.FIXED
			meta.defaultBackground = false
			meta.backgroundColor = 0
			meta.brightness = Display.Brightness(15, 15)
		}
	}

	/**
	 * The digit row: the totaliser's drums (white kilojoules, the fading
	 * digit in red), or the LCD's green readout on the digital instruments.
	 */
	private fun roll(text: String) {
		val lcd = spec.style == GaugeCatalog.Style.DIGITAL
		val height = if (lcd) 10.0 else 8.0

		val component = if (lcd) {
			Component.text(text, LCD_GREEN)
		} else {
			Component.text()
				.append(Component.text(text, NamedTextColor.WHITE))
				.append(Component.text(meterFraction(), NamedTextColor.RED))
				.build()
		}

		val existing = digits

		if (existing != null) {
			existing.updateEntityData(true) { this.text = component }

			return
		}

		if (spec.windowX == null || spec.windowY == null) {
			return
		}

		digits = FakeTextDisplay(textPoint(spec.windowX.toDouble(), spec.windowY.toDouble(), height)) { _, meta ->
			meta.text = component
			meta.scale = plateScale(height)
			meta.alignment = TextDisplay.TextAlignment.CENTER
			meta.billboardConstraints = Display.Billboard.FIXED
			meta.defaultBackground = false
			meta.backgroundColor = 0
			meta.brightness = Display.Brightness(15, 15)
		}
	}

	private fun clear() {
		needle?.remove()
		needle2?.remove()
		bar?.remove()
		bar2?.remove()
		plate?.remove()
		digits?.remove()
		needle = null
		needle2 = null
		bar = null
		bar2 = null
		plate = null
		digits = null
		shownFrac = Double.NaN
		shownSecond = Double.NaN
		shownPlate = null
		shownDigits = null
	}

	/**
	 * A point on the dial, as a world location: face pixels in, the world's
	 * own frame out, with the yaw the displays need to face the reader.
	 *
	 * On a north-facing block the face's rightward axis (as the reader sees
	 * it) is world west, which is the minus sign on the x offset; the face's
	 * downward axis is world down.
	 */
	private fun facePoint(px: Double, py: Double, lift: Double): Location {
		val span = form.faceSpan
		val dx = -(px - 32.0) / 64.0 * span
		val dy = (32.0 - py) / 64.0 * span
		val (x, z) = turn(0.5 + dx, form.faceDepth - lift, facing)

		// the painted face is not always centred on the block: the unit's
		// housing sits low (feet below, conduit behind), so its face centre
		// is the form's own number, not 0.5
		return Location(pos.world, pos.x + x, pos.y + form.faceCenterY + dy, pos.z + z, yawOf(facing), 0f)
	}

	private fun plateScale(heightPx: Double): Vector3f {
		val scale = scaleFor(heightPx).toFloat()

		return Vector3f(scale, scale, scale)
	}

	private fun scaleFor(heightPx: Double): Double = (heightPx / 64.0 * form.faceSpan) * 40.0 / 9.0

	/**
	 * Where a text display goes for text meant to be CENTRED on a face point:
	 * its anchor is the bottom middle of the text and the block grows upward,
	 * so the anchor drops half a line below the point. Forgetting this is how
	 * the meter's digits shipped floating above their own window.
	 */
	private fun textPoint(px: Double, py: Double, heightPx: Double): Location {
		val location = facePoint(px, py, TEXT_LIFT)
		location.y -= scaleFor(heightPx) * 9.0 / 40.0 / 2.0

		return location
	}

	// ---- the exact reading -----------------------------------------------------

	/** What right-clicking the instrument says, precisely and in words. */
	private fun readout(): List<String> {
		val diode = attachedDiode()

		if (diode != null) {
			val energy = diode.energyLevel()
			val fluid = diode.fluidLevel()

			return listOf(
				"<yellow>⇉ Van một chiều:</yellow> <gray>tổng đã đi qua</gray> <white>${fmt(diode.movedEnergy)} J</white>"
					+ " <gray>·</gray> <white>${fmt(diode.movedItems)}</white> <gray>vật phẩm ·</gray>"
					+ " <white>${fmt(diode.movedFluid)} mB</white>",
				"<gray>Tốc độ đồng hồ này đang đo:</gray> <white>${signed(smoothedRate)} /s</white>",
				"<gray>Đệm trong van:</gray> <white>${percent(energy.first, energy.second)}</white> <gray>điện ·</gray>"
					+ " <white>${percent(fluid.first, fluid.second)}</white> <gray>chất lỏng</gray>",
			)
		}

		val anchors = anchors()

		return when (spec.metric) {
			GaugeCatalog.Metric.ENERGY_STORED,
			GaugeCatalog.Metric.ENERGY_FLOW,
			GaugeCatalog.Metric.ENERGY_LOAD,
			GaugeCatalog.Metric.ENERGY_GEN,
			GaugeCatalog.Metric.ENERGY_TOTAL,
			-> {
				val stats = GaugeSources.energy(anchors)
					?: return listOf("<gray>Không tìm thấy lưới điện nào quanh đồng hồ.</gray>")

				val lines = mutableListOf(
					"<yellow>⚡ Lưới điện:</yellow> <white>${fmt(stats.stored.toDouble())} J</white>"
						+ " / ${fmt(stats.capacity.toDouble())} J"
						+ " <gray>(${percent(stats.stored, stats.capacity)})</gray>",
					"<gray>Sạc/xả:</gray> <white>${signed(stats.flow.toDouble())} J/s</white>"
						+ " <gray>· tiêu thụ</gray> <white>${fmt(stats.load.toDouble())} J/s</white>"
						+ " <gray>· phát</gray> <white>${fmt(stats.gen.toDouble())} J/s</white>",
				)

				if (spec.metric == GaugeCatalog.Metric.ENERGY_TOTAL) {
					lines += "<gray>Tổng đã ghi:</gray> <white>${fmt(total)} J</white>"
				}

				lines
			}

			GaugeCatalog.Metric.FLUID_STORED, GaugeCatalog.Metric.FLUID_FLOW,
			GaugeCatalog.Metric.FLUID_IN, GaugeCatalog.Metric.FLUID_OUT, GaugeCatalog.Metric.FLUID_TOTAL,
			-> {
				val stats = GaugeSources.fluid(anchors)
					?: return listOf("<gray>Không tìm thấy đường ống nào quanh đồng hồ.</gray>")

				val lines = mutableListOf(
					"<aqua>🪣 Chất lỏng:</aqua> <white>${fmt(stats.amount.toDouble())} mB</white>"
						+ " / ${fmt(stats.capacity.toDouble())} mB"
						+ " <gray>(${percent(stats.amount, stats.capacity)})</gray>",
					"<blue>Nước:</blue> <white>${fmt(stats.water.toDouble())} mB</white>"
						+ " <gray>·</gray> <red>Dung nham:</red> <white>${fmt(stats.lava.toDouble())} mB</white>",
				)

				if (spec.metric == GaugeCatalog.Metric.FLUID_TOTAL) {
					lines += "<gray>Tổng đã ghi:</gray> <white>${fmt(total)} mB</white>"
				}

				if (intakeDial != null) {
					lines += ("<gray>Bơm vào:</gray> <white>${fmt(ledger.intake)} mB/s</white>"
						+ " <gray>· xả ra:</gray> <white>${fmt(ledger.output)} mB/s</white>")
				} else {
					lines += "<gray>Tốc độ (bơm/xả ròng):</gray> <white>${signed(smoothedRate)} mB/s</white>"
				}

				lines
			}

			GaugeCatalog.Metric.ITEM_FLOW, GaugeCatalog.Metric.ITEM_STORED,
			GaugeCatalog.Metric.ITEM_IN, GaugeCatalog.Metric.ITEM_OUT, GaugeCatalog.Metric.ITEM_TOTAL,
			-> {
				val count = GaugeSources.items(anchors)
					?: return listOf("<gray>Không tìm thấy mạng vật phẩm nào quanh đồng hồ.</gray>")

				val lines = mutableListOf(
					"<gold>📦 Vật phẩm trong mạng:</gold> <white>${fmt(count.toDouble())}</white>",
				)

				if (spec.metric == GaugeCatalog.Metric.ITEM_TOTAL) {
					lines += "<gray>Tổng đã ghi:</gray> <white>${fmt(total)}</white>"
				}

				if (intakeDial != null) {
					lines += ("<gray>Nhận vào:</gray> <white>${fmt(ledger.intake)} /s</white>"
						+ " <gray>· lấy ra:</gray> <white>${fmt(ledger.output)} /s</white>")
				} else {
					lines += "<gray>Tốc độ (vào/ra ròng):</gray> <white>${signed(smoothedRate)} /s</white>"
				}

				lines
			}

			GaugeCatalog.Metric.MULTI, GaugeCatalog.Metric.MULTI_FLOW -> {
				if (probing) {
					val stats = GaugeSources.probe(neighboursOf(pos))
						?: return listOf("<gray>Không có khối nào đọc được kề bên đồng hồ.</gray>")

					return buildList {
						if (stats.hasEnergy) {
							add(
								"<yellow>⚡ Các khối kề:</yellow> <white>${fmt(stats.energyStored.toDouble())} J</white>"
									+ " / ${fmt(stats.energyCapacity.toDouble())} J"
									+ " <gray>· ${signed(stats.energyFlow.toDouble())} J/s</gray>",
							)
						}

						if (stats.fluid != null) {
							add(
								"<aqua>🪣</aqua> <white>${fmt(stats.fluid.amount.toDouble())} mB</white>"
									+ " / ${fmt(stats.fluid.capacity.toDouble())} mB",
							)
						}

						if (stats.itemCount != null) {
							add(
								"<gold>📦</gold> <white>${fmt(stats.itemCount.toDouble())}</white>"
									+ " / ${fmt((stats.itemCapacity ?: 0L).toDouble())} <gray>chỗ chứa</gray>",
							)
						}
					}
				}

				val host = GaugeSources.host(hostPos())
					?: return listOf("<gray>Không có gì để đo sau đồng hồ; đang đọc lưới bên cạnh.</gray>")

				buildList {
					val energy = host.energy

					if (energy != null) {
						add(
							"<yellow>⚡ Khối phía sau:</yellow> <white>${fmt(energy.energy.toDouble())} J</white>"
								+ " / ${fmt(energy.maxEnergy.toDouble())} J"
								+ " <gray>· ${signed((energy.energyPlus - energy.energyMinus) * 20.0)} J/s</gray>",
						)
					}

					if (host.fluid != null) {
						add(
							"<aqua>🪣</aqua> <white>${fmt(host.fluid.amount.toDouble())} mB</white>"
								+ " / ${fmt(host.fluid.capacity.toDouble())} mB",
						)
					}

					if (host.itemCount != null) {
						add(
							"<gold>📦</gold> <white>${fmt(host.itemCount.toDouble())}</white>"
								+ " / ${fmt((host.itemCapacity ?: 0L).toDouble())} <gray>chỗ chứa</gray>",
						)
					}
				}
			}
		}
	}

	// ---- the meter window --------------------------------------------------

	/** Whether the dial is centre-zero: drain on the left, fill on the right. */
	private val signedDial: Boolean
		get() = when (spec.metric) {
			GaugeCatalog.Metric.ENERGY_FLOW,
			GaugeCatalog.Metric.ITEM_FLOW,
			GaugeCatalog.Metric.FLUID_FLOW,
			GaugeCatalog.Metric.MULTI_FLOW,
			-> true

			else -> false
		}

	/**
	 * The meter window: the last couple of minutes as a bar chart, one column
	 * a second, and the live value as a fat bar beside it - the same
	 * furniture as the power cell's plot, drawn from the dial fractions the
	 * needle already shows, so the chart and the needle cannot disagree.
	 */
	@TileEntityMenuClass
	inner class GaugeMenu : GlobalTileEntityMenu() {

		private val chartImage = BufferedImage(CHART_W, CHART_H, BufferedImage.TYPE_INT_ARGB)
		private val barImage = BufferedImage(18, CHART_H, BufferedImage.TYPE_INT_ARGB)

		private val chart = object : Canvas(chartImage) {
			override fun modifyItemBuilder(x: Int, y: Int, viewer: Player, itemBuilder: ItemBuilder) {
				itemBuilder.setName(Component.translatable("block.lunasmp.${spec.id}", NamedTextColor.WHITE))

				for (line in readout()) {
					itemBuilder.addLoreLines(MM.deserialize(line))
				}
			}
		}

		private val bar = object : Canvas(barImage) {
			override fun modifyItemBuilder(x: Int, y: Int, viewer: Player, itemBuilder: ItemBuilder) {
				itemBuilder.setName(Component.translatable("block.lunasmp.${spec.id}", NamedTextColor.WHITE))
			}
		}

		private val buttons = mutableListOf<AbstractItem>()

		private fun button(build: () -> ItemProvider, click: () -> Unit): AbstractItem {
			val item = object : AbstractItem() {
				override fun getItemProvider(player: Player): ItemProvider = build()

				override fun handleClick(clickType: ClickType, player: Player, click: Click) {
					click()
					storeData(FIXED_RANGE, fixedRange)
					redraw()
				}
			}

			buttons += item

			return item
		}

		override val gui: Gui = Gui.builder()
			.setStructure(
				"1 - - - - - - - 2",
				"| c c c c c c v |",
				"| c c c c c c v |",
				"| c c c c c c v |",
				"3 a q e - - - - 4",
			)
			.addIngredient('c', chart)
			.addIngredient('v', bar)
			// the range knob: auto-range by default, or a hand-pinned full
			// scale walked up and down the same 1-2-5 ladder
			.addIngredient('a', button({
				ItemBuilder(if (fixedRange > 0.0) org.bukkit.Material.REPEATER else org.bukkit.Material.COMPARATOR)
					.setName(
						if (fixedRange > 0.0) {
							"<yellow>Thang đo: <white>${fmt(fixedRange)}</white> (cố định)"
						} else {
							"<green>Thang đo: tự động</green> <gray>(hiện ${fmt(range)})"
						},
					)
			}) {
				fixedRange = if (fixedRange > 0.0) 0.0 else niceCeil(max(range, 1.0))
			})
			.addIngredient('q', button({
				ItemBuilder(org.bukkit.Material.RED_STAINED_GLASS_PANE).setName("<red>Giảm thang đo")
			}) {
				if (fixedRange > 0.0) {
					fixedRange = max(1.0, stepDown(fixedRange))
				}
			})
			.addIngredient('e', button({
				ItemBuilder(org.bukkit.Material.LIME_STAINED_GLASS_PANE).setName("<green>Tăng thang đo")
			}) {
				if (fixedRange > 0.0) {
					fixedRange = stepUp(fixedRange)
				}
			})
			.build()

		init {
			redraw()
		}

		fun redraw() {
			paintChart()
			paintBar()
			chart.notifyWindows()
			bar.notifyWindows()

			for (item in buttons) {
				item.notifyWindows()
			}
		}

		private fun paintChart() {
			val g = chartImage.createGraphics()

			g.color = INK
			g.fillRect(0, 0, CHART_W, CHART_H)

			if (signedDial) {
				g.color = GRID
				g.fillRect(0, CHART_H / 2, CHART_W, 1)
			}

			for (i in 0 until historySize) {
				val frac = history[(historyAt - historySize + i + HISTORY + HISTORY) % HISTORY]
				val x = CHART_W - historySize + i

				if (signedDial) {
					val signed = (frac - 0.5) * 2.0
					val h = (abs(signed) * (CHART_H / 2.0 - 1.0)).toInt()

					if (signed >= 0.0) {
						g.color = FILL_UP
						g.fillRect(x, CHART_H / 2 - h, 1, h)
					} else {
						g.color = FILL_DOWN
						g.fillRect(x, CHART_H / 2 + 1, 1, h)
					}
				} else {
					val h = (frac * (CHART_H - 1.0)).toInt()

					g.color = fillColour(frac)
					g.fillRect(x, CHART_H - h, 1, h)
				}
			}

			g.dispose()
		}

		private fun paintBar() {
			val g = barImage.createGraphics()

			g.color = INK
			g.fillRect(0, 0, 18, CHART_H)
			g.color = GRID
			g.drawRect(2, 0, 13, CHART_H - 1)

			val frac = if (historySize > 0) {
				history[(historyAt - 1 + HISTORY) % HISTORY]
			} else {
				0.0
			}

			if (signedDial) {
				val signed = (frac - 0.5) * 2.0
				val h = (abs(signed) * (CHART_H / 2.0 - 2.0)).toInt()

				g.color = GRID
				g.fillRect(3, CHART_H / 2, 12, 1)

				if (signed >= 0.0) {
					g.color = FILL_UP
					g.fillRect(3, CHART_H / 2 - h, 12, h)
				} else {
					g.color = FILL_DOWN
					g.fillRect(3, CHART_H / 2 + 1, 12, h)
				}
			} else {
				val h = (frac * (CHART_H - 2.0)).toInt()

				g.color = fillColour(frac)
				g.fillRect(3, CHART_H - 1 - h, 12, h)
			}

			g.dispose()
		}
	}

	private companion object {

		/** Key the totaliser's joules are stored under. */
		const val TOTAL = "total"

		/** Key the hand-pinned full scale is stored under; 0 = auto. */
		const val FIXED_RANGE = "fixedRange"

		/** Chart columns, one a second; the canvas is six slots by three. */
		const val HISTORY = 108
		const val CHART_W = 108
		const val CHART_H = 54

		/** How often and how stubbornly the wire audit repairs a dead link. */
		const val AUDIT_MS = 5_000L
		const val MAX_AUDIT_STRIKES = 3

		val INK = Color(0x10, 0x12, 0x16)
		val GRID = Color(0x3a, 0x3f, 0x4a)
		val FILL_UP = Color(0x3d, 0x8b, 0x40)
		val FILL_DOWN = Color(0xc2, 0x2f, 0x2a)
		val FILL_AMBER = Color(0xd9, 0xa5, 0x21)

		/** The load-zone palette: calm green, warning amber, pegged red. */
		fun fillColour(frac: Double): Color = when {
			frac >= 0.9 -> FILL_DOWN
			frac >= 0.72 -> FILL_AMBER
			else -> FILL_UP
		}

		/** How far the moving parts float in front of the painted face, in blocks. */
		const val NEEDLE_LIFT = 0.02
		const val TEXT_LIFT = 0.01

		/** The blade of a needle model, centre to tip, in blocks at scale one. */
		const val BLADE = 7.0 / 16.0

		/** The LED bar model: authored width and height, in blocks at scale one. */
		const val BAR_AUTHORED_W = 2.0 / 16.0
		const val BAR_AUTHORED_H = 8.0 / 16.0

		/** How wide the live bar is on the painted face, in face pixels. */
		const val BAR_WIDTH_PX = 8.0

		/** The LCD readout's green. */
		val LCD_GREEN: TextColor = TextColor.color(0x7C, 0xE0, 0x8A)

		/**
		 * The sign that turns a clockwise dial angle into a rotation about the
		 * display's local z axis. If a live needle ever runs the wrong way
		 * around in game, this is the one number to flip.
		 */
		const val SPIN = -1.0

		/** The smallest full scale each family of rate dial will tune down to. */
		const val FLOOR_ENERGY = 200.0
		const val FLOOR_ITEMS = 4.0
		const val FLOOR_FLUID = 100.0

		/** The one key a host-reading intake/output meter keeps its books under. */
		const val HOST_STOCK = "host"

		/**
		 * How far the water needle stands off the lava needle on a fluid
		 * dial, in blocks: a fifth of a pixel, which is more than the depth
		 * buffer needs and less than the eye notices as a gap.
		 */
		const val WATER_LAYER = 0.0125

		/** How far each of a fluid column's two bars sits off the slot centre, in face pixels. */
		const val TWIN_BAR_SHIFT = 1.5

		const val IDLE_PLATE = "---"
		const val PLUS_MINUS = "±"

		const val MAX_DASHBOARD = 32

		val FACES = listOf(
			BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH,
			BlockFace.WEST, BlockFace.UP, BlockFace.DOWN,
		)

		/** Everything a cable carries: the wired unit passes all of it through. */
		val WIRE_TYPES: Set<NetworkType<*>> = setOf(
			DefaultNetworkTypes.ENERGY,
			DefaultNetworkTypes.ITEM,
			DefaultNetworkTypes.FLUID,
		)

		val MM = MiniMessage.miniMessage()

		/** One 1-2-5 step up: 1 -> 2 -> 5 -> 10. */
		fun stepUp(value: Double): Double {
			val decade = 10.0.pow(floor(log10(value)))

			return when {
				value < decade * 1.5 -> decade * 2.0
				value < decade * 3.5 -> decade * 5.0
				else -> decade * 10.0
			}
		}

		/** One 1-2-5 step down: 10 -> 5 -> 2 -> 1. */
		fun stepDown(value: Double): Double {
			val decade = 10.0.pow(floor(log10(value)))

			return when {
				value > decade * 7.0 -> decade * 5.0
				value > decade * 3.5 -> decade * 2.0
				value > decade * 1.5 -> decade
				else -> decade / 2.0
			}
		}

		/** The 1-2-5 ladder every multimeter range knob climbs. */
		fun niceCeil(value: Double): Double {
			if (value <= 0.0) {
				return 1.0
			}

			val decade = 10.0.pow(floor(log10(value)))
			val mantissa = value / decade

			return when {
				mantissa <= 1.0 -> decade
				mantissa <= 2.0 -> 2.0 * decade
				mantissa <= 5.0 -> 5.0 * decade
				else -> 10.0 * decade
			}
		}

		/** Short SI formatting: 950, 2.5K, 13M. */
		fun fmt(value: Double): String {
			val magnitude = abs(value)

			return when {
				magnitude >= 1_000_000_000 -> "%.1fG".format(value / 1_000_000_000)
				magnitude >= 1_000_000 -> "%.1fM".format(value / 1_000_000)
				magnitude >= 1_000 -> "%.1fK".format(value / 1_000)
				else -> ceil(value).toLong().toString()
			}
		}

		fun signed(value: Double): String = if (value >= 0) "+${fmt(value)}" else fmt(value)

		fun percent(current: Long, capacity: Long): String =
			if (capacity <= 0) "0%" else "${(current * 100 / capacity)}%"

		/**
		 * North-authored face coordinates into world offsets. Nova's
		 * rotated() turns the model counterclockwise seen from above
		 * (WEST is +90), so a point (dx, dz) from the block centre maps to
		 * (dz, -dx) for west and (-dz, dx) for east. The first version had
		 * east and west swapped: every east- or west-facing instrument drew
		 * its overlays mirrored and up to twelve pixels off the face, while
		 * north and south looked perfect.
		 */
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
