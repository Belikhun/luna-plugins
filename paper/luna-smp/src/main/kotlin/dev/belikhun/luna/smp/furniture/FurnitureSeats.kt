package dev.belikhun.luna.smp.furniture

import dev.belikhun.luna.smp.LunaSmp
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.plugin.Plugin
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitFun
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.world.BlockPos

/**
 * Seats for sit-able furniture: an invisible marker armor stand the player
 * mounts. The stand is tagged so strays from a crash never survive a chunk
 * load, and every legitimate one dies with its dismount.
 */
@Init(stage = InitStage.POST_WORLD)
object FurnitureSeats : Listener {

	/** Entity tag marking seat stands as ours; cleanup keys on it. */
	private const val SEAT_TAG = "luna_smp_seat"

	/** Where a player is put down when they get up, in order of preference. */
	private val AROUND = listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

	private val plugin: Plugin?
		get() = Bukkit.getPluginManager().getPlugin("LunaSmp")

	@InitFun
	private fun init() {
		val owner = plugin

		// the plugin half can disable itself (no economy provider); furniture
		// then loses its seats, but a config problem must not sink the server
		if (owner == null || !owner.isEnabled) {
			Bukkit.getLogger().warning("[LunaSmp] Plugin tắt nên ghế ngồi không hoạt động; nội thất vẫn đặt được.")

			return
		}

		Bukkit.getPluginManager().registerEvents(this, owner)
	}

	/**
	 * Seat [player] on the block at [pos]. [seatHeight] is where the player's
	 * seat lands, in block fractions; [yaw] faces them the way the furniture
	 * faces. False when the seat is already taken.
	 */
	fun seat(player: Player, pos: BlockPos, seatHeight: Double, yaw: Float): Boolean {
		val world = pos.world
		val location = Location(world, pos.x + 0.5, pos.y + seatHeight, pos.z + 0.5, yaw, 0f)

		val taken = world.getNearbyEntities(location, 0.4, 0.6, 0.4)
			.any { entity -> entity.scoreboardTags.contains(SEAT_TAG) }

		if (taken) {
			return false
		}

		val stand = world.spawn(location, ArmorStand::class.java) { entity ->
			entity.isVisible = false
			entity.isMarker = true
			entity.isSmall = true
			entity.setGravity(false)
			entity.isPersistent = false
			entity.addScoreboardTag(SEAT_TAG)
		}

		return stand.addPassenger(player)
	}

	@EventHandler
	private fun onDismount(event: EntityDismountEvent) {
		val stand = event.dismounted

		if (!stand.scoreboardTags.contains(SEAT_TAG)) {
			return
		}

		val seat = stand.location.clone()
		stand.remove()

		val rider = event.entity as? Player ?: return
		val owner = plugin ?: return

		// the game puts a dismounting rider where its vehicle was, and the
		// vehicle is inside the furniture's own collider: that lands the
		// player inside a barrier, which suffocates them or drops them
		// through the floor. Stand them somewhere they actually fit instead,
		// next tick, because the dismount is still in progress
		Bukkit.getScheduler().runTask(owner, Runnable {
			if (!rider.isOnline || rider.vehicle != null) {
				return@Runnable
			}

			val spot = footing(seat) ?: return@Runnable

			spot.yaw = rider.location.yaw
			spot.pitch = rider.location.pitch
			rider.teleport(spot)
		})
	}

	/**
	 * Somewhere beside [seat] a player fits: standing room with something
	 * under it. The block the seat itself is in is tried last, since that is
	 * the furniture, and only the block above it can be stood in.
	 */
	private fun footing(seat: Location): Location? {
		val world = seat.world
		val here = seat.block

		val candidates = buildList {
			for (face in AROUND) {
				add(here.getRelative(face))
			}

			add(here.getRelative(BlockFace.UP))
		}

		for (block in candidates) {
			val head = block.getRelative(BlockFace.UP)
			val ground = block.getRelative(BlockFace.DOWN)

			if (block.isPassable && head.isPassable && !ground.isPassable) {
				return Location(world, block.x + 0.5, block.y.toDouble(), block.z + 0.5)
			}
		}

		// nothing fits: leaving them on the seat's own block is still better
		// than inside it, since that is where they were before they sat down
		return Location(world, here.x + 0.5, (here.y + 1).toDouble(), here.z + 0.5)
	}

	@EventHandler
	private fun onQuit(event: PlayerQuitEvent) {
		val vehicle = event.player.vehicle

		if (vehicle != null && vehicle.scoreboardTags.contains(SEAT_TAG)) {
			vehicle.remove()
		}
	}

	/** A seat with no rider is a leftover; a crash is the only way to make one. */
	@EventHandler
	private fun onChunkLoad(event: ChunkLoadEvent) {
		for (entity in event.chunk.entities) {
			if (entity.scoreboardTags.contains(SEAT_TAG) && entity.passengers.isEmpty()) {
				entity.remove()
			}
		}
	}
}
