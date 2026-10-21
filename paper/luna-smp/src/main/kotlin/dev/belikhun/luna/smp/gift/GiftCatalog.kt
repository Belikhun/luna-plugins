package dev.belikhun.luna.smp.gift

import dev.belikhun.luna.smp.LunaSmp
import dev.belikhun.luna.smp.birthday.BirthdayCatalog
import net.minecraft.world.level.block.Blocks
import org.bukkit.block.BlockFace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.world.block.NovaBlock
import xyz.xenondevs.nova.world.block.behavior.BlockDrops
import xyz.xenondevs.nova.world.block.behavior.BlockSounds
import xyz.xenondevs.nova.world.block.behavior.Breakable
import xyz.xenondevs.nova.world.block.sound.SoundGroup
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties

/**
 * The gift set's blocks: for now the love bouquet, a big bunch of flowers in
 * a paper wrap that stands where it is put down, on a table or on the ground.
 *
 * The model and its sheets come from tools/furniture-gen/bouquet.ts. It is a
 * plain block, no tile entity: it does nothing but look the part and come
 * back as itself when picked up.
 */
object GiftCatalog {

	const val BOUQUET_ID = "bo_hoa_tinh_yeu"

	private val WALLS = setOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

	/**
	 * The bouquet, turning its bow to whoever sets it down. It sits in the
	 * eight-pixel heavy core, about the width of its wrap, so it can be aimed
	 * at and walked round.
	 */
	val BOUQUET: NovaBlock = LunaSmp.block(BOUQUET_ID) {
		stateProperties(
			DefaultBlockStateProperties.FACING.scope(WALLS) { ctx ->
				BirthdayCatalog.facingToward(ctx[DefaultContextParamTypes.SOURCE_DIRECTION])
			},
		)

		entityBacked(stateSelector = { Blocks.HEAVY_CORE.defaultBlockState() }) {
			defaultModel.rotated()
		}

		behaviors(
			Breakable(hardness = 0.3),
			BlockSounds(SoundGroup.GRASS),
			BlockDrops,
		)
	}
}
