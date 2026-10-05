package dev.belikhun.luna.smp.trungthu

import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockBreak
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockInteract
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.util.BlockUtils
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.TileEntity
import kotlin.math.ceil
import kotlin.math.min

/**
 * A mooncake on its plate, cut in four. Each right-click eats a quarter and
 * the model shows what is left, the yolk in the cut; the last quarter takes
 * the plate with it. Breaking a whole cake gives it back; a cut one, like a
 * vanilla cake, gives nothing.
 */
class MooncakePlateTile(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data) {

	private val index: Int
		get() = (blockState[MooncakeCatalog.VARIANT] ?: 0).coerceIn(0, TrungThuTable.MOONCAKES.size - 1)

	private val eaten: Int
		get() = blockState[MooncakeCatalog.EATEN] ?: 0

	private var finished = false

	init {
		dropProvider {
			if (finished || eaten > 0) {
				emptyList()
			} else {
				listOfNotNull(MooncakeItems.MOONCAKES[TrungThuTable.MOONCAKES[index].id]?.createItemStack())
			}
		}
	}

	override fun handleRightClick(ctx: Context<BlockInteract>): Boolean {
		val player = ctx[DefaultContextParamTypes.SOURCE_ENTITY] as? Player ?: return false
		val hand = ctx[DefaultContextParamTypes.INTERACTION_HAND] ?: EquipmentSlot.HAND

		if (hand != EquipmentSlot.HAND || player.isSneaking) {
			return false
		}

		return eatQuarter(player)
	}

	/**
	 * Eats one quarter, feeding [player] when there is one (the self-test eats
	 * with nobody), and takes the plate away with the last. False once it is
	 * already finished.
	 */
	fun eatQuarter(player: Player?): Boolean {
		if (finished) {
			return false
		}

		val cake = TrungThuTable.MOONCAKES[index]
		val nutrition = ceil(cake.nutrition / QUARTERS.toDouble()).toInt()
		val saturation = cake.saturation / QUARTERS

		if (player != null) {
			player.foodLevel = min(20, player.foodLevel + nutrition)
			player.saturation = min(player.foodLevel.toFloat(), player.saturation + saturation)
			MooncakeBehavior.applyEffect(player, cake.effect, 1.0 / QUARTERS)
		}

		val centre = pos.location.add(0.5, 0.35, 0.5)
		pos.world.playSound(centre, "entity.generic.eat", SoundCategory.PLAYERS, 0.9f, 1f)
		pos.world.spawnParticle(Particle.ITEM, centre, 8, 0.15, 0.1, 0.15, 0.04, ItemStack(Material.COOKIE))

		val next = eaten + 1

		if (next < QUARTERS) {
			updateBlockState(blockState.with(MooncakeCatalog.EATEN, next))
			return true
		}

		finished = true
		pos.world.playSound(centre, "entity.player.burp", SoundCategory.PLAYERS, 0.6f, 1f)

		// breakBlock, not breakBlockNaturally: an empty plate gives nothing back
		runTask {
			if (isEnabled) {
				BlockUtils.breakBlock(
					Context.intention(BlockBreak)
						.param(DefaultContextParamTypes.BLOCK_POS, pos)
						.param(DefaultContextParamTypes.BLOCK_DROPS, false)
						.param(DefaultContextParamTypes.BLOCK_BREAK_EFFECTS, false)
						.build(),
				)
			}
		}

		return true
	}

	private companion object {

		const val QUARTERS = MooncakeCatalog.QUARTERS
	}
}
