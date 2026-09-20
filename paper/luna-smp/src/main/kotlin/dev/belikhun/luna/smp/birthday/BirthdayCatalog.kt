package dev.belikhun.luna.smp.birthday

import dev.belikhun.luna.smp.LunaSmp
import dev.belikhun.luna.smp.Hitboxes
import dev.belikhun.luna.smp.furniture.FurnitureCatalog
import net.kyori.adventure.key.Key
import net.minecraft.world.level.block.Blocks
import org.bukkit.block.BlockFace
import org.bukkit.util.Vector
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.world.block.NovaBlock
import xyz.xenondevs.nova.world.block.behavior.BlockSounds
import xyz.xenondevs.nova.world.block.behavior.Breakable
import xyz.xenondevs.nova.world.block.behavior.TileEntityDrops
import xyz.xenondevs.nova.world.block.behavior.TileEntityInteractive
import xyz.xenondevs.nova.world.block.sound.SoundGroup
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.block.state.property.impl.IntProperty
import kotlin.math.abs

/**
 * The birthday set's blocks: the cake tower (two blocks, placed as one), the
 * gift box, and the plate a slice is served on.
 *
 * The models and the sheets are drawn by tools/furniture-gen/birthday.ts; the
 * behaviour is all hand-written here, because the set is one occasion's
 * worth of bespoke things rather than a table of pieces. Constants and
 * helper sets come first and the registrations last: a Kotlin object
 * initialises top to bottom, and a registration reading a value declared
 * below it gets null, which is how one catalog once took the server down at
 * boot.
 */
object BirthdayCatalog {

	const val CAKE_ID = "birthday_cake"
	const val CAKE_TOP_ID = "birthday_cake_top"
	const val GIFT_BOX_ID = "gift_box"
	const val PLATED_CAKE_ID = "plated_cake"
	const val CAKE_STAND_ID = "cake_stand"
	const val PLUSHIE_ID = "phuynhi_plushie"

	/** How many plates the lower tiers give before only the core is left. */
	const val SLICES = 8

	/** How many cuts have been taken out of the lower tiers. */
	val BITES = IntProperty(Key.key("lunasmp", "bites"))

	private val WALLS = setOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

	/**
	 * The wall the piece turns its front to: the one facing the placer, so
	 * the first cut of the cake and the front of a plate are toward whoever
	 * set it down. A look along +z is a player facing south, and a piece
	 * facing them faces north.
	 */
	fun facingToward(direction: Vector?): BlockFace {
		if (direction == null) {
			return BlockFace.NORTH
		}

		if (abs(direction.x) > abs(direction.z)) {
			return if (direction.x > 0) BlockFace.WEST else BlockFace.EAST
		}

		return if (direction.z > 0) BlockFace.NORTH else BlockFace.SOUTH
	}

	private fun facingOnPlace(ctx: Context<BlockPlace>): BlockFace =
		facingToward(ctx[DefaultContextParamTypes.SOURCE_DIRECTION])

	/**
	 * The lower block of the tower: the plate and the two big tiers, in a
	 * full barrier. Its model follows the number of cuts, and turns with the
	 * facing so the first cut is on the placer's side.
	 */
	val CAKE: NovaBlock = LunaSmp.tileEntity(CAKE_ID, ::CakeTile) {
		stateProperties(
			DefaultBlockStateProperties.FACING.scope(WALLS) { ctx -> facingOnPlace(ctx) },
			BITES.scope(0..SLICES) { 0 },
		)

		// every four ticks: sparks off the candles while they burn, and the
		// night sky's stars once the wish is made
		tickrate(16)

		entityBacked(stateSelector = { Blocks.BARRIER.defaultBlockState() }) {
			val bites = getPropertyValueOrNull(BITES) ?: 0

			val model = if (bites == 0) {
				defaultModel
			} else {
				getModel("lunasmp:block/${CAKE_ID}_bite$bites")
			}

			model.rotated()
		}

		behaviors(
			Breakable(hardness = 0.5),
			BlockSounds(SoundGroup.WOOL),
			TileEntityDrops,
			TileEntityInteractive,
		)
	}

	/**
	 * The upper block: the small top tier with its candles and the sparkler,
	 * standing in the eight-pixel heavy core the tier fills. It is placed by
	 * the lower block and never by hand, so it has no item; its lit state is
	 * the furniture's own LIT property.
	 */
	val CAKE_TOP: NovaBlock = LunaSmp.tileEntity(CAKE_TOP_ID, ::CakeTopTile) {
		stateProperties(FurnitureCatalog.LIT.scope(setOf(false, true)) { false })

		entityBacked(stateSelector = { Blocks.HEAVY_CORE.defaultBlockState() }) {
			if (getPropertyValueOrNull(FurnitureCatalog.LIT) == true) {
				defaultModel
			} else {
				getModel("lunasmp:block/${CAKE_TOP_ID}_off")
			}
		}

		behaviors(
			Breakable(hardness = 0.5),
			BlockSounds(SoundGroup.WOOL),
			TileEntityDrops,
			TileEntityInteractive,
		)
	}

	/**
	 * The gift box: a wrapped box whose lid lifts while somebody has it open.
	 * A full barrier; the box nearly fills its block.
	 */
	val GIFT_BOX: NovaBlock = LunaSmp.tileEntity(GIFT_BOX_ID, ::GiftBoxTile) {
		stateProperties(FurnitureCatalog.OPEN.scope(setOf(false, true)) { false })

		entityBacked(stateSelector = { Blocks.BARRIER.defaultBlockState() }) {
			if (getPropertyValueOrNull(FurnitureCatalog.OPEN) == true) {
				getModel("lunasmp:block/${GIFT_BOX_ID}_open")
			} else {
				defaultModel
			}
		}

		behaviors(
			Breakable(hardness = 0.5),
			BlockSounds(SoundGroup.WOOL),
			TileEntityDrops,
			TileEntityInteractive,
		)
	}

	/**
	 * A plate with a slice on it, set down by sneaking with the slice in
	 * hand. It stands in an empty flower pot, the six-pixel box on the floor
	 * of the block that the plate fits; the pot is already skinned invisible
	 * by the luna-hitboxes base pack for the planters. No item of its own:
	 * breaking it gives the slice back.
	 */
	val PLATED_CAKE: NovaBlock = LunaSmp.tileEntity(PLATED_CAKE_ID, ::PlatedCakeTile) {
		stateProperties(DefaultBlockStateProperties.FACING.scope(WALLS) { ctx -> facingOnPlace(ctx) })

		entityBacked(stateSelector = { Blocks.FLOWER_POT.defaultBlockState() }) {
			defaultModel.rotated()
		}

		behaviors(
			Breakable(hardness = 0.3),
			BlockSounds(SoundGroup.WOOL),
			TileEntityDrops,
			TileEntityInteractive,
		)
	}

	/**
	 * The empty cake stand: a plate on a short gold stem, placed where the
	 * cake will go. It stands in the three-pixel floor plate the walkways use
	 * (a powered iron trapdoor, skinned invisible), and the cake takes its
	 * place when it arrives.
	 */
	val CAKE_STAND: NovaBlock = LunaSmp.tileEntity(CAKE_STAND_ID, ::CakeStandTile) {
		entityBacked(stateSelector = { Hitboxes.plate(false) }) {
			defaultModel
		}

		behaviors(
			Breakable(hardness = 0.5),
			BlockSounds(SoundGroup.METAL),
			TileEntityDrops,
			TileEntityInteractive,
		)
	}

	/**
	 * The plushie: a sitting doll wearing her skin, turned to face whoever set
	 * it down. It sits in the eight-pixel heavy core, which is about the
	 * doll's own footprint.
	 */
	val PLUSHIE: NovaBlock = LunaSmp.tileEntity(PLUSHIE_ID, ::PlushieTile) {
		stateProperties(DefaultBlockStateProperties.FACING.scope(WALLS) { ctx -> facingOnPlace(ctx) })

		entityBacked(stateSelector = { Blocks.HEAVY_CORE.defaultBlockState() }) {
			defaultModel.rotated()
		}

		behaviors(
			Breakable(hardness = 0.3),
			BlockSounds(SoundGroup.WOOL),
			TileEntityDrops,
			TileEntityInteractive,
		)
	}
}
