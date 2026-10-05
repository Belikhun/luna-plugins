package dev.belikhun.luna.smp.trungthu

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.Levelled
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import xyz.xenondevs.nova.initialize.DisableFun
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitFun
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.util.runTaskTimer
import xyz.xenondevs.nova.util.serverTick
import java.util.UUID

/**
 * Everyone carrying a lantern: the tune their toy is playing and the light it
 * throws round them.
 *
 * The light is a `minecraft:light` block that follows the carrier's head, a
 * block at a time, placed only into air and taken back the moment they move
 * on, put the lantern away, leave or the server stops. Two carriers never
 * fight over one space: a block already holding somebody's light is left to
 * them. The music is played from the carrier's position, so everyone nearby
 * hears it, and restarts from the top whenever the lantern comes back into a
 * hand or changes tune.
 *
 * A light block left behind by a crash is invisible and harmless apart from
 * the light itself; `/fill ... air replace light` clears one.
 */
@Init(stage = InitStage.POST_WORLD)
object HeldLanterns : Listener {

	/** How bright a carried lantern is: a candle behind paper, a notch under a torch. */
	private const val LEVEL = 13

	/** How loud the toy is. */
	private const val VOLUME = 0.7f

	private class Carrier(var song: Int, var start: Int, var seen: Int, var light: Block?)

	private val carriers = HashMap<UUID, Carrier>()

	/** Which carrier owns the light standing in each block. */
	private val owners = HashMap<Block, UUID>()

	private var sweeper: BukkitTask? = null

	private val plugin: Plugin?
		get() = Bukkit.getPluginManager().getPlugin("LunaSmp")

	@InitFun
	private fun init() {
		val owner = plugin

		if (owner == null || !owner.isEnabled) {
			return
		}

		Bukkit.getPluginManager().registerEvents(this, owner)
	}

	@DisableFun
	private fun disable() {
		for (id in carriers.keys.toList()) {
			release(id)
		}

		sweeper?.cancel()
		sweeper = null
	}

	/**
	 * Called every tick a lantern is in [player]'s hand. [song] is the tune the
	 * lantern is set to, or null when its music is switched off.
	 */
	fun tick(player: Player, song: Int?) {
		step(player.uniqueId, player.eyeLocation.block, player.location.add(0.0, 1.0, 0.0), song)
	}

	/**
	 * One tick of carrying for [id]: the light follows [eye], the tune plays at
	 * [at]. Returns how many sounds played this tick. The player-free form is
	 * what the self-test drives.
	 */
	fun step(id: UUID, eye: Block, at: Location, song: Int?): Int {
		val now = serverTick
		val carrier = carriers.getOrPut(id) { Carrier(-1, now, now - 10, null) }

		// both hands can report on the same tick; the first one wins
		if (carrier.seen == now) {
			return 0
		}

		val returning = carrier.seen < now - 2
		val tune = song ?: -1

		if (returning || carrier.song != tune) {
			if (carrier.song != tune) {
				ToyTunes.stop(carrier.song, at)
			}
			carrier.song = tune
			carrier.start = now
		}

		carrier.seen = now
		follow(id, eye, carrier)
		ensureSweeper()

		val playing = ToyTunes.SONGS.getOrNull(tune) ?: return 0

		return ToyTunes.play(playing, now - carrier.start, at, VOLUME, Bukkit.getPlayer(id))
	}

	/** Where [id]'s light stands now, if anywhere. */
	fun lightOf(id: UUID): Block? = carriers[id]?.light

	/** Whether [id] is still counted as carrying a lantern. */
	fun isCarrying(id: UUID): Boolean = carriers.containsKey(id)

	private fun follow(id: UUID, target: Block, carrier: Carrier) {
		if (target == carrier.light) {
			// something may have been built into it; a light is replaceable
			if (target.type != Material.LIGHT) {
				owners.remove(target)
				carrier.light = null
			}

			return
		}

		clear(id, carrier)

		if (!target.type.isAir || owners.containsKey(target)) {
			return
		}

		target.setType(Material.LIGHT, false)

		val data = target.blockData as Levelled
		data.level = LEVEL
		target.setBlockData(data, false)

		owners[target] = id
		carrier.light = target
	}

	private fun clear(id: UUID, carrier: Carrier) {
		val block = carrier.light ?: return
		carrier.light = null

		if (owners[block] != id) {
			return
		}

		owners.remove(block)

		if (block.type == Material.LIGHT) {
			block.setType(Material.AIR, false)
		}
	}

	private fun release(id: UUID) {
		val carrier = carriers.remove(id) ?: return
		Bukkit.getPlayer(id)?.let { ToyTunes.stop(carrier.song, it.location) }
		clear(id, carrier)
	}

	/** Once a tick while anybody carries one: puts away the light of whoever stopped. */
	private fun ensureSweeper() {
		if (sweeper != null) {
			return
		}

		sweeper = runTaskTimer(1, 1) {
			val now = serverTick
			val gone = carriers.filterValues { it.seen < now - 1 }.keys

			for (id in gone) {
				release(id)
			}

			if (carriers.isEmpty()) {
				sweeper?.cancel()
				sweeper = null
			}
		}
	}

	@EventHandler
	fun onQuit(event: PlayerQuitEvent) {
		release(event.player.uniqueId)
	}
}
