package dev.belikhun.luna.smp.crops

import dev.belikhun.luna.smp.Hitboxes
import dev.belikhun.luna.smp.LunaSmp
import net.kyori.adventure.key.Key
import net.minecraft.world.level.block.Blocks
import xyz.xenondevs.nova.world.block.NovaBlock
import xyz.xenondevs.nova.world.block.behavior.BlockSounds
import xyz.xenondevs.nova.world.block.behavior.Breakable
import xyz.xenondevs.nova.world.block.sound.SoundGroup
import xyz.xenondevs.nova.world.block.state.property.impl.IntProperty

/**
 * The generated crop table.
 *
 * GENERATED FILE - do not edit by hand. Change tools/furniture-gen/crops.ts and
 * re-run the generator; the sprites, models, configs and language files beside
 * this table come from the same pass, so editing one of them alone drifts the
 * set.
 *
 * A crop is one block whose `age` counts Stardew *days*, not frames: parsnip
 * reaches four, ancient fruit reaches twenty-eight, and [stages] says which of
 * its sprites each age draws. Counting days rather than pictures is what lets a
 * regrowing crop simply be wound back to `maturity - regrow` when it is picked,
 * and what keeps the pace the game published rather than a pace invented here.
 *
 * Every crop is entity-backed. A plant needs geometry that leaves its block and
 * a hitbox that is not a cube, and there is no backing-state budget anywhere
 * near the six hundred states this roster would otherwise reserve. A trellis
 * crop stands in a fence post instead of a structure void, which is the whole
 * of what "cannot be walked through" means here.
 */
@Suppress("unused")
object CropCatalog {

	/** How many days a crop has been growing. */
	val AGE: IntProperty = IntProperty(Key.key("lunasmp", "age"))

	/** The seasons a crop can be planted and grown in. */
	enum class Season {
		SPRING, SUMMER, FALL, WINTER,
	}

	data class Spec(
		val id: String,
		val seasons: Set<Season>,
		/** Days to maturity, which is also this block's highest age. */
		val maturity: Int,
		/** The age each sprite takes over at, rising; the last is [maturity]. */
		val stages: List<Int>,
		/** Days back to the next harvest, or null when picking ends the plant. */
		val regrow: Int?,
		/** How many items one harvest gives. */
		val count: Int = 1,
		/** The chance of one more on top of that. */
		val extra: Double = 0.0,
		/** What the game sells it for, which only the tooltip uses. */
		val price: Int,
		/** Hunger restored, or 0 for a crop the game calls inedible. */
		val nutrition: Int,
		val saturation: Float,
		/** Climbs a trellis, so it is solid to walk into. */
		val trellis: Boolean = false,
	) {

		/** The block this crop grows as. */
		val blockId: String
			get() = "${id}_crop"

		/** The item that plants it. */
		val seedId: String
			get() = "${id}_seeds"

		/** Whether picking it leaves the plant standing. */
		val regrows: Boolean
			get() = regrow != null
	}

	private val SPECS = listOf(
		Spec(id = "blue_jazz", seasons = setOf(Season.SPRING), maturity = 7, stages = listOf(1, 3, 5, 7), regrow = null, price = 50, nutrition = 2, saturation = 1.2f),
		Spec(id = "cauliflower", seasons = setOf(Season.SPRING), maturity = 12, stages = listOf(1, 3, 7, 11, 12), regrow = null, price = 175, nutrition = 3, saturation = 1.8f),
		Spec(id = "coffee_bean", seasons = setOf(Season.SPRING, Season.SUMMER), maturity = 10, stages = listOf(1, 3, 5, 8, 10), regrow = 2, count = 4, extra = 0.02, price = 15, nutrition = 0, saturation = 0.0f),
		Spec(id = "garlic", seasons = setOf(Season.SPRING), maturity = 4, stages = listOf(1, 2, 3, 4), regrow = null, price = 60, nutrition = 1, saturation = 0.6f),
		Spec(id = "green_bean", seasons = setOf(Season.SPRING), maturity = 10, stages = listOf(1, 2, 3, 6, 10), regrow = 3, price = 40, nutrition = 1, saturation = 0.6f, trellis = true),
		Spec(id = "kale", seasons = setOf(Season.SPRING), maturity = 6, stages = listOf(1, 3, 5, 6), regrow = null, price = 110, nutrition = 2, saturation = 1.2f),
		Spec(id = "parsnip", seasons = setOf(Season.SPRING), maturity = 4, stages = listOf(1, 2, 3, 4), regrow = null, price = 35, nutrition = 1, saturation = 0.6f),
		Spec(id = "rhubarb", seasons = setOf(Season.SPRING), maturity = 13, stages = listOf(2, 4, 6, 9, 13), regrow = null, price = 220, nutrition = 0, saturation = 0.0f),
		Spec(id = "garden_strawberry", seasons = setOf(Season.SPRING), maturity = 8, stages = listOf(1, 2, 4, 6, 8), regrow = 4, extra = 0.02, price = 120, nutrition = 2, saturation = 1.2f),
		Spec(id = "unmilled_rice", seasons = setOf(Season.SPRING), maturity = 8, stages = listOf(1, 3, 5, 8), regrow = null, extra = 0.1, price = 30, nutrition = 1, saturation = 0.6f),
		Spec(id = "blueberry", seasons = setOf(Season.SUMMER), maturity = 13, stages = listOf(1, 4, 7, 11, 13), regrow = 4, count = 3, extra = 0.02, price = 50, nutrition = 1, saturation = 0.6f),
		Spec(id = "corn", seasons = setOf(Season.SUMMER, Season.FALL), maturity = 14, stages = listOf(2, 5, 8, 11, 14), regrow = 4, price = 50, nutrition = 1, saturation = 0.6f),
		Spec(id = "hops", seasons = setOf(Season.SUMMER), maturity = 11, stages = listOf(1, 2, 4, 7, 11), regrow = 1, price = 25, nutrition = 2, saturation = 1.2f, trellis = true),
		Spec(id = "hot_pepper", seasons = setOf(Season.SUMMER), maturity = 5, stages = listOf(1, 2, 3, 4, 5), regrow = 3, extra = 0.03, price = 40, nutrition = 1, saturation = 0.6f),
		Spec(id = "radish", seasons = setOf(Season.SUMMER), maturity = 6, stages = listOf(2, 3, 5, 6), regrow = null, price = 90, nutrition = 2, saturation = 1.2f),
		Spec(id = "red_cabbage", seasons = setOf(Season.SUMMER), maturity = 9, stages = listOf(2, 3, 5, 7, 9), regrow = null, price = 260, nutrition = 3, saturation = 1.8f),
		Spec(id = "starfruit", seasons = setOf(Season.SUMMER), maturity = 13, stages = listOf(2, 5, 7, 10, 13), regrow = null, price = 750, nutrition = 5, saturation = 3.0f),
		Spec(id = "summer_spangle", seasons = setOf(Season.SUMMER), maturity = 8, stages = listOf(1, 3, 6, 8), regrow = null, price = 90, nutrition = 2, saturation = 1.2f),
		Spec(id = "summer_squash", seasons = setOf(Season.SUMMER), maturity = 6, stages = listOf(1, 2, 3, 5, 6), regrow = 3, price = 45, nutrition = 3, saturation = 1.8f),
		Spec(id = "tomato", seasons = setOf(Season.SUMMER), maturity = 11, stages = listOf(2, 4, 6, 8, 11), regrow = 4, extra = 0.05, price = 60, nutrition = 1, saturation = 0.6f),
		Spec(id = "amaranth", seasons = setOf(Season.FALL), maturity = 7, stages = listOf(1, 3, 5, 7), regrow = null, price = 150, nutrition = 2, saturation = 1.2f),
		Spec(id = "artichoke", seasons = setOf(Season.FALL), maturity = 8, stages = listOf(2, 4, 5, 7, 8), regrow = null, price = 160, nutrition = 1, saturation = 0.6f),
		Spec(id = "bok_choy", seasons = setOf(Season.FALL), maturity = 4, stages = listOf(1, 2, 3, 4), regrow = null, price = 80, nutrition = 1, saturation = 0.6f),
		Spec(id = "broccoli", seasons = setOf(Season.FALL), maturity = 8, stages = listOf(2, 4, 6, 8), regrow = 4, price = 70, nutrition = 3, saturation = 1.8f),
		Spec(id = "cranberries", seasons = setOf(Season.FALL), maturity = 7, stages = listOf(1, 3, 4, 5, 7), regrow = 5, count = 2, extra = 0.1, price = 75, nutrition = 2, saturation = 1.2f),
		Spec(id = "eggplant", seasons = setOf(Season.FALL), maturity = 5, stages = listOf(1, 2, 3, 4, 5), regrow = 5, extra = 0.002, price = 60, nutrition = 1, saturation = 0.6f),
		Spec(id = "fairy_rose", seasons = setOf(Season.FALL), maturity = 12, stages = listOf(1, 5, 9, 12), regrow = null, price = 290, nutrition = 2, saturation = 1.2f),
		Spec(id = "grape", seasons = setOf(Season.FALL), maturity = 10, stages = listOf(1, 2, 4, 7, 10), regrow = 3, price = 80, nutrition = 2, saturation = 1.2f, trellis = true),
		Spec(id = "yam", seasons = setOf(Season.FALL), maturity = 10, stages = listOf(1, 4, 7, 10), regrow = null, price = 160, nutrition = 2, saturation = 1.2f),
		Spec(id = "powdermelon", seasons = setOf(Season.WINTER), maturity = 7, stages = listOf(1, 3, 4, 6, 7), regrow = null, price = 60, nutrition = 3, saturation = 1.8f),
		Spec(id = "ancient_fruit", seasons = setOf(Season.SPRING, Season.SUMMER, Season.FALL), maturity = 28, stages = listOf(2, 9, 16, 23, 28), regrow = 7, price = 550, nutrition = 0, saturation = 0.0f),
		Spec(id = "cactus_fruit", seasons = setOf(Season.SUMMER), maturity = 12, stages = listOf(2, 4, 6, 9, 12), regrow = 3, price = 75, nutrition = 3, saturation = 1.8f),
		Spec(id = "pineapple", seasons = setOf(Season.SUMMER), maturity = 14, stages = listOf(1, 4, 7, 11, 14), regrow = 7, price = 300, nutrition = 6, saturation = 3.6f),
		Spec(id = "sweet_gem_berry", seasons = setOf(Season.FALL), maturity = 24, stages = listOf(2, 6, 12, 18, 24), regrow = null, price = 3000, nutrition = 0, saturation = 0.0f),
		Spec(id = "taro_root", seasons = setOf(Season.SUMMER), maturity = 10, stages = listOf(1, 3, 6, 10), regrow = null, price = 100, nutrition = 2, saturation = 1.2f),
		Spec(id = "tea_leaves", seasons = setOf(Season.SPRING, Season.SUMMER, Season.FALL), maturity = 20, stages = listOf(10, 20), regrow = 1, price = 50, nutrition = 0, saturation = 0.0f),
	)

	/** Every crop's spec, by crop id. */
	val BY_ID: Map<String, Spec> = SPECS.associateBy { it.id }

	/** Every crop's spec, by the id of the block it grows as. */
	val BY_BLOCK: Map<String, Spec> = SPECS.associateBy { it.blockId }

	/** Every crop block, by crop id. */
	val BLOCKS: Map<String, NovaBlock> = SPECS.associate { it.id to register(it) }

	/**
	 * The withered plant.
	 *
	 * One block for the whole roster, because a crop that died out of season is
	 * no longer a parsnip or a melon, it is straw; the game draws one dead-crop
	 * sprite for the same reason. It grows into nothing and drops nothing.
	 */
	val DEAD: NovaBlock = LunaSmp.block("dead_crop") {
		behaviors(Breakable(hardness = 0.0), BlockSounds(SoundGroup.CROP))

		entityBacked(stateSelector = { Blocks.STRUCTURE_VOID.defaultBlockState() }) {
			getModel("lunasmp:block/dead_crop")
		}
	}

	/**
	 * Which sprite an age draws: the first stage whose day threshold the crop
	 * has not yet passed, and the ripe sprite once it is at maturity.
	 */
	fun stageOf(spec: Spec, age: Int): Int {
		for (index in spec.stages.indices) {
			if (age < spec.stages[index]) {
				return index
			}
		}

		return spec.stages.size - 1
	}

	private fun register(spec: Spec): NovaBlock = LunaSmp.block(spec.blockId) {
		stateProperties(AGE.scope(0..spec.maturity) { 0 })
		behaviors(CropBehavior, Breakable(hardness = 0.0), BlockSounds(SoundGroup.CROP))

		// a trellis crop stands in a fence post, which is the four-pixel column
		// Nova keeps from ever growing arms; everything else is a structure
		// void, so a field is walked through rather than waded through
		entityBacked(
			stateSelector = {
				if (spec.trellis) {
					Hitboxes.post()
				} else {
					Blocks.STRUCTURE_VOID.defaultBlockState()
				}
			},
		) {
			getModel("lunasmp:block/${spec.id}_crop_stage${stageOf(spec, getPropertyValueOrNull(AGE) ?: 0)}")
		}
	}
}
