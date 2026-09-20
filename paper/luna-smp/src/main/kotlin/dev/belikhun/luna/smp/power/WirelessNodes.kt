package dev.belikhun.luna.smp.power

import xyz.xenondevs.nova.world.BlockPos

/**
 * Every wireless power node currently in the world, so a switch can find
 * the nodes whose reach covers it without scanning anything. The mirror of
 * [WirelessLamps], for the other direction.
 */
object WirelessNodes {

	private val nodes = LinkedHashSet<WirelessNodeTile>()

	fun register(node: WirelessNodeTile) {
		nodes += node
	}

	fun unregister(node: WirelessNodeTile) {
		nodes -= node
	}

	/** The nodes whose reach covers the centre of the block at [pos]. */
	fun covering(pos: BlockPos): List<WirelessNodeTile> {
		val centre = pos.location.add(0.5, 0.5, 0.5)

		return nodes.filter { node -> node.pos.world == pos.world && node.covers(centre) }
	}
}
