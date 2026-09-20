package dev.belikhun.luna.smp.power

import org.bukkit.block.BlockFace
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.tileentity.network.Network
import xyz.xenondevs.nova.world.block.tileentity.network.ProtoNetwork
import xyz.xenondevs.nova.world.block.tileentity.network.node.MutableNetworkNodeConnection
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkBridge
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkEndPoint
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkNode
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkType
import xyz.xenondevs.nova.world.block.tileentity.network.type.energy.EnergyNetwork
import xyz.xenondevs.nova.world.format.NetworkState

/**
 * The graph surgery behind a wire cover's per-type faces.
 *
 * Nova's own picture of a bridge is one set of faces shared by every network
 * type it carries: a cable that conducts north conducts energy, items and
 * fluid north. A cover keeps a face set PER type instead, and this is where
 * the difference is enforced. The cover registers with Nova the ordinary way
 * (all three types, every face any type allows), so Nova builds and persists
 * the networks as it does for a cable; then [apply] walks the cover's six
 * faces, cuts every connection its masks forbid and makes every connection
 * its masks want that Nova has not made. Everything here is written against
 * the same state primitives Nova's own tasks use, and runs inside a queued
 * write on the network thread.
 *
 * Cutting or joining a link to a MACHINE is what Nova does when a machine's
 * own side configuration changes, so those two paths are Nova's public
 * functions verbatim. A link to another CONDUCTOR has no such function, since
 * no Nova bridge ever drops a single edge: cutting one may split a network
 * in two, joining one may merge two into one, and the two routines below do
 * exactly the bookkeeping Nova's remove-bridge and add-bridge tasks do for
 * the same events, on one edge instead of on a whole node.
 */
object WireCoverNetworking {

	/**
	 * Brings the network state in line with [cover]'s masks, returning whether
	 * anything changed. Every node touched is told of the update, as a Nova
	 * task would tell it.
	 */
	suspend fun apply(state: NetworkState, cover: WireCoverTile): Boolean {
		if (cover !in state) {
			return false
		}

		val enlarge = ArrayList<Pair<ProtoNetwork<*>, NetworkEndPoint>>()
		val reinit = HashSet<ProtoNetwork<*>>()
		val touched = HashSet<NetworkNode>()

		for (type in WireCoverTile.TYPES) {
			for (face in WireCoverTile.FACES) {
				val wanted = cover.conducts(type, face)
				val connected = state.hasConnection(cover, type, face)

				if (wanted == connected) {
					continue
				}

				val neighbour = state.getNearbyNodes(cover.pos, setOf(face))[face] ?: continue

				if (wanted) {
					if (join(state, cover, neighbour, type, face, enlarge, reinit)) {
						touched += neighbour
					}
				} else {
					cut(state, cover, neighbour, type, face, reinit)
					touched += neighbour
				}
			}
		}

		if (touched.isEmpty()) {
			return false
		}

		for ((network, endPoint) in enlarge) {
			if (network in state) {
				network.enlargeCluster(endPoint)
			}
		}

		for (network in reinit) {
			if (network in state) {
				network.initCluster()
			}
		}

		cover.handleNetworkUpdate(state)

		for (node in touched) {
			node.handleNetworkUpdate(state)
		}

		return true
	}

	/**
	 * Whether the state holds a connection [cover]'s masks forbid, or lacks
	 * one they want and a neighbour would accept. Read-only: the cover asks
	 * this on every network update and queues [apply] when it is true.
	 */
	suspend fun needsApply(state: NetworkState, cover: WireCoverTile): Boolean {
		if (cover !in state) {
			return false
		}

		for (type in WireCoverTile.TYPES) {
			for (face in WireCoverTile.FACES) {
				val wanted = cover.conducts(type, face)
				val connected = state.hasConnection(cover, type, face)

				if (connected && !wanted) {
					return true
				}

				if (wanted && !connected && accepts(state, cover, state.getNearbyNodes(cover.pos, setOf(face))[face], type, face)) {
					return true
				}
			}
		}

		return false
	}

	/** Whether [neighbour] would take a link of [type] from [cover] over [face]. */
	private suspend fun accepts(
		state: NetworkState,
		cover: WireCoverTile,
		neighbour: NetworkNode?,
		type: NetworkType<*>,
		face: BlockFace,
	): Boolean {
		val opposite = face.oppositeFace

		return when (neighbour) {
			is NetworkBridge -> neighbour.typeId == cover.typeId && opposite in state.getAllowedFaces(neighbour, type)
			is NetworkEndPoint -> opposite in state.getAllowedFaces(neighbour, type)
			else -> false
		}
	}

	private suspend fun join(
		state: NetworkState,
		cover: WireCoverTile,
		neighbour: NetworkNode,
		type: NetworkType<*>,
		face: BlockFace,
		enlarge: MutableList<Pair<ProtoNetwork<*>, NetworkEndPoint>>,
		reinit: MutableSet<ProtoNetwork<*>>,
	): Boolean {
		if (!accepts(state, cover, neighbour, type, face)) {
			return false
		}

		when (neighbour) {
			is NetworkEndPoint -> {
				val enlarged = HashSet<ProtoNetwork<*>>()
				state.connectEndPointToBridge(neighbour, cover, type, face.oppositeFace, enlarged)

				for (network in enlarged) {
					enlarge += network to neighbour
				}
			}

			is NetworkBridge -> return joinBridges(state, cover, neighbour, erase(type), face, reinit)
		}

		return true
	}

	private suspend fun cut(
		state: NetworkState,
		cover: WireCoverTile,
		neighbour: NetworkNode,
		type: NetworkType<*>,
		face: BlockFace,
		reinit: MutableSet<ProtoNetwork<*>>,
	) {
		when (neighbour) {
			is NetworkEndPoint -> state.disconnectEndPointFromBridge(neighbour, cover, type, face.oppositeFace, reinit)
			is NetworkBridge -> cutBridges(state, cover, neighbour, erase(type), face, reinit)
		}
	}

	/**
	 * Links two conductors over one edge of [type], merging their networks
	 * when they were two: the add-bridge task's merge, for one edge.
	 */
	private suspend fun <N : Network<N>> joinBridges(
		state: NetworkState,
		a: NetworkBridge,
		b: NetworkBridge,
		type: NetworkType<N>,
		face: BlockFace,
		reinit: MutableSet<ProtoNetwork<*>>,
	): Boolean {
		// a bridge that supports a type always sits in a network of it; a
		// missing one means the state is mid-change, and the link waits
		val networkA = state.getNetwork(a, type) ?: return false
		val networkB = state.getNetwork(b, type) ?: return false

		state.setConnection(a, type, face)
		state.setConnection(b, type, face.oppositeFace)

		if (networkA.uuid == networkB.uuid) {
			networkA.markDirty()
			return true
		}

		state -= networkA
		state -= networkB

		val merged = state.createNetwork(type)
		merged.addAll(networkA)
		merged.addAll(networkB)
		reassign(state, merged)

		invalidate(networkA, reinit)
		invalidate(networkB, reinit)
		reinit += merged

		return true
	}

	/**
	 * Drops one edge of [type] between two conductors, splitting their
	 * network when that edge was the only path: the remove-bridge task's
	 * recalculation, for one edge.
	 */
	private suspend fun <N : Network<N>> cutBridges(
		state: NetworkState,
		a: NetworkBridge,
		b: NetworkBridge,
		type: NetworkType<N>,
		face: BlockFace,
		reinit: MutableSet<ProtoNetwork<*>>,
	) {
		state.removeConnection(a, type, face)
		state.removeConnection(b, type, face.oppositeFace)

		val network = state.getNetwork(a, type) ?: return
		val sideA = explore(state, a, type)

		// still reachable the long way round: nothing to split
		if (b.pos in sideA) {
			network.markDirty()
			return
		}

		val sideB = explore(state, b, type)

		state -= network

		// a node in an unloaded chunk is met as a placeholder the state does
		// not hold; a network may not carry those, so they are left out here
		// exactly as Nova's own recalculation leaves them out
		val networkA = ProtoNetwork(state, type, nodes = sideA.filterTo(HashMap()) { it.value.node in state })
		val networkB = ProtoNetwork(state, type, nodes = sideB.filterTo(HashMap()) { it.value.node in state })
		state += networkA
		state += networkB
		reassign(state, networkA)
		reassign(state, networkB)

		invalidate(network, reinit)
		reinit += networkA
		reinit += networkB
	}

	/**
	 * Everything reachable from [start] over [type] connections: conductors
	 * are walked through, machines are leaves that remember which face they
	 * were reached on, exactly as Nova lays a network out.
	 */
	private suspend fun explore(
		state: NetworkState,
		start: NetworkNode,
		type: NetworkType<*>,
	): MutableMap<BlockPos, MutableNetworkNodeConnection> {
		val nodes = HashMap<BlockPos, MutableNetworkNodeConnection>()
		val queue = ArrayDeque<Pair<BlockFace?, NetworkNode>>()
		val seen = HashSet<NetworkNode>()

		queue += null to start
		seen += start

		while (queue.isNotEmpty()) {
			val (approach, node) = queue.removeFirst()
			val connection = nodes.getOrPut(node.pos) { MutableNetworkNodeConnection(node) }

			if (node is NetworkEndPoint && approach != null) {
				connection.faces += approach.oppositeFace
			}

			if (node !is NetworkBridge) {
				continue
			}

			state.forEachConnectedNode(node, type) { via, next ->
				if (next !in seen) {
					seen += next
					queue += via to next
				} else if (next is NetworkEndPoint) {
					// a machine met again from another conductor is in the
					// network on that face too
					nodes.getOrPut(next.pos) { MutableNetworkNodeConnection(next) }.faces += via.oppositeFace
				}
			}
		}

		return nodes
	}

	/** Points every node of [network] at it, in the node's own network table. */
	private suspend fun reassign(state: NetworkState, network: ProtoNetwork<*>) {
		for ((_, connection) in network.nodes) {
			when (val node = connection.node) {
				is NetworkBridge -> state.setNetwork(node, network)
				is NetworkEndPoint -> state.setNetwork(node, connection.faces, network)
			}
		}
	}

	/** Marks every network clustered with [network] for a fresh cluster. */
	private fun invalidate(network: ProtoNetwork<*>, reinit: MutableSet<ProtoNetwork<*>>) {
		val cluster = network.cluster ?: return

		for (clustered in cluster) {
			clustered.invalidateCluster()
			reinit += clustered
		}
	}

	/**
	 * A network type with its parameter forgotten and recalled as one
	 * concrete type, so the generic routines above can be called on a
	 * `NetworkType<*>`. Nothing in them touches the parameter; it only exists
	 * to make Nova's signatures line up, and erasure makes the cast free.
	 */
	@Suppress("UNCHECKED_CAST")
	private fun erase(type: NetworkType<*>): NetworkType<EnergyNetwork> =
		type as NetworkType<EnergyNetwork>
}
