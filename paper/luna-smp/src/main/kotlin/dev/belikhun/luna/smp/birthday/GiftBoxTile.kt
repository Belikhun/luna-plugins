package dev.belikhun.luna.smp.birthday

import dev.belikhun.luna.smp.furniture.FurnitureCatalog
import org.bukkit.Particle
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockInteract
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.TileEntity
import xyz.xenondevs.nova.world.block.tileentity.menu.TileEntityMenuClass

/**
 * The gift box: a small chest wrapped in paper. Its twenty-seven slots are
 * persistent, so breaking the box keeps what is inside it on the item, the
 * way a shulker box does, and placing that item brings it all back - which
 * is what lets one person pack it and another unwrap it.
 *
 * The lid lifts while anybody has it open, and the first time somebody other
 * than the one who packed it looks inside, it makes a little more of a fuss.
 */
class GiftBoxTile(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data) {

	private val inventory = storedInventory("gifts", 27, persistent = true)

	/** How many windows on this box are open right now; the lid follows it. */
	private var viewers = 0

	/** Whether somebody other than the packer has already opened it. */
	private var unwrapped: Boolean
		get() = retrieveDataOrNull<Boolean>("unwrapped") ?: false
		set(value) = storeData("unwrapped", value)

	@TileEntityMenuClass
	inner class GiftMenu : GlobalTileEntityMenu() {

		override val gui = Gui.builder()
			.setStructure(
				"x x x x x x x x x",
				"x x x x x x x x x",
				"x x x x x x x x x",
			)
			.addIngredient('x', inventory)
			.build()

		init {
			windowBuilder.addOpenHandler { opened() }
			windowBuilder.addCloseHandler { closed() }
		}
	}

	override fun handleRightClick(ctx: Context<BlockInteract>): Boolean {
		val player = ctx[DefaultContextParamTypes.SOURCE_ENTITY] as? Player ?: return false
		val hand = ctx[DefaultContextParamTypes.INTERACTION_HAND] ?: EquipmentSlot.HAND

		// sneaking is the build gesture everywhere in this addon
		if (hand != EquipmentSlot.HAND || player.isSneaking) {
			return false
		}

		val stranger = ownerUuid != null && ownerUuid != player.uniqueId

		if (stranger && !unwrapped && !inventory.isEmpty) {
			unwrapped = true
			celebrate()
		}

		menuContainer.openWindow(player)

		return true
	}

	private fun opened() {
		viewers++
		setOpen(true)

		val centre = centre()
		pos.world.playSound(centre, "block.chest.open", SoundCategory.BLOCKS, 0.7f, 1.2f)
		pos.world.playSound(centre, "block.amethyst_block.chime", SoundCategory.BLOCKS, 0.8f, 1.3f)
		pos.world.spawnParticle(Particle.HEART, centre.add(0.0, 0.6, 0.0), 4, 0.35, 0.2, 0.35, 0.0)
	}

	private fun closed() {
		viewers = maxOf(0, viewers - 1)

		if (viewers == 0) {
			setOpen(false)
			pos.world.playSound(centre(), "block.chest.close", SoundCategory.BLOCKS, 0.6f, 1.2f)
		}
	}

	/** The first unwrapping: a burst of hearts and the little fanfare of a finished challenge. */
	private fun celebrate() {
		val centre = centre()
		pos.world.playSound(centre, "ui.toast.challenge_complete", SoundCategory.PLAYERS, 0.8f, 1f)
		pos.world.spawnParticle(Particle.HEART, centre.add(0.0, 0.8, 0.0), 14, 0.7, 0.5, 0.7, 0.0)
		pos.world.spawnParticle(Particle.TOTEM_OF_UNDYING, centre, 40, 0.3, 0.3, 0.3, 0.4)
	}

	private fun setOpen(value: Boolean) {
		if (!isEnabled || (blockState[FurnitureCatalog.OPEN] == true) == value) {
			return
		}

		updateBlockState(blockState.with(FurnitureCatalog.OPEN, value))
	}

	private fun centre() = pos.location.add(0.5, 0.5, 0.5)
}
