package dev.belikhun.luna.smp.power

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.GameMode
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.commons.collections.enumMap
import xyz.xenondevs.commons.provider.provider
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockBreak
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockInteract
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.util.CUBE_FACES
import xyz.xenondevs.nova.util.item.novaItem
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.NetworkedTileEntity
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkConnectionType
import xyz.xenondevs.nova.world.format.WorldDataManager
import java.util.EnumMap

/**
 * The head of a pole: the crossarm the spans terminate on.
 *
 * Unlike the base and the masts under it, the head is not a bridge but an
 * ENDPOINT with one energy buffer, reachable only from below. That is what
 * makes a span cheap. The pole's own network fills the buffer through the mast
 * it stands on, exactly as it would charge any battery, and a span is then
 * nothing but joules moved from one head's buffer into the other's: no walk
 * over the network graph, no second network type, and the flux counters stay
 * honest, so a meter anywhere on either pole reads the line's real traffic.
 *
 * A buffer at each end is also what makes the line behave like a tie between
 * two grids rather than a pipe. Power crosses toward whichever end is emptier,
 * up to the tier's rate, so two pole lines meeting at a junction pole share
 * out what there is instead of one starving the other.
 *
 * A span is remembered by both heads, drawn by exactly one of them (the end
 * whose position sorts first), and carries ENERGY only.
 */
class PoleHeadTile(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : NetworkedTileEntity(pos, blockState, data) {

	/**
	 * The buffer a span draws from and fills. Its capacity is deliberately
	 * generous: a network may turn a buffer over once a tick, so the capacity
	 * is the ceiling on how fast the pole can charge its own head, and it has
	 * to stay clear of the fastest tier's rate or the wire would be metered
	 * by the buffer rather than by its own tier.
	 */
	private val energyHolder = storedEnergyHolder(
		provider(BUFFER_CAPACITY),
		NetworkConnectionType.BUFFER,
		blockedFaces = SIDE_FACES,
		defaultConnectionConfig = ::connectionConfig,
	)

	private var links: MutableList<Wires.Link> = mutableListOf()

	/** The rope of every span this head is the drawing end of. */
	private val drawn = HashMap<BlockPos, Wires.Rope>()

	private var painted = false
	private var ticks = 0

	/** Only the underside talks to a network; the arm is not a terminal. */
	private fun connectionConfig(): EnumMap<BlockFace, NetworkConnectionType> =
		CUBE_FACES.associateWithTo(enumMap()) { face ->
			if (face == BlockFace.DOWN) {
				NetworkConnectionType.BUFFER
			} else {
				NetworkConnectionType.NONE
			}
		}

	// ---- lifecycle -----------------------------------------------------------

	override fun handleEnable() {
		super.handleEnable()

		links = Wires.decode(retrieveDataOrNull<String>(LINKS) ?: "", pos)
	}

	override fun handleDisable() {
		super.handleDisable()

		save()
		clearDrawn()
		painted = false
	}

	override fun handleBreak(ctx: Context<BlockBreak>) {
		super.handleBreak(ctx)

		// a span dies with either of its ends, and the spool that strung it
		// comes back rather than vanishing with the pole
		for (link in links.toList()) {
			(WorldDataManager.getTileEntity(link.target) as? PoleHeadTile)?.dropLinkTo(pos)
			refund(link)
		}

		links.clear()
		clearDrawn()
	}

	override fun handleTick() {
		if (!painted) {
			redraw()
		}

		for (link in links) {
			if (owns(link)) {
				transfer(link)
			}
		}

		ticks++

		// the housekeeping is once a second; only the transfer above has to
		// keep up with a network's own tick
		if (ticks % 20 != 0) {
			return
		}

		prune()

		// a viewer who walked into range after the pair was spawned has the
		// two entities but not the tie between them; the packet is idempotent
		for (rope in drawn.values) {
			rope.tie()
		}
	}

	// ---- the span ------------------------------------------------------------

	/**
	 * Which end drives a span. Both heads remember it, so without a rule both
	 * would move the same joules and both would draw the same wire; the end
	 * whose position sorts first does the work.
	 */
	private fun owns(link: Wires.Link): Boolean {
		val target = link.target

		if (pos.x != target.x) {
			return pos.x < target.x
		}

		if (pos.y != target.y) {
			return pos.y < target.y
		}

		return pos.z < target.z
	}

	/**
	 * Moves this tick's joules across one span, toward the emptier end.
	 *
	 * Half the difference is the most that ever moves, so the two ends
	 * converge on a share rather than trading the same charge back and forth
	 * every tick; the tier's rate is what actually binds in a line that is
	 * carrying anything.
	 */
	private fun transfer(link: Wires.Link) {
		val other = WorldDataManager.getTileEntity(link.target) as? PoleHeadTile ?: return
		val mine = energyHolder.energy
		val theirs = other.energyHolder.energy

		if (mine == theirs) {
			return
		}

		val source = if (mine > theirs) this else other
		val sink = if (mine > theirs) other else this
		val half = (maxOf(mine, theirs) - minOf(mine, theirs)) / 2L
		val space = sink.energyHolder.maxEnergy - sink.energyHolder.energy
		val move = minOf(link.tier.rate / 20L, half, source.energyHolder.energy, space)

		if (move <= 0L) {
			return
		}

		source.energyHolder.energy -= move
		sink.energyHolder.energy += move
	}

	/**
	 * Drops the spans whose far end is provably gone: the chunk is loaded and
	 * there is no head there any more. An unloaded far end says nothing, so
	 * it is left alone - this is the repair for a partner broken while this
	 * head was out of the world.
	 */
	private fun prune() {
		var changed = false

		for (link in links.toList()) {
			val target = link.target

			if (!target.world.isChunkLoaded(target.x shr 4, target.z shr 4)) {
				continue
			}

			if (WorldDataManager.getTileEntity(target) is PoleHeadTile) {
				continue
			}

			links.remove(link)
			changed = true
		}

		if (changed) {
			save()
			redraw()
		}
	}

	// ---- stringing a span ------------------------------------------------------

	override fun handleRightClick(ctx: Context<BlockInteract>): Boolean {
		val player = ctx[DefaultContextParamTypes.SOURCE_ENTITY] as? Player ?: return false
		val hand = ctx[DefaultContextParamTypes.INTERACTION_HAND] ?: EquipmentSlot.HAND

		if (hand != EquipmentSlot.HAND) {
			return false
		}

		// sneaking is the build gesture everywhere in this addon
		if (player.isSneaking) {
			return false
		}

		val held = player.inventory.itemInMainHand
		val tier = tierOf(held)

		if (tier == null) {
			report(player)
			return true
		}

		string(player, tier, held)
		return true
	}

	/** The wire tier a held spool is, or null when it is not a spool. */
	private fun tierOf(stack: ItemStack): PowerCatalog.TierSpec? {
		val id = stack.novaItem?.id ?: return null

		if (id.namespace() != "lunasmp") {
			return null
		}

		val name = id.value()

		if (!name.startsWith(SPOOL_PREFIX)) {
			return null
		}

		return PowerCatalog.TIER_BY_ID[name.removePrefix(SPOOL_PREFIX)]
	}

	/**
	 * The two-click gesture: the first head is remembered, the second one is
	 * joined to it. Every refusal says which rule it broke, because the two
	 * that bite in practice - the span and a head that is already full - are
	 * both invisible until someone is told.
	 */
	private fun string(player: Player, tier: PowerCatalog.TierSpec, held: ItemStack) {
		val anchor = Wires.anchorOf(player)

		if (anchor == null || anchor.pos == pos || anchor.tier.id != tier.id) {
			Wires.setAnchor(player, Wires.Anchor(pos, tier))
			player.sendMessage(
				Component.text("Đã chọn đầu trụ này. Nhấn chuột phải vào đầu trụ thứ hai để nối dây.", NamedTextColor.YELLOW),
			)

			return
		}

		if (anchor.pos.world != pos.world) {
			player.sendMessage(Component.text("Hai đầu trụ không ở cùng một thế giới.", NamedTextColor.RED))
			Wires.clearAnchor(player)

			return
		}

		val other = WorldDataManager.getTileEntity(anchor.pos) as? PoleHeadTile

		if (other == null) {
			player.sendMessage(Component.text("Không tìm thấy đầu trụ đã chọn. Hãy chọn lại.", NamedTextColor.RED))
			Wires.clearAnchor(player)

			return
		}

		val distance = Wires.terminal(pos).distance(Wires.terminal(anchor.pos))

		if (distance > tier.span) {
			player.sendMessage(
				Component.text(
					"Quá xa: %.1f khối. %s chỉ với được %.0f khối.".format(distance, tier.vi, tier.span),
					NamedTextColor.RED,
				),
			)

			return
		}

		if (links.any { it.target == anchor.pos }) {
			player.sendMessage(Component.text("Hai đầu trụ này đã có dây nối.", NamedTextColor.RED))
			Wires.clearAnchor(player)

			return
		}

		if (links.size >= MAX_LINKS || other.links.size >= MAX_LINKS) {
			player.sendMessage(
				Component.text("Một đầu trụ chỉ nối được $MAX_LINKS dây.", NamedTextColor.RED),
			)

			return
		}

		addLink(Wires.Link(anchor.pos, tier))
		other.addLink(Wires.Link(pos, tier))
		Wires.clearAnchor(player)
		consume(player, held)

		player.sendMessage(
			Component.text(
				"Đã nối %s: %.1f khối, tải %s J/s.".format(tier.vi, distance, format(tier.rate)),
				NamedTextColor.GREEN,
			),
		)
	}

	/** What this head is doing, for a click with anything but a spool. */
	private fun report(player: Player) {
		if (links.isEmpty()) {
			player.sendMessage(
				Component.text("Đầu trụ chưa có dây. Nhấn chuột phải bằng cuộn dây điện để nối.", NamedTextColor.GRAY),
			)

			return
		}

		player.sendMessage(
			Component.text("Đầu trụ điện: ${links.size} dây nối", NamedTextColor.AQUA),
		)

		for (link in links) {
			val distance = Wires.terminal(pos).distance(Wires.terminal(link.target))

			player.sendMessage(
				Component.text(
					"  %s -> %d %d %d · %.1f khối · %s J/s".format(
						link.tier.vi, link.target.x, link.target.y, link.target.z, distance, format(link.tier.rate),
					),
					NamedTextColor.GRAY,
				),
			)
		}

		player.sendMessage(
			Component.text(
				"  Đệm: ${format(energyHolder.energy)} / ${format(energyHolder.maxEnergy)} J",
				NamedTextColor.GRAY,
			),
		)
	}

	// ---- the link list ----------------------------------------------------------

	private fun addLink(link: Wires.Link) {
		links += link
		save()
		redraw()
	}

	/** Drops the span to [target], however this head was told to. */
	private fun dropLinkTo(target: BlockPos) {
		if (!links.removeAll { it.target == target }) {
			return
		}

		save()
		redraw()
	}

	private fun save() {
		storeData(LINKS, Wires.encode(links))
	}

	private fun refund(link: Wires.Link) {
		val stack = PowerItems.SPOOLS[link.tier.id]?.createItemStack() ?: return

		pos.world.dropItemNaturally(pos.location.add(0.5, 0.5, 0.5), stack)
	}

	private fun consume(player: Player, held: ItemStack) {
		if (player.gameMode == GameMode.CREATIVE) {
			return
		}

		held.amount = held.amount - 1
		player.inventory.setItemInMainHand(held)
	}

	// ---- drawing ------------------------------------------------------------------

	/** Hangs a rope on every span this head owns, and none on the rest. */
	private fun redraw() {
		clearDrawn()
		painted = true

		for (link in links) {
			if (!owns(link)) {
				continue
			}

			val rope = Wires.Rope(Wires.terminal(pos), Wires.terminal(link.target))

			rope.tie()
			drawn[link.target] = rope
		}
	}

	private fun clearDrawn() {
		for (rope in drawn.values) {
			rope.remove()
		}

		drawn.clear()
	}

	private fun format(value: Long): String =
		when {
			value >= 1_000_000L -> "%.1fM".format(value / 1_000_000.0)
			value >= 1_000L -> "%.1fk".format(value / 1_000.0)
			else -> value.toString()
		}

	private companion object {

		const val LINKS = "links"
		const val SPOOL_PREFIX = "power_wire_"

		/**
		 * How many spans one arm carries. Three insulators are drawn on it;
		 * a fourth is what lets a pole be a junction rather than only a link
		 * in a chain.
		 */
		const val MAX_LINKS = 4

		/** Ten seconds of the slowest tier, a twentieth of a tick's worth
		 * of the fastest: high enough that no tier is capacity-metered. */
		const val BUFFER_CAPACITY = 400_000L

		val SIDE_FACES: Set<BlockFace> = CUBE_FACES.filterTo(HashSet()) { it != BlockFace.DOWN }
	}
}
