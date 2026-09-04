package dev.belikhun.luna.smp.furniture

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.MultipleFacing
import org.bukkit.inventory.ItemStack
import org.joml.Vector3f
import dev.belikhun.luna.smp.flora.FloraCatalog
import xyz.xenondevs.nova.util.item.novaItem

/**
 * What a pot can hold, and how it stands in one.
 *
 * Vanilla lets you pot about thirty plants, each as its own `potted_*` block;
 * this is the other approach, ported from the Gardener's Dream datapack: the
 * plant is not part of the pot at all, it is its own block model standing in
 * one, so anything that grows can go in a pot and the pot does not need a
 * variant per plant.
 *
 * A plant is described by the block that draws it, how big it is drawn and
 * where that lands it. Most need nothing said about them: a poppy in a pot is
 * a poppy at full size. What needs saying is the two ways an item is not its
 * own block - a carrot is `carrots`, a glow berry is a cave vine - and the
 * plants that are a full cube, which have to be cut down to fit a pot.
 */
object PotPlants {

	/**
	 * A plant, ready to be drawn standing in a pot.
	 *
	 * Exactly one of [block] and [item] is set. A vanilla plant is a block
	 * state and is drawn as one; our own flora has no block state a client
	 * could be told about (it is a structure void wearing a model), so it is
	 * drawn from its item instead, which carries the same model.
	 */
	data class Plant(
		val block: BlockData?,
		val item: ItemStack?,
		val scale: Vector3f,
		val offset: Vector3f,
		/** Drawn upside down: what grows from a ceiling grows up out of a pot. */
		val flipped: Boolean,
	)

	private val ONE = Vector3f(1f, 1f, 1f)
	private val ORIGIN = Vector3f()

	/** What a bush is cut down to, so the pot still reads as a pot. */
	private val BUSH = Vector3f(0.75f, 0.75f, 0.75f)

	/**
	 * The items whose plant is a different block, or the same block in a state
	 * nothing else would pick: a crop is potted grown, not as a seedling.
	 */
	private val STATES: Map<Material, String> = mapOf(
		Material.WHEAT to "minecraft:wheat[age=7]",
		Material.CARROT to "minecraft:carrots[age=7]",
		Material.POTATO to "minecraft:potatoes[age=7]",
		Material.BEETROOT to "minecraft:beetroots[age=3]",
		Material.NETHER_WART to "minecraft:nether_wart[age=3]",
		Material.MELON_SEEDS to "minecraft:melon_stem[age=7]",
		Material.PUMPKIN_SEEDS to "minecraft:pumpkin_stem[age=7]",
		Material.SWEET_BERRIES to "minecraft:sweet_berry_bush[age=3]",
		Material.GLOW_BERRIES to "minecraft:cave_vines[age=5,berries=true]",
		Material.PITCHER_POD to "minecraft:pitcher_crop[age=3,half=lower]",
		Material.PITCHER_PLANT to "minecraft:pitcher_crop[age=4,half=upper]",
		Material.TORCHFLOWER_SEEDS to "minecraft:torchflower_crop[age=1]",
		Material.CHORUS_FRUIT to "minecraft:chorus_plant[down=true,up=true,north=false,east=false,south=false,west=false]",
		Material.POPPED_CHORUS_FRUIT to "minecraft:chorus_flower[age=5]",
		Material.WEEPING_VINES to "minecraft:weeping_vines_plant",
		Material.TWISTING_VINES to "minecraft:twisting_vines_plant",
		Material.BAMBOO to "minecraft:bamboo[age=0,leaves=small,stage=0]",
		Material.SEA_PICKLE to "minecraft:sea_pickle[pickles=1,waterlogged=false]",
		Material.PALE_MOSS_CARPET to "minecraft:pale_moss_carpet[bottom=true,north=low,east=low,south=low,west=low]",
	)

	/** Plants drawn at less than a block, and where that leaves them. */
	private val SIZES: Map<Material, Vector3f> = mapOf(
		Material.MANGROVE_ROOTS to Vector3f(0.25f, 0.9f, 0.25f),
		Material.MOSS_BLOCK to Vector3f(0.5f, 0.9f, 0.5f),
		Material.PALE_MOSS_BLOCK to Vector3f(0.5f, 0.9f, 0.5f),
		Material.PALE_MOSS_CARPET to Vector3f(0.5f, 0.5f, 0.5f),

		Material.SHORT_GRASS to Vector3f(0.4f, 0.9f, 0.4f),
		Material.TALL_GRASS to Vector3f(0.4f, 0.9f, 0.4f),
		Material.LARGE_FERN to Vector3f(0.4f, 0.9f, 0.4f),
		Material.SEAGRASS to Vector3f(0.4f, 0.9f, 0.4f),
		Material.SUGAR_CANE to Vector3f(0.4f, 0.9f, 0.4f),
		Material.NETHER_SPROUTS to Vector3f(0.4f, 1.3f, 0.4f),

		Material.WHEAT to Vector3f(0.35f, 0.4f, 0.35f),
		Material.CACTUS to Vector3f(0.35f, 0.4f, 0.35f),
		Material.CARROT to Vector3f(0.35f, 0.6f, 0.35f),
		Material.POTATO to Vector3f(0.35f, 0.6f, 0.35f),
		Material.BEETROOT to Vector3f(0.35f, 0.6f, 0.35f),
		Material.NETHER_WART to Vector3f(0.35f, 0.6f, 0.35f),
		Material.CRIMSON_ROOTS to Vector3f(0.35f, 0.6f, 0.35f),
		Material.WARPED_ROOTS to Vector3f(0.35f, 0.6f, 0.35f),
		Material.PITCHER_POD to Vector3f(0.4f, 0.6f, 0.4f),

		Material.CHORUS_FLOWER to Vector3f(0.75f, 0.75f, 0.75f),
		Material.POPPED_CHORUS_FRUIT to Vector3f(0.75f, 0.75f, 0.75f),

		Material.VINE to Vector3f(0.6f, 0.6f, 0.6f),
		Material.GLOW_LICHEN to Vector3f(0.5f, 0.5f, 0.5f),
		Material.SCULK_VEIN to Vector3f(0.5f, 0.5f, 0.5f),
		Material.SCULK_SENSOR to Vector3f(0.4f, 0.4f, 0.4f),
	)

	/**
	 * The blocks drawn in one corner of their own block rather than across it;
	 * a quarter turn of the pot would swing them off it, so they are pushed
	 * back to the middle first.
	 */
	private val CORNERED = setOf(Material.PINK_PETALS, Material.WILDFLOWERS, Material.LEAF_LITTER)

	/** Grows down from a ceiling, and so grows up out of a pot. */
	private val HANGING = setOf(
		Material.SPORE_BLOSSOM,
		Material.GLOW_BERRIES,
		Material.HANGING_ROOTS,
		Material.PALE_HANGING_MOSS,
	)

	/**
	 * The dead corals, which vanilla's coral tags leave out: dead coral is no
	 * longer a plant to the game, and is still a fine thing to keep in a pot.
	 */
	private val DEAD_CORALS = setOf(
		Material.DEAD_BRAIN_CORAL, Material.DEAD_BUBBLE_CORAL, Material.DEAD_FIRE_CORAL,
		Material.DEAD_HORN_CORAL, Material.DEAD_TUBE_CORAL,
		Material.DEAD_BRAIN_CORAL_FAN, Material.DEAD_BUBBLE_CORAL_FAN, Material.DEAD_FIRE_CORAL_FAN,
		Material.DEAD_HORN_CORAL_FAN, Material.DEAD_TUBE_CORAL_FAN,
	)

	/** Cut down to fit a pot, the same as the living coral blocks are. */
	private val DEAD_CORAL_BLOCKS = setOf(
		Material.DEAD_BRAIN_CORAL_BLOCK, Material.DEAD_BUBBLE_CORAL_BLOCK,
		Material.DEAD_FIRE_CORAL_BLOCK, Material.DEAD_HORN_CORAL_BLOCK,
		Material.DEAD_TUBE_CORAL_BLOCK,
	)

	/**
	 * Everything that is a plant without being in one of vanilla's own plant
	 * tags: the grasses, the fungi, the vines, the things that grow on a wall
	 * and the two blocks (moss, mangrove roots) a garden is built out of.
	 */
	private val LOOSE = setOf(
		Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN,
		Material.DEAD_BUSH, Material.BUSH, Material.FIREFLY_BUSH,
		Material.SHORT_DRY_GRASS, Material.TALL_DRY_GRASS,
		Material.SEAGRASS, Material.KELP, Material.SUGAR_CANE, Material.BAMBOO,
		Material.CACTUS, Material.CACTUS_FLOWER,
		Material.VINE, Material.TWISTING_VINES, Material.WEEPING_VINES,
		Material.CRIMSON_ROOTS, Material.WARPED_ROOTS, Material.NETHER_SPROUTS,
		Material.CRIMSON_FUNGUS, Material.WARPED_FUNGUS,
		Material.BROWN_MUSHROOM, Material.RED_MUSHROOM,
		Material.SPORE_BLOSSOM, Material.HANGING_ROOTS, Material.PALE_HANGING_MOSS,
		Material.GLOW_LICHEN, Material.SCULK_VEIN, Material.SCULK_SENSOR,
		Material.BIG_DRIPLEAF, Material.SMALL_DRIPLEAF,
		Material.CHORUS_FLOWER, Material.SEA_PICKLE,
		Material.MOSS_BLOCK, Material.PALE_MOSS_BLOCK, Material.PALE_MOSS_CARPET,
		Material.MANGROVE_ROOTS, Material.MANGROVE_PROPAGULE,
		Material.AZALEA, Material.FLOWERING_AZALEA,
		Material.PINK_PETALS, Material.WILDFLOWERS, Material.LEAF_LITTER,
	)

	/**
	 * Everything a pot takes. The tags are read the first time somebody plants
	 * something rather than at load, because a tag is only populated once the
	 * server has built its registries.
	 */
	private val ALLOWED: Set<Material> by lazy {
		buildSet {
			addAll(Tag.SAPLINGS.values)
			addAll(Tag.FLOWERS.values)
			addAll(Tag.LEAVES.values)
			addAll(Tag.CORALS.values)
			addAll(Tag.CORAL_PLANTS.values)
			addAll(Tag.CORAL_BLOCKS.values)
			addAll(DEAD_CORALS)
			addAll(DEAD_CORAL_BLOCKS)
			addAll(LOOSE)
			addAll(STATES.keys)
		}
	}

	private val cache = HashMap<Material, Plant>()

	/**
	 * The plant a held stack puts in a pot, or null when it is not one.
	 *
	 * Our own flora is asked about first, because a flora item is a vanilla
	 * item underneath and would otherwise be judged on that: the whole set is
	 * built on one carrier item, so falling through would pot every one of
	 * them as whatever that carrier happens to be.
	 *
	 * @param stack what the player is holding
	 */
	fun of(stack: ItemStack): Plant? = ours(stack) ?: of(stack.type)

	/**
	 * The plant a vanilla item puts in a pot, or null when the item is not one.
	 *
	 * @param item what the player is holding
	 */
	fun of(item: Material): Plant? {
		cache[item]?.let { return it }

		if (item !in ALLOWED) {
			return null
		}

		val plant = build(item) ?: return null
		cache[item] = plant

		return plant
	}

	/**
	 * One of our own flowers, or null when the stack is anything else.
	 *
	 * Only the flora that is a plant qualifies: the flowering stones are
	 * building blocks and the wall vines are drawn flat against a wall they
	 * would not have in a pot. A bush is cut down a little, the way the leaves
	 * are, since a bush drawn at its own size wears the pot rather than
	 * standing in it.
	 */
	private fun ours(stack: ItemStack): Plant? {
		val id = stack.novaItem?.block?.id ?: return null

		if (id.namespace() != "lunasmp") {
			return null
		}

		val spec = FloraCatalog.BY_ID[id.value()] ?: return null

		if (spec.backing != FloraCatalog.Backing.PLANT) {
			return null
		}

		val one = stack.clone()
		one.amount = 1

		val scale = if (id.value().endsWith("_bush")) BUSH else ONE

		return Plant(null, one, scale, ORIGIN, false)
	}

	private fun build(item: Material): Plant? {
		val block = dataOf(item) ?: return null
		val scale = SIZES[item] ?: cubeScale(item)
		val offset = if (item in CORNERED) Vector3f(0.25f, 0f, 0.25f) else ORIGIN

		return Plant(block, null, scale, offset, item in HANGING)
	}

	/**
	 * A plant that fills its own block would fill the room the pot stands in,
	 * so leaves, coral and moss are cut to half a block and left a little short
	 * of the ceiling.
	 */
	private fun cubeScale(item: Material): Vector3f =
		if (Tag.LEAVES.isTagged(item) || Tag.CORAL_BLOCKS.isTagged(item) || item in DEAD_CORAL_BLOCKS) {
			Vector3f(0.5f, 0.9f, 0.5f)
		} else {
			ONE
		}

	private fun dataOf(item: Material): BlockData? {
		STATES[item]?.let { return Bukkit.createBlockData(it) }

		if (!item.isBlock) {
			return null
		}

		val data = item.createBlockData()

		// a two-block plant shows its top half, which is where a rose bush
		// keeps its roses and a sunflower its flower
		if (data is Bisected) {
			data.half = Bisected.Half.TOP
		}

		// glow lichen and sculk vein draw on whichever faces they are told to;
		// the underside is the one that lands them flat on the soil
		if (data is MultipleFacing) {
			val faces = if (item == Material.VINE) data.allowedFaces else setOf(BlockFace.DOWN)

			for (face in faces) {
				if (face in data.allowedFaces) {
					data.setFace(face, true)
				}
			}
		}

		return data
	}
}
