package dev.belikhun.luna.smp.trungthu

import dev.belikhun.luna.smp.LunaSmp
import net.kyori.adventure.key.Key
import net.minecraft.world.level.block.Blocks
import org.bukkit.block.BlockFace
import org.bukkit.util.Vector
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
 * The plate a mooncake is cut on, the one block the mooncakes need. It lives
 * apart from [TrungThuCatalog] because the mooncakes stay on survival all
 * year while the rest of the Mid-Autumn set only ships in the event build
 * (`-Ptrungthu`, see build.gradle.kts).
 *
 * Constants and helpers first, registrations last: a Kotlin object
 * initialises top to bottom, and a registration that reads a value declared
 * below it gets null at boot.
 */
object MooncakeCatalog {

	/** Which mooncake is on a plate, by its index in [TrungThuTable.MOONCAKES]. */
	val VARIANT = IntProperty(Key.key("lunasmp", "variant"))

	/** How many quarters of the mooncake on a plate have been eaten. */
	val EATEN = IntProperty(Key.key("lunasmp", "eaten"))

	/** A mooncake is cut in four. */
	const val QUARTERS = 4

	private val WALLS = setOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

	/**
	 * The wall a piece turns its front to: the one facing the placer. A look
	 * along +z is a player facing south, and a piece facing them faces north.
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

	/**
	 * A plate with a mooncake on it, set down by sneaking with the cake in
	 * hand. It stands in an empty flower pot, the six-pixel box on the floor
	 * the plate fits, already hidden by the luna-hitboxes base pack. One
	 * block serves every kind of cake: the kind and the quarters eaten are
	 * both state, which is what picks the model.
	 */
	val MOONCAKE_PLATE: NovaBlock = LunaSmp.tileEntity("mooncake_plate", ::MooncakePlateTile) {
		stateProperties(
			DefaultBlockStateProperties.FACING.scope(WALLS) { ctx -> facingToward(ctx[DefaultContextParamTypes.SOURCE_DIRECTION]) },
			VARIANT.scope(0 until TrungThuTable.MOONCAKES.size) { 0 },
			EATEN.scope(0 until QUARTERS) { 0 },
		)

		entityBacked(stateSelector = { Blocks.FLOWER_POT.defaultBlockState() }) {
			val index = getPropertyValueOrNull(VARIANT) ?: 0
			val eaten = getPropertyValueOrNull(EATEN) ?: 0
			val cake = TrungThuTable.MOONCAKES[index.coerceIn(0, TrungThuTable.MOONCAKES.size - 1)]

			getModel("lunasmp:block/mooncake_plate_${cake.id}_$eaten").rotated()
		}

		behaviors(
			Breakable(hardness = 0.3),
			BlockSounds(SoundGroup.WOOL),
			TileEntityDrops,
			TileEntityInteractive,
		)
	}
}
