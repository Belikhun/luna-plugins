package dev.belikhun.luna.smp.power

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.GameMode
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.integration.protection.ProtectionManager
import xyz.xenondevs.nova.util.BlockUtils
import xyz.xenondevs.nova.util.CUBE_FACES
import xyz.xenondevs.nova.util.item.novaItem
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.NovaBlock
import xyz.xenondevs.nova.world.block.tileentity.network.NetworkManager
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkBridge
import xyz.xenondevs.nova.world.format.WorldDataManager
import xyz.xenondevs.nova.world.item.behavior.ItemBehavior
import xyz.xenondevs.nova.world.player.WrappedPlayerInteractEvent
import xyz.xenondevs.nova.world.pos
import kotlin.math.min

/**
 * What a wire cover does when it is clicked onto a cable: it takes the
 * cable's place.
 *
 * Placed on anything else, a cover is an ordinary block and Nova places it.
 * Aimed at a Logistics cable, the cable is swapped out for the cover, the
 * cover conducts on exactly the faces the cable did, and the cable goes back
 * into the player's inventory, so covering a run costs the covers and
 * nothing else, and changes nothing about how the run is wired.
 *
 * Finding the cable is the one subtle part. A straight cable backs onto a
 * chain block, which a click lands on; a junction or a lone cable backs onto
 * a structure void, which a click passes straight through to whatever is
 * behind it. So when the clicked block is not a cable, the player's line of
 * sight is walked as far as that block, and the first cable on it is the one
 * they were looking at.
 */
object WireCoverPlacer : ItemBehavior {

	override fun handleInteract(
		player: Player,
		itemStack: ItemStack,
		action: Action,
		wrappedEvent: WrappedPlayerInteractEvent,
	) {
		if (action != Action.RIGHT_CLICK_BLOCK) {
			return
		}

		val event = wrappedEvent.event
		val hand = event.hand ?: EquipmentSlot.HAND
		val cover = itemStack.novaItem?.block ?: return
		val clicked = event.clickedBlock?.pos ?: return
		val target = cableAt(player, clicked) ?: return

		event.isCancelled = true
		wrappedEvent.actionPerformed = true

		if (!ProtectionManager.canBreak(player, itemStack, target) || !ProtectionManager.canPlace(player, itemStack, target)) {
			player.sendActionBar(Component.text("Bạn không được sửa dây ở đây.", NamedTextColor.RED))
			return
		}

		val cable = WorldDataManager.getTileEntity(target) as? NetworkBridge ?: return

		// the faces live in the network state, which is only readable off
		// the main thread; the swap itself has to happen back on it
		NetworkManager.queueRead(target.chunkPos) { state ->
			val faces = runCatching { state.getBridgeFaces(cable).toSet() }.getOrDefault(CUBE_FACES)

			runTask {
				swap(player, hand, cover, target, faces)
			}
		}
	}

	private fun swap(player: Player, hand: EquipmentSlot, cover: NovaBlock, target: BlockPos, faces: Set<BlockFace>) {
		val cableState = WorldDataManager.getBlockState(target) ?: return

		// the read was queued; the cable may have gone in between
		if (!isCable(cableState.block.id)) {
			return
		}

		val refund = cableState.block.item?.createItemStack()

		// placing over the cable breaks it first, with its drops discarded,
		// which is why the cable item is handed back by hand below
		val ctx = Context.intention(BlockPlace)
			.param(DefaultContextParamTypes.BLOCK_POS, target)
			.param(DefaultContextParamTypes.BLOCK_TYPE_NOVA, cover)
			.param(DefaultContextParamTypes.SOURCE_PLAYER, player)
			.param(DefaultContextParamTypes.BLOCK_PLACE_EFFECTS, true)
			.build()

		if (!BlockUtils.placeBlock(ctx)) {
			return
		}

		(WorldDataManager.getTileEntity(target) as? WireCoverTile)?.adoptFaces(faces)

		if (player.gameMode != GameMode.CREATIVE) {
			val held = player.inventory.getItem(hand)

			if (held.novaItem?.block == cover) {
				held.amount -= 1
			}

			if (refund != null) {
				val leftover = player.inventory.addItem(refund)

				for (stack in leftover.values) {
					player.world.dropItemNaturally(player.location, stack)
				}
			}
		}

		player.sendActionBar(Component.text("Đã bọc dây điện; dây cũ trả về túi.", NamedTextColor.GREEN))
	}

	/**
	 * The cable the player is looking at, or null when there is none: the
	 * clicked block itself, or the first cable along the line of sight before
	 * it, for the void-backed cables a click goes straight through.
	 */
	private fun cableAt(player: Player, clicked: BlockPos): BlockPos? {
		if (isCableAt(clicked)) {
			return clicked
		}

		val eye = player.eyeLocation
		val direction = eye.direction.normalize()
		val hit = clicked.location.toVector().add(Vector(0.5, 0.5, 0.5))
		val reach = min(eye.toVector().distance(hit) + 1.0, MAX_REACH)

		var distance = 0.0
		var last: BlockPos? = null

		while (distance <= reach) {
			val at = eye.clone().add(direction.clone().multiply(distance)).pos

			if (at != last) {
				last = at

				if (at == clicked) {
					break
				}

				if (isCableAt(at)) {
					return at
				}
			}

			distance += STEP
		}

		return null
	}

	private fun isCableAt(pos: BlockPos): Boolean {
		val id = WorldDataManager.getBlockState(pos)?.block?.id ?: return false
		return isCable(id)
	}

	private fun isCable(id: Key): Boolean =
		id.namespace() == "logistics" && id.value().endsWith("_cable")

	/** How far along the line of sight a cable is looked for, in blocks. */
	private const val MAX_REACH = 6.0

	/** The walk's step: fine enough never to skip a block corner-on. */
	private const val STEP = 0.1
}
