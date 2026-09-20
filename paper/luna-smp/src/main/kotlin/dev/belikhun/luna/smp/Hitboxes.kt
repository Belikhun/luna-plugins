package dev.belikhun.luna.smp

import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Half
import org.bukkit.block.BlockFace

/**
 * The backing states shared by everything hung flat on a surface.
 *
 * A resin clump is the one vanilla block that lies flat against its wall,
 * holds onto any face, and does nothing at all when clicked. The warped
 * trapdoors these replaced swing open and play their sound on the client,
 * before the server ever gets to refuse the interaction, so consuming the
 * click server-side could never stop the flicker.
 */
object Hitboxes {

	/**
	 * A clump stuck to the wall behind a panel that looks toward [facing]:
	 * the one-pixel outline hugs the wall exactly where the panel hangs.
	 */
	fun clumpAgainst(facing: BlockFace): BlockState = Blocks.RESIN_CLUMP.defaultBlockState()
		.setValue(BlockStateProperties.NORTH, facing == BlockFace.SOUTH)
		.setValue(BlockStateProperties.SOUTH, facing == BlockFace.NORTH)
		.setValue(BlockStateProperties.EAST, facing == BlockFace.WEST)
		.setValue(BlockStateProperties.WEST, facing == BlockFace.EAST)

	/** A clump stuck to the ceiling, for the fittings that hang from it. */
	fun clumpOnCeiling(): BlockState = Blocks.RESIN_CLUMP.defaultBlockState()
		.setValue(BlockStateProperties.UP, true)

	/**
	 * A bare fence post: the four-pixel column through the middle of the
	 * block, full height, with a fence's real collision. The pole pieces
	 * stand in this; Nova's mixin keeps the neighbours from ever growing it
	 * arms, exactly as it keeps a clump from popping off its wall.
	 */
	fun post(): BlockState = Blocks.WARPED_FENCE.defaultBlockState()

	/**
	 * A bare wall post: the eight-pixel column through the middle of the
	 * block, full height. The line devices (switches, the one-way bridge)
	 * are authored around the cable at block centre, so their click target
	 * has to be centred too - a floor-hugging core misses half the body,
	 * and all of it on a vertical run. Resin brick walls never generate,
	 * so the sacrifice costs the world nothing.
	 */
	fun linePost(): BlockState = Blocks.RESIN_BRICK_WALL.defaultBlockState()

	/**
	 * A closed plate three pixels thick on the floor of the block, or on its
	 * ceiling: an IRON trapdoor, the one trapdoor a hand click never moves,
	 * so clients predict nothing on it - the warped trapdoor the walkways
	 * would otherwise borrow swings and sounds client-side on every click.
	 * POWERED marks the state as ours, exactly as the iron door's does: the
	 * luna-hitboxes skin hides only powered iron trapdoors, and a real one
	 * stays visible unless redstone holds it.
	 */
	fun plate(top: Boolean): BlockState = Blocks.IRON_TRAPDOOR.defaultBlockState()
		.setValue(BlockStateProperties.HALF, if (top) Half.TOP else Half.BOTTOM)
		.setValue(BlockStateProperties.OPEN, false)
		.setValue(BlockStateProperties.POWERED, true)
}
