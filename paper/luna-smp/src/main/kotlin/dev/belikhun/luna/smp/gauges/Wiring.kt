package dev.belikhun.luna.smp.gauges

import net.kyori.adventure.key.Key
import org.bukkit.block.BlockFace
import org.joml.Quaternionf
import xyz.xenondevs.nova.util.pitch
import xyz.xenondevs.nova.util.yaw
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkBridge
import xyz.xenondevs.nova.world.format.NetworkState
import xyz.xenondevs.nova.world.format.WorldDataManager
import xyz.xenondevs.nova.world.item.NovaItem
import xyz.xenondevs.nova.world.model.Model

/**
 * The one ugly fact of joining somebody else's cable run.
 *
 * Nova links a bridge to a neighbouring bridge only when the two report an
 * **equal** 'typeId' (AddBridgeTask compares them with an equality check, read
 * in the 0.22.3 bytecode), and every Logistics cable reports its own block id.
 * A bridge with an id of its own therefore never joins a cable line - it cuts
 * it, which is the opposite of what a switch spliced into a line is for.
 *
 * So a device that wants to sit in a line impersonates it: it looks at the
 * bridges beside it, adopts the first type id that is not one of ours, and
 * re-checks once a second so a cable placed after the device is picked up
 * without anyone having to flick anything. Two of our own devices side by
 * side agree through the same rule: whichever of them touches a real cable
 * adopts its id, and the other adopts it from the first.
 *
 * The cost of the trick is honest and small: a device only splices cleanly
 * into ONE kind of cable at a time, so a run through a switch should be the
 * same tier on both sides.
 */
object Wiring {

	/** The id a device answers with when it has no line to impersonate. */
	fun ownId(name: String): Key = Key.key("lunasmp", name)

	/**
	 * The type id of the line at these faces, or null when there is none.
	 * A foreign id (a real cable) beats one of our own devices, which merely
	 * passes along what it learned itself.
	 */
	fun lineIdAt(pos: BlockPos, faces: Collection<BlockFace>): Key? {
		var borrowed: Key? = null

		for (face in faces) {
			val neighbour = WorldDataManager.getTileEntity(pos.advance(face, 1)) as? NetworkBridge ?: continue
			val id = neighbour.typeId

			if (id.namespace() != "lunasmp") {
				return id
			}

			if (borrowed == null) {
				borrowed = id
			}
		}

		return borrowed
	}

	/**
	 * The faces where a same-id bridge sits next door but the network state
	 * records no connection to it: the links that SHOULD exist and do not.
	 *
	 * This is the ground-truth check behind the periodic self-heal. An empty
	 * set from a healthy device costs one table lookup per neighbour; a
	 * non-empty set means the device's registration went stale (an older
	 * build's type id, a load-order race) and a remove-and-re-add is due.
	 * A device the state does not know at all reports every candidate face.
	 */
	suspend fun unlinkedFaces(
		state: NetworkState,
		bridge: NetworkBridge,
		pos: BlockPos,
		faces: Collection<BlockFace>,
	): Set<BlockFace> {
		val connected = runCatching { state.getConnectedNodes(bridge) }.getOrNull()
		val missing = mutableSetOf<BlockFace>()

		for (face in faces) {
			val neighbour = WorldDataManager.getTileEntity(pos.advance(face, 1)) as? NetworkBridge ?: continue

			if (neighbour.typeId != bridge.typeId) {
				continue
			}

			if (connected == null || connected.column(face).isEmpty()) {
				missing += face
			}
		}

		return missing
	}

	/**
	 * The display models for a set of couplings: one prebaked directional
	 * item per face, at the block centre, identity rotation. Runtime
	 * quaternions are retired here: the vertical pitch sign shipped wrong
	 * twice, because a horizontal wire (pitch 0) can never falsify it -
	 * the generator now bakes each direction's geometry instead.
	 */
	fun jointModels(pos: BlockPos, faces: Collection<BlockFace>, items: Map<BlockFace, NovaItem>): Set<Model> {
		val models = HashSet<Model>()

		for (face in faces) {
			val item = items[face] ?: continue

			models += Model(
				item.createClientsideItemBuilder().get(),
				pos.location.add(0.5, 0.5, 0.5),
			)
		}

		return models
	}
}
