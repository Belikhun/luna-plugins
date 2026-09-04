package dev.belikhun.luna.smp.gauges

import dev.belikhun.luna.smp.LunaSmp
import net.kyori.adventure.key.Key
import org.bukkit.block.BlockFace
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockBreak
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.TileEntity
import xyz.xenondevs.nova.world.block.tileentity.network.NetworkManager
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkEndPoint
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkNode
import xyz.xenondevs.nova.world.block.tileentity.network.type.DefaultNetworkTypes
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkConnectionType
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkType
import xyz.xenondevs.nova.world.block.tileentity.network.type.energy.EnergyBridge
import xyz.xenondevs.nova.world.block.tileentity.network.type.energy.holder.DefaultEnergyHolder

/**
 * The powered light panel: a framed diffuser cube that runs off the wire it
 * is part of.
 *
 * The panel is an energy bridge on every face, exactly the way a cable is
 * one - which is the whole point: a ceiling of panels laid side by side is
 * its own wiring, fed from one cable touching any panel of the run. Each
 * second every panel draws a few joules out of the batteries and generators
 * of the network it sits in; while the draw is met, the panel is lit, and
 * its backing block (a reserved copper bulb) is the actual light source. A
 * network that runs dry goes dark, panel by panel.
 */
class LightPanelTile(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data), EnergyBridge {

	private var valid = false

	/** The periodic are-we-actually-linked check; see [auditWire]. */
	private var nextAudit = 0L
	private var auditStrikes = 0

	override val isValid: Boolean
		get() = valid

	override var typeId: Key = Wiring.ownId("light_panel")
		private set

	override val linkedNodes: Set<NetworkNode>
		get() = emptySet()

	override val energyTransferRate: Long
		get() = 1_000_000_000L

	private val on: Boolean
		get() = blockState[GaugeCatalog.ON] == true

	override fun handleEnable() {
		super.handleEnable()

		typeId = Wiring.lineIdAt(pos, FACES) ?: typeId
		NetworkManager.queueRemoveBridge(this)
		NetworkManager.queueAddBridge(this, TYPES, FACES.toSet())
		valid = true
	}

	override fun handleDisable() {
		super.handleDisable()
		valid = false
	}

	override fun handleBreak(ctx: Context<BlockBreak>) {
		NetworkManager.queueRemoveBridge(this)
		valid = false
	}

	override fun handleTick() {
		val sampled = Wiring.lineIdAt(pos, FACES)

		if (sampled != null && sampled != typeId) {
			typeId = sampled
			NetworkManager.queueRemoveBridge(this)
			NetworkManager.queueAddBridge(this, TYPES, FACES.toSet())
		}

		auditWire()

		val lit = drain()

		if (lit != on) {
			updateBlockState(blockState.with(GaugeCatalog.ON, lit))
		}
	}

	/**
	 * Takes this second's joules out of the network the panel bridges: from
	 * the batteries and the producers' charge, wherever there is any. The
	 * panel is lit only when the full draw was met - half-fed light does not
	 * flicker, it fails, the way a real fixture browns out.
	 *
	 * Extraction is gated per FACE, not per holder: a holder may only be
	 * drained through a face it exposes to THIS network with extraction
	 * allowed. The blanket allowedConnectionType check was a backdoor - the
	 * one-way bridge's buffer is a BUFFER-typed holder whose inlet face is
	 * INSERT-only, and panels on the inlet side were quietly sucking the
	 * valve's charge backwards through it.
	 */
	private fun drain(): Boolean =
		GaugeSources.drainEnergy(pos, DRAIN_PER_SECOND) >= DRAIN_PER_SECOND

	/** The same stale-registration repair every wire device runs. */
	private fun auditWire() {
		val now = System.currentTimeMillis()

		if (now < nextAudit || auditStrikes >= MAX_AUDIT_STRIKES) {
			return
		}

		nextAudit = now + AUDIT_MS

		NetworkManager.queueRead(pos.chunkPos) { state ->
			val missing = Wiring.unlinkedFaces(state, this, pos, FACES)

			if (missing.isEmpty()) {
				auditStrikes = 0
				return@queueRead
			}

			auditStrikes++
			LunaSmp.logger.warn(
				"light panel at {} has unlinked faces {} (typeId {}, strike {}); re-adding its bridge",
				pos, missing, typeId, auditStrikes,
			)

			NetworkManager.queueRemoveBridge(this)
			NetworkManager.queueAddBridge(this, TYPES, FACES.toSet())
		}
	}

	private companion object {

		/** What one panel costs the wire, per second: 10 J/s read as free. */
		const val DRAIN_PER_SECOND = 40L

		const val AUDIT_MS = 5_000L
		const val MAX_AUDIT_STRIKES = 3

		val FACES = listOf(
			BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH,
			BlockFace.WEST, BlockFace.UP, BlockFace.DOWN,
		)

		val TYPES: Set<NetworkType<*>> = setOf(DefaultNetworkTypes.ENERGY)
	}
}
