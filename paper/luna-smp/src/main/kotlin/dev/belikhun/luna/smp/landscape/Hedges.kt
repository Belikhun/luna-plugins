package dev.belikhun.luna.smp.landscape

import dev.belikhun.luna.smp.LunaSmp
import net.kyori.adventure.key.Key
import org.bukkit.block.BlockFace
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.util.BlockUtils
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.NovaBlock
import xyz.xenondevs.nova.world.block.behavior.BlockBehavior
import xyz.xenondevs.nova.world.block.behavior.BlockDrops
import xyz.xenondevs.nova.world.block.behavior.BlockSounds
import xyz.xenondevs.nova.world.block.behavior.Breakable
import xyz.xenondevs.nova.world.block.sound.SoundGroup
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.state.property.impl.BooleanProperty
import xyz.xenondevs.nova.world.format.WorldDataManager
import xyz.xenondevs.nova.world.item.NovaItem

/**
 * Flower hedges: the flowering bushes as a hedge that joins up like a fence.
 *
 * Each hedge is a bushy post with an arm reaching out to every hedge beside it,
 * whatever it flowers, so a row of them is one continuous hedge with corners
 * and crossings rather than a line of separate cubes. The look is the bush
 * sheet of the same flower; the models (a post and one per arm combination)
 * come from tools/furniture-gen/parkset.ts: post and arms are all a block tall and\n * twelve wide, and faces where hedges meet are left out, so a run is one seamless\n * wall of leaves.
 *
 * This object is also what registers them: nothing else touches it.
 */
@Init(stage = InitStage.PRE_PACK)
object Hedges {

	/** The flowers a hedge comes in: id, English, Vietnamese. */
	val FLOWERS: List<Triple<String, String, String>> = listOf(
		Triple("rose_bush", "Rose", "Hoa Hồng"),
		Triple("peony", "Peony", "Mẫu Đơn"),
		Triple("lilac", "Lilac", "Tử Đinh Hương"),
		Triple("pink_tulip", "Pink Tulip", "Tulip Hồng"),
		Triple("white_tulip", "White Tulip", "Tulip Trắng"),
		Triple("oxeye_daisy", "Daisy", "Cúc Trắng"),
		Triple("poppy", "Poppy", "Anh Túc"),
		Triple("allium", "Allium", "Hành Tím"),
	)

	private val SIDES: Map<BlockFace, BooleanProperty> = linkedMapOf(
		BlockFace.NORTH to BooleanProperty(Key.key("lunasmp", "hedge_n")),
		BlockFace.EAST to BooleanProperty(Key.key("lunasmp", "hedge_e")),
		BlockFace.SOUTH to BooleanProperty(Key.key("lunasmp", "hedge_s")),
		BlockFace.WEST to BooleanProperty(Key.key("lunasmp", "hedge_w")),
	)

	private val LETTERS = mapOf(BlockFace.NORTH to "n", BlockFace.EAST to "e", BlockFace.SOUTH to "s", BlockFace.WEST to "w")

	/** The model suffix for a state's arms: "post" with none, otherwise the letters in n-e-s-w order. */
	private fun armKey(arms: (BlockFace) -> Boolean): String {
		val key = SIDES.keys.filter(arms).joinToString("") { LETTERS.getValue(it) }
		return key.ifEmpty { "post" }
	}

	val BLOCKS: Map<String, NovaBlock> = FLOWERS.associate { (flower, _, _) ->
		val id = "${flower}_hedge"

		flower to LunaSmp.block(id) {
			stateProperties(*SIDES.values.map { it.scope(setOf(false, true)) { false } }.toTypedArray())

			// a full-block collider: the hedge is a solid wall of leaves twelve wide
			entityBacked(stateSelector = { net.minecraft.world.level.block.Blocks.BARRIER.defaultBlockState() }) {
				getModel("lunasmp:block/${id}_${armKey { face -> getPropertyValueOrNull(SIDES.getValue(face)) == true }}")
			}

			behaviors(
				Breakable(hardness = 0.2),
				BlockSounds(SoundGroup.GRASS),
				BlockDrops,
				HedgeConnect,
			)
		}
	}

	private val ALL: Set<NovaBlock> = BLOCKS.values.toSet()

	val ITEMS: Map<String, NovaItem> = BLOCKS.mapValues { (_, block) -> LunaSmp.item(block) {} }

	private fun isHedge(pos: BlockPos): Boolean = WorldDataManager.getBlockState(pos)?.block in ALL

	private fun loaded(pos: BlockPos): Boolean = pos.world.isChunkLoaded(pos.x shr 4, pos.z shr 4)

	/** The state [state] should be in at [pos]: an arm toward every hedge beside it. */
	private fun connected(pos: BlockPos, state: NovaBlockState): NovaBlockState {
		var next = state

		for ((face, property) in SIDES) {
			val side = pos.add(face.modX, 0, face.modZ)
			// a neighbour in an unloaded chunk keeps whatever arm it already had
			val joined = if (loaded(side)) isHedge(side) else state[property] == true
			next = next.with(property, joined)
		}

		return next
	}

	/** Joins a hedge to its neighbours when it is placed, and again whenever one of them changes. */
	object HedgeConnect : BlockBehavior {

		override fun updateShape(pos: BlockPos, state: NovaBlockState, neighborPos: BlockPos): NovaBlockState {
			if (neighborPos.y != pos.y) {
				return state
			}

			return connected(pos, state)
		}

		override fun handlePlace(pos: BlockPos, state: NovaBlockState, ctx: Context<BlockPlace>) {
			// a tick later: the placement is still being written when this runs
			runTask {
				val now = WorldDataManager.getBlockState(pos) ?: return@runTask

				if (now.block !in ALL) {
					return@runTask
				}

				val next = connected(pos, now)

				if (next != now) {
					BlockUtils.updateBlockState(pos, next)
				}
			}
		}
	}
}
