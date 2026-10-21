package dev.belikhun.luna.smp.landscape

import dev.belikhun.luna.smp.LunaSmp
import dev.belikhun.luna.smp.birthday.BirthdayCatalog
import dev.belikhun.luna.smp.furniture.Seating
import net.kyori.adventure.key.Key
import net.minecraft.world.level.block.Blocks
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionType
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockInteract
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
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
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.block.state.property.impl.BooleanProperty
import xyz.xenondevs.nova.world.format.WorldDataManager
import xyz.xenondevs.nova.world.item.NovaItem

/**
 * The park set: benches in every wood and stone, and public drinking fountains.
 *
 * A bench joins the bench beside it that faces the same way: each piece draws its
 * armrest, end post and legs only on a side with no bench, so a row of them is one
 * long bench to sit along. Every seat is a seat (the furniture's own Seating).
 *
 * A fountain gives a drink: right-click with an empty hand to drink (a mouthful of
 * food and the sound of it), with a glass bottle to fill it with water.
 *
 * The models and the two painted sheets come from tools/furniture-gen/parkset.ts.
 */
@Init(stage = InitStage.PRE_PACK)
object ParkSet {

	/** Bench materials: id, English, Vietnamese, stone or not. Kept in step with parkset.ts. */
	val BENCHES: List<Triple<String, String, String>> = listOf(
		Triple("oak", "Oak", "Gỗ Sồi"), Triple("spruce", "Spruce", "Gỗ Vân Sam"), Triple("birch", "Birch", "Gỗ Bạch Dương"),
		Triple("jungle", "Jungle", "Gỗ Rừng"), Triple("acacia", "Acacia", "Gỗ Keo"), Triple("dark_oak", "Dark Oak", "Gỗ Sồi Sẫm"),
		Triple("mangrove", "Mangrove", "Gỗ Đước"), Triple("cherry", "Cherry", "Gỗ Anh Đào"), Triple("pale_oak", "Pale Oak", "Gỗ Sồi Nhạt"),
		Triple("bamboo", "Bamboo", "Tre"), Triple("crimson", "Crimson", "Gỗ Đỏ Thẫm"), Triple("warped", "Warped", "Gỗ Biến Dị"),
		Triple("stone", "Stone", "Đá"), Triple("smooth_stone", "Smooth Stone", "Đá Nhẵn"), Triple("cobblestone", "Cobblestone", "Đá Cuội"),
		Triple("mossy_cobblestone", "Mossy Cobblestone", "Đá Cuội Rêu"), Triple("stone_brick", "Stone Brick", "Gạch Đá"),
		Triple("mossy_stone_brick", "Mossy Stone Brick", "Gạch Đá Rêu"), Triple("andesite", "Andesite", "Andesit"),
		Triple("granite", "Granite", "Đá Hoa Cương"), Triple("diorite", "Diorite", "Diorit"), Triple("deepslate", "Deepslate", "Đá Phiến Sâu"),
		Triple("sandstone", "Sandstone", "Sa Thạch"), Triple("red_sandstone", "Red Sandstone", "Sa Thạch Đỏ"), Triple("quartz", "Quartz", "Thạch Anh"),
		Triple("blackstone", "Blackstone", "Đá Đen"), Triple("tuff", "Tuff", "Đá Tuff"), Triple("brick", "Brick", "Gạch"),
		Triple("mud_brick", "Mud Brick", "Gạch Bùn"), Triple("calcite", "Calcite", "Canxit"),
	)

	private val STONE = setOf(
		"stone", "smooth_stone", "cobblestone", "mossy_cobblestone", "stone_brick", "mossy_stone_brick", "andesite", "granite",
		"diorite", "deepslate", "sandstone", "red_sandstone", "quartz", "blackstone", "tuff", "brick", "mud_brick", "calcite",
	)

	val FOUNTAINS: List<String> = listOf("stone_brick", "mossy_stone_brick", "andesite", "quartz", "deepslate", "sandstone")

	private val WALLS = setOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

	/** Joined to a bench on the sitter's left, and on their right. */
	private val LEFT = BooleanProperty(Key.key("lunasmp", "bench_left"))
	private val RIGHT = BooleanProperty(Key.key("lunasmp", "bench_right"))

	private fun facingOnPlace(ctx: Context<BlockPlace>): BlockFace =
		BirthdayCatalog.facingToward(ctx[DefaultContextParamTypes.SOURCE_DIRECTION])

	val BENCH_BLOCKS: Map<String, NovaBlock> = BENCHES.associate { (material, _, _) ->
		val id = "park_bench_$material"
		val stone = material in STONE

		material to LunaSmp.block(id) {
			stateProperties(
				DefaultBlockStateProperties.FACING.scope(WALLS) { ctx -> facingOnPlace(ctx) },
				LEFT.scope(setOf(false, true)) { false },
				RIGHT.scope(setOf(false, true)) { false },
			)

			entityBacked(stateSelector = { Blocks.HEAVY_CORE.defaultBlockState() }) {
				val left = getPropertyValueOrNull(LEFT) == true
				val right = getPropertyValueOrNull(RIGHT) == true
				val key = when {
					left && right -> "lr"
					left -> "l"
					right -> "r"
					else -> "single"
				}

				getModel("lunasmp:block/${id}_$key").rotated()
			}

			behaviors(
				Breakable(hardness = if (stone) 1.5 else 1.0),
				BlockSounds(if (stone) SoundGroup.STONE else SoundGroup.WOOD),
				BlockDrops,
				Seating(if (stone) 0.6 else 0.55),
				BenchConnect,
			)
		}
	}

	private val ALL_BENCHES: Set<NovaBlock> = BENCH_BLOCKS.values.toSet()

	val BENCH_ITEMS: Map<String, NovaItem> = BENCH_BLOCKS.mapValues { (_, block) -> LunaSmp.item(block) {} }

	val FOUNTAIN_BLOCKS: Map<String, NovaBlock> = FOUNTAINS.associateWith { material ->
		val id = "drinking_fountain_$material"

		LunaSmp.block(id) {
			stateProperties(DefaultBlockStateProperties.FACING.scope(WALLS) { ctx -> facingOnPlace(ctx) })

			entityBacked(stateSelector = { Blocks.BARRIER.defaultBlockState() }) {
				defaultModel.rotated()
			}

			behaviors(
				Breakable(hardness = 1.5),
				BlockSounds(SoundGroup.STONE),
				BlockDrops,
				Drink,
			)
		}
	}

	val FOUNTAIN_ITEMS: Map<String, NovaItem> = FOUNTAIN_BLOCKS.mapValues { (_, block) -> LunaSmp.item(block) {} }

	// ---- benches joining up ------------------------------------------------------------------

	private fun leftOf(facing: BlockFace): BlockFace = when (facing) {
		BlockFace.NORTH -> BlockFace.WEST
		BlockFace.WEST -> BlockFace.SOUTH
		BlockFace.SOUTH -> BlockFace.EAST
		else -> BlockFace.NORTH
	}

	private fun benchFacing(pos: BlockPos): BlockFace? {
		val state = WorldDataManager.getBlockState(pos) ?: return null

		if (state.block !in ALL_BENCHES) {
			return null
		}

		return state[DefaultBlockStateProperties.FACING]
	}

	private fun joined(pos: BlockPos, state: NovaBlockState): NovaBlockState {
		val facing = state[DefaultBlockStateProperties.FACING] ?: return state
		val left = leftOf(facing)
		val right = left.oppositeFace
		val l = benchFacing(pos.add(left.modX, 0, left.modZ)) == facing
		val r = benchFacing(pos.add(right.modX, 0, right.modZ)) == facing

		return state.with(LEFT, l).with(RIGHT, r)
	}

	object BenchConnect : BlockBehavior {

		override fun updateShape(pos: BlockPos, state: NovaBlockState, neighborPos: BlockPos): NovaBlockState {
			if (neighborPos.y != pos.y) {
				return state
			}

			return joined(pos, state)
		}

		override fun handlePlace(pos: BlockPos, state: NovaBlockState, ctx: Context<BlockPlace>) {
			runTask {
				val now = WorldDataManager.getBlockState(pos) ?: return@runTask

				if (now.block !in ALL_BENCHES) {
					return@runTask
				}

				val next = joined(pos, now)

				if (next != now) {
					BlockUtils.updateBlockState(pos, next)
				}
			}
		}
	}

	// ---- drinking ------------------------------------------------------------------------------

	object Drink : BlockBehavior {

		override fun handleInteract(pos: BlockPos, state: NovaBlockState, ctx: Context<BlockInteract>): Boolean {
			val player = ctx[DefaultContextParamTypes.SOURCE_ENTITY] as? Player ?: return false
			val hand = ctx[DefaultContextParamTypes.INTERACTION_HAND] ?: EquipmentSlot.HAND

			if (hand != EquipmentSlot.HAND || player.isSneaking) {
				return false
			}

			val held = player.inventory.getItem(hand)
			val spout = pos.location.add(0.5, 0.95, 0.4)

			if (held.type == Material.GLASS_BOTTLE) {
				val water = ItemStack(Material.POTION)
				val meta = water.itemMeta as PotionMeta
				meta.basePotionType = PotionType.WATER
				water.itemMeta = meta

				held.amount -= 1
				player.inventory.setItem(hand, held)

				for (leftover in player.inventory.addItem(water).values) {
					player.world.dropItem(player.location, leftover)
				}

				player.world.playSound(spout, Sound.ITEM_BOTTLE_FILL, 1f, 1f)
				return true
			}

			if (!held.type.isAir) {
				return false
			}

			player.foodLevel = minOf(20, player.foodLevel + 1)
			player.saturation = minOf(player.foodLevel.toFloat(), player.saturation + 1f)
			player.world.playSound(spout, Sound.ENTITY_GENERIC_DRINK, 1f, 1.1f)
			player.world.spawnParticle(Particle.SPLASH, spout, 12, 0.1, 0.05, 0.1, 0.0)
			return true
		}
	}
}
