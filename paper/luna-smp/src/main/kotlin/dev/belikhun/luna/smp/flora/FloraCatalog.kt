package dev.belikhun.luna.smp.flora

import dev.belikhun.luna.smp.Hitboxes
import dev.belikhun.luna.smp.LunaSmp
import net.kyori.adventure.key.Key
import net.minecraft.world.level.block.Blocks
import org.bukkit.block.BlockFace
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.resources.builder.layout.block.BackingStateCategory
import xyz.xenondevs.nova.world.block.AbstractNovaBlockBuilder
import xyz.xenondevs.nova.world.block.NovaBlock
import xyz.xenondevs.nova.world.block.behavior.BlockBehaviorHolder
import xyz.xenondevs.nova.world.block.behavior.BlockDrops
import xyz.xenondevs.nova.world.block.behavior.BlockSounds
import xyz.xenondevs.nova.world.block.behavior.Breakable
import xyz.xenondevs.nova.world.block.behavior.TileEntityDrops
import xyz.xenondevs.nova.world.block.sound.SoundGroup
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.block.state.property.DefaultScopedBlockStateProperties
import xyz.xenondevs.nova.world.block.state.property.impl.BooleanProperty
import kotlin.math.abs

/**
 * The generated flora table.
 *
 * GENERATED FILE - do not edit by hand. Change tools/furniture-gen/flora.ts and
 * re-run the generator; the models, configs and language files beside this table
 * come from the same pass, so editing one of them alone drifts the set.
 *
 * Two backings, chosen by what the block actually is. A flowering stone block is
 * a full cube, so it is a reserved note block state: a wall of them lights,
 * occludes and culls exactly like the stone it is made of, and costs no display
 * entities at all, which matters for something meant to be built with in bulk.
 * Everything else is a plant, and gets the entity backing over a structure void:
 * nothing to walk into, a small box in the middle to aim at, and geometry that
 * is free to leave the block, which is what lets a two-block flower be one block.
 */
@Suppress("unused")
object FloraCatalog {

	/** Whether a flower that watches the sky is lit. */
	val GLOWING = BooleanProperty(Key.key("lunasmp", "glowing"))

	/** The walls a vine can hang on. */
	private val WALLS = setOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

	/** What the block is, which decides how the client is told to draw it. */
	enum class Backing { CUBE, PLANT, WALL }

	/**
	 * What a flower does to whoever is near it.
	 *
	 * A `ticking` effect reaches past its own block and has to go looking, so its
	 * flower is registered as a tile entity; the rest are answered from the
	 * collision that triggered them and cost nothing until somebody steps in.
	 * `harmful` is what makes a flower hold its fire on peaceful.
	 */
	enum class Effect(val ticking: Boolean, val harmful: Boolean = false) {
		/** Sets whoever steps on it alight, unless they are at home in fire. */
		BURN(false, true),
		/** Goes off underfoot. */
		BLAST(false, true),
		/** Poisons whoever wades into it. */
		POISON(false, true),
		/** Simply hurts. */
		RUE(false, true),
		/** Heals whoever stands in it. */
		MEND(false),
		/** Hardens whoever stands in it. */
		GUARD(false),
		/** Turns luck for whoever stands in it. */
		FORTUNE(false),
		/** Strengthens the people around it while something is hunting them. */
		RALLY(true),
		/** Takes the fight out of everything around it. */
		LULL(true),
		/** Hostiles walk back out of its ring. */
		REPEL(true),
		/** Hostiles drop what they were chasing and come to it. */
		LURE(true),
		/** Gives off a little light of its own, which is only ever particles. */
		GLIMMER(true),
		/** Makes the noises of things that are not there. */
		DREAD(true),
	}

	data class Spec(
		val id: String,
		val backing: Backing,
		/** The flat sprite the item shows in a menu, on the ground and framed. */
		val icon: String? = null,
		val nightLight: Int = 0,
		val effect: Effect? = null,
		/** Turns to face whoever places it, the way a carved pumpkin does. */
		val directional: Boolean = false,
		val hardness: Double = 0.0,
		val sounds: SoundGroup = SoundGroup.GRASS,
	)

	private val SPECS = listOf(
		Spec(id = "dandelion_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "poppy_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "blue_orchid_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "allium_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "azure_bluet_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "red_tulip_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "orange_tulip_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "white_tulip_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "pink_tulip_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "oxeye_daisy_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "cornflower_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "lily_of_the_valley_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "wither_rose_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "torchflower_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "chorus_flower_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "pink_petals_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "spore_blossom_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "open_eyeblossom_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "closed_eyeblossom_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "wildflowers_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "cactus_flower_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "sunflower_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "lilac_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "rose_bush_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "peony_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "pitcher_plant_mossy_cobblestone", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "dandelion_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "poppy_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "blue_orchid_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "allium_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "azure_bluet_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "red_tulip_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "orange_tulip_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "white_tulip_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "pink_tulip_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "oxeye_daisy_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "cornflower_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "lily_of_the_valley_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "wither_rose_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "torchflower_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "chorus_flower_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "pink_petals_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "spore_blossom_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "open_eyeblossom_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "closed_eyeblossom_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "wildflowers_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "cactus_flower_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "sunflower_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "lilac_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "rose_bush_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "peony_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "pitcher_plant_mossy_stone_bricks", backing = Backing.CUBE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "dandelion_vine", backing = Backing.WALL, icon = "lunasmp:block/dandelion_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "poppy_vine", backing = Backing.WALL, icon = "lunasmp:block/poppy_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "blue_orchid_vine", backing = Backing.WALL, icon = "lunasmp:block/blue_orchid_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "allium_vine", backing = Backing.WALL, icon = "lunasmp:block/allium_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "azure_bluet_vine", backing = Backing.WALL, icon = "lunasmp:block/azure_bluet_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "red_tulip_vine", backing = Backing.WALL, icon = "lunasmp:block/red_tulip_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "orange_tulip_vine", backing = Backing.WALL, icon = "lunasmp:block/orange_tulip_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "white_tulip_vine", backing = Backing.WALL, icon = "lunasmp:block/white_tulip_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "pink_tulip_vine", backing = Backing.WALL, icon = "lunasmp:block/pink_tulip_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "oxeye_daisy_vine", backing = Backing.WALL, icon = "lunasmp:block/oxeye_daisy_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "cornflower_vine", backing = Backing.WALL, icon = "lunasmp:block/cornflower_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "lily_of_the_valley_vine", backing = Backing.WALL, icon = "lunasmp:block/lily_of_the_valley_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "wither_rose_vine", backing = Backing.WALL, icon = "lunasmp:block/wither_rose_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "torchflower_vine", backing = Backing.WALL, icon = "lunasmp:block/torchflower_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "chorus_flower_vine", backing = Backing.WALL, icon = "lunasmp:block/chorus_flower_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "pink_petals_vine", backing = Backing.WALL, icon = "lunasmp:block/pink_petals_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "spore_blossom_vine", backing = Backing.WALL, icon = "lunasmp:block/spore_blossom_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "open_eyeblossom_vine", backing = Backing.WALL, icon = "lunasmp:block/open_eyeblossom_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "closed_eyeblossom_vine", backing = Backing.WALL, icon = "lunasmp:block/closed_eyeblossom_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "wildflowers_vine", backing = Backing.WALL, icon = "lunasmp:block/wildflowers_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "cactus_flower_vine", backing = Backing.WALL, icon = "lunasmp:block/cactus_flower_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "sunflower_vine", backing = Backing.WALL, icon = "lunasmp:block/sunflower_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "lilac_vine", backing = Backing.WALL, icon = "lunasmp:block/lilac_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "rose_bush_vine", backing = Backing.WALL, icon = "lunasmp:block/rose_bush_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "peony_vine", backing = Backing.WALL, icon = "lunasmp:block/peony_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "pitcher_plant_vine", backing = Backing.WALL, icon = "lunasmp:block/pitcher_plant_vine", hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "dandelion_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "poppy_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "blue_orchid_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "allium_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "azure_bluet_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "red_tulip_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "orange_tulip_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "white_tulip_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "pink_tulip_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "oxeye_daisy_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "cornflower_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "lily_of_the_valley_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "wither_rose_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "torchflower_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "chorus_flower_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "pink_petals_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "spore_blossom_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "open_eyeblossom_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "closed_eyeblossom_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "wildflowers_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "cactus_flower_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "sunflower_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "lilac_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "rose_bush_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "peony_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "pitcher_plant_bush", backing = Backing.PLANT, hardness = 0.2, sounds = SoundGroup.GRASS),
		Spec(id = "alstroemeria", backing = Backing.PLANT, icon = "lunasmp:block/alstroemeria", hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "hydrangea", backing = Backing.PLANT, icon = "lunasmp:block/hydrangea", hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "marigold", backing = Backing.PLANT, icon = "lunasmp:block/marigold", hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "daisies", backing = Backing.PLANT, icon = "lunasmp:block/daisies", hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "purple_cornflower", backing = Backing.PLANT, icon = "lunasmp:block/purple_cornflower", hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "petunia", backing = Backing.PLANT, icon = "lunasmp:block/petunia", hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "begonia", backing = Backing.PLANT, icon = "lunasmp:block/begonia", hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "snapdragon", backing = Backing.PLANT, icon = "lunasmp:block/snapdragon", hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "sweet_alyssum", backing = Backing.PLANT, icon = "lunasmp:block/sweet_alyssum", effect = Effect.GLIMMER, hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "gaillardia", backing = Backing.PLANT, icon = "lunasmp:block/gaillardia", effect = Effect.BURN, hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "oriental_poppy", backing = Backing.PLANT, icon = "lunasmp:block/oriental_poppy", effect = Effect.BLAST, hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "water_hemlock", backing = Backing.PLANT, icon = "lunasmp:block/water_hemlock", effect = Effect.POISON, hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "chrysanthemum", backing = Backing.PLANT, icon = "lunasmp:block/chrysanthemum", effect = Effect.MEND, hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "autumn_crocus", backing = Backing.PLANT, icon = "lunasmp:block/autumn_crocus", effect = Effect.DREAD, hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "black_eyed_susan", backing = Backing.PLANT, icon = "lunasmp:block/black_eyed_susan", effect = Effect.GUARD, hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "coreopsis", backing = Backing.PLANT, icon = "lunasmp:block/coreopsis", effect = Effect.FORTUNE, hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "dahlia", backing = Backing.PLANT, icon = "lunasmp:block/dahlia", effect = Effect.RALLY, hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "lavender", backing = Backing.PLANT, icon = "lunasmp:block/lavender", effect = Effect.LULL, hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "velvets", backing = Backing.PLANT, icon = "lunasmp:block/velvets", hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "bone_flower", backing = Backing.PLANT, icon = "lunasmp:block/bone_flower", hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "trade_flower", backing = Backing.PLANT, icon = "lunasmp:block/trade_flower", hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "root_of_the_worlds", backing = Backing.PLANT, icon = "lunasmp:block/root_of_the_worlds", hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "ethereal_orchid", backing = Backing.PLANT, icon = "lunasmp:block/ethereal_orchid", hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "dreadpetal", backing = Backing.PLANT, icon = "lunasmp:block/dreadpetal", effect = Effect.REPEL, hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "blindblossom", backing = Backing.PLANT, icon = "lunasmp:block/blindblossom", effect = Effect.LURE, hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "coal_flower", backing = Backing.PLANT, icon = "lunasmp:block/coal_flower", hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "copper_flower", backing = Backing.PLANT, icon = "lunasmp:block/copper_flower", hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "iron_flower", backing = Backing.PLANT, icon = "lunasmp:block/iron_flower", hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "gold_flower", backing = Backing.PLANT, icon = "lunasmp:block/gold_flower", hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "diamond_flower", backing = Backing.PLANT, icon = "lunasmp:block/diamond_flower", hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "emerald_flower", backing = Backing.PLANT, icon = "lunasmp:block/emerald_flower", hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "zinnia", backing = Backing.PLANT, icon = "lunasmp:block/zinnia_top", hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "cosmos", backing = Backing.PLANT, icon = "lunasmp:block/cosmos_top", hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "geranium", backing = Backing.PLANT, icon = "lunasmp:block/geranium_top", hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "oenothera", backing = Backing.PLANT, icon = "lunasmp:block/oenothera_top", effect = Effect.RUE, hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "autumn_asters", backing = Backing.PLANT, icon = "lunasmp:block/autumn_asters_top", hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "jack_flower", backing = Backing.PLANT, nightLight = 15, directional = true, hardness = 0.0, sounds = SoundGroup.GRASS),
		Spec(id = "coal_petal_block", backing = Backing.CUBE, hardness = 3.0, sounds = SoundGroup.STONE),
		Spec(id = "copper_petal_block", backing = Backing.CUBE, hardness = 3.0, sounds = SoundGroup.STONE),
		Spec(id = "iron_petal_block", backing = Backing.CUBE, hardness = 3.0, sounds = SoundGroup.STONE),
		Spec(id = "gold_petal_block", backing = Backing.CUBE, hardness = 3.0, sounds = SoundGroup.STONE),
		Spec(id = "diamond_petal_block", backing = Backing.CUBE, hardness = 3.0, sounds = SoundGroup.STONE),
		Spec(id = "emerald_petal_block", backing = Backing.CUBE, hardness = 3.0, sounds = SoundGroup.STONE),
	)

	/** Every flora block's spec, by block id. */
	val BY_ID: Map<String, Spec> = SPECS.associateBy { it.id }

	/** Every flora block, by its id. */
	val BLOCKS: Map<String, NovaBlock> = SPECS.associate { it.id to register(it) }

	/** Whether a spec has anything to do that a collision cannot answer. */
	private fun ticks(spec: Spec): Boolean =
		spec.nightLight > 0 || spec.effect?.ticking == true

	private fun register(spec: Spec): NovaBlock {
		if (!ticks(spec)) {
			return LunaSmp.block(spec.id) {
				configure(spec)
				behaviors(*(behaviorsOf(spec) + BlockDrops).toTypedArray())
			}
		}

		return LunaSmp.tileEntity(spec.id, ::FloraTile) {
			configure(spec)
			// once a second covers both jobs: an aura only has to be refreshed
			// before it lapses, and dusk is not a moment anybody can time
			tickrate(1)
			behaviors(*(behaviorsOf(spec) + TileEntityDrops).toTypedArray())
		}
	}

	private fun behaviorsOf(spec: Spec): List<BlockBehaviorHolder> = buildList {
		if (spec.effect != null && !spec.effect.ticking) {
			add(StepEffect(spec.effect))
		}

		add(Breakable(hardness = spec.hardness))
		add(BlockSounds(spec.sounds))
	}

	private fun AbstractNovaBlockBuilder<*>.configure(spec: Spec) {
		if (spec.nightLight > 0) {
			stateProperties(GLOWING.scope(setOf(false, true)) { false })
		}

		if (spec.backing == Backing.WALL) {
			stateProperties(DefaultBlockStateProperties.FACING.scope(WALLS) { ctx -> wallOf(ctx) })
		}

		if (spec.directional) {
			stateProperties(DefaultScopedBlockStateProperties.FACING_HORIZONTAL)
		}

		if (spec.backing == Backing.CUBE) {
			stateBacked(BackingStateCategory.NOTE_BLOCK) { defaultModel }
			return
		}

		// a wall plant's outline is the wall's: the flat clump against the
		// face it hangs on, exactly like a vanilla vine, instead of a small
		// box floating in the middle of the block
		entityBacked(stateSelector = {
			if (spec.backing == Backing.WALL) {
				val facing = getPropertyValueOrNull(DefaultBlockStateProperties.FACING) ?: BlockFace.NORTH

				Hitboxes.clumpAgainst(facing.oppositeFace)
			} else {
				Blocks.STRUCTURE_VOID.defaultBlockState()
			}
		}) {
			var model = when {
				spec.nightLight > 0 && getPropertyValueOrNull(GLOWING) != true -> getModel("lunasmp:block/${spec.id}_off")
				else -> defaultModel
			}

			if (spec.backing == Backing.WALL || spec.directional) {
				model = model.rotated()
			}

			model
		}
	}

	/**
	 * The wall a vine hangs on: the one it was stuck to, not the one the placer
	 * happened to be facing. Stuck to a floor or a ceiling there is no wall to
	 * read, and it falls back to standing across the placer's view.
	 */
	private fun wallOf(ctx: Context<BlockPlace>): BlockFace {
		val clicked = ctx[DefaultContextParamTypes.CLICKED_BLOCK_FACE]

		if (clicked != null && clicked in WALLS) {
			return clicked.oppositeFace
		}

		val direction = ctx[DefaultContextParamTypes.SOURCE_DIRECTION] ?: return BlockFace.NORTH

		if (abs(direction.x) > abs(direction.z)) {
			return if (direction.x > 0) BlockFace.WEST else BlockFace.EAST
		}

		return if (direction.z > 0) BlockFace.NORTH else BlockFace.SOUTH
	}
}
