package dev.belikhun.luna.smp.gauges

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.commons.collections.enumMap
import xyz.xenondevs.commons.provider.mutableProvider
import xyz.xenondevs.invui.Click
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.inventory.event.ItemPostUpdateEvent
import xyz.xenondevs.invui.item.AbstractItem
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.invui.item.ItemProvider
import xyz.xenondevs.nova.ui.menu.EnergyBar
import xyz.xenondevs.nova.ui.menu.FluidBar
import xyz.xenondevs.nova.ui.menu.addIngredient
import xyz.xenondevs.nova.util.CUBE_FACES
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.block.tileentity.NetworkedTileEntity
import xyz.xenondevs.nova.world.block.tileentity.menu.TileEntityMenuClass
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkConnectionType
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.FluidType
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.container.NetworkedFluidContainer
import java.util.EnumMap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The one-way bridge: a check valve for everything a cable carries, with an
 * adjustable throughput per type.
 *
 * It is deliberately NOT a network bridge. A bridge joins its two sides into
 * one network; this block is an endpoint with a face on each of two networks,
 * which is what keeps them two networks.
 *
 * Energy stays one buffer, inlet INSERT and outlet EXTRACT, and its
 * throughput knob is the buffer's own capacity: the networks can each turn
 * the buffer over at most once per tick, so capacity = limit/20 IS the limit,
 * with no counter pollution and nothing moving twice.
 *
 * Items and fluid are two stages: an inlet inventory/tank only the input
 * network can fill, an outlet stage only the output network can drain, and
 * the tile itself pumps between them - up to the set rate, or as much as
 * fits when unlimited. What the pump moves is the honest throughput total
 * the attached gauges differentiate.
 *
 * Orientation: the FACING face (towards whoever placed it) is the inlet; the
 * flow arrows on the housing point out of the block's back.
 */
class NetworkDiodeTile(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : NetworkedTileEntity(pos, blockState, data) {

	private val facing: BlockFace
		get() = blockState[DefaultBlockStateProperties.FACING] ?: BlockFace.NORTH

	/** Things come in where the placer stood and leave out of the back. */
	val inputFace: BlockFace
		get() = facing

	val outputFace: BlockFace
		get() = facing.oppositeFace

	/** The four faces across the flow, dead to every network. */
	private val sideFaces: Set<BlockFace> =
		CUBE_FACES.filterTo(HashSet()) { it != inputFace && it != outputFace }

	// ---- the operator's throughput limits, 0 = unlimited ---------------------

	private var limitEnergy = 0L
	private var limitItems = 0L
	private var limitFluid = 0L

	/** The pumps' fractional budgets, so a limit of 3/s still moves 3 a second. */
	private var itemBudget = 0.0
	private var fluidBudget = 0.0

	// ---- what has crossed the bridge, monotonic ----------------------------

	/** Joules that left through the outlet, ever. */
	var movedEnergy = 0.0
		private set

	/** Items the pump carried across, ever. */
	var movedItems = 0.0
		private set

	/** Millibuckets the pump carried across, ever. */
	var movedFluid = 0.0
		private set

	// ---- the stages ---------------------------------------------------------

	/** The energy buffer's live capacity: the throughput knob wears it down. */
	private val energyCapacity = mutableProvider(ENERGY_BUFFER)

	private val energyHolder = storedEnergyHolder(
		energyCapacity,
		NetworkConnectionType.BUFFER,
		blockedFaces = sideFaces,
		defaultConnectionConfig = ::connectionConfig,
	)

	private val inputInventory = storedInventory("input", 9)
	private val outputInventory = storedInventory("output", 9, postUpdateHandler = ::handleOutputUpdate)

	private val itemHolder = storedItemHolder(
		inputInventory to NetworkConnectionType.INSERT,
		outputInventory to NetworkConnectionType.EXTRACT,
		blockedFaces = sideFaces,
		defaultInventoryConfig = {
			mapOf(
				inputFace to inputInventory,
				outputFace to outputInventory,
			)
		},
		defaultConnectionConfig = ::connectionConfig,
	)

	private val inputTank = storedFluidContainer(
		"tank_in",
		setOf(FluidType.WATER, FluidType.LAVA),
		xyz.xenondevs.commons.provider.provider(FLUID_BUFFER),
	)

	private val outputTank = storedFluidContainer(
		"tank_out",
		setOf(FluidType.WATER, FluidType.LAVA),
		xyz.xenondevs.commons.provider.provider(FLUID_BUFFER),
		updateHandler = ::handleOutputTankUpdate,
	)

	private val fluidHolder = storedFluidHolder(
		inputTank to NetworkConnectionType.INSERT,
		outputTank to NetworkConnectionType.EXTRACT,
		blockedFaces = sideFaces,
		defaultContainerConfig = {
			val containers = EnumMap<BlockFace, NetworkedFluidContainer>(BlockFace::class.java)

			containers[inputFace] = inputTank
			containers[outputFace] = outputTank
			containers
		},
		defaultConnectionConfig = ::connectionConfig,
	)

	private var lastOutputFluid = 0L

	/** Inlet swallows, outlet yields, nothing else exists. */
	private fun connectionConfig(): EnumMap<BlockFace, NetworkConnectionType> =
		CUBE_FACES.associateWithTo(enumMap()) { face ->
			when (face) {
				inputFace -> NetworkConnectionType.INSERT
				outputFace -> NetworkConnectionType.EXTRACT
				else -> NetworkConnectionType.NONE
			}
		}

	// ---- lifecycle -----------------------------------------------------------

	override fun handleEnable() {
		super.handleEnable()

		movedEnergy = retrieveDataOrNull<Double>(MOVED_ENERGY) ?: 0.0
		movedItems = retrieveDataOrNull<Double>(MOVED_ITEMS) ?: 0.0
		movedFluid = retrieveDataOrNull<Double>(MOVED_FLUID) ?: 0.0
		limitEnergy = retrieveDataOrNull<Long>(LIMIT_ENERGY) ?: 0L
		limitItems = retrieveDataOrNull<Long>(LIMIT_ITEMS) ?: 0L
		limitFluid = retrieveDataOrNull<Long>(LIMIT_FLUID) ?: 0L
		lastOutputFluid = outputTank.amount

		applyEnergyLimit()
	}

	override fun handleDisable() {
		super.handleDisable()

		storeData(MOVED_ENERGY, movedEnergy)
		storeData(MOVED_ITEMS, movedItems)
		storeData(MOVED_FLUID, movedFluid)
		storeData(LIMIT_ENERGY, limitEnergy)
		storeData(LIMIT_ITEMS, limitItems)
		storeData(LIMIT_FLUID, limitFluid)
	}

	override fun handleTick() {
		// energyMinus is the last completed tick's outflow; read every tick,
		// each tick's sum lands here exactly once
		movedEnergy += energyHolder.energyMinus

		pumpItems()
		pumpFluid()
	}

	// ---- the throughput knobs --------------------------------------------------

	/**
	 * The energy limit IS the buffer's capacity: each network can turn the
	 * buffer over at most once per tick, so capacity = limit/20 caps the
	 * sustained flow at the limit without touching the flux counters.
	 */
	private fun applyEnergyLimit() {
		val capacity = if (limitEnergy > 0L) {
			max(limitEnergy / 20L, 1L)
		} else {
			ENERGY_BUFFER
		}

		energyCapacity.set(capacity)

		if (energyHolder.energy > capacity) {
			energyHolder.energy = capacity
		}
	}

	private fun stepLimit(current: Long, floor: Long, up: Boolean): Long {
		if (up) {
			// stepping up from unlimited wraps to the smallest limit
			if (current <= 0L) {
				return floor
			}

			val stepped = GaugeSources.stepUp(current.toDouble()).toLong()

			// past the top of the ladder, the knob opens fully again
			return if (stepped > LIMIT_CEILING) 0L else stepped
		}

		if (current <= 0L) {
			return LIMIT_CEILING
		}

		val stepped = GaugeSources.stepDown(current.toDouble()).toLong()

		return if (stepped < floor) 0L else stepped
	}

	// ---- the pumps ---------------------------------------------------------------

	/** Carries items inlet -> outlet, up to the budget, and counts them. */
	private fun pumpItems() {
		val budget = if (limitItems > 0L) {
			itemBudget = min(itemBudget + limitItems / 20.0, limitItems.toDouble())
			floor(itemBudget).toInt()
		} else {
			Int.MAX_VALUE
		}

		if (budget <= 0) {
			return
		}

		var moved = 0

		for (slot in 0 until inputInventory.size) {
			if (moved >= budget) {
				break
			}

			val stack = inputInventory.getItem(slot) ?: continue
			val allowance = min(stack.amount, budget - moved)
			val carry = stack.clone().apply { amount = allowance }
			val leftover = outputInventory.addItem(SELF_UPDATE_REASON, carry)
			val transferred = allowance - leftover

			if (transferred <= 0) {
				continue
			}

			inputInventory.addItemAmount(SELF_UPDATE_REASON, slot, -transferred)
			moved += transferred
		}

		if (moved > 0) {
			movedItems += moved.toDouble()

			if (limitItems > 0L) {
				itemBudget -= moved.toDouble()
			}
		}
	}

	/** Carries fluid inlet -> outlet, up to the budget, and counts it. */
	private fun pumpFluid() {
		val type = inputTank.type ?: return

		if (!outputTank.accepts(type)) {
			return
		}

		val budget = if (limitFluid > 0L) {
			fluidBudget = min(fluidBudget + limitFluid / 20.0, limitFluid.toDouble())
			floor(fluidBudget).toLong()
		} else {
			Long.MAX_VALUE
		}

		val amount = minOf(budget, inputTank.amount, outputTank.capacity - outputTank.amount)

		if (amount <= 0L) {
			return
		}

		inputTank.takeFluid(amount)
		outputTank.addFluid(type, amount)
		movedFluid += amount.toDouble()

		if (limitFluid > 0L) {
			fluidBudget -= amount.toDouble()
		}
	}

	// ---- the outlet counters ---------------------------------------------------

	private fun handleOutputUpdate(event: ItemPostUpdateEvent) {
		// the pump's own writes carry SELF_UPDATE_REASON; only what the
		// output network (or a hand) takes counts as nothing here - the
		// throughput total is the pump's, counted where the move happens
	}

	private fun handleOutputTankUpdate() {
		lastOutputFluid = outputTank.amount
	}

	// ---- readings for the gauges -------------------------------------------------

	/** How congested the crossing is: the energy buffer's fill. */
	fun energyLevel(): Pair<Long, Long> = energyHolder.energy to energyHolder.maxEnergy

	fun fluidLevel(): Pair<Long, Long> =
		(inputTank.amount + outputTank.amount) to (inputTank.capacity + outputTank.capacity)

	fun itemLevel(): Pair<Long, Long> {
		var count = 0L

		for (inventory in listOf(inputInventory, outputInventory)) {
			for (stack in inventory.items) {
				if (stack != null) {
					count += stack.amount
				}
			}
		}

		return count to (inputInventory.size + outputInventory.size) * 64L
	}

	// ---- the window ---------------------------------------------------------------

	/**
	 * Both stages laid open: inlet slots left, outlet right, the energy level
	 * and inlet tank between them, and one throughput knob per type down the
	 * middle. No side configuration button on purpose - the sides ARE the
	 * device.
	 */
	@TileEntityMenuClass
	inner class DiodeMenu : GlobalTileEntityMenu() {

		private val knobs = mutableListOf<AbstractItem>()

		private fun knob(build: () -> ItemProvider, click: (ClickType) -> Unit): AbstractItem {
			val item = object : AbstractItem() {
				override fun getItemProvider(player: Player): ItemProvider = build()

				override fun handleClick(clickType: ClickType, player: Player, click: Click) {
					click(clickType)
					storeData(LIMIT_ENERGY, limitEnergy)
					storeData(LIMIT_ITEMS, limitItems)
					storeData(LIMIT_FLUID, limitFluid)

					for (each in knobs) {
						each.notifyWindows()
					}
				}
			}

			knobs += item

			return item
		}

		private fun limitName(label: String, value: Long, unit: String): String = if (value > 0L) {
			"<yellow>$label: <white>${GaugeSources.fmt(value.toDouble())} $unit</white>"
		} else {
			"<green>$label: không giới hạn</green>"
		}

		private val limitLore = listOf(
			Component.text("Bấm: tăng · Chuột phải: giảm", NamedTextColor.GRAY),
			Component.text("Shift: mở hết cỡ", NamedTextColor.GRAY),
		)

		override val gui: Gui = Gui.builder()
			.setStructure(
				"1 - - - - - - - 2",
				"i i i e f q o o o",
				"i i i e f w o o o",
				"i i i e f r o o o",
				"3 - - - - - - - 4",
			)
			.addIngredient('i', inputInventory)
			.addIngredient('o', outputInventory)
			.addIngredient('e', EnergyBar(3, energyHolder))
			.addIngredient('f', FluidBar(3, fluidHolder, inputTank))
			.addIngredient('q', knob({
				ItemBuilder(Material.REDSTONE).setName(limitName("Điện", limitEnergy, "J/s")).setLore(limitLore)
			}) { clickType ->
				limitEnergy = when {
					clickType.isShiftClick -> 0L
					clickType.isRightClick -> stepLimit(limitEnergy, FLOOR_ENERGY_LIMIT, up = false)
					else -> stepLimit(limitEnergy, FLOOR_ENERGY_LIMIT, up = true)
				}

				applyEnergyLimit()
			})
			.addIngredient('w', knob({
				ItemBuilder(Material.HOPPER).setName(limitName("Vật phẩm", limitItems, "/s")).setLore(limitLore)
			}) { clickType ->
				limitItems = when {
					clickType.isShiftClick -> 0L
					clickType.isRightClick -> stepLimit(limitItems, FLOOR_ITEM_LIMIT, up = false)
					else -> stepLimit(limitItems, FLOOR_ITEM_LIMIT, up = true)
				}

				itemBudget = 0.0
			})
			.addIngredient('r', knob({
				ItemBuilder(Material.WATER_BUCKET).setName(limitName("Chất lỏng", limitFluid, "mB/s")).setLore(limitLore)
			}) { clickType ->
				limitFluid = when {
					clickType.isShiftClick -> 0L
					clickType.isRightClick -> stepLimit(limitFluid, FLOOR_FLUID_LIMIT, up = false)
					else -> stepLimit(limitFluid, FLOOR_FLUID_LIMIT, up = true)
				}

				fluidBudget = 0.0
			})
			.build()
	}

	private companion object {

		/**
		 * Stage sizes: small enough that a dead outlet never hoards a base's
		 * production, large enough that the crossing is never the bottleneck
		 * when the knob is wide open.
		 */
		const val ENERGY_BUFFER = 25_000L
		const val FLUID_BUFFER = 8_000L

		/** Where each knob's ladder bottoms out; below it comes unlimited. */
		const val FLOOR_ENERGY_LIMIT = 20L
		const val FLOOR_ITEM_LIMIT = 1L
		const val FLOOR_FLUID_LIMIT = 10L

		/** Where each ladder tops out; above it the knob opens fully. */
		const val LIMIT_CEILING = 1_000_000_000L

		const val MOVED_ENERGY = "movedEnergy"
		const val MOVED_ITEMS = "movedItems"
		const val MOVED_FLUID = "movedFluid"
		const val LIMIT_ENERGY = "limitEnergy"
		const val LIMIT_ITEMS = "limitItems"
		const val LIMIT_FLUID = "limitFluid"
	}
}
