package dev.belikhun.luna.smp.furniture

import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Levelled
import org.bukkit.entity.Player
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockBreak
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockInteract
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.resources.builder.layout.block.BlockSelectorScope
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.behavior.BlockBehavior
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.state.property.BlockStateProperty
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.block.state.property.impl.BooleanProperty
import xyz.xenondevs.nova.world.format.WorldDataManager

/**
 * Sit-able furniture: a right-click with an empty-ish intent seats the player
 * on the block, facing the way the furniture faces.
 */
class Seating(private val seatHeight: Double) : BlockBehavior {

	override fun handleInteract(pos: BlockPos, state: NovaBlockState, ctx: Context<BlockInteract>): Boolean {
		val player = ctx[DefaultContextParamTypes.SOURCE_ENTITY] as? Player ?: return false

		if (player.isSneaking) {
			return false
		}

		val facing = state[DefaultBlockStateProperties.FACING] ?: BlockFace.SOUTH
		val yaw = when (facing) {
			BlockFace.NORTH -> 180f
			BlockFace.EAST -> -90f
			BlockFace.WEST -> 90f
			else -> 0f
		}

		return FurnitureSeats.seat(player, pos, seatHeight, yaw)
	}
}

/**
 * A piece that is always lit.
 *
 * The vanilla block behind the model cannot emit light, so a vanilla light
 * block carries the glow and lives and dies with the furniture. The offset is
 * per piece because a chandelier hangs from the ceiling and must light the
 * block below it: a light block buried in the ceiling is simply dark.
 */
class EmitsLight(private val offset: Int, private val level: Int) : BlockBehavior {

	override fun handlePlace(pos: BlockPos, state: NovaBlockState, ctx: Context<BlockPlace>) {
		val target = pos.add(0, offset, 0).block

		if (!target.type.isAir) {
			return
		}

		target.type = Material.LIGHT

		val data = target.blockData as Levelled
		data.level = level
		target.blockData = data
	}

	override fun handleBreak(pos: BlockPos, state: NovaBlockState, ctx: Context<BlockBreak>) {
		val target = pos.add(0, offset, 0).block

		if (target.type == Material.LIGHT) {
			target.type = Material.AIR
		}
	}
}

/**
 * Swallows clicks that vanilla would turn into potting a plant.
 *
 * A standing planter's hitbox block is a real (invisible) flower pot, and
 * vanilla's pot interaction runs on the real block: without this, clicking a
 * planter with a sapling in hand would write a potted-plant block over the
 * hitbox and leave a visible vanilla pot inside the furniture.
 */
object PotGuard : BlockBehavior {

	override fun handleInteract(pos: BlockPos, state: NovaBlockState, ctx: Context<BlockInteract>): Boolean {
		val held = ctx[DefaultContextParamTypes.INTERACTION_ITEM_STACK] ?: return false

		return pottable(held.type)
	}

	private fun pottable(type: Material): Boolean {
		// the two names vanilla does not derive by prefixing POTTED_
		if (type == Material.AZALEA || type == Material.FLOWERING_AZALEA) {
			return true
		}

		return Material.matchMaterial("POTTED_" + type.name) != null
	}
}

/** The four sides a panel can reach out to, in the order a model spells them. */
private val HORIZONTAL = listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

private val LETTERS = mapOf(
	BlockFace.NORTH to "n",
	BlockFace.EAST to "e",
	BlockFace.SOUTH to "s",
	BlockFace.WEST to "w",
)

/**
 * Panels that join up with their neighbours the way a fence does.
 *
 * The model is one arm per connected side, so a run is straight, two runs
 * meeting make a corner and four make a crossing, all of it out of the block
 * state rather than out of separate corner pieces. A panel only joins its own
 * family: every trellis meets every other trellis, whatever it flowers, but a
 * lattice is a different screen and stands on its own.
 *
 * A panel always draws at least two arms: a lone one, or the last one left at
 * the end of a run, is a straight wall rather than a stub sticking out of
 * nowhere. That is the only place the shape is not simply "wherever the
 * neighbours are".
 */
object PanelConnect : BlockBehavior {

	override fun updateShape(pos: BlockPos, state: NovaBlockState, neighborPos: BlockPos): NovaBlockState {
		if (faceBetween(pos, neighborPos) == null) {
			return state
		}

		val family = FurnitureCatalog.CONNECTING[state.block.id.value()] ?: return state
		val current = armsOf(state)
		val arms = resolve(pos, family, current)

		return if (arms == current) state else state.with(valuesOf(arms))
	}

	/**
	 * The sides a panel reaches out to the moment it is placed: its neighbours
	 * if it has any, otherwise a straight wall across the placer's view.
	 */
	fun armsOnPlace(ctx: Context<BlockPlace>, family: String): Set<BooleanProperty> {
		return facesOnPlace(ctx, family).mapNotNullTo(HashSet()) { FurnitureCatalog.SIDES[it] }
	}

	/**
	 * Whether the placed panel turns its flowered face around. The base models
	 * face south (an east-west run) or west (a north-south run), so the panel
	 * flips when the placer stands on the other side of it: the flowers look
	 * at whoever plants them, whichever way they were facing.
	 *
	 * For a corner or a crossing there is no single "other side", so the
	 * dominant axis of the placer's view decides, the same way a lone panel
	 * picks the wall it stands across.
	 */
	fun flippedOnPlace(ctx: Context<BlockPlace>, family: String): Boolean {
		val direction = ctx[DefaultContextParamTypes.SOURCE_DIRECTION] ?: return false
		val arms = facesOnPlace(ctx, family)
		val northSouth = BlockFace.NORTH in arms || BlockFace.SOUTH in arms
		val eastWest = BlockFace.EAST in arms || BlockFace.WEST in arms

		return when {
			northSouth && !eastWest -> direction.x < 0
			eastWest && !northSouth -> direction.z > 0
			Math.abs(direction.x) > Math.abs(direction.z) -> direction.x < 0
			else -> direction.z > 0
		}
	}

	/** The faces the placed panel reaches out to, before they become properties. */
	private fun facesOnPlace(ctx: Context<BlockPlace>, family: String): Set<BlockFace> {
		val pos = ctx[DefaultContextParamTypes.BLOCK_POS]
		val facing = facingArms(ctx)

		return if (pos == null) facing else resolve(pos, family, facing)
	}

	/**
	 * Which arms a panel at [pos] draws: one per panel beside it, or [fallback]
	 * when it has nothing to join. A panel with a single neighbour reaches out
	 * the other way too, so the end of a run is a wall rather than a stub.
	 *
	 * A neighbour in a chunk that is not loaded is left as [fallback] says: a
	 * panel must not lose an arm just because the world it joins is asleep.
	 */
	private fun resolve(pos: BlockPos, family: String, fallback: Set<BlockFace>): Set<BlockFace> {
		val touching = HORIZONTAL.filterTo(HashSet()) { face ->
			val side = pos.add(face.modX, 0, face.modZ)

			if (loaded(side)) isPanel(side, family) else face in fallback
		}

		return when (touching.size) {
			0 -> fallback
			1 -> setOf(touching.first(), touching.first().oppositeFace)
			else -> touching
		}
	}

	/**
	 * The arms a block state actually draws, which is also the shape its pane
	 * hitbox takes: fewer than two stored arms complete to a straight run.
	 */
	fun drawnArms(scope: BlockSelectorScope): Set<BlockFace> {
		val arms = HORIZONTAL.filterTo(HashSet()) { face ->
			scope.getPropertyValueOrNull(FurnitureCatalog.SIDES[face]!!) == true
		}

		return complete(arms, BlockFace.EAST)
	}

	/** The model name for the arms this block state draws, such as `nes`. */
	fun armKey(scope: BlockSelectorScope): String {
		val drawn = drawnArms(scope)

		return HORIZONTAL.filter { it in drawn }.joinToString("") { LETTERS[it]!! }
	}

	private fun armsOf(state: NovaBlockState): Set<BlockFace> =
		HORIZONTAL.filterTo(HashSet()) { face -> state[FurnitureCatalog.SIDES[face]!!] == true }

	/** A state with fewer than two arms is never placed, but is still drawn. */
	private fun complete(arms: Set<BlockFace>, along: BlockFace): Set<BlockFace> = when (arms.size) {
		0 -> setOf(along, along.oppositeFace)
		1 -> setOf(arms.first(), arms.first().oppositeFace)
		else -> arms
	}

	private fun loaded(pos: BlockPos): Boolean =
		pos.world.isChunkLoaded(pos.x shr 4, pos.z shr 4)

	private fun valuesOf(arms: Set<BlockFace>): Map<BlockStateProperty<*>, Any> =
		FurnitureCatalog.SIDES.entries.associate { (face, property) -> property to (face in arms) }

	/** A wall placed with nothing to join stands across the placer's view. */
	private fun facingArms(ctx: Context<BlockPlace>): Set<BlockFace> {
		val direction = ctx[DefaultContextParamTypes.SOURCE_DIRECTION]

		if (direction != null && Math.abs(direction.x) > Math.abs(direction.z)) {
			return setOf(BlockFace.NORTH, BlockFace.SOUTH)
		}

		return setOf(BlockFace.EAST, BlockFace.WEST)
	}

	private fun isPanel(pos: BlockPos, family: String): Boolean {
		val block = WorldDataManager.getBlockState(pos)?.block ?: return false

		return block.id.namespace() == "lunasmp" && FurnitureCatalog.CONNECTING[block.id.value()] == family
	}

}

/** The horizontal face from [pos] toward a same-height [other], if adjacent. */
private fun faceBetween(pos: BlockPos, other: BlockPos): BlockFace? {
	if (pos.y != other.y) {
		return null
	}

	return when {
		other.x == pos.x + 1 && other.z == pos.z -> BlockFace.EAST
		other.x == pos.x - 1 && other.z == pos.z -> BlockFace.WEST
		other.z == pos.z + 1 && other.x == pos.x -> BlockFace.SOUTH
		other.z == pos.z - 1 && other.x == pos.x -> BlockFace.NORTH
		else -> null
	}
}

/**
 * How a run of tables becomes one table.
 *
 * Each side property answers "is the block there this same piece", kept
 * current by neighbour updates; the model selector hides the legs on shared
 * corners and the skirts along joined edges. Placement seeds the properties
 * through the scoped placement functions, so this behavior only has to keep
 * them true as the neighbourhood changes.
 */
object TableConnect : BlockBehavior {

	override fun updateShape(pos: BlockPos, state: NovaBlockState, neighborPos: BlockPos): NovaBlockState {
		val face = faceBetween(pos, neighborPos) ?: return state
		val property = FurnitureCatalog.SIDES[face] ?: return state
		val joined = FurnitureCatalog.sameBlock(neighborPos, state.block.id.value())

		return if (state[property] == joined) {
			state
		} else {
			state.with(property, joined)
		}
	}
}
