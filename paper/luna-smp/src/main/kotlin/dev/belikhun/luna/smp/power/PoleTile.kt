package dev.belikhun.luna.smp.power

import dev.belikhun.luna.smp.LunaSmp
import dev.belikhun.luna.smp.gauges.GaugeItems
import dev.belikhun.luna.smp.gauges.Wiring
import net.kyori.adventure.key.Key
import org.bukkit.block.BlockFace
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockBreak
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.TileEntity
import xyz.xenondevs.nova.world.block.tileentity.network.NetworkManager
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkBridge
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkNode
import xyz.xenondevs.nova.world.block.tileentity.network.type.DefaultNetworkTypes
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkType
import xyz.xenondevs.nova.world.block.tileentity.network.type.energy.EnergyBridge
import xyz.xenondevs.nova.world.format.NetworkState
import xyz.xenondevs.nova.world.format.WorldDataManager
import xyz.xenondevs.nova.world.model.FixedMultiModel
import xyz.xenondevs.nova.util.runTask

/**
 * The two conducting parts of a pole: the base it stands on and the mast
 * segments that give it height.
 *
 * Both are energy bridges, exactly the way a Logistics cable is one, so the
 * charge a cable delivers at the foot is the charge the head at the top hands
 * to a span. What differs is which faces they conduct on:
 *
 * - the **base** conducts on all six, because it is where a cable is meant to
 *   be brought in: from any side, or up out of the ground.
 * - a **mast** conducts up and down only. A pole often runs past a machine
 *   wall or another line, and a mast that bridged sideways would silently fuse
 *   whatever it brushed into the pole's own network.
 *
 * Neither carries items or fluid. A pole line is for power; a cable that wants
 * to move goods still has to go the long way round, which is the point.
 *
 * The type-id impersonation is the same trick every wire device here plays
 * (see [Wiring]): Nova joins two bridges only when their type ids match, so a
 * pole adopts the id of the cable beside it. The whole family shares ONE own
 * id for the case where there is no cable at all, so a pole with nothing
 * plugged in still conducts along itself from base to head.
 */
class PoleTile(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data), EnergyBridge {

	private val role = PowerCatalog.PART_BY_ID.getValue(blockState.block.id.value()).role

	/** The faces this part conducts on. */
	private val faces: Set<BlockFace> = when (role) {
		PowerCatalog.Role.BASE -> ALL_FACES
		else -> UPRIGHT
	}

	private var valid = false

	/**
	 * The fittings the base grows toward each cable actually linked to it.
	 *
	 * The pole's column is eight pixels wide, so a cable run visibly stops
	 * short of it at the block border; the gauges solved the same gap with
	 * these couplings, and they double as a truth indicator, since one only
	 * appears where the network state records a connection.
	 */
	private val joints = FixedMultiModel()

	/** The periodic are-we-actually-linked check; see [auditWire]. */
	private var nextAudit = 0L
	private var auditStrikes = 0
	private var mismatchReported = false

	override val isValid: Boolean
		get() = valid

	// one id for the whole family, so the parts of an unplugged pole still
	// find each other: two bridges link only on an equal type id, and
	// per-block ids would leave a mast refusing to talk to its own base
	override var typeId: Key = Wiring.ownId(FAMILY)
		private set

	override val linkedNodes: Set<NetworkNode>
		get() = emptySet()

	/**
	 * A pole is a conductor, not a restriction: the span it feeds is what
	 * meters the line, and Nova takes the SMALLEST transfer rate of every
	 * bridge in a network, so a modest number here would throttle the cable
	 * run the pole is spliced into rather than just the pole.
	 */
	override val energyTransferRate: Long
		get() = 1_000_000_000L

	override fun handleEnable() {
		super.handleEnable()

		typeId = Wiring.lineIdAt(pos, faces) ?: typeId

		// remove first, always: queueAddBridge is a silent no-op for a node
		// the network state already holds, so a stale registration restored
		// from disk would never be replaced
		NetworkManager.queueRemoveBridge(this)
		NetworkManager.queueAddBridge(this, TYPES, faces)
		valid = true
	}

	override fun handleDisable() {
		super.handleDisable()

		valid = false
		joints.clear()
	}

	override fun handleBreak(ctx: Context<BlockBreak>) {
		NetworkManager.queueRemoveBridge(this)
		valid = false
		joints.clear()
	}

	override suspend fun handleNetworkLoaded(state: NetworkState) {
		updateJoints(state)
	}

	override suspend fun handleNetworkUpdate(state: NetworkState) {
		updateJoints(state)
	}

	/**
	 * Grows a fitting toward every cable the network state says is linked
	 * here. Only the base does this: a mast conducts up and down into other
	 * pole parts, whose bodies already meet its own.
	 */
	private suspend fun updateJoints(state: NetworkState) {
		if (role != PowerCatalog.Role.BASE) {
			return
		}

		val linked = runCatching { state.getConnectedNodes(this).columnKeySet() }.getOrNull() ?: return
		val models = Wiring.jointModels(pos, linked, GaugeItems.JOINTS)

		runTask {
			if (isEnabled) {
				joints.replaceModels(models)
			}
		}
	}

	override fun handleTick() {
		val sampled = Wiring.lineIdAt(pos, faces)

		if (sampled != null && sampled != typeId) {
			typeId = sampled
			NetworkManager.queueRemoveBridge(this)
			NetworkManager.queueAddBridge(this, TYPES, faces)
		}

		auditWire()
	}

	/**
	 * The same stale-registration repair every wire device here runs: ask the
	 * network state which of the neighbours it should be linked to it is not,
	 * and re-register when the answer is not empty.
	 */
	private fun auditWire() {
		val now = System.currentTimeMillis()

		if (now < nextAudit || auditStrikes >= MAX_AUDIT_STRIKES) {
			return
		}

		nextAudit = now + AUDIT_MS

		NetworkManager.queueRead(pos.chunkPos) { state ->
			val missing = Wiring.unlinkedFaces(state, this, pos, faces)

			if (missing.isEmpty()) {
				auditStrikes = 0

				// unlinkedFaces only judges neighbours whose type id already
				// matches, so it cannot see the other way a cable fails to
				// link: impersonation never took. Say which id we hold and
				// which the neighbour holds, or the log stays silent about
				// the one fault that looks identical in game.
				reportMismatch()
				return@queueRead
			}

			auditStrikes++
			LunaSmp.logger.warn(
				"power pole at {} has unlinked faces {} (typeId {}, strike {}); re-adding its bridge",
				pos, missing, typeId, auditStrikes,
			)

			NetworkManager.queueRemoveBridge(this)
			NetworkManager.queueAddBridge(this, TYPES, faces)
		}
	}

	/**
	 * Logs a neighbouring bridge this pole could never link to because the
	 * two hold different type ids. Once per pole: a permanent mismatch is a
	 * design fact (two cable tiers meeting at one pole), not an event.
	 */
	private fun reportMismatch() {
		if (mismatchReported) {
			return
		}

		for (face in faces) {
			val neighbour = WorldDataManager.getTileEntity(pos.advance(face, 1)) as? NetworkBridge ?: continue

			if (neighbour.typeId == typeId) {
				continue
			}

			mismatchReported = true
			LunaSmp.logger.warn(
				"power pole at {} cannot link the bridge on its {} face: it holds {}, we hold {}",
				pos, face, neighbour.typeId, typeId,
			)
		}
	}

	private companion object {

		/** The one type id every part of a pole answers with unplugged. */
		const val FAMILY = "power_pole"

		const val AUDIT_MS = 5_000L
		const val MAX_AUDIT_STRIKES = 3

		val ALL_FACES: Set<BlockFace> = setOf(
			BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH,
			BlockFace.WEST, BlockFace.UP, BlockFace.DOWN,
		)

		val UPRIGHT: Set<BlockFace> = setOf(BlockFace.UP, BlockFace.DOWN)

		val TYPES: Set<NetworkType<*>> = setOf(DefaultNetworkTypes.ENERGY)
	}
}
