package dev.belikhun.luna.smp.power

import xyz.xenondevs.nova.world.region.DynamicRegion

/**
 * Every wireless fixture currently in the world.
 *
 * A node has to find the fixtures inside its reach once a second, and a cube
 * of thirty-seven blocks a side is fifty thousand positions: too many to ask
 * the world about. So the fixtures announce themselves instead, on enable and
 * disable, and a node walks this set and keeps the ones inside its region -
 * a few hundred distance checks at the most, whatever the reach.
 */
object WirelessLamps {

	private val lamps = LinkedHashSet<WirelessLampTile>()

	fun register(lamp: WirelessLampTile) {
		lamps += lamp
	}

	fun unregister(lamp: WirelessLampTile) {
		lamps -= lamp
	}

	/** The fixtures whose centre lies inside [region], in registration order. */
	fun within(region: DynamicRegion): List<WirelessLampTile> {
		val world = region.world

		return lamps.filter { lamp ->
			lamp.pos.world == world && region.contains(lamp.centre)
		}
	}
}
