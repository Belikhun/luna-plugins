package dev.belikhun.luna.smp.power

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket
import org.bukkit.Location
import org.bukkit.entity.Player
import xyz.xenondevs.nova.util.send
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.fakeentity.impl.FakeSlime
import java.util.UUID

/**
 * The span between two pole heads: what a link is, how it is written down, and
 * how it is drawn.
 *
 * A span is not a block. Both heads remember it, so either of them can take it
 * down, and exactly one of them draws it, so the line is not painted twice.
 *
 * Drawing is a vanilla lead between two client-side entities: the rope, the
 * sag and the curve are the client's own, exactly as they are for a lead tied
 * between two fence posts. See [Rope] for why nothing here is real.
 */
object Wires {

	/** One span, as the head at either end remembers it. */
	data class Link(val target: BlockPos, val tier: PowerCatalog.TierSpec)

	/** A span a player has started but not finished. */
	data class Anchor(val pos: BlockPos, val tier: PowerCatalog.TierSpec)

	private val anchors = HashMap<UUID, Anchor>()

	/** The head this player picked as one end, if they are mid-span. */
	fun anchorOf(player: Player): Anchor? = anchors[player.uniqueId]

	fun setAnchor(player: Player, anchor: Anchor) {
		anchors[player.uniqueId] = anchor
	}

	fun clearAnchor(player: Player) {
		anchors.remove(player.uniqueId)
	}

	// ---- writing a head's links down ----------------------------------------
	//
	// One ASCII line per head, spans separated by semicolons: "x,y,z,tier".
	// Deliberately not a list of compounds - Nova's own string serialisation
	// mangles non-ASCII on reload, and a format this simple is one a log line
	// can show and a person can read.

	/** The links of one head, as the single string its tile data holds. */
	fun encode(links: Collection<Link>): String =
		links.joinToString(";") { link ->
			"${link.target.x},${link.target.y},${link.target.z},${link.tier.id}"
		}

	/**
	 * The links back out of that string, in the world the head itself is in.
	 * A span whose tier is no longer in the catalog is dropped rather than
	 * guessed at, which is what keeps a retired tier from resurrecting.
	 */
	fun decode(text: String, pos: BlockPos): MutableList<Link> {
		val links = mutableListOf<Link>()

		for (entry in text.split(';')) {
			if (entry.isEmpty()) {
				continue
			}

			val parts = entry.split(',')

			if (parts.size != 4) {
				continue
			}

			val x = parts[0].toIntOrNull() ?: continue
			val y = parts[1].toIntOrNull() ?: continue
			val z = parts[2].toIntOrNull() ?: continue
			val tier = PowerCatalog.TIER_BY_ID[parts[3]] ?: continue

			links += Link(BlockPos(pos.world, x, y, z), tier)
		}

		return links
	}

	// ---- the rope -----------------------------------------------------------

	/**
	 * Where a span leaves a head: the middle of its crossarm, at the height
	 * the insulator caps reach. Every wire on a head terminates at the same
	 * point on purpose - a line that changed insulator at every pole would
	 * visibly jog sideways along its whole length.
	 */
	fun terminal(pos: BlockPos): Location =
		pos.location.add(0.5, PowerCatalog.TERMINAL_HEIGHT, 0.5)

	/**
	 * One span, drawn as a real lead.
	 *
	 * Two rounds of drawing the curve ourselves went wrong in ways a level
	 * wire could not show (a display's own rotation frame is not the frame the
	 * prebaked couplings are cut in), so the curve is not ours any more: a
	 * lead is what the client already knows how to hang between two points,
	 * and it hangs it in the same rope, with the same sag, as a lead between
	 * two fence posts.
	 *
	 * Both ends are FAKE entities - packets, with nothing in the world - which
	 * is what makes this legal at any length. A real leash is server physics:
	 * it drags the leashed mob toward its holder and snaps past twelve blocks
	 * (`Leashable.LEASH_TOO_FAR_DIST`), so no real leash could span a pole
	 * line. A client-side pair has no physics at all; the client is simply
	 * told that one is tied to the other and draws the rope.
	 *
	 * The ends are slimes because the rope is drawn for a `Leashable`, and
	 * every `Mob` is one. They are the smallest a slime comes, and NOT
	 * smaller: the first cut set the size to zero, hoping to collapse the
	 * client's two rope anchors onto the entity's own position, and drew
	 * nothing at all. `Entity.shouldRenderAtSqrDistance` takes the entity's
	 * bounding box, multiplies its average dimension by 64 and refuses to
	 * render past that, so a zero-size entity has a render distance of
	 * exactly zero - it and its rope are never drawn anywhere.
	 *
	 * A size-1 slime is 0.52 across, which is both its own render distance
	 * (0.52 x 64, a little over 33 blocks - hence the cap on the top tier's
	 * span) and, near enough, the width of the pole itself, so the invisible
	 * box hides inside the head's own block instead of intercepting clicks.
	 *
	 * The two ends are NOT interchangeable. The client hangs the rope from
	 * the leashed end's `getLeashOffset` - `(0, eyeHeight, width * 0.4)` in
	 * the entity's own frame - and lands it on the holding end's
	 * `getRopeHoldPosition`, which is `eyeHeight * 0.7` straight up. Each end
	 * is therefore placed at the terminal MINUS its own offset, so that both
	 * of those anchors come out exactly on the insulator. The yaw is pinned
	 * to zero because the leashed offset is rotated by it.
	 */
	class Rope(from: Location, to: Location) {

		private val tail = FakeSlime(seat(from, TAIL_LIFT, TAIL_REACH)) { _, meta ->
			meta.size = 1
			meta.isInvisible = true
			meta.isSilent = true
		}

		private val head = FakeSlime(seat(to, HEAD_LIFT, 0.0)) { _, meta ->
			meta.size = 1
			meta.isInvisible = true
			meta.isSilent = true
		}

		/**
		 * Tells everyone who can see the hanging end that it is tied to the
		 * other, which is the whole of the rope.
		 *
		 * Re-sent on a timer rather than once: a viewer who came into range
		 * after the pair was spawned gets the two entities from the fake-entity
		 * tracker but not this, and the packet is idempotent, so repeating it
		 * costs two bytes and removes the ordering question entirely.
		 */
		fun tie() {
			if (tail.viewers.isEmpty()) {
				return
			}

			val buffer = FriendlyByteBuf(Unpooled.buffer())

			buffer.writeInt(tail.entityId)
			buffer.writeInt(head.entityId)

			// the packet has no public constructor taking ids - it reads them
			// off two real entities, which is exactly what we do not have - so
			// it is decoded from the two ints its own codec writes
			val packet = ClientboundSetEntityLinkPacket.STREAM_CODEC.decode(buffer)

			for (player in tail.viewers) {
				player.send(packet)
			}
		}

		fun remove() {
			tail.remove()
			head.remove()
		}

		private companion object {

			/** A size-1 slime: 0.52 wide, 0.52 tall, eyes at 0.325. */
			const val EYE_HEIGHT = 0.325
			const val WIDTH = 0.52

			/** Where the leashed end hangs its rope, relative to itself. */
			const val TAIL_LIFT = EYE_HEIGHT
			const val TAIL_REACH = WIDTH * 0.4

			/** And where the holding end takes it. */
			const val HEAD_LIFT = EYE_HEIGHT * 0.7

			/**
			 * An end placed so that its own rope anchor lands on [at]. The
			 * reach is cancelled along z because a yaw of zero leaves the
			 * leashed offset pointing that way.
			 */
			fun seat(at: Location, lift: Double, reach: Double): Location =
				Location(at.world, at.x, at.y - lift, at.z - reach, 0.0f, 0.0f)
		}
	}
}
