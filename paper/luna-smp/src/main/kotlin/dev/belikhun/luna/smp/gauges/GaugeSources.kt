package dev.belikhun.luna.smp.gauges

import org.bukkit.block.Container
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.tileentity.NetworkedTileEntity
import xyz.xenondevs.nova.world.block.tileentity.network.NetworkManager
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkEndPoint
import xyz.xenondevs.nova.world.block.tileentity.network.type.DefaultNetworkTypes
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkConnectionType
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkType
import xyz.xenondevs.nova.world.block.tileentity.network.type.energy.holder.DefaultEnergyHolder
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.holder.FluidHolder
import xyz.xenondevs.nova.world.block.tileentity.network.type.item.holder.ItemHolder
import xyz.xenondevs.nova.world.block.tileentity.network.type.item.inventory.NetworkedInventory
import xyz.xenondevs.nova.world.format.WorldDataManager

/**
 * Where a gauge's numbers come from.
 *
 * A gauge is an observer, not a participant: it never joins a network, it
 * finds the networks whose nodes sit at its anchor positions and reads their
 * holders. Everything a dial can honestly show is here:
 *
 * - Stocks (stored energy, tank levels, chest fill) are read directly off the
 *   holders.
 * - Energy flux is read off the per-tick counters every [DefaultEnergyHolder]
 *   keeps: 'energyPlus' and 'energyMinus' hold the last completed tick's
 *   additions and removals, and read as zero when the machine has gone quiet.
 *   Every mutation feeds them - a generator filling its own buffer, the
 *   network draining it, a furnace burning its charge - so consumption is the
 *   sum of what consumer-side holders lost, production the sum of what
 *   producer-side holders gained, and battery flow the buffers' signed net.
 * - Fluid and item throughput has no such counter in Nova, so those flows are
 *   the sampled change of the stock: a net fill or drain rate. A pipe passing
 *   liquid straight through reads as zero, which is a physical truth about
 *   observing stocks rather than a bug to fix here.
 */
object GaugeSources {

	/** Everything the energy dials share, gathered in one sweep. Rates are per second. */
	data class EnergyStats(
		val stored: Long,
		val capacity: Long,
		val flow: Long,
		val load: Long,
		val gen: Long,
	)

	data class FluidStats(
		val amount: Long,
		val capacity: Long,
	)

	/**
	 * What one host block offers a multipurpose meter, in priority order:
	 * its energy holder if it has one, else its tanks, else its inventory.
	 */
	data class HostStats(
		val energy: DefaultEnergyHolder?,
		val fluid: FluidStats?,
		val itemCount: Long?,
		val itemCapacity: Long?,
	)

	/**
	 * Reads every energy network touching the anchors, merged.
	 *
	 * A holder's role is judged PER NETWORK, from the faces it exposes to
	 * that network - the same judgement EnergyNetwork itself makes - not
	 * from its declared allowedConnectionType. The difference is the one-way
	 * bridge: its single BUFFER-declared holder is this network's consumer
	 * through its inlet face and the next network's provider through its
	 * outlet, so a load gauge in a transit segment between two bridges reads
	 * the actual through-power (what leaves the downstream bridge's charge)
	 * instead of a hard zero, and a stored gauge on a consumer-only side no
	 * longer counts the valve's own buffer as a battery.
	 */
	fun energy(anchors: Collection<BlockPos>): EnergyStats? {
		var found = false
		var stored = 0L
		var capacity = 0L
		var flow = 0L
		var load = 0L
		var gen = 0L

		// a buffer's stock is counted once, even when two touched networks
		// both see it as a buffer
		val counted = HashSet<DefaultEnergyHolder>()

		for (network in NetworkManager.networks) {
			if (network.type != DefaultNetworkTypes.ENERGY) {
				continue
			}

			if (anchors.none { anchor -> network.nodes.containsKey(anchor) }) {
				continue
			}

			for (connection in network.nodes.values) {
				val endpoint = connection.node as? NetworkEndPoint ?: continue

				for (holder in endpoint.holders) {
					if (holder !is DefaultEnergyHolder) {
						continue
					}

					var insert = false
					var extract = false

					for (face in connection.faces) {
						val type = holder.connectionConfig[face] ?: continue

						insert = insert || type.insert
						extract = extract || type.extract
					}

					if (!insert && !extract) {
						continue
					}

					found = true

					when {
						// a battery of this network: level and charge rate
						insert && extract -> {
							if (counted.add(holder)) {
								stored += holder.energy
								capacity = saturating(capacity, holder.maxEnergy)
								flow += holder.energyPlus - holder.energyMinus
							}
						}

						// this network's consumer: what left its charge
						insert -> load += holder.energyMinus

						// this network's provider: what entered its charge
						else -> gen += holder.energyPlus
					}
				}
			}
		}

		if (!found) {
			return null
		}

		return EnergyStats(stored, capacity, flow * 20, load * 20, gen * 20)
	}

	/** Reads every fluid network touching the anchors, merged. */
	fun fluid(anchors: Collection<BlockPos>): FluidStats? {
		val containers = holdersAt(anchors, DefaultNetworkTypes.FLUID) { endpoint ->
			endpoint.holders.filterIsInstance<FluidHolder>().flatMap { it.containers.keys }
		}

		if (containers.isEmpty()) {
			return null
		}

		return FluidStats(
			containers.sumOf { it.amount },
			containers.fold(0L) { total, container -> saturating(total, container.capacity) },
		)
	}

	data class ItemStats(
		val count: Long,
		val capacity: Long,
	)

	/**
	 * Counts the item networks' stock against their room: every inventory's
	 * items, and its slot count at a stack of 64 apiece - the same yardstick
	 * the multipurpose meter holds a single chest to.
	 */
	fun itemsStored(anchors: Collection<BlockPos>): ItemStats? {
		val inventories = holdersAt(anchors, DefaultNetworkTypes.ITEM) { endpoint ->
			endpoint.holders.filterIsInstance<ItemHolder>().flatMap { it.containers.keys }
		}

		if (inventories.isEmpty()) {
			return null
		}

		return ItemStats(
			inventories.sumOf { countOf(it) },
			inventories.fold(0L) { total, inventory -> saturating(total, inventory.size * 64L) },
		)
	}

	/** Counts every item sitting in the item networks touching the anchors. */
	fun items(anchors: Collection<BlockPos>): Long? {
		val inventories = holdersAt(anchors, DefaultNetworkTypes.ITEM) { endpoint ->
			endpoint.holders.filterIsInstance<ItemHolder>().flatMap { it.containers.keys }
		}

		if (inventories.isEmpty()) {
			return null
		}

		return inventories.sumOf { countOf(it) }
	}

	/**
	 * What the multipurpose probe block reads: every block in a set of
	 * positions, merged. Energy is the sum over every holder the neighbours
	 * carry - a machine's internal charge is a stock as honestly as a
	 * battery's - and the flow is their net gain per second.
	 */
	data class ProbeStats(
		val energyStored: Long,
		val energyCapacity: Long,
		val energyFlow: Long,
		val hasEnergy: Boolean,
		val fluid: FluidStats?,
		val itemCount: Long?,
		val itemCapacity: Long?,
	)

	/** Merges [host] readings over several positions; null when none reads. */
	fun probe(positions: Collection<BlockPos>): ProbeStats? {
		var energyStored = 0L
		var energyCapacity = 0L
		var energyFlux = 0L
		var hasEnergy = false

		var fluidAmount = 0L
		var fluidCapacity = 0L
		var hasFluid = false

		var itemCount = 0L
		var itemCapacity = 0L
		var hasItems = false

		for (pos in positions) {
			val tile = WorldDataManager.getTileEntity(pos)

			if (tile is NetworkedTileEntity) {
				for (holder in tile.holders.filterIsInstance<DefaultEnergyHolder>()) {
					hasEnergy = true
					energyStored += holder.energy
					energyCapacity = saturating(energyCapacity, holder.maxEnergy)
					energyFlux += holder.energyPlus - holder.energyMinus
				}

				for (container in tile.holders.filterIsInstance<FluidHolder>().flatMap { it.containers.keys }.toSet()) {
					hasFluid = true
					fluidAmount += container.amount
					fluidCapacity = saturating(fluidCapacity, container.capacity)
				}

				for (inventory in tile.holders.filterIsInstance<ItemHolder>().flatMap { it.containers.keys }.toSet()) {
					hasItems = true
					itemCount += countOf(inventory)
					itemCapacity = saturating(itemCapacity, inventory.size * 64L)
				}

				continue
			}

			val state = pos.block.state as? Container ?: continue

			hasItems = true

			for (stack in state.inventory.contents) {
				if (stack == null || stack.type.isAir) {
					itemCapacity += 64L
					continue
				}

				itemCount += stack.amount
				itemCapacity += stack.maxStackSize.toLong()
			}
		}

		if (!hasEnergy && !hasFluid && !hasItems) {
			return null
		}

		return ProbeStats(
			energyStored,
			energyCapacity,
			energyFlux * 20,
			hasEnergy,
			if (hasFluid) FluidStats(fluidAmount, fluidCapacity) else null,
			if (hasItems) itemCount else null,
			if (hasItems) itemCapacity else null,
		)
	}

	/** What the block behind a multipurpose meter offers it, if anything. */
	fun host(pos: BlockPos): HostStats? {
		val tile = WorldDataManager.getTileEntity(pos)

		if (tile is NetworkedTileEntity) {
			val energy = tile.holders.filterIsInstance<DefaultEnergyHolder>().firstOrNull()

			val containers = tile.holders
				.filterIsInstance<FluidHolder>()
				.flatMap { it.containers.keys }
				.toSet()

			val fluid = if (containers.isEmpty()) {
				null
			} else {
				FluidStats(
					containers.sumOf { it.amount },
					containers.fold(0L) { total, container -> saturating(total, container.capacity) },
				)
			}

			val inventories = tile.holders
				.filterIsInstance<ItemHolder>()
				.flatMap { it.containers.keys }
				.toSet()

			val count = if (inventories.isEmpty()) null else inventories.sumOf { countOf(it) }
			val itemCapacity = if (inventories.isEmpty()) null else inventories.sumOf { it.size * 64L }

			if (energy == null && fluid == null && count == null) {
				return null
			}

			return HostStats(energy, fluid, count, itemCapacity)
		}

		// a plain chest, barrel or furnace: still worth a fill meter
		val state = pos.block.state as? Container ?: return null
		val inventory = state.inventory

		var count = 0L
		var capacity = 0L

		for (stack in inventory.contents) {
			if (stack == null || stack.type.isAir) {
				capacity += 64L
				continue
			}

			count += stack.amount
			capacity += stack.maxStackSize.toLong()
		}

		return HostStats(null, null, count, capacity)
	}

	/**
	 * Collects holders from every network of one type whose graph includes any
	 * of the anchor positions. The set keeps a holder that shows up through
	 * two anchors from being counted twice.
	 */
	private fun <H> holdersAt(
		anchors: Collection<BlockPos>,
		type: NetworkType<*>,
		pick: (NetworkEndPoint) -> Collection<H>,
	): Set<H> {
		val out = LinkedHashSet<H>()

		for (network in NetworkManager.networks) {
			if (network.type != type) {
				continue
			}

			if (anchors.none { anchor -> network.nodes.containsKey(anchor) }) {
				continue
			}

			for (connection in network.nodes.values) {
				val endpoint = connection.node as? NetworkEndPoint ?: continue

				out += pick(endpoint)
			}
		}

		return out
	}

	/** Every item in one networked inventory, by copy: the only read it offers. */
	private fun countOf(inventory: NetworkedInventory): Long {
		val contents = arrayOfNulls<ItemStack>(inventory.size)

		@Suppress("UNCHECKED_CAST")
		inventory.copyContents(contents as Array<ItemStack>)

		var total = 0L

		for (stack in contents) {
			if (stack != null && !stack.type.isAir) {
				total += stack.amount
			}
		}

		return total
	}

	/**
	 * Draws [amount] joules out of the energy networks whose node map holds
	 * [pos], and returns how much was actually taken. Extraction is gated per
	 * FACE: a holder is only drained through a face it exposes to that
	 * network with extraction allowed, so a one-way bridge's inlet can never
	 * be sucked backwards. The light panel and the siren both feed here.
	 */
	fun drainEnergy(pos: BlockPos, amount: Long): Long {
		var remaining = amount

		for (network in NetworkManager.networks) {
			if (remaining <= 0L) {
				break
			}

			if (network.type != DefaultNetworkTypes.ENERGY || !network.nodes.containsKey(pos)) {
				continue
			}

			for (connection in network.nodes.values) {
				if (remaining <= 0L) {
					break
				}

				val endpoint = connection.node as? NetworkEndPoint ?: continue

				for (holder in endpoint.holders) {
					if (holder !is DefaultEnergyHolder) {
						continue
					}

					val extractable = connection.faces.any { face ->
						holder.connectionConfig[face]?.extract == true
					}

					if (!extractable) {
						continue
					}

					val take = minOf(remaining, holder.energy)

					if (take > 0L) {
						holder.energy -= take
						remaining -= take
					}

					if (remaining <= 0L) {
						break
					}
				}
			}
		}

		return amount - remaining
	}

	/**
	 * Draws [amount] joules straight out of the charge of the machine at
	 * [pos], ignoring face rules, and returns how much was actually taken. A
	 * panel screwed onto a machine taps the device it is bolted to, not the
	 * network's extraction etiquette - a furnace exposes no extract face at
	 * all, yet its own charge is exactly what should pay for its own panel.
	 */
	fun drainEnergyAt(pos: BlockPos, amount: Long): Long {
		val tile = WorldDataManager.getTileEntity(pos) as? NetworkedTileEntity ?: return 0L

		var remaining = amount

		for (holder in tile.holders.filterIsInstance<DefaultEnergyHolder>()) {
			val take = minOf(remaining, holder.energy)

			if (take > 0L) {
				holder.energy -= take
				remaining -= take
			}

			if (remaining <= 0L) {
				break
			}
		}

		return amount - remaining
	}

	// ---- the shared range ladder -------------------------------------------

	/** The 1-2-5 ladder every range and limit knob climbs. */
	fun niceCeil(value: Double): Double {
		if (value <= 0.0) {
			return 1.0
		}

		val decade = Math.pow(10.0, Math.floor(Math.log10(value)))
		val mantissa = value / decade

		return when {
			mantissa <= 1.0 -> decade
			mantissa <= 2.0 -> 2.0 * decade
			mantissa <= 5.0 -> 5.0 * decade
			else -> 10.0 * decade
		}
	}

	/** One 1-2-5 step up: 1 -> 2 -> 5 -> 10. */
	fun stepUp(value: Double): Double {
		val decade = Math.pow(10.0, Math.floor(Math.log10(value)))

		return when {
			value < decade * 1.5 -> decade * 2.0
			value < decade * 3.5 -> decade * 5.0
			else -> decade * 10.0
		}
	}

	/** One 1-2-5 step down: 10 -> 5 -> 2 -> 1. */
	fun stepDown(value: Double): Double {
		val decade = Math.pow(10.0, Math.floor(Math.log10(value)))

		return when {
			value > decade * 7.0 -> decade * 5.0
			value > decade * 3.5 -> decade * 2.0
			value > decade * 1.5 -> decade
			else -> decade / 2.0
		}
	}

	/** Short SI formatting for readouts and knob labels: 950, 2.5K, 13M. */
	fun fmt(value: Double): String {
		val magnitude = Math.abs(value)

		return when {
			magnitude >= 1_000_000_000 -> "%.1fG".format(value / 1_000_000_000)
			magnitude >= 1_000_000 -> "%.1fM".format(value / 1_000_000)
			magnitude >= 1_000 -> "%.1fK".format(value / 1_000)
			else -> Math.ceil(value).toLong().toString()
		}
	}

	/** Adds capacities without letting a creative tank wrap the total. */
	private fun saturating(total: Long, addition: Long): Long {
		val sum = total + addition

		return if (sum < total) Long.MAX_VALUE else sum
	}
}
