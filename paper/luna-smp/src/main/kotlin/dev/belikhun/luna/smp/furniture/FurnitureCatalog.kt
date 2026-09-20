package dev.belikhun.luna.smp.furniture

import dev.belikhun.luna.smp.Hitboxes
import dev.belikhun.luna.smp.LunaSmp
import dev.belikhun.luna.smp.power.WirelessLampTile
import net.kyori.adventure.key.Key
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.core.Direction
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoorHingeSide
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.Half
import org.bukkit.block.BlockFace
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.resources.builder.layout.block.BackingStateCategory
import xyz.xenondevs.nova.resources.builder.layout.block.BlockSelectorScope
import xyz.xenondevs.nova.resources.builder.layout.block.BlockModelSelectorScope
import xyz.xenondevs.nova.resources.builder.model.ModelBuilder
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.AbstractNovaBlockBuilder
import xyz.xenondevs.nova.world.block.NovaBlock
import xyz.xenondevs.nova.world.block.behavior.BlockDrops
import xyz.xenondevs.nova.world.block.behavior.BlockSounds
import xyz.xenondevs.nova.world.block.behavior.Breakable
import xyz.xenondevs.nova.world.block.behavior.TileEntityDrops
import xyz.xenondevs.nova.world.block.behavior.TileEntityInteractive
import xyz.xenondevs.nova.world.block.sound.SoundGroup
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.block.state.property.DefaultScopedBlockStateProperties
import xyz.xenondevs.nova.world.block.state.property.impl.BooleanProperty
import xyz.xenondevs.nova.world.format.WorldDataManager
import kotlin.math.abs

/**
 * The generated furniture table.
 *
 * GENERATED FILE - do not edit by hand. Change tools/furniture-gen/catalog.ts
 * and re-run the generator; the models, configs and language files beside this
 * table come from the same pass, so editing one of them alone drifts the set.
 *
 * Every piece is entity-backed: furniture is exactly the case of a model that
 * is neither a cube nor block-shaped. The vanilla block behind the model is
 * what the game collides with and what the outline is drawn around: a barrier
 * (full solid cube) for anything you stand or sit on, a structure void (a
 * 6x6x6 box in the middle, no collider) for small decor and hanging pieces,
 * and for the panels a glass pane, whose post-and-arms shape follows the
 * panel's own connections. Borrowed blocks are skinned invisible by the hitbox
 * base pack (resource_pack/base_packs/luna-hitboxes), which is what reserves
 * them for this job server-wide. None of them may be an occluding block: an
 * occluder culls the face of whatever it sits against, and an invisible
 * occluder turns that culled face into a hole in the ground.
 */
@Suppress("unused")
object FurnitureCatalog {

	/** Whether a piece that lights up is currently burning. */
	val LIT = BooleanProperty(Key.key("lunasmp", "lit"))

	/**
	 * Whether a hanging-capable piece hangs. Decided once, at placement, by
	 * which face the player clicked: the underside of a ceiling hangs the
	 * piece, anything else stands it on the floor.
	 */
	val HANGING = BooleanProperty(Key.key("lunasmp", "hanging"))

	/** One per side: whether a connecting panel reaches out that way. */
	/**
	 * Whether a panel's flowered face is turned back at whoever placed it.
	 * A panel is not mirror-symmetric through its thickness, so without this
	 * a straight run could only ever face south or west.
	 */
	val FLIPPED = BooleanProperty(Key.key("lunasmp", "flipped"))

	/** Whether a piece that opens is open. */
	val OPEN = BooleanProperty(Key.key("lunasmp", "open"))

	/** The flipped leaf of a double door, hinge and handle mirrored. */
	val MIRROR = BooleanProperty(Key.key("lunasmp", "mirror"))
	val TOP = BooleanProperty(Key.key("lunasmp", "top"))

	val NORTH = BooleanProperty(Key.key("lunasmp", "north"))
	val EAST = BooleanProperty(Key.key("lunasmp", "east"))
	val SOUTH = BooleanProperty(Key.key("lunasmp", "south"))
	val WEST = BooleanProperty(Key.key("lunasmp", "west"))

	/** The side properties, in the order a model's name spells them. */
	/** A standing player's eye above their feet; sneaking is close enough. */
	private const val EYE_HEIGHT = 1.62

	val SIDES: Map<BlockFace, BooleanProperty> = linkedMapOf(
		BlockFace.NORTH to NORTH,
		BlockFace.EAST to EAST,
		BlockFace.SOUTH to SOUTH,
		BlockFace.WEST to WEST,
	)

	/** Where an item set down on a piece sits, in block units, facing north. */
	data class Slot(val x: Double, val y: Double, val z: Double)

	/** A piece that is simply always lit. */
	/**
	 * What a piece that opens sounds like, both ways.
	 *
	 * The sounds are held as their vanilla names, not as Sound constants:
	 * this table is built while the addon is still loading, and touching the
	 * sound registry that early loads it before the server means to, which
	 * takes the whole boot down with "registry is already loaded".
	 */
	enum class OpenSound(val open: String, val close: String) {
		DOOR("block.iron_door.open", "block.iron_door.close"),
		WOOD("block.wooden_door.open", "block.wooden_door.close"),
		SHUTTER("block.bamboo_wood_trapdoor.open", "block.bamboo_wood_trapdoor.close"),
		TAP("item.bucket.fill", "block.lever.click"),
	}

	data class Openable(val sound: OpenSound = OpenSound.WOOD)

	data class Light(
		val level: Int,
		val offset: Int,
	)

	/** What a piece that toggles does when it is lit. */
	data class Lamp(
		val level: Int,
		val offset: Int,
		val flint: Boolean,
		val litByDefault: Boolean,
	)

	/** The vanilla shape behind a piece; every value past SMALL is a borrowed
	 * visible block, skinned invisible by the hitbox base pack. */
	enum class Box {
		/** A barrier: the full cube. */
		SOLID,
		/** A structure void: a 6x6x6 box in the middle, no collider. */
		SMALL,
		/** A glass pane: post and arms, following a panel's connections. */
		PANE,
		/** A door's own slab, which swings with the state; two of these stack. */
		DOOR,
		/** A trapdoor stood on end: three pixels flush against one wall. */
		FLAT,
		/** A real trapdoor's swing: flat on the floor, standing when open. */
		TRAPDOOR,
		/** A closed iron trapdoor: a three-pixel plate on the floor or the
		 * ceiling that no hand click moves; the walkways stand on it. */
		PLATE,
		/** A closed trapdoor at the top of the block: three pixels of ceiling. */
		CEILING,
		/** A heavy core: the 8x8x8 block in the middle, under seats and boards. */
		PEDESTAL,
		/** An empty flower pot: the little box a planter stands as. */
		POT,
		/** A bare fence post: the four-pixel full-height column of a pole. */
		POST,
	}

	data class Spec(
		val id: String,
		val seatHeight: Double? = null,
		val directional: Boolean = false,
		/** Faces away from the placer instead of back at them: a railing goes
		 * on the edge you are looking past, not the one you stand at. */
		val faceAway: Boolean = false,

		/** A wall clock: the tile spins two live hands over the painted face. */
		val clock: Boolean = false,
		/** A real cube, drawn from a reserved note block state. */
		val cube: Boolean = false,
		val hanging: Boolean = false,
		/** Swings, rolls or folds open on a click; null when it does not. */
		val openable: Openable? = null,
		/** Fish swim about inside it. */
		val aquarium: Boolean = false,
		/** The upper half of a two-block piece, for the shape it borrows. */
		val upper: Boolean = false,
		/** A piece beside this one that opens along with it. */
		val openPartner: String? = null,
		val connects: String? = null,
		/** A member of a connecting family for its neighbours' sake only: a
		 * gate the fence on either side reaches out to. */
		val joins: String? = null,
		/** Joins neighbouring copies of itself: shared legs, joined skirts. */
		val legs: Boolean = false,
		val box: Box = Box.SOLID,
		val light: Light? = null,
		/** Lit only while a wireless power node in reach pays for it. */
		val wireless: Boolean = false,
		val lamp: Lamp? = null,
		val surface: List<Slot> = emptyList(),
		val surfaceUpright: Boolean = false,
		val storageRows: Int = 0,
		val showcase: Boolean = false,
		val pot: Boolean = false,
		val plantSlots: List<Slot> = listOf(Slot(0.5, 0.4375, 0.5)),
		val hardness: Double = 1.0,
		val sounds: SoundGroup = SoundGroup.WOOD,
	)

	private val SPECS = listOf(
		Spec(id = "oak_chair", seatHeight = 0.60, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_chair", seatHeight = 0.60, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "birch_chair", seatHeight = 0.60, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_chair", seatHeight = 0.60, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_chair", seatHeight = 0.60, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_chair", seatHeight = 0.60, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_chair", seatHeight = 0.60, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_chair", seatHeight = 0.60, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "bamboo_chair", seatHeight = 0.60, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_chair", seatHeight = 0.60, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "warped_chair", seatHeight = 0.60, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "pale_oak_chair", seatHeight = 0.60, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "oak_bench", seatHeight = 0.55, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_bench", seatHeight = 0.55, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "birch_bench", seatHeight = 0.55, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_bench", seatHeight = 0.55, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_bench", seatHeight = 0.55, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_bench", seatHeight = 0.55, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_bench", seatHeight = 0.55, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_bench", seatHeight = 0.55, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "bamboo_bench", seatHeight = 0.55, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_bench", seatHeight = 0.55, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "warped_bench", seatHeight = 0.55, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "pale_oak_bench", seatHeight = 0.55, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "oak_table", legs = true, surface = listOf(Slot(0.250, 1.001, 0.250), Slot(0.250, 1.001, 0.750), Slot(0.750, 1.001, 0.250), Slot(0.750, 1.001, 0.750)), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_table", legs = true, surface = listOf(Slot(0.250, 1.001, 0.250), Slot(0.250, 1.001, 0.750), Slot(0.750, 1.001, 0.250), Slot(0.750, 1.001, 0.750)), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "birch_table", legs = true, surface = listOf(Slot(0.250, 1.001, 0.250), Slot(0.250, 1.001, 0.750), Slot(0.750, 1.001, 0.250), Slot(0.750, 1.001, 0.750)), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_table", legs = true, surface = listOf(Slot(0.250, 1.001, 0.250), Slot(0.250, 1.001, 0.750), Slot(0.750, 1.001, 0.250), Slot(0.750, 1.001, 0.750)), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_table", legs = true, surface = listOf(Slot(0.250, 1.001, 0.250), Slot(0.250, 1.001, 0.750), Slot(0.750, 1.001, 0.250), Slot(0.750, 1.001, 0.750)), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_table", legs = true, surface = listOf(Slot(0.250, 1.001, 0.250), Slot(0.250, 1.001, 0.750), Slot(0.750, 1.001, 0.250), Slot(0.750, 1.001, 0.750)), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_table", legs = true, surface = listOf(Slot(0.250, 1.001, 0.250), Slot(0.250, 1.001, 0.750), Slot(0.750, 1.001, 0.250), Slot(0.750, 1.001, 0.750)), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_table", legs = true, surface = listOf(Slot(0.250, 1.001, 0.250), Slot(0.250, 1.001, 0.750), Slot(0.750, 1.001, 0.250), Slot(0.750, 1.001, 0.750)), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "bamboo_table", legs = true, surface = listOf(Slot(0.250, 1.001, 0.250), Slot(0.250, 1.001, 0.750), Slot(0.750, 1.001, 0.250), Slot(0.750, 1.001, 0.750)), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_table", legs = true, surface = listOf(Slot(0.250, 1.001, 0.250), Slot(0.250, 1.001, 0.750), Slot(0.750, 1.001, 0.250), Slot(0.750, 1.001, 0.750)), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "warped_table", legs = true, surface = listOf(Slot(0.250, 1.001, 0.250), Slot(0.250, 1.001, 0.750), Slot(0.750, 1.001, 0.250), Slot(0.750, 1.001, 0.750)), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "pale_oak_table", legs = true, surface = listOf(Slot(0.250, 1.001, 0.250), Slot(0.250, 1.001, 0.750), Slot(0.750, 1.001, 0.250), Slot(0.750, 1.001, 0.750)), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "oak_coffee_table", directional = true, legs = true, surface = listOf(Slot(0.250, 0.750, 0.500), Slot(0.750, 0.750, 0.500)), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_coffee_table", directional = true, legs = true, surface = listOf(Slot(0.250, 0.750, 0.500), Slot(0.750, 0.750, 0.500)), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "birch_coffee_table", directional = true, legs = true, surface = listOf(Slot(0.250, 0.750, 0.500), Slot(0.750, 0.750, 0.500)), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_coffee_table", directional = true, legs = true, surface = listOf(Slot(0.250, 0.750, 0.500), Slot(0.750, 0.750, 0.500)), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_coffee_table", directional = true, legs = true, surface = listOf(Slot(0.250, 0.750, 0.500), Slot(0.750, 0.750, 0.500)), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_coffee_table", directional = true, legs = true, surface = listOf(Slot(0.250, 0.750, 0.500), Slot(0.750, 0.750, 0.500)), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_coffee_table", directional = true, legs = true, surface = listOf(Slot(0.250, 0.750, 0.500), Slot(0.750, 0.750, 0.500)), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_coffee_table", directional = true, legs = true, surface = listOf(Slot(0.250, 0.750, 0.500), Slot(0.750, 0.750, 0.500)), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "bamboo_coffee_table", directional = true, legs = true, surface = listOf(Slot(0.250, 0.750, 0.500), Slot(0.750, 0.750, 0.500)), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_coffee_table", directional = true, legs = true, surface = listOf(Slot(0.250, 0.750, 0.500), Slot(0.750, 0.750, 0.500)), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "warped_coffee_table", directional = true, legs = true, surface = listOf(Slot(0.250, 0.750, 0.500), Slot(0.750, 0.750, 0.500)), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "oak_shelf", directional = true, box = Box.PEDESTAL, surface = listOf(Slot(0.167, 0.375, 0.781), Slot(0.500, 0.375, 0.781), Slot(0.833, 0.375, 0.781)), surfaceUpright = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_shelf", directional = true, box = Box.PEDESTAL, surface = listOf(Slot(0.167, 0.375, 0.781), Slot(0.500, 0.375, 0.781), Slot(0.833, 0.375, 0.781)), surfaceUpright = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "birch_shelf", directional = true, box = Box.PEDESTAL, surface = listOf(Slot(0.167, 0.375, 0.781), Slot(0.500, 0.375, 0.781), Slot(0.833, 0.375, 0.781)), surfaceUpright = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_shelf", directional = true, box = Box.PEDESTAL, surface = listOf(Slot(0.167, 0.375, 0.781), Slot(0.500, 0.375, 0.781), Slot(0.833, 0.375, 0.781)), surfaceUpright = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_shelf", directional = true, box = Box.PEDESTAL, surface = listOf(Slot(0.167, 0.375, 0.781), Slot(0.500, 0.375, 0.781), Slot(0.833, 0.375, 0.781)), surfaceUpright = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_shelf", directional = true, box = Box.PEDESTAL, surface = listOf(Slot(0.167, 0.375, 0.781), Slot(0.500, 0.375, 0.781), Slot(0.833, 0.375, 0.781)), surfaceUpright = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_shelf", directional = true, box = Box.PEDESTAL, surface = listOf(Slot(0.167, 0.375, 0.781), Slot(0.500, 0.375, 0.781), Slot(0.833, 0.375, 0.781)), surfaceUpright = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_shelf", directional = true, box = Box.PEDESTAL, surface = listOf(Slot(0.167, 0.375, 0.781), Slot(0.500, 0.375, 0.781), Slot(0.833, 0.375, 0.781)), surfaceUpright = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "bamboo_shelf", directional = true, box = Box.PEDESTAL, surface = listOf(Slot(0.167, 0.375, 0.781), Slot(0.500, 0.375, 0.781), Slot(0.833, 0.375, 0.781)), surfaceUpright = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_shelf", directional = true, box = Box.PEDESTAL, surface = listOf(Slot(0.167, 0.375, 0.781), Slot(0.500, 0.375, 0.781), Slot(0.833, 0.375, 0.781)), surfaceUpright = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "warped_shelf", directional = true, box = Box.PEDESTAL, surface = listOf(Slot(0.167, 0.375, 0.781), Slot(0.500, 0.375, 0.781), Slot(0.833, 0.375, 0.781)), surfaceUpright = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "oak_drawer", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_drawer", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "birch_drawer", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_drawer", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_drawer", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_drawer", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_drawer", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_drawer", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "bamboo_drawer", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_drawer", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "warped_drawer", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "white_sofa", seatHeight = 0.50, directional = true, box = Box.PEDESTAL, hardness = 0.8, sounds = SoundGroup.WOOD),
		Spec(id = "orange_sofa", seatHeight = 0.50, directional = true, box = Box.PEDESTAL, hardness = 0.8, sounds = SoundGroup.WOOD),
		Spec(id = "magenta_sofa", seatHeight = 0.50, directional = true, box = Box.PEDESTAL, hardness = 0.8, sounds = SoundGroup.WOOD),
		Spec(id = "light_blue_sofa", seatHeight = 0.50, directional = true, box = Box.PEDESTAL, hardness = 0.8, sounds = SoundGroup.WOOD),
		Spec(id = "yellow_sofa", seatHeight = 0.50, directional = true, box = Box.PEDESTAL, hardness = 0.8, sounds = SoundGroup.WOOD),
		Spec(id = "lime_sofa", seatHeight = 0.50, directional = true, box = Box.PEDESTAL, hardness = 0.8, sounds = SoundGroup.WOOD),
		Spec(id = "pink_sofa", seatHeight = 0.50, directional = true, box = Box.PEDESTAL, hardness = 0.8, sounds = SoundGroup.WOOD),
		Spec(id = "gray_sofa", seatHeight = 0.50, directional = true, box = Box.PEDESTAL, hardness = 0.8, sounds = SoundGroup.WOOD),
		Spec(id = "light_gray_sofa", seatHeight = 0.50, directional = true, box = Box.PEDESTAL, hardness = 0.8, sounds = SoundGroup.WOOD),
		Spec(id = "cyan_sofa", seatHeight = 0.50, directional = true, box = Box.PEDESTAL, hardness = 0.8, sounds = SoundGroup.WOOD),
		Spec(id = "purple_sofa", seatHeight = 0.50, directional = true, box = Box.PEDESTAL, hardness = 0.8, sounds = SoundGroup.WOOD),
		Spec(id = "blue_sofa", seatHeight = 0.50, directional = true, box = Box.PEDESTAL, hardness = 0.8, sounds = SoundGroup.WOOD),
		Spec(id = "brown_sofa", seatHeight = 0.50, directional = true, box = Box.PEDESTAL, hardness = 0.8, sounds = SoundGroup.WOOD),
		Spec(id = "green_sofa", seatHeight = 0.50, directional = true, box = Box.PEDESTAL, hardness = 0.8, sounds = SoundGroup.WOOD),
		Spec(id = "red_sofa", seatHeight = 0.50, directional = true, box = Box.PEDESTAL, hardness = 0.8, sounds = SoundGroup.WOOD),
		Spec(id = "black_sofa", seatHeight = 0.50, directional = true, box = Box.PEDESTAL, hardness = 0.8, sounds = SoundGroup.WOOD),
		Spec(id = "white_table_lamp", directional = true, box = Box.SMALL, wireless = true, lamp = Lamp(level = 13, offset = 1, flint = false, litByDefault = true), hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "orange_table_lamp", directional = true, box = Box.SMALL, wireless = true, lamp = Lamp(level = 13, offset = 1, flint = false, litByDefault = true), hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "magenta_table_lamp", directional = true, box = Box.SMALL, wireless = true, lamp = Lamp(level = 13, offset = 1, flint = false, litByDefault = true), hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "light_blue_table_lamp", directional = true, box = Box.SMALL, wireless = true, lamp = Lamp(level = 13, offset = 1, flint = false, litByDefault = true), hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "yellow_table_lamp", directional = true, box = Box.SMALL, wireless = true, lamp = Lamp(level = 13, offset = 1, flint = false, litByDefault = true), hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "lime_table_lamp", directional = true, box = Box.SMALL, wireless = true, lamp = Lamp(level = 13, offset = 1, flint = false, litByDefault = true), hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "pink_table_lamp", directional = true, box = Box.SMALL, wireless = true, lamp = Lamp(level = 13, offset = 1, flint = false, litByDefault = true), hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "gray_table_lamp", directional = true, box = Box.SMALL, wireless = true, lamp = Lamp(level = 13, offset = 1, flint = false, litByDefault = true), hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "light_gray_table_lamp", directional = true, box = Box.SMALL, wireless = true, lamp = Lamp(level = 13, offset = 1, flint = false, litByDefault = true), hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "cyan_table_lamp", directional = true, box = Box.SMALL, wireless = true, lamp = Lamp(level = 13, offset = 1, flint = false, litByDefault = true), hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "purple_table_lamp", directional = true, box = Box.SMALL, wireless = true, lamp = Lamp(level = 13, offset = 1, flint = false, litByDefault = true), hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "blue_table_lamp", directional = true, box = Box.SMALL, wireless = true, lamp = Lamp(level = 13, offset = 1, flint = false, litByDefault = true), hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "brown_table_lamp", directional = true, box = Box.SMALL, wireless = true, lamp = Lamp(level = 13, offset = 1, flint = false, litByDefault = true), hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "green_table_lamp", directional = true, box = Box.SMALL, wireless = true, lamp = Lamp(level = 13, offset = 1, flint = false, litByDefault = true), hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "red_table_lamp", directional = true, box = Box.SMALL, wireless = true, lamp = Lamp(level = 13, offset = 1, flint = false, litByDefault = true), hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "black_table_lamp", directional = true, box = Box.SMALL, wireless = true, lamp = Lamp(level = 13, offset = 1, flint = false, litByDefault = true), hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "rose_planter", directional = true, hanging = true, box = Box.POT, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "lilac_planter", directional = true, hanging = true, box = Box.POT, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "peony_planter", directional = true, hanging = true, box = Box.POT, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "sunflower_planter", directional = true, hanging = true, box = Box.POT, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "vine_planter", directional = true, hanging = true, box = Box.POT, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "rose_trellis", connects = "trellis", box = Box.PANE, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "lilac_trellis", connects = "trellis", box = Box.PANE, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "peony_trellis", connects = "trellis", box = Box.PANE, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "sunflower_trellis", connects = "trellis", box = Box.PANE, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "vine_trellis", connects = "trellis", box = Box.PANE, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "blue_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "green_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "purple_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "red_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "clay_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "dark_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "light_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "modern_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "teacup_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "pink_vase", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "cursed_urn", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "luxury_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "bright_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "bright_blue_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "sage_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "olive_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "plain_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "trimmed_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "heart_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "prize_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "basin_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "boot_planter", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "fresh_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "totem_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "clean_red_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "clean_white_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "patterned_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "petal_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "azalea_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "bloom_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "cauldron_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "wave_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "composter_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "glass_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "paper_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "porcelain_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "rustic_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "barrel_planter", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "basket_planter", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "sapling_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "black_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "bucket_planter", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "bronze_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "gold_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "white_terracotta_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "orange_terracotta_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "magenta_terracotta_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "light_blue_terracotta_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "yellow_terracotta_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "lime_terracotta_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "pink_terracotta_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "gray_terracotta_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "light_gray_terracotta_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "cyan_terracotta_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "purple_terracotta_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "blue_terracotta_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "brown_terracotta_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "green_terracotta_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "red_terracotta_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "black_terracotta_pot", directional = true, hanging = true, box = Box.PEDESTAL, pot = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "long_planter", directional = true, box = Box.PEDESTAL, pot = true, plantSlots = listOf(Slot(0.2800, 0.4375, 0.5000), Slot(0.7200, 0.4375, 0.5000)), hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "flower_basket", directional = true, box = Box.PEDESTAL, pot = true, plantSlots = listOf(Slot(0.3000, 0.3125, 0.3500), Slot(0.7000, 0.3125, 0.4000), Slot(0.5000, 0.3125, 0.7200)), hardness = 0.4, sounds = SoundGroup.WOOD),
		Spec(id = "display_case", showcase = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jar_lamp", directional = true, box = Box.SMALL, wireless = true, lamp = Lamp(level = 14, offset = 1, flint = false, litByDefault = true), hardness = 0.5, sounds = SoundGroup.STONE),
		Spec(id = "bamboo_lamp", directional = true, box = Box.SMALL, wireless = true, lamp = Lamp(level = 14, offset = 1, flint = false, litByDefault = true), hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "light_bulb_lamp", directional = true, box = Box.SMALL, wireless = true, lamp = Lamp(level = 15, offset = 1, flint = false, litByDefault = true), hardness = 0.5, sounds = SoundGroup.STONE),
		Spec(id = "red_candelabra", directional = true, box = Box.SMALL, lamp = Lamp(level = 12, offset = 1, flint = true, litByDefault = false), hardness = 0.8, sounds = SoundGroup.STONE),
		Spec(id = "brazier", lamp = Lamp(level = 15, offset = 1, flint = true, litByDefault = false), hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "soul_brazier", lamp = Lamp(level = 12, offset = 1, flint = true, litByDefault = false), hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "chandelier", directional = true, light = Light(level = 15, offset = -1), hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "soul_chandelier", directional = true, light = Light(level = 12, offset = -1), hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "lattice", connects = "lattice", box = Box.PANE, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "oak_kitchen_counter", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_kitchen_counter", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "birch_kitchen_counter", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_kitchen_counter", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_kitchen_counter", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_kitchen_counter", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_kitchen_counter", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_kitchen_counter", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "bamboo_kitchen_counter", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_kitchen_counter", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "warped_kitchen_counter", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "oak_kitchen_sink", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_kitchen_sink", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "birch_kitchen_sink", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_kitchen_sink", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_kitchen_sink", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_kitchen_sink", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_kitchen_sink", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_kitchen_sink", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "bamboo_kitchen_sink", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_kitchen_sink", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "warped_kitchen_sink", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "oak_cupboard", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_cupboard", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "birch_cupboard", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_cupboard", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_cupboard", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_cupboard", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_cupboard", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_cupboard", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "bamboo_cupboard", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_cupboard", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "warped_cupboard", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "oak_blinds", directional = true, openable = Openable(sound = OpenSound.SHUTTER), box = Box.FLAT, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_blinds", directional = true, openable = Openable(sound = OpenSound.SHUTTER), box = Box.FLAT, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "birch_blinds", directional = true, openable = Openable(sound = OpenSound.SHUTTER), box = Box.FLAT, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_blinds", directional = true, openable = Openable(sound = OpenSound.SHUTTER), box = Box.FLAT, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_blinds", directional = true, openable = Openable(sound = OpenSound.SHUTTER), box = Box.FLAT, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_blinds", directional = true, openable = Openable(sound = OpenSound.SHUTTER), box = Box.FLAT, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_blinds", directional = true, openable = Openable(sound = OpenSound.SHUTTER), box = Box.FLAT, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_blinds", directional = true, openable = Openable(sound = OpenSound.SHUTTER), box = Box.FLAT, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_blinds", directional = true, openable = Openable(sound = OpenSound.SHUTTER), box = Box.FLAT, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "warped_blinds", directional = true, openable = Openable(sound = OpenSound.SHUTTER), box = Box.FLAT, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "iron_blinds", directional = true, openable = Openable(sound = OpenSound.SHUTTER), box = Box.FLAT, hardness = 0.5, sounds = SoundGroup.STONE),
		Spec(id = "book_stack_1", directional = true, box = Box.SMALL, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "book_stack_2", directional = true, box = Box.SMALL, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "book_stack_3", directional = true, box = Box.SMALL, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "book_stack_4", directional = true, box = Box.SMALL, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "book_stack_5", directional = true, box = Box.SMALL, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "book_stack_6", directional = true, box = Box.SMALL, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "book_stack_7", directional = true, box = Box.SMALL, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "alban_picture", directional = true, box = Box.FLAT, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "aztec_picture", directional = true, box = Box.FLAT, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "aztec2_picture", directional = true, box = Box.FLAT, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "beach_picture", directional = true, box = Box.FLAT, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "bomb_picture", directional = true, box = Box.FLAT, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "flower_picture", directional = true, box = Box.FLAT, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "frog_picture", directional = true, box = Box.FLAT, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "kebab_picture", directional = true, box = Box.FLAT, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "plant_picture", directional = true, box = Box.FLAT, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "river_picture", directional = true, box = Box.FLAT, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "shroom_picture", directional = true, box = Box.FLAT, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "sunset_picture", directional = true, box = Box.FLAT, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "wasteland_picture", directional = true, box = Box.FLAT, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "crate", storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "apple_crate", storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "carrot_crate", storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "potato_crate", storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "wheat_crate", storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "egg_crate", storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "honeycomb_crate", storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "sweet_berry_crate", storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "white_bed_head", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "white_bed_foot", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "orange_bed_head", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "orange_bed_foot", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "magenta_bed_head", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "magenta_bed_foot", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "light_blue_bed_head", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "light_blue_bed_foot", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "yellow_bed_head", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "yellow_bed_foot", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "lime_bed_head", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "lime_bed_foot", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "pink_bed_head", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "pink_bed_foot", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "gray_bed_head", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "gray_bed_foot", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "light_gray_bed_head", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "light_gray_bed_foot", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "cyan_bed_head", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "cyan_bed_foot", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "purple_bed_head", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "purple_bed_foot", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "blue_bed_head", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "blue_bed_foot", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "brown_bed_head", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "brown_bed_foot", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "green_bed_head", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "green_bed_foot", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "red_bed_head", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "red_bed_foot", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "black_bed_head", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "black_bed_foot", seatHeight = 0.56, directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "oak_vanity", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_vanity", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "birch_vanity", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_vanity", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_vanity", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_vanity", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_vanity", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_vanity", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "bamboo_vanity", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_vanity", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "warped_vanity", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "pale_oak_vanity", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "aquarium", directional = true, aquarium = true, light = Light(level = 6, offset = 1), hardness = 0.8, sounds = SoundGroup.STONE),
		Spec(id = "glass_door", directional = true, openable = Openable(sound = OpenSound.DOOR), box = Box.DOOR, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "glass_door_top", directional = true, openable = Openable(sound = OpenSound.DOOR), upper = true, box = Box.DOOR, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "glass_trapdoor", directional = true, openable = Openable(sound = OpenSound.DOOR), box = Box.TRAPDOOR, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "toilet", seatHeight = 0.50, directional = true, openable = Openable(sound = OpenSound.SHUTTER), hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "bathtub", seatHeight = 0.40, directional = true, openable = Openable(sound = OpenSound.TAP), openPartner = "bathtub_tap", hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "bathtub_tap", directional = true, openable = Openable(sound = OpenSound.TAP), openPartner = "bathtub", hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "sink", directional = true, openable = Openable(sound = OpenSound.TAP), hardness = 1.2, sounds = SoundGroup.STONE),
		Spec(id = "oak_mirror", directional = true, box = Box.FLAT, hardness = 0.6, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_mirror", directional = true, box = Box.FLAT, hardness = 0.6, sounds = SoundGroup.WOOD),
		Spec(id = "birch_mirror", directional = true, box = Box.FLAT, hardness = 0.6, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_mirror", directional = true, box = Box.FLAT, hardness = 0.6, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_mirror", directional = true, box = Box.FLAT, hardness = 0.6, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_mirror", directional = true, box = Box.FLAT, hardness = 0.6, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_mirror", directional = true, box = Box.FLAT, hardness = 0.6, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_mirror", directional = true, box = Box.FLAT, hardness = 0.6, sounds = SoundGroup.WOOD),
		Spec(id = "bamboo_mirror", directional = true, box = Box.FLAT, hardness = 0.6, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_mirror", directional = true, box = Box.FLAT, hardness = 0.6, sounds = SoundGroup.WOOD),
		Spec(id = "warped_mirror", directional = true, box = Box.FLAT, hardness = 0.6, sounds = SoundGroup.WOOD),
		Spec(id = "wall_clock", directional = true, box = Box.FLAT, clock = true, hardness = 0.8, sounds = SoundGroup.WOOD),
		Spec(id = "white_refrigerator", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "white_refrigerator_top", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "light_gray_refrigerator", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "light_gray_refrigerator_top", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "gray_refrigerator", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "gray_refrigerator_top", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "black_refrigerator", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "black_refrigerator_top", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "red_refrigerator", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "red_refrigerator_top", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "light_blue_refrigerator", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "light_blue_refrigerator_top", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "yellow_refrigerator", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "yellow_refrigerator_top", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "lime_refrigerator", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "lime_refrigerator_top", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "oak_oven", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "spruce_oven", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "birch_oven", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "jungle_oven", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "acacia_oven", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "dark_oak_oven", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "mangrove_oven", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "cherry_oven", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "bamboo_oven", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "crimson_oven", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "warped_oven", directional = true, storageRows = 3, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "oak_tv", directional = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_tv", directional = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "birch_tv", directional = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_tv", directional = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_tv", directional = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_tv", directional = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_tv", directional = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_tv", directional = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "bamboo_tv", directional = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_tv", directional = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "warped_tv", directional = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "oak_tv_stand", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_tv_stand", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "birch_tv_stand", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_tv_stand", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_tv_stand", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_tv_stand", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_tv_stand", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_tv_stand", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "bamboo_tv_stand", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_tv_stand", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "warped_tv_stand", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "hazard_block", cube = true, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "drain_grate", hardness = 3.0, sounds = SoundGroup.STONE),
		Spec(id = "ceiling_lamp", box = Box.CEILING, wireless = true, lamp = Lamp(level = 15, offset = -1, flint = false, litByDefault = true), hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "clinker_bricks", cube = true, hardness = 3.0, sounds = SoundGroup.STONE),
		Spec(id = "stained_clinker_bricks", cube = true, hardness = 3.0, sounds = SoundGroup.STONE),
		Spec(id = "slag_bricks", cube = true, hardness = 3.0, sounds = SoundGroup.STONE),
		Spec(id = "rebar_concrete", cube = true, hardness = 4.0, sounds = SoundGroup.STONE),
		Spec(id = "rebar_concrete_tiles", cube = true, hardness = 4.0, sounds = SoundGroup.STONE),
		Spec(id = "industrial_planks", cube = true, hardness = 1.5, sounds = SoundGroup.WOOD),
		Spec(id = "grit_dirt", cube = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "grit_sand", cube = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "steel_floor_grating", hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "steel_table", surface = listOf(Slot(0.250, 1.000, 0.250), Slot(0.250, 1.000, 0.750), Slot(0.750, 1.000, 0.250), Slot(0.750, 1.000, 0.750)), hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "treated_wood_table", surface = listOf(Slot(0.250, 1.000, 0.250), Slot(0.250, 1.000, 0.750), Slot(0.750, 1.000, 0.250), Slot(0.750, 1.000, 0.750)), hardness = 1.5, sounds = SoundGroup.WOOD),
		Spec(id = "treated_wood_stool", seatHeight = 0.55, box = Box.PEDESTAL, hardness = 1.5, sounds = SoundGroup.WOOD),
		Spec(id = "treated_wood_pole", box = Box.POST, hardness = 1.5, sounds = SoundGroup.WOOD),
		Spec(id = "thin_steel_pole", box = Box.POST, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "thick_steel_pole", box = Box.POST, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "steel_railing", directional = true, box = Box.DOOR, faceAway = true, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "steel_mesh_fence", connects = "mesh", box = Box.PANE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "iron_bulb_light", directional = true, box = Box.FLAT, light = Light(level = 14, offset = 0), wireless = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "iron_inset_light", directional = true, box = Box.FLAT, light = Light(level = 12, offset = 0), wireless = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "ceiling_edge_light", directional = true, box = Box.FLAT, light = Light(level = 12, offset = 0), wireless = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "floor_edge_light", directional = true, box = Box.FLAT, light = Light(level = 10, offset = 0), wireless = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "metal_sliding_door", directional = true, openable = Openable(sound = OpenSound.DOOR), box = Box.DOOR, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "metal_sliding_door_top", directional = true, openable = Openable(sound = OpenSound.DOOR), upper = true, box = Box.DOOR, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "industrial_wood_door", directional = true, openable = Openable(sound = OpenSound.WOOD), box = Box.DOOR, hardness = 1.5, sounds = SoundGroup.WOOD),
		Spec(id = "industrial_wood_door_top", directional = true, openable = Openable(sound = OpenSound.WOOD), upper = true, box = Box.DOOR, hardness = 1.5, sounds = SoundGroup.WOOD),
		Spec(id = "iron_hatch", directional = true, openable = Openable(sound = OpenSound.DOOR), box = Box.TRAPDOOR, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "steel_mesh_gate", directional = true, openable = Openable(sound = OpenSound.DOOR), joins = "mesh", box = Box.DOOR, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "steel_catwalk", legs = true, box = Box.PLATE, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "steel_framed_window", connects = "window", box = Box.PANE, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "panzerglass", hardness = 3.0, sounds = SoundGroup.STONE),
		Spec(id = "steel_double_t_support", directional = true, box = Box.CEILING, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "treated_wood_pole_head", box = Box.POST, hardness = 1.5, sounds = SoundGroup.WOOD),
		Spec(id = "treated_wood_pole_support", box = Box.POST, hardness = 1.5, sounds = SoundGroup.WOOD),
		Spec(id = "thin_steel_pole_head", box = Box.POST, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "thick_steel_pole_head", box = Box.POST, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "oak_palisade", connects = "palisade", box = Box.PANE, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_palisade", connects = "palisade", box = Box.PANE, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "birch_palisade", connects = "palisade", box = Box.PANE, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_palisade", connects = "palisade", box = Box.PANE, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_palisade", connects = "palisade", box = Box.PANE, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_palisade", connects = "palisade", box = Box.PANE, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_palisade", connects = "palisade", box = Box.PANE, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_palisade", connects = "palisade", box = Box.PANE, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "bamboo_palisade", connects = "palisade", box = Box.PANE, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_palisade", connects = "palisade", box = Box.PANE, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "warped_palisade", connects = "palisade", box = Box.PANE, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "oak_support", directional = true, box = Box.SMALL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "oak_hanging_support", directional = true, box = Box.SMALL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_support", directional = true, box = Box.SMALL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_hanging_support", directional = true, box = Box.SMALL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "birch_support", directional = true, box = Box.SMALL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "birch_hanging_support", directional = true, box = Box.SMALL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_support", directional = true, box = Box.SMALL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_hanging_support", directional = true, box = Box.SMALL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_support", directional = true, box = Box.SMALL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_hanging_support", directional = true, box = Box.SMALL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_support", directional = true, box = Box.SMALL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_hanging_support", directional = true, box = Box.SMALL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_support", directional = true, box = Box.SMALL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_hanging_support", directional = true, box = Box.SMALL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_support", directional = true, box = Box.SMALL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_hanging_support", directional = true, box = Box.SMALL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "bamboo_support", directional = true, box = Box.SMALL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "bamboo_hanging_support", directional = true, box = Box.SMALL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_support", directional = true, box = Box.SMALL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_hanging_support", directional = true, box = Box.SMALL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "warped_support", directional = true, box = Box.SMALL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "warped_hanging_support", directional = true, box = Box.SMALL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "oak_seat", seatHeight = 0.45, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_seat", seatHeight = 0.45, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "birch_seat", seatHeight = 0.45, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_seat", seatHeight = 0.45, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_seat", seatHeight = 0.45, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_seat", seatHeight = 0.45, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_seat", seatHeight = 0.45, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_seat", seatHeight = 0.45, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "bamboo_seat", seatHeight = 0.45, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_seat", seatHeight = 0.45, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "warped_seat", seatHeight = 0.45, directional = true, box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "oak_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "birch_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "warped_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "stone_pillar", hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "smooth_stone_pillar", hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "sandstone_pillar", hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "red_sandstone_pillar", hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "blackstone_pillar", hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "basalt_pillar", hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "tuff_pillar", hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "mud_pillar", hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "iron_bar_panel", directional = true, openable = Openable(sound = OpenSound.DOOR), box = Box.TRAPDOOR, hardness = 2.0, sounds = SoundGroup.STONE),
		Spec(id = "bonfire", box = Box.SMALL, light = Light(level = 15, offset = 1), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "soul_bonfire", box = Box.SMALL, light = Light(level = 10, offset = 1), hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "step_ladder", directional = true, box = Box.SMALL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "rope_coil", cube = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "rocky_dirt", cube = true, hardness = 0.6, sounds = SoundGroup.STONE),
		Spec(id = "iron_lantern", box = Box.SMALL, light = Light(level = 14, offset = 1), wireless = true, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "hanging_iron_lantern", box = Box.SMALL, light = Light(level = 14, offset = -1), wireless = true, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "iron_wall_lantern", directional = true, box = Box.FLAT, light = Light(level = 14, offset = 1), wireless = true, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "gold_lantern", box = Box.SMALL, light = Light(level = 14, offset = 1), wireless = true, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "hanging_gold_lantern", box = Box.SMALL, light = Light(level = 14, offset = -1), wireless = true, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "gold_wall_lantern", directional = true, box = Box.FLAT, light = Light(level = 14, offset = 1), wireless = true, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "silver_lantern", box = Box.SMALL, light = Light(level = 14, offset = 1), wireless = true, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "hanging_silver_lantern", box = Box.SMALL, light = Light(level = 14, offset = -1), wireless = true, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "silver_wall_lantern", directional = true, box = Box.FLAT, light = Light(level = 14, offset = 1), wireless = true, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "iron_chandelier", box = Box.SMALL, light = Light(level = 15, offset = -1), hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "gold_chandelier", box = Box.SMALL, light = Light(level = 15, offset = -1), hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "silver_chandelier", box = Box.SMALL, light = Light(level = 15, offset = -1), hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "iron_candle", box = Box.SMALL, light = Light(level = 6, offset = 1), hardness = 0.5, sounds = SoundGroup.STONE),
		Spec(id = "gold_candle", box = Box.SMALL, light = Light(level = 6, offset = 1), hardness = 0.5, sounds = SoundGroup.STONE),
		Spec(id = "silver_candle", box = Box.SMALL, light = Light(level = 6, offset = 1), hardness = 0.5, sounds = SoundGroup.STONE),
		Spec(id = "gold_chain", box = Box.SMALL, hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "silver_chain", box = Box.SMALL, hardness = 1.0, sounds = SoundGroup.STONE),
		Spec(id = "clay_vase", hardness = 0.8, sounds = SoundGroup.STONE),
		Spec(id = "patterned_vase", hardness = 0.8, sounds = SoundGroup.STONE),
		Spec(id = "striped_vase", hardness = 0.8, sounds = SoundGroup.STONE),
		Spec(id = "jade_vase", hardness = 0.8, sounds = SoundGroup.STONE),
		Spec(id = "dark_vase", hardness = 0.8, sounds = SoundGroup.STONE),
		Spec(id = "painted_vase", hardness = 0.8, sounds = SoundGroup.STONE),
		Spec(id = "iron_barrel", hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "crushing_tub", box = Box.PEDESTAL, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "white_painted_wood", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "orange_painted_wood", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "magenta_painted_wood", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "light_blue_painted_wood", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "yellow_painted_wood", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "lime_painted_wood", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "pink_painted_wood", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "gray_painted_wood", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "light_gray_painted_wood", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cyan_painted_wood", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "purple_painted_wood", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "blue_painted_wood", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "brown_painted_wood", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "green_painted_wood", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "red_painted_wood", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "black_painted_wood", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "clay_wall", cube = true, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "crossed_clay_wall", cube = true, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "diagonal_clay_wall", cube = true, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "slate", cube = true, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "slate_bricks", cube = true, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "chiseled_slate", cube = true, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "slate_pillar", cube = true, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "slate_roof", cube = true, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "slate_tiles", cube = true, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "stone_column", cube = true, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "andesite_column", cube = true, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "diorite_column", cube = true, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "granite_column", cube = true, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "wooden_barrel", storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cabinet", directional = true, storageRows = 3, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "apiary", directional = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "straw_beehive", directional = true, hardness = 0.6, sounds = SoundGroup.WOOD),
		Spec(id = "brewing_barrel", directional = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "gargoyle", directional = true, hardness = 1.5, sounds = SoundGroup.STONE),
		Spec(id = "iron_torch", box = Box.SMALL, light = Light(level = 14, offset = 1), wireless = true, hardness = 0.5, sounds = SoundGroup.STONE),
		Spec(id = "iron_wall_torch", directional = true, box = Box.FLAT, light = Light(level = 14, offset = 1), wireless = true, hardness = 0.5, sounds = SoundGroup.STONE),
		Spec(id = "wooden_lantern", box = Box.SMALL, light = Light(level = 14, offset = 1), wireless = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "wooden_wall_lantern", directional = true, box = Box.FLAT, light = Light(level = 14, offset = 1), wireless = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "book_stack", box = Box.PEDESTAL, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "paper_stack", box = Box.PEDESTAL, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "candlestick", box = Box.PEDESTAL, light = Light(level = 9, offset = 1), hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "gold_candlestick", box = Box.PEDESTAL, light = Light(level = 9, offset = 1), hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "globe", directional = true, box = Box.PEDESTAL, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "ink_and_quill", directional = true, box = Box.SMALL, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "potion_bottles", directional = true, box = Box.PEDESTAL, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "scarecrow", directional = true, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "bone_pile", directional = true, box = Box.SMALL, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "trophy", directional = true, box = Box.PEDESTAL, hardness = 0.5, sounds = SoundGroup.WOOD),
		Spec(id = "oak_timber_frame", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "oak_timber_frame_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "oak_timber_frame_post", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "oak_timber_frame_cross", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "oak_braced_timber_frame", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "oak_braced_timber_frame_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "oak_braced_timber_frame_post", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "oak_braced_timber_frame_cross", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_timber_frame", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_timber_frame_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_timber_frame_post", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_timber_frame_cross", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_braced_timber_frame", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_braced_timber_frame_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_braced_timber_frame_post", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "spruce_braced_timber_frame_cross", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "birch_timber_frame", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "birch_timber_frame_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "birch_timber_frame_post", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "birch_timber_frame_cross", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "birch_braced_timber_frame", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "birch_braced_timber_frame_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "birch_braced_timber_frame_post", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "birch_braced_timber_frame_cross", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_timber_frame", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_timber_frame_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_timber_frame_post", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_timber_frame_cross", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_braced_timber_frame", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_braced_timber_frame_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_braced_timber_frame_post", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "jungle_braced_timber_frame_cross", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_timber_frame", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_timber_frame_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_timber_frame_post", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_timber_frame_cross", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_braced_timber_frame", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_braced_timber_frame_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_braced_timber_frame_post", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "acacia_braced_timber_frame_cross", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_timber_frame", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_timber_frame_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_timber_frame_post", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_timber_frame_cross", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_braced_timber_frame", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_braced_timber_frame_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_braced_timber_frame_post", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "dark_oak_braced_timber_frame_cross", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_timber_frame", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_timber_frame_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_timber_frame_post", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_timber_frame_cross", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_braced_timber_frame", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_braced_timber_frame_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_braced_timber_frame_post", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "mangrove_braced_timber_frame_cross", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_timber_frame", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_timber_frame_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_timber_frame_post", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_timber_frame_cross", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_braced_timber_frame", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_braced_timber_frame_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_braced_timber_frame_post", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "cherry_braced_timber_frame_cross", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "bamboo_timber_frame", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "bamboo_timber_frame_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "bamboo_timber_frame_post", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "bamboo_timber_frame_cross", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "bamboo_braced_timber_frame", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "bamboo_braced_timber_frame_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "bamboo_braced_timber_frame_post", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "bamboo_braced_timber_frame_cross", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_timber_frame", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_timber_frame_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_timber_frame_post", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_timber_frame_cross", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_braced_timber_frame", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_braced_timber_frame_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_braced_timber_frame_post", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "crimson_braced_timber_frame_cross", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "warped_timber_frame", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "warped_timber_frame_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "warped_timber_frame_post", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "warped_timber_frame_cross", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "warped_braced_timber_frame", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "warped_braced_timber_frame_beam", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "warped_braced_timber_frame_post", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
		Spec(id = "warped_braced_timber_frame_cross", cube = true, hardness = 1.0, sounds = SoundGroup.WOOD),
	)

	/** Every piece's spec, by block id. */
	val BY_ID: Map<String, Spec> = SPECS.associateBy { it.id }

	/**
	 * What each connecting piece joins up with, by block id. A panel only
	 * reaches out to its own family: every trellis meets every other trellis,
	 * whatever it flowers, but a lattice is a different screen at a different
	 * depth and stands on its own.
	 */
	val CONNECTING: Map<String, String> = SPECS
		.filter { it.connects != null || it.joins != null }
		.associate { it.id to (it.connects ?: it.joins!!) }

	/** Every generated block, by its id. */
	val BLOCKS: Map<String, NovaBlock> = SPECS.associate { it.id to register(it) }

	/** The wearable flower crowns, by item id; each has a model and nothing else. */
	val CROWNS: List<String> = listOf(
		"red_crown",
		"yellow_crown",
		"blue_crown",
		"white_crown",
		"pink_crown",
		"black_crown",
		"tulip_crown",
	)

	private fun register(spec: Spec): NovaBlock {
		// a street light keeps everything the furniture gives it - model,
		// facing, hitbox, hardness - and borrows the wireless fixture's tile.
		// Deliberately NO new state property: adding one orphans every placed
		// instance, and the lit state lives in the tile instead
		if (spec.wireless) {
			return LunaSmp.tileEntity(spec.id, ::WirelessLampTile) {
				configure(spec)

				// once a second: a fixture only asks whether it was paid for
				tickrate(1)
				behaviors(*(behaviorsOf(spec) + TileEntityDrops + TileEntityInteractive).toTypedArray())
			}
		}

		val stateful = spec.lamp != null || spec.surface.isNotEmpty()
			|| spec.storageRows > 0 || spec.showcase || spec.pot || spec.openable != null
			|| spec.aquarium || spec.clock

		if (!stateful) {
			return LunaSmp.block(spec.id) {
				configure(spec)
				behaviors(*(behaviorsOf(spec) + BlockDrops).toTypedArray())
			}
		}

		val constructor = when {
			spec.lamp != null -> ::FurnitureLamp
			spec.storageRows > 0 -> ::FurnitureStorage
			spec.pot -> ::FurniturePot
			spec.openable != null -> ::FurnitureOpen
			spec.aquarium -> ::FurnitureAquarium
			spec.clock -> ::FurnitureClock
			else -> ::FurnitureDisplay
		}

		return LunaSmp.tileEntity(spec.id, constructor) {
			configure(spec)
			// only the tank has anything to do per tick: the lamp reacts to a
			// click, the containers to their own inventories
			tickrate(if (spec.aquarium || spec.clock || spec.openable?.sound == OpenSound.TAP) 1 else 0)
			// the auto-built upper half of a door drops nothing: its item
			// comes back from the lower half, which breaks with it
			val drops = if (spec.box == Box.DOOR && spec.upper) {
				emptyList()
			} else {
				listOf(TileEntityDrops)
			}

			behaviors(*(behaviorsOf(spec) + drops + TileEntityInteractive).toTypedArray())
		}
	}

	private fun behaviorsOf(spec: Spec) = buildList {
		if (spec.seatHeight != null) {
			add(Seating(spec.seatHeight))
		}

		if (spec.connects != null) {
			add(PanelConnect)
		}

		if (spec.legs) {
			add(TableConnect)
		}

		// a wireless piece's light is the node's to give: its level and offset
		// are read by the fixture tile, never placed at build time
		if (spec.light != null && !spec.wireless) {
			add(EmitsLight(spec.light.offset, spec.light.level))
		}

		// the pot behind a standing planter is a real flower pot: a click with
		// a pottable plant must be swallowed, or vanilla pots the plant into
		// the hitbox block itself
		if (spec.box == Box.POT) {
			add(PotGuard)
		}

		add(Breakable(hardness = spec.hardness))
		add(BlockSounds(spec.sounds))
	}

	private fun AbstractNovaBlockBuilder<*>.configure(spec: Spec) {
		// a real cube needs no display entity and no borrowed hitbox: it is a
		// reserved note block state, so it lights, occludes and culls exactly
		// like the block it looks like
		if (spec.cube) {
			stateBacked(BackingStateCategory.NOTE_BLOCK) { defaultModel }
			return
		}

		if (spec.connects != null) {
			stateProperties(*SIDES.values.map { scopedSide(spec, it) }.toTypedArray(), scopedFlipped(spec))
		}

		if (spec.hanging) {
			stateProperties(scopedHanging())
		}

		if (spec.openable != null) {
			stateProperties(OPEN.scope(setOf(false, true)) { false })
		}

		if (spec.box == Box.TRAPDOOR || spec.box == Box.PLATE) {
			stateProperties(TOP.scope(setOf(false, true)) { ctx -> topOnPlace(ctx) })
		}

		// real doors only: the steel railing borrows the door COLLIDER for
		// its hitbox but has no leaf to mirror - giving it the property once
		// killed a boot on a steel_railing_mirror model that never existed
		if (spec.box == Box.DOOR && spec.openable != null) {
			stateProperties(MIRROR.scope(setOf(false, true)) { ctx -> doorMirror(spec, ctx) })
		}

		if (spec.legs) {
			stateProperties(*SIDES.map { (face, property) -> scopedLegSide(spec, face, property) }.toTypedArray())
		}

		if (spec.directional) {
			when {
				spec.box == Box.DOOR && spec.openable != null -> stateProperties(DefaultBlockStateProperties.FACING.scope(
					setOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST),
				) { ctx -> doorFacing(spec, ctx) })

				spec.faceAway -> stateProperties(DefaultBlockStateProperties.FACING.scope(
					setOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST),
				) { ctx -> awayFacing(ctx) })

				else -> stateProperties(DefaultScopedBlockStateProperties.FACING_HORIZONTAL)
			}
		}

		if (spec.lamp != null) {
			stateProperties(scopedLit(spec))
		}

		entityBacked(stateSelector = { hitboxOf(spec, this) }) {
			var model = when {
				spec.connects != null -> getModel("lunasmp:block/${spec.id}_${PanelConnect.armKey(this)}${flipKey()}")
				spec.box == Box.DOOR && spec.openable != null -> doorModel(spec, this)
				spec.openable != null && getPropertyValueOrNull(OPEN) == true -> getModel("lunasmp:block/${spec.id}_open")
				(spec.box == Box.TRAPDOOR || spec.box == Box.PLATE) && getPropertyValueOrNull(TOP) == true -> getModel("lunasmp:block/${spec.id}_top")
				spec.legs && legMask(this) != 0 -> getModel("lunasmp:block/${spec.id}_c${legMask(this)}")
				spec.hanging && getPropertyValueOrNull(HANGING) == true -> getModel("lunasmp:block/hanging_${spec.id}")
				spec.lamp != null && getPropertyValueOrNull(LIT) == false -> getModel("lunasmp:block/${spec.id}_off")
				else -> defaultModel
			}

			if (spec.directional) {
				model = model.rotated()
			}

			model
		}
	}

	/**
	 * The vanilla block state whose shape backs this piece. A pane-backed
	 * panel gets the pane state matching the arms it draws, so the collision
	 * and the outline follow the panel through corners and crossings.
	 */
	private fun hitboxOf(spec: Spec, scope: BlockSelectorScope): BlockState {
		// an open door is walked through: the shape goes with the state, not
		// with the piece, or it would still stand in its own doorway. Only a
		// door, though - a running tap is not a hole in the sink, and the
		// pane box is what says a piece is a door
		if (spec.openable != null && spec.box == Box.PANE && scope.getPropertyValueOrNull(OPEN) == true) {
			return Blocks.STRUCTURE_VOID.defaultBlockState()
		}

		return shapeOf(spec, scope)
	}

	private fun shapeOf(spec: Spec, scope: BlockSelectorScope): BlockState = when (spec.box) {
		Box.SOLID -> Blocks.BARRIER.defaultBlockState()

		Box.SMALL -> Blocks.STRUCTURE_VOID.defaultBlockState()

		Box.PANE ->
			if (spec.connects != null) {
				paneState(PanelConnect.drawnArms(scope))
			} else {
				paneState(acrossArms(scope.getPropertyValueOrNull(DefaultBlockStateProperties.FACING)))
			}

		Box.DOOR -> doorState(spec, scope)

		// the one shape that still borrows a real warped trapdoor, because a
		// hatch WANTS the trapdoor's behaviour: the client's open prediction
		// matches the toggle the server makes, so nothing flickers
		// an IRON trapdoor, never a wooden one: a hand cannot open an iron
		// trapdoor, so the one vanilla path that toggled the backing under a
		// piece - a sneaking click the tile hands back to vanilla so blocks
		// can be placed against it - now does nothing. A warped backing that
		// got toggled that way became a state Nova no longer recognised as
		// the piece's, the piece was dropped, and an invisible vanilla
		// trapdoor stayed behind ("the glass trapdoor went transparent and
		// broke into a warped trapdoor"). POWERED marks it as ours for the
		// hitbox skin, exactly as the walkway plate does.
		Box.TRAPDOOR -> Blocks.IRON_TRAPDOOR.defaultBlockState()
			.setValue(BlockStateProperties.HORIZONTAL_FACING, facingOf(scope))
			.setValue(BlockStateProperties.OPEN, spec.openable != null && scope.getPropertyValueOrNull(OPEN) == true)
			.setValue(
				BlockStateProperties.HALF,
				if (scope.getPropertyValueOrNull(TOP) == true) Half.TOP else Half.BOTTOM,
			)
			.setValue(BlockStateProperties.POWERED, true)

		// the same plate, but a closed IRON trapdoor: nothing a hand click
		// moves, so the client predicts no swing on a walkway
		Box.PLATE -> Hitboxes.plate(scope.getPropertyValueOrNull(TOP) == true)

		// a resin clump against the wall: one pixel of outline exactly where
		// the panel hangs, and a block the client predicts nothing for - the
		// warped trapdoor it replaced swung open client-side on every click
		Box.FLAT -> Hitboxes.clumpAgainst(
			scope.getPropertyValueOrNull(DefaultBlockStateProperties.FACING) ?: BlockFace.NORTH,
		)

		// the same clump on the ceiling, for the flush fittings up there
		Box.CEILING -> Hitboxes.clumpOnCeiling()

		Box.PEDESTAL -> Blocks.HEAVY_CORE.defaultBlockState()

		Box.POST -> Hitboxes.post()

		// a hanging planter is a rope-hung pot mid-block: nothing pot-shaped
		// hangs, so that state falls back to the small box
		Box.POT ->
			if (scope.getPropertyValueOrNull(HANGING) == true) {
				Blocks.STRUCTURE_VOID.defaultBlockState()
			} else {
				Blocks.FLOWER_POT.defaultBlockState()
			}
	}

	/**
	 * The door slab this half of a door stands in.
	 *
	 * A vanilla door faces the way you came at it, so the state takes the
	 * piece's own facing turned around; open swings it a quarter turn, which
	 * is the whole reason a door borrows a door rather than a pane.
	 */
	private fun doorState(spec: Spec, scope: BlockSelectorScope): BlockState {
		val open = spec.openable != null && scope.getPropertyValueOrNull(OPEN) == true

		// an IRON door, tenth sacrifice: a hand-openable backing makes every
		// client toggle it predictively, and that local change cascades shape
		// updates into the neighbours - a note-block-canvas Nova block above
		// or below (clinker, hazard...) recomputes its instrument and renders
		// as a bare noteblock until the server resyncs. Iron doors ignore
		// hand clicks client-side, so nothing is predicted and nothing
		// flickers; our own toggle stays a plain server-side state swap.
		return Blocks.IRON_DOOR.defaultBlockState()
			.setValue(BlockStateProperties.HORIZONTAL_FACING, facingOf(scope).opposite)
			.setValue(BlockStateProperties.OPEN, open)
			// POWERED marks the state as OURS: the luna-hitboxes skin hides
			// only powered iron doors, so real (unpowered) iron doors stay
			// visible in the world - the first all-states sacrifice made
			// every player-built iron door transparent. Powered is inert
			// here: Nova neuters vanilla block logic at Nova positions.
			.setValue(BlockStateProperties.POWERED, true)
			// the mirrored leaf swings the other way, outline and all
			.setValue(
				BlockStateProperties.DOOR_HINGE,
				if (scope.getPropertyValueOrNull(MIRROR) == true) DoorHingeSide.RIGHT else DoorHingeSide.LEFT,
			)
			.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, if (spec.upper) DoubleBlockHalf.UPPER else DoubleBlockHalf.LOWER)
	}

	/**
	 * Vanilla's trapdoor half, recovered without the click point this nova
	 * version hands out: top when a ceiling was clicked, bottom for a floor,
	 * and for a wall the placer's own look ray is run onto the clicked plane
	 * - the same point the client aimed at - and the half is whichever side
	 * of the middle it lands on.
	 */
	/** The door leaf's model: base or mirrored, closed or swung open. */
	private fun doorModel(spec: Spec, scope: BlockModelSelectorScope): ModelBuilder {
		val open = scope.getPropertyValueOrNull(OPEN) == true
		val mirror = scope.getPropertyValueOrNull(MIRROR) == true
		val suffix = (if (mirror) "_mirror" else "") + (if (open) "_open" else "")

		if (suffix.isEmpty()) {
			return scope.defaultModel
		}

		return scope.getModel("lunasmp:block/${spec.id}$suffix")
	}

	/**
	 * Whether a freshly placed door leaf is the mirrored one. A neighbouring
	 * half of the same door decides first: the second leaf of a pair takes
	 * the opposite of its neighbour, so the two read as one double door.
	 * Otherwise the WALL decides, the way vanilla picks a hinge: the leaf
	 * anchors its hinge against the solid side of the doorway, so a single
	 * door in an opening swings off the jamb rather than into it.
	 */
	private fun doorMirror(spec: Spec, ctx: Context<BlockPlace>): Boolean {
		val pos = ctx[DefaultContextParamTypes.BLOCK_POS] ?: return false

		// the wall decides first (the user's own priority): the leaf seats
		// its hinge against the solid side of the doorway, so a single door
		// swings off the jamb. The unmirrored leaf hinges on the facing's
		// CLOCKWISE side - the viewer's left, verified in game 2026-09-03
		// after the first guess shipped the other chirality. Occluding = a
		// real jamb, so glass, slabs and our own doors do not count.
		val facing = ctx[DefaultContextParamTypes.SOURCE_DIRECTION]
			?.let { direction ->
				if (abs(direction.x) > abs(direction.z)) {
					if (direction.x > 0) BlockFace.WEST else BlockFace.EAST
				} else {
					if (direction.z > 0) BlockFace.NORTH else BlockFace.SOUTH
				}
			}
			?: BlockFace.NORTH

		val hingeWall = pos.advance(clockwise(facing), 1).block.type.isOccluding
		val mirrorWall = pos.advance(counterclockwise(facing), 1).block.type.isOccluding

		if (hingeWall != mirrorWall) {
			return mirrorWall
		}

		// no deciding wall: a neighbouring half of the same door - the second
		// leaf takes the opposite, so the pair reads as one double door
		val family = spec.id.removeSuffix("_top")

		for (face in listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)) {
			val state = WorldDataManager.getBlockState(pos.advance(face, 1)) ?: continue

			if (state.block.id.value().removeSuffix("_top") != family) {
				continue
			}

			return state[MIRROR] != true
		}

		return false
	}

	private fun clockwise(face: BlockFace): BlockFace = when (face) {
		BlockFace.NORTH -> BlockFace.EAST
		BlockFace.EAST -> BlockFace.SOUTH
		BlockFace.SOUTH -> BlockFace.WEST
		else -> BlockFace.NORTH
	}

	private fun counterclockwise(face: BlockFace): BlockFace = when (face) {
		BlockFace.NORTH -> BlockFace.WEST
		BlockFace.WEST -> BlockFace.SOUTH
		BlockFace.SOUTH -> BlockFace.EAST
		else -> BlockFace.NORTH
	}

	/**
	 * Which way a door half faces when placed: the way a neighbouring half of
	 * the same door already faces, so a double doorway lines up whatever
	 * angle each leaf was placed from - two adjacent doors placed from
	 * slightly different angles used to land on opposite facings, one leaf
	 * two pixels behind the other. With no neighbour, back at the placer.
	 */
	private fun doorFacing(spec: Spec, ctx: Context<BlockPlace>): BlockFace {
		val pos = ctx[DefaultContextParamTypes.BLOCK_POS]

		if (pos != null) {
			val family = spec.id.removeSuffix("_top")

			for (face in listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)) {
				val state = WorldDataManager.getBlockState(pos.advance(face, 1)) ?: continue

				if (state.block.id.value().removeSuffix("_top") != family) {
					continue
				}

				val neighbour = state[DefaultBlockStateProperties.FACING] ?: continue

				return neighbour
			}
		}

		return awayFacing(ctx).oppositeFace
	}

	/** The horizontal face the placer is looking along, not the one looking back. */
	private fun awayFacing(ctx: Context<BlockPlace>): BlockFace {
		val direction = ctx[DefaultContextParamTypes.SOURCE_DIRECTION] ?: return BlockFace.NORTH

		return if (abs(direction.x) > abs(direction.z)) {
			if (direction.x > 0) BlockFace.EAST else BlockFace.WEST
		} else {
			if (direction.z > 0) BlockFace.SOUTH else BlockFace.NORTH
		}
	}

	private fun topOnPlace(ctx: Context<BlockPlace>): Boolean {
		val clicked = ctx[DefaultContextParamTypes.CLICKED_BLOCK_FACE] ?: return false

		if (clicked == BlockFace.UP) {
			return false
		}

		if (clicked == BlockFace.DOWN) {
			return true
		}

		val pos = ctx[DefaultContextParamTypes.BLOCK_POS] ?: return false
		val source = ctx[DefaultContextParamTypes.SOURCE_LOCATION] ?: return false
		val look = ctx[DefaultContextParamTypes.SOURCE_DIRECTION] ?: return false

		// the clicked face belongs to the supporting block, so its plane is
		// this block's own boundary on the side facing that support
		val plane = when (clicked) {
			BlockFace.NORTH -> pos.z + 1.0
			BlockFace.SOUTH -> pos.z.toDouble()
			BlockFace.EAST -> pos.x.toDouble()
			else -> pos.x + 1.0
		}

		val origin = when (clicked) {
			BlockFace.NORTH, BlockFace.SOUTH -> source.z
			else -> source.x
		}

		val delta = when (clicked) {
			BlockFace.NORTH, BlockFace.SOUTH -> look.z
			else -> look.x
		}

		if (abs(delta) < 1e-6) {
			return false
		}

		val t = (plane - origin) / delta
		val hitY = source.y + EYE_HEIGHT + look.y * t

		return hitY - pos.y > 0.5
	}

	/** A table side connects when the block there is the same table. */
	private fun scopedLegSide(spec: Spec, face: BlockFace, property: BooleanProperty) =
		property.scope(setOf(false, true)) { ctx ->
			val pos = ctx[DefaultContextParamTypes.BLOCK_POS]

			pos != null && sameBlock(pos.add(face.modX, 0, face.modZ), spec.id)
		}

	/** Whether the nova block at [pos] is the piece named [id]. */
	fun sameBlock(pos: BlockPos, id: String): Boolean =
		WorldDataManager.getBlockState(pos)?.block?.id?.value() == id

	/** Which sides of a connecting table have a matching neighbour, as bits. */
	private fun legMask(scope: BlockSelectorScope): Int {
		var mask = 0

		SIDES.values.forEachIndexed { index, property ->
			if (scope.getPropertyValueOrNull(property) == true) {
				mask = mask or (1 shl index)
			}
		}

		return mask
	}

	/** The way a piece faces, as the game names directions. */
	private fun facingOf(scope: BlockSelectorScope): Direction = when (scope.getPropertyValueOrNull(DefaultBlockStateProperties.FACING)) {
		BlockFace.NORTH -> Direction.NORTH
		BlockFace.EAST -> Direction.EAST
		BlockFace.WEST -> Direction.WEST
		else -> Direction.SOUTH
	}

	private fun paneState(arms: Set<BlockFace>): BlockState {
		var state = Blocks.MAGENTA_STAINED_GLASS_PANE.defaultBlockState()
		state = state.setValue(BlockStateProperties.NORTH, BlockFace.NORTH in arms)
		state = state.setValue(BlockStateProperties.EAST, BlockFace.EAST in arms)
		state = state.setValue(BlockStateProperties.SOUTH, BlockFace.SOUTH in arms)
		state = state.setValue(BlockStateProperties.WEST, BlockFace.WEST in arms)

		return state
	}

	/** A facing panel stands across its facing: a north-facing wall runs east-west. */
	private fun acrossArms(facing: BlockFace?): Set<BlockFace> = when (facing) {
		BlockFace.EAST, BlockFace.WEST -> setOf(BlockFace.NORTH, BlockFace.SOUTH)
		else -> setOf(BlockFace.EAST, BlockFace.WEST)
	}

	private fun scopedLit(spec: Spec) =
		// a wireless lamp starts dark and is lit by the first node that pays
		LIT.scope(setOf(false, true)) { spec.lamp?.litByDefault == true && !spec.wireless }

	private fun scopedHanging() =
		HANGING.scope(setOf(false, true)) { ctx -> ctx[DefaultContextParamTypes.CLICKED_BLOCK_FACE] == BlockFace.DOWN }

	private fun BlockSelectorScope.flipKey(): String =
		if (getPropertyValueOrNull(FLIPPED) == true) "_f" else ""

	private fun scopedFlipped(spec: Spec) =
		FLIPPED.scope(setOf(false, true)) { ctx -> PanelConnect.flippedOnPlace(ctx, spec.connects!!) }

	private fun scopedSide(spec: Spec, property: BooleanProperty) =
		property.scope(setOf(false, true)) { ctx -> property in PanelConnect.armsOnPlace(ctx, spec.connects!!) }
}
