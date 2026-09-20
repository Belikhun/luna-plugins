package dev.belikhun.luna.smp.power

import dev.belikhun.luna.smp.gauges.GaugeCatalog
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.commons.provider.provider
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.item.Item
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.nova.addon.simpleupgrades.gui.OpenUpgradesItem
import xyz.xenondevs.nova.addon.simpleupgrades.registry.UpgradeTypes
import xyz.xenondevs.nova.addon.simpleupgrades.storedEnergyHolder
import xyz.xenondevs.nova.addon.simpleupgrades.storedRegion
import xyz.xenondevs.nova.addon.simpleupgrades.storedUpgradeHolder
import xyz.xenondevs.nova.ui.menu.EnergyBar
import xyz.xenondevs.nova.ui.menu.sideconfig.OpenSideConfigItem
import xyz.xenondevs.nova.ui.menu.sideconfig.SideConfigMenu
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.util.serverTick
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.NetworkedTileEntity
import xyz.xenondevs.nova.world.block.tileentity.menu.TileEntityMenuClass
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkConnectionType
import xyz.xenondevs.nova.world.region.Region
import xyz.xenondevs.nova.world.region.VisualRegion
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * The wireless power node: a store of energy that pays for every wireless
 * fixture inside a cube around it.
 *
 * It is a consumer to the network and nothing else. Its charge is its own,
 * kept for the lamps it feeds.
 *
 * A BUFFER holder was tried instead, so that the node would double as a
 * battery the rest of the grid could draw on, and it was reverted: a holder
 * that is both provider and consumer on one network gets balanced against
 * the grid's other stores every tick, so the node sat there charging and
 * discharging in a loop - thousands of joules a tick of churn going nowhere,
 * plainly visible on its own energy bar. A node stores for its own need.
 *
 * It spends EVERY TICK, the way a furnace burns: each tick every fixture in
 * reach costs a twentieth of its per-second draw, so the load a meter reads
 * off the node is flat rather than a once-a-second spike. Since a twentieth
 * of a draw is rarely a whole number, the fraction is carried between ticks
 * (see [carry]) and a joule is taken whenever it adds up to one, which makes
 * the consumption exact over any second rather than merely close.
 *
 * A fixture it could not pay for goes dark on its own a moment later, so a
 * browning-out grid dims its lights rather than stalling.
 *
 * Reach is not free. Every tick the node first pays to radiate its field,
 * [LampCatalog.FIELD_DRAW_PER_BLOCK] a second for every block of its current
 * radius, and only then pays its fixtures; a node that cannot keep the field
 * up lights nothing that tick. So consumption climbs linearly with range,
 * whether or not the extra reach has a lamp in it, which is what makes the
 * reach knob in the menu worth turning down.
 *
 * The three Simple Upgrades it takes are the three numbers that matter:
 * RANGE widens the cube [LampCatalog.RANGE_STEP] blocks a side per upgrade
 * (the node ships its own range ladder in its config, which the upgrades
 * GUI shows), ENERGY multiplies the buffer, and EFFICIENCY divides both the
 * field cost and what each fixture costs. The reach can also be pulled in
 * below its maximum from the menu, so two nodes can share a building without
 * both paying for the lamps in the middle of it.
 */
class WirelessNodeTile(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : NetworkedTileEntity(pos, blockState, data) {

	private val upgradeHolder = storedUpgradeHolder(UpgradeTypes.ENERGY, UpgradeTypes.EFFICIENCY, UpgradeTypes.RANGE)

	/**
	 * INSERT on every face: the network fills it, and cannot take it back.
	 *
	 * The connection type is what decides the role the energy network gives
	 * a holder per face, so this one word is the difference between a store
	 * of its own and a battery the grid balances against - and the latter
	 * oscillates, which is why this is INSERT. See the class comment.
	 */
	private val energyHolder = storedEnergyHolder(
		provider(LampCatalog.NODE_CAPACITY),
		upgradeHolder,
		NetworkConnectionType.INSERT,
	)

	private val region = storedRegion(
		"region",
		provider(MIN_RADIUS),
		provider(LampCatalog.NODE_RADIUS),
		LampCatalog.NODE_RADIUS,
		upgradeHolder,
	) { radius -> Region.surrounding(pos, radius) }

	/**
	 * The fixtures in reach, re-found once a second rather than every tick.
	 *
	 * Fixtures do not move, so rescanning at the paying clock would be the
	 * same answer twenty times over; the charger in Nova's own Machines
	 * addon caches its players the same way. One placed mid-second lights up
	 * on the next scan.
	 */
	private var reach: List<WirelessLampTile> = emptyList()
	private var rescan = 0

	/**
	 * Joules already taken out of the store and not yet spent on a fixture.
	 *
	 * A fixture's per-tick cost is its draw over twenty, divided by the
	 * efficiency upgrade, so it is almost never a whole number - and joules
	 * are whole. The node therefore BUYS whole joules out of its store and
	 * spends them in fractions: a fixture is fed only when this pool covers
	 * its cost, and the pool is topped up beforehand if it does not.
	 *
	 * Buying first is what makes an empty node go dark. Deducting a rounded
	 * cost instead lets a fixture whose fractional cost rounds down to zero
	 * be fed for nothing, and since a fixture stays lit for two seconds on
	 * one payment, a node with no charge at all would have kept a whole room
	 * lit indefinitely at high efficiency.
	 */
	private var pool = 0.0

	/** What the last second looked like, for the menu. */
	private var inReach = 0
	private var fed = 0
	private var starved = 0
	private var spent = 0L

	/** Whether the field was paid for on the last tick. */
	private var radiating = false

	/**
	 * Which lamp channels this node has switched off, as a bit per channel.
	 *
	 * A light switch inside the reach flips a bit here; a fixture on a
	 * channel that is off is simply not paid, so it goes dark within its
	 * grace and costs nothing while it waits. The set is the node's, not the
	 * switch's, because it is the node that decides who gets paid - and a
	 * room with two nodes reaching into it has both flipped by one switch.
	 */
	private var channelsOff = 0

	/** Whether [channel] is on for the fixtures this node feeds. */
	fun channelOn(channel: Int): Boolean = channelsOff and (1 shl channel) == 0

	/** Flips [channel]; returns its new state. */
	fun toggleChannel(channel: Int): Boolean {
		channelsOff = channelsOff xor (1 shl channel)
		storeData(CHANNELS_OFF, channelsOff)

		return channelOn(channel)
	}

	/** Whether the reach covers a point; the switch asks this. */
	fun covers(location: org.bukkit.Location): Boolean = region.contains(location)

	/** The store's charge and capacity, for a fixture's menu to show who pays for it. */
	fun storedEnergy(): Long = energyHolder.energy

	fun storedCapacity(): Long = energyHolder.maxEnergy

	/** This second's joules so far, and how far into the second we are. */
	private var spending = 0L
	private var window = 0

	private val on: Boolean
		get() = blockState[GaugeCatalog.ON] == true

	override fun handleEnable() {
		super.handleEnable()

		channelsOff = retrieveDataOrNull<Int>(CHANNELS_OFF) ?: 0
		WirelessNodes.register(this)
		glow()
	}

	override fun handleDisable() {
		super.handleDisable()

		WirelessNodes.unregister(this)
		VisualRegion.removeRegion(uuid)
	}

	override fun handleBreak(ctx: xyz.xenondevs.nova.context.Context<xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockBreak>) {
		super.handleBreak(ctx)

		WirelessNodes.unregister(this)
	}

	override fun handleTick() {
		if (--rescan <= 0) {
			rescan = TICKS_PER_SECOND
			reach = WirelessLamps.within(region)
		}

		val tick = serverTick
		val efficiency = upgradeHolder.getValue(UpgradeTypes.EFFICIENCY)

		// the field comes first: reach is paid for before any fixture is,
		// and a node that cannot hold its field up lights nothing this tick
		radiating = buy(LampCatalog.FIELD_DRAW_PER_BLOCK * region.size / (TICKS_PER_SECOND * efficiency))

		var fedCount = 0
		var starvedCount = 0

		if (!radiating) {
			starvedCount = reach.size
		}

		for (lamp in reach) {
			if (!radiating) {
				break
			}

			// a switched-off channel is not starved, it is off: nothing is
			// paid and nothing is counted against the node
			if (!channelOn(lamp.channel)) {
				continue
			}

			// the scan is a second old, so a fixture in it may have been
			// broken since; and another node may have paid for this one
			// already on this very tick
			if (!lamp.isEnabled) {
				continue
			}

			if (lamp.isFresh(tick)) {
				fedCount++
				continue
			}

			if (!buy(lamp.draw / (TICKS_PER_SECOND * efficiency))) {
				starvedCount++
				continue
			}

			lamp.feed(pos, tick)
			fedCount++
		}

		inReach = reach.size
		fed = fedCount
		starved = starvedCount

		// the menu reads joules a second, which is a measurement rather than
		// a multiplication: it is what was actually taken over twenty ticks
		if (++window >= TICKS_PER_SECOND) {
			spent = spending
			spending = 0L
			window = 0
		}

		val charged = energyHolder.energy > 0L

		if (charged != on) {
			updateBlockState(blockState.with(GaugeCatalog.ON, charged))
			glow()
		}
	}

	/**
	 * Pays [cost] joules out of the pool, buying whole joules from the store
	 * first when the pool is short. False, and nothing taken, when the store
	 * cannot cover the purchase - the caller then goes without.
	 */
	private fun buy(cost: Double): Boolean {
		if (pool < cost) {
			val whole = ceil(cost - pool).toLong()

			if (energyHolder.energy < whole) {
				return false
			}

			energyHolder.energy -= whole
			pool += whole
			spending += whole
		}

		pool -= cost

		return true
	}

	/** The emitter glows at full brightness while the node holds a charge. */
	private fun glow() {
		val brightness = if (on) Display.Brightness(15, 15) else null

		runTask {
			if (!isEnabled) {
				return@runTask
			}

			displayEntities?.forEach { display ->
				display.updateEntityData(true) { this.brightness = brightness }
			}
		}
	}

	/** The lamp tally shown in the menu, refreshed once a second. */
	private fun lampsItem(): Item = Item.builder()
		.updatePeriodically(20)
		.setItemProvider {
			val efficiency = upgradeHolder.getValue(UpgradeTypes.EFFICIENCY)
			val field = (LampCatalog.FIELD_DRAW_PER_BLOCK * region.size / efficiency).roundToLong()
			val builder = ItemBuilder(if (fed > 0) Material.LANTERN else Material.SOUL_LANTERN)
				.setName("<yellow>Đèn trong tầm: <white>$inReach</white>")
				.addLoreLines("<gray>Đang sáng: <green>$fed</green>")

			if (starved > 0) {
				builder.addLoreLines("<gray>Thiếu điện: <red>$starved</red>")
			}

			if (radiating) {
				builder.addLoreLines("<gray>Phát sóng bán kính <white>${region.size}</white>: <white>$field</white> J/s")
			} else {
				builder.addLoreLines("<dark_red>MẤT ĐIỆN</dark_red> <gray>- cần <white>$field</white> J/s để phát sóng bán kính ${region.size}")
			}

			builder.addLoreLines("<gray>Tiêu thụ: <white>$spent</white> J/s")

			builder
		}
		.build()

	@TileEntityMenuClass
	inner class NodeMenu(player: Player) : IndividualTileEntityMenu(player) {

		private val sideConfigGui = SideConfigMenu(this@WirelessNodeTile, ::openWindow)

		override val gui: Gui = Gui.builder()
			.setStructure(
				"1 - - - - - - - 2",
				"| s # # e # # p |",
				"| v # l e # # n |",
				"| u # # e # # m |",
				"3 - - - - - - - 4",
			)
			.addIngredient('s', OpenSideConfigItem(sideConfigGui))
			.addIngredient('v', region.visualizeRegionItem)
			.addIngredient('p', region.increaseSizeItem)
			.addIngredient('m', region.decreaseSizeItem)
			.addIngredient('n', region.displaySizeItem)
			.addIngredient('u', OpenUpgradesItem(upgradeHolder))
			.addIngredient('l', lampsItem())
			.addIngredient('e', EnergyBar(3, energyHolder))
			.build()
	}

	private companion object {

		/** The reach can be pulled in to a single block each way, never off. */
		const val MIN_RADIUS = 1

		/** The paying clock: this tile ticks every tick, so twenty a second. */
		const val TICKS_PER_SECOND = 20

		const val CHANNELS_OFF = "channels_off"
	}
}
