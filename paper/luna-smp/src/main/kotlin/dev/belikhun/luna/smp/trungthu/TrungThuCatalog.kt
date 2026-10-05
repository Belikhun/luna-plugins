package dev.belikhun.luna.smp.trungthu

import dev.belikhun.luna.smp.LunaSmp
import net.kyori.adventure.key.Key
import net.minecraft.world.level.block.Blocks
import org.bukkit.block.BlockFace
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

/**
 * The Mid-Autumn blocks: every lantern (stood on a floor, hung on a wall,
 * hung from a ceiling), the lion-dance drum, the pomelo dog, the feast tray
 * and the tò he post. The mooncake plate is in [MooncakeCatalog].
 *
 * Models and ids come from tools/furniture-gen/trungthu.ts through
 * [TrungThuTable]. Constants and helpers first, registrations last: a Kotlin
 * object initialises top to bottom, and a registration that reads a value
 * declared below it gets null at boot.
 */
object TrungThuCatalog {

	const val FLOOR = 0
	const val WALL = 1
	const val CEILING = 2

	/** How a lantern is mounted: on the floor, on a wall, or from a ceiling. */
	val MOUNT = IntProperty(Key.key("lunasmp", "mount"))

	private val WALLS = setOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

	/** Clicking a ceiling hangs it, clicking a wall hangs it there, anything else stands it up. */
	private fun mountOnPlace(ctx: Context<BlockPlace>): Int =
		when (ctx[DefaultContextParamTypes.CLICKED_BLOCK_FACE]) {
			BlockFace.DOWN -> CEILING
			in WALLS -> WALL
			else -> FLOOR
		}

	/** On a wall the lantern looks out along the clicked face; elsewhere it turns to the placer. */
	private fun facingOnPlace(ctx: Context<BlockPlace>): BlockFace {
		val clicked = ctx[DefaultContextParamTypes.CLICKED_BLOCK_FACE]

		if (clicked != null && clicked in WALLS) {
			return clicked
		}

		return MooncakeCatalog.facingToward(ctx[DefaultContextParamTypes.SOURCE_DIRECTION])
	}

	/**
	 * One lantern. It stands in a structure void, the small box in the middle
	 * of the block with no collision: every mount keeps the lantern's body
	 * round the middle, and a lantern is not something to bump into.
	 */
	private fun registerLantern(lantern: TrungThuTable.Lantern): NovaBlock =
		LunaSmp.tileEntity(lantern.id, ::LanternTile) {
			stateProperties(
				DefaultBlockStateProperties.FACING.scope(WALLS) { ctx -> facingOnPlace(ctx) },
				MOUNT.scope(FLOOR..CEILING) { ctx -> mountOnPlace(ctx) },
			)

			// every tick while its tune plays; the light is kept once a second
			tickrate(20)

			entityBacked(stateSelector = { Blocks.STRUCTURE_VOID.defaultBlockState() }) {
				val model = when (getPropertyValueOrNull(MOUNT)) {
					WALL -> getModel("lunasmp:block/${lantern.id}_wall")
					CEILING -> getModel("lunasmp:block/${lantern.id}_ceiling")
					else -> defaultModel
				}

				model.rotated()
			}

			behaviors(
				Breakable(hardness = 0.2),
				BlockSounds(SoundGroup.WOOL),
				TileEntityDrops,
				TileEntityInteractive,
			)
		}

	/** Every lantern block, by id. */
	val LANTERNS: Map<String, NovaBlock> = TrungThuTable.LANTERNS.associate { it.id to registerLantern(it) }

	/**
	 * The lion-dance drum on its stand. A full barrier: the drum fills most
	 * of its block, and a barrier lets room light reach the display.
	 */
	val LION_DRUM: NovaBlock = LunaSmp.tileEntity("lion_drum", ::DrumTile) {
		stateProperties(
			DefaultBlockStateProperties.FACING.scope(WALLS) { ctx -> MooncakeCatalog.facingToward(ctx[DefaultContextParamTypes.SOURCE_DIRECTION]) },
		)

		// every tick while it is being played: the beat is timed in ticks
		tickrate(20)

		entityBacked(stateSelector = { Blocks.BARRIER.defaultBlockState() }) {
			defaultModel.rotated()
		}

		behaviors(
			Breakable(hardness = 0.8),
			BlockSounds(SoundGroup.WOOD),
			TileEntityDrops,
			TileEntityInteractive,
		)
	}

	/** The pomelo dog, turned to whoever set it down, in the eight-pixel heavy core. */
	val POMELO_DOG: NovaBlock = LunaSmp.tileEntity("pomelo_dog", ::PomeloDogTile) {
		stateProperties(
			DefaultBlockStateProperties.FACING.scope(WALLS) { ctx -> MooncakeCatalog.facingToward(ctx[DefaultContextParamTypes.SOURCE_DIRECTION]) },
		)

		entityBacked(stateSelector = { Blocks.HEAVY_CORE.defaultBlockState() }) {
			defaultModel.rotated()
		}

		behaviors(
			Breakable(hardness = 0.2),
			BlockSounds(SoundGroup.WOOL),
			TileEntityDrops,
			TileEntityInteractive,
		)
	}

	/**
	 * Mâm cỗ Trung Thu, the feast tray: pure decoration, turned to whoever
	 * set it down, in the six-pixel flower pot like the mooncake plate.
	 */
	val MAM_CO: NovaBlock = LunaSmp.block("mam_co") {
		stateProperties(
			DefaultBlockStateProperties.FACING.scope(WALLS) { ctx -> MooncakeCatalog.facingToward(ctx[DefaultContextParamTypes.SOURCE_DIRECTION]) },
		)

		entityBacked(stateSelector = { Blocks.FLOWER_POT.defaultBlockState() }) {
			defaultModel.rotated()
		}

		behaviors(
			Breakable(hardness = 0.3),
			BlockSounds(SoundGroup.WOOD),
		)
	}

	/** A tò he seller's straw post, stood where it is set, with no collision. */
	val TO_HE: NovaBlock = LunaSmp.block("to_he") {
		stateProperties(
			DefaultBlockStateProperties.FACING.scope(WALLS) { ctx -> MooncakeCatalog.facingToward(ctx[DefaultContextParamTypes.SOURCE_DIRECTION]) },
		)

		entityBacked(stateSelector = { Blocks.STRUCTURE_VOID.defaultBlockState() }) {
			defaultModel.rotated()
		}

		behaviors(
			Breakable(hardness = 0.2),
			BlockSounds(SoundGroup.GRASS),
		)
	}
}
