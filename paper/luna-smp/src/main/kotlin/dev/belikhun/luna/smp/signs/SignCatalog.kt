package dev.belikhun.luna.smp.signs

import dev.belikhun.luna.smp.Hitboxes
import dev.belikhun.luna.smp.LunaSmp
import net.kyori.adventure.key.Key
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.bukkit.block.BlockFace
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.resources.builder.layout.block.BlockSelectorScope
import xyz.xenondevs.nova.world.block.AbstractNovaBlockBuilder
import xyz.xenondevs.nova.world.block.NovaBlock
import xyz.xenondevs.nova.world.block.behavior.BlockDrops
import xyz.xenondevs.nova.world.block.behavior.BlockSounds
import xyz.xenondevs.nova.world.block.behavior.Breakable
import xyz.xenondevs.nova.world.block.behavior.TileEntityDrops
import xyz.xenondevs.nova.world.block.behavior.TileEntityInteractive
import xyz.xenondevs.nova.world.block.sound.SoundGroup
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.block.state.property.impl.BooleanProperty
import kotlin.math.abs

/**
 * The generated sign table.
 *
 * GENERATED FILE - do not edit by hand. Change tools/furniture-gen/signs.ts and
 * re-run the generator; the models, configs and language files beside this table
 * come from the same pass, so editing one of them alone drifts the set.
 *
 * Every sign is one flat, two-sided plane wearing a 32x32 painted face, which is
 * what lets a triangular sign actually be triangular: the corners of the texture
 * are transparent and there is no geometry behind them.
 *
 * A sign is placed one of two ways, decided by what was clicked. Against a wall
 * it hangs flat and looks away from that wall, and its shape is a trapdoor held
 * open - three pixels against the wall. On the ground or a ceiling it stands on
 * its own post instead, and its shape is a glass pane: a two-pixel column
 * through the whole block with the panel's width across it. Both vanilla blocks
 * are already skinned invisible by the hitbox base pack.
 *
 * Signs that carry words carry a text display rather than a texture; see
 * [SignTile].
 */
@Suppress("unused")
object SignCatalog {

	/** Whether the sign stands on its own post rather than hanging on a wall. */
	val POSTED = BooleanProperty(Key.key("lunasmp", "posted"))

	/** The walls a sign can hang on. */
	private val WALLS = setOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

	/**
	 * Where a sign's face sits, in blocks from the north edge of its own block,
	 * with the model authored facing north. The text display is parked a hair in
	 * front of this.
	 */
	const val WALL_FACE = 0.9000

	/** The same, for a sign on a post: the panel stands nearer the middle. */
	const val POST_FACE = 0.4313

	/** How a sign is written on, when it is written on at all. */
	data class Text(
		/** How wide one block of board is, in blocks, inside its border. */
		val width: Double,
		/** How tall one block of board is. */
		val height: Double,
		/** Characters that fit across one block of board. */
		val across: Int,
		/** The colour text takes when it names no colour of its own. */
		val colour: Int,
		val shadow: Boolean,
		/**
		 * Boards of this kind that touch, in the same plane and facing the same
		 * way, merge into one: a solid rectangle of blackboards is a single
		 * board with a single text display spanning it.
		 */
		val merges: Boolean,
	)

	data class Spec(
		val id: String,
		/** Present when the sign carries words rather than a pictogram. */
		val text: Text? = null,
		/** The bare pole a sign stands on: no face, no facing, no mounting. */
		val post: Boolean = false,
		val hardness: Double = 1.0,
		val sounds: SoundGroup = SoundGroup.STONE,
	)

	private val SPECS = listOf(
		Spec(id = "sign_stop", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_give_way", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_no_entry", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_speed_30", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_speed_50", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_speed_80", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_no_parking", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_parking", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_crossing", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_no_pedestrians", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_roadworks", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_priority_road", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_one_way", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_hard_hat", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_ear_protection", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_eye_protection", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_no_smoking", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_no_fire", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_slippery", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_falling_objects", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_first_aid", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_emergency_exit", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_fire_extinguisher", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_high_voltage", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_danger_of_death", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_earth_point", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "sign_no_water", hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "street_sign", text = Text(width = 0.82, height = 0.44, across = 14, colour = 0xffffff, shadow = false, merges = false), hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "notice_board", text = Text(width = 0.72, height = 0.72, across = 16, colour = 0x2a2622, shadow = false, merges = false), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "blackboard", text = Text(width = 0.94, height = 0.94, across = 16, colour = 0xf2f0e6, shadow = false, merges = true), hardness = 1.2, sounds = SoundGroup.STONE),
		Spec(id = "sign_post", post = true, hardness = 1.0, sounds = SoundGroup.STONE),
	)

	/** Every sign's spec, by block id. */
	val BY_ID: Map<String, Spec> = SPECS.associateBy { it.id }

	/** Every sign block, by its id. */
	val BLOCKS: Map<String, NovaBlock> = SPECS.associate { it.id to register(it) }

	private fun register(spec: Spec): NovaBlock {
		if (spec.post) {
			return LunaSmp.block(spec.id) {
				// a glass pane with nothing to connect to is the two-pixel
				// column through the middle of the block - the same shape a
				// single iron bar has, because a stained glass pane *is* an
				// IronBarsBlock with the same node width. So the outline sits
				// on the pole, and a stack of poles collides as one post.
				entityBacked(stateSelector = { Blocks.MAGENTA_STAINED_GLASS_PANE.defaultBlockState() }) {
					defaultModel
				}

				behaviors(Breakable(hardness = spec.hardness), BlockSounds(spec.sounds), BlockDrops)
			}
		}

		// a pictogram says everything it has to say in its texture, so it needs
		// no tile entity, no display entity and nothing stored
		if (spec.text == null) {
			return LunaSmp.block(spec.id) {
				configure(spec)
				behaviors(Breakable(hardness = spec.hardness), BlockSounds(spec.sounds), BlockDrops)
			}
		}

		return LunaSmp.tileEntity(spec.id, ::SignTile) {
			configure(spec)
			// nothing to do per tick: a board changes when somebody writes on
			// it or when a neighbour appears, and both of those are events
			tickrate(0)
			behaviors(
				Breakable(hardness = spec.hardness),
				BlockSounds(spec.sounds),
				TileEntityDrops,
				TileEntityInteractive,
			)
		}
	}

	private fun AbstractNovaBlockBuilder<*>.configure(spec: Spec) {
		stateProperties(
			DefaultBlockStateProperties.FACING.scope(WALLS) { ctx -> facingOnPlace(ctx) },
			POSTED.scope(setOf(false, true)) { ctx -> postedOnPlace(ctx) },
		)

		entityBacked(stateSelector = { hitboxOf(this) }) {
			val model = if (getPropertyValueOrNull(POSTED) == true) {
				getModel("lunasmp:block/${spec.id}_post")
			} else {
				defaultModel
			}

			model.rotated()
		}
	}

	/**
	 * Which way a sign looks when it is placed.
	 *
	 * Clicking a wall hangs it on that wall, so it looks along the face that was
	 * clicked - away from the block it is stuck to. Anywhere else it is on a
	 * post, and turns to face whoever put it there.
	 */
	private fun facingOnPlace(ctx: Context<BlockPlace>): BlockFace {
		val clicked = ctx[DefaultContextParamTypes.CLICKED_BLOCK_FACE]

		if (clicked != null && clicked in WALLS) {
			return clicked
		}

		val direction = ctx[DefaultContextParamTypes.SOURCE_DIRECTION] ?: return BlockFace.NORTH

		if (abs(direction.x) > abs(direction.z)) {
			return if (direction.x > 0) BlockFace.WEST else BlockFace.EAST
		}

		return if (direction.z > 0) BlockFace.NORTH else BlockFace.SOUTH
	}

	/** A sign is on a post unless it was hung on a wall. */
	private fun postedOnPlace(ctx: Context<BlockPlace>): Boolean {
		val clicked = ctx[DefaultContextParamTypes.CLICKED_BLOCK_FACE]

		return clicked == null || clicked !in WALLS
	}

	private fun hitboxOf(scope: BlockSelectorScope): BlockState {
		val facing = scope.getPropertyValueOrNull(DefaultBlockStateProperties.FACING) ?: BlockFace.NORTH

		if (scope.getPropertyValueOrNull(POSTED) == true) {
			return paneState(facing)
		}

		return Hitboxes.clumpAgainst(facing)
	}

	/**
	 * The pane state a post-mounted sign borrows: the post itself plus the two
	 * arms lying across the sign's facing, which is the panel's own width.
	 */
	private fun paneState(facing: BlockFace): BlockState {
		val northSouth = facing == BlockFace.EAST || facing == BlockFace.WEST

		return Blocks.MAGENTA_STAINED_GLASS_PANE.defaultBlockState()
			.setValue(BlockStateProperties.NORTH, northSouth)
			.setValue(BlockStateProperties.SOUTH, northSouth)
			.setValue(BlockStateProperties.EAST, !northSouth)
			.setValue(BlockStateProperties.WEST, !northSouth)
	}

}
