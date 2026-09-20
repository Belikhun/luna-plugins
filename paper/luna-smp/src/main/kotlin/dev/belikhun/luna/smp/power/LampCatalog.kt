package dev.belikhun.luna.smp.power

import dev.belikhun.luna.smp.Hitboxes
import dev.belikhun.luna.smp.LunaSmp
import dev.belikhun.luna.smp.gauges.GaugeCatalog
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.bukkit.block.BlockFace
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.resources.builder.layout.block.BlockModelSelectorScope
import xyz.xenondevs.nova.resources.builder.model.ModelBuilder
import xyz.xenondevs.nova.world.block.NovaBlock
import xyz.xenondevs.nova.world.block.behavior.BlockSounds
import xyz.xenondevs.nova.world.block.behavior.Breakable
import xyz.xenondevs.nova.world.block.behavior.TileEntityDrops
import xyz.xenondevs.nova.world.block.behavior.TileEntityInteractive
import xyz.xenondevs.nova.world.block.sound.SoundGroup
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import kotlin.math.abs

/**
 * The generated wireless-lamp table: the node and the fixtures it lights.
 *
 * GENERATED FILE - do not edit by hand. Change tools/furniture-gen/lamps.ts
 * and re-run the generator; the models, the textures and this table come from
 * the same pass, so editing one of them alone drifts the set.
 *
 * A fixture's draw is what it costs the node each second, before the node's
 * efficiency upgrade; its level is the vanilla light it throws into the block
 * it faces. The node's own two numbers are the reach and the buffer of an
 * un-upgraded node - the upgrades build on them at runtime.
 */
@Suppress("unused")
object LampCatalog {

	/**
	 * The shapes a fixture comes in; the shape picks the hitbox. STREET is
	 * the furniture catalog's lights - lanterns, torches, lamps, the
	 * Engineer's Decor fittings - registered there and only borrowing the
	 * tile, so this catalog registers no block for them.
	 */
	enum class Kind {
		BULB, PANEL, BLOCK, POLE, STREET,
	}

	/** Where a fixture throws its light: the block it faces, above, or below. */
	enum class Target {
		FRONT, UP, DOWN,
	}

	/** Which block-state property mirrors the lit state, if any. */
	enum class State {
		NONE, ON, LIT,
	}

	data class LampSpec(
		val id: String,
		val kind: Kind,
		/** Joules a second the fixture costs the node that lights it. */
		val draw: Long,
		/** The vanilla light level it throws. */
		val level: Int,
		val target: Target,
		val state: State,
	)

	const val NODE_ID = "wireless_power_node"
	const val SWITCH_ID = "wireless_light_switch"
	const val BUTTON_ID = "wireless_light_button"

	/** How far a bare node reaches, in blocks each way. */
	const val NODE_RADIUS = 8

	/** What a bare node holds, in joules. */
	const val NODE_CAPACITY = 10000L

	/**
	 * What radiating the field costs, in joules a second per block of radius:
	 * a node's standing draw is this times its current reach, on top of what
	 * its fixtures take, so reach is paid for whether or not it is used.
	 */
	const val FIELD_DRAW_PER_BLOCK = 10L

	/** Blocks of reach one range upgrade buys; the config ladder is built from it. */
	const val RANGE_STEP = 2

	val LAMPS: List<LampSpec> = listOf(
		LampSpec(id = "wireless_bulb", kind = Kind.BULB, draw = 90L, level = 14, target = Target.FRONT, state = State.ON),
		LampSpec(id = "wireless_light_panel", kind = Kind.PANEL, draw = 150L, level = 15, target = Target.FRONT, state = State.ON),
		LampSpec(id = "wireless_light_block", kind = Kind.BLOCK, draw = 300L, level = 15, target = Target.UP, state = State.ON),
		LampSpec(id = "wireless_light_pole", kind = Kind.POLE, draw = 120L, level = 15, target = Target.UP, state = State.ON),
		LampSpec(id = "white_table_lamp", kind = Kind.STREET, draw = 78L, level = 13, target = Target.UP, state = State.LIT),
		LampSpec(id = "orange_table_lamp", kind = Kind.STREET, draw = 78L, level = 13, target = Target.UP, state = State.LIT),
		LampSpec(id = "magenta_table_lamp", kind = Kind.STREET, draw = 78L, level = 13, target = Target.UP, state = State.LIT),
		LampSpec(id = "light_blue_table_lamp", kind = Kind.STREET, draw = 78L, level = 13, target = Target.UP, state = State.LIT),
		LampSpec(id = "yellow_table_lamp", kind = Kind.STREET, draw = 78L, level = 13, target = Target.UP, state = State.LIT),
		LampSpec(id = "lime_table_lamp", kind = Kind.STREET, draw = 78L, level = 13, target = Target.UP, state = State.LIT),
		LampSpec(id = "pink_table_lamp", kind = Kind.STREET, draw = 78L, level = 13, target = Target.UP, state = State.LIT),
		LampSpec(id = "gray_table_lamp", kind = Kind.STREET, draw = 78L, level = 13, target = Target.UP, state = State.LIT),
		LampSpec(id = "light_gray_table_lamp", kind = Kind.STREET, draw = 78L, level = 13, target = Target.UP, state = State.LIT),
		LampSpec(id = "cyan_table_lamp", kind = Kind.STREET, draw = 78L, level = 13, target = Target.UP, state = State.LIT),
		LampSpec(id = "purple_table_lamp", kind = Kind.STREET, draw = 78L, level = 13, target = Target.UP, state = State.LIT),
		LampSpec(id = "blue_table_lamp", kind = Kind.STREET, draw = 78L, level = 13, target = Target.UP, state = State.LIT),
		LampSpec(id = "brown_table_lamp", kind = Kind.STREET, draw = 78L, level = 13, target = Target.UP, state = State.LIT),
		LampSpec(id = "green_table_lamp", kind = Kind.STREET, draw = 78L, level = 13, target = Target.UP, state = State.LIT),
		LampSpec(id = "red_table_lamp", kind = Kind.STREET, draw = 78L, level = 13, target = Target.UP, state = State.LIT),
		LampSpec(id = "black_table_lamp", kind = Kind.STREET, draw = 78L, level = 13, target = Target.UP, state = State.LIT),
		LampSpec(id = "jar_lamp", kind = Kind.STREET, draw = 84L, level = 14, target = Target.UP, state = State.LIT),
		LampSpec(id = "bamboo_lamp", kind = Kind.STREET, draw = 84L, level = 14, target = Target.UP, state = State.LIT),
		LampSpec(id = "light_bulb_lamp", kind = Kind.STREET, draw = 90L, level = 15, target = Target.UP, state = State.LIT),
		LampSpec(id = "ceiling_lamp", kind = Kind.STREET, draw = 90L, level = 15, target = Target.DOWN, state = State.LIT),
		LampSpec(id = "iron_bulb_light", kind = Kind.STREET, draw = 84L, level = 14, target = Target.FRONT, state = State.NONE),
		LampSpec(id = "iron_inset_light", kind = Kind.STREET, draw = 72L, level = 12, target = Target.FRONT, state = State.NONE),
		LampSpec(id = "ceiling_edge_light", kind = Kind.STREET, draw = 72L, level = 12, target = Target.FRONT, state = State.NONE),
		LampSpec(id = "floor_edge_light", kind = Kind.STREET, draw = 60L, level = 10, target = Target.FRONT, state = State.NONE),
		LampSpec(id = "iron_lantern", kind = Kind.STREET, draw = 84L, level = 14, target = Target.UP, state = State.NONE),
		LampSpec(id = "hanging_iron_lantern", kind = Kind.STREET, draw = 84L, level = 14, target = Target.DOWN, state = State.NONE),
		LampSpec(id = "iron_wall_lantern", kind = Kind.STREET, draw = 84L, level = 14, target = Target.UP, state = State.NONE),
		LampSpec(id = "gold_lantern", kind = Kind.STREET, draw = 84L, level = 14, target = Target.UP, state = State.NONE),
		LampSpec(id = "hanging_gold_lantern", kind = Kind.STREET, draw = 84L, level = 14, target = Target.DOWN, state = State.NONE),
		LampSpec(id = "gold_wall_lantern", kind = Kind.STREET, draw = 84L, level = 14, target = Target.UP, state = State.NONE),
		LampSpec(id = "silver_lantern", kind = Kind.STREET, draw = 84L, level = 14, target = Target.UP, state = State.NONE),
		LampSpec(id = "hanging_silver_lantern", kind = Kind.STREET, draw = 84L, level = 14, target = Target.DOWN, state = State.NONE),
		LampSpec(id = "silver_wall_lantern", kind = Kind.STREET, draw = 84L, level = 14, target = Target.UP, state = State.NONE),
		LampSpec(id = "iron_torch", kind = Kind.STREET, draw = 84L, level = 14, target = Target.UP, state = State.NONE),
		LampSpec(id = "iron_wall_torch", kind = Kind.STREET, draw = 84L, level = 14, target = Target.UP, state = State.NONE),
		LampSpec(id = "wooden_lantern", kind = Kind.STREET, draw = 84L, level = 14, target = Target.UP, state = State.NONE),
		LampSpec(id = "wooden_wall_lantern", kind = Kind.STREET, draw = 84L, level = 14, target = Target.UP, state = State.NONE),
	)

	/** Every fixture's spec, by block id. */
	val LAMP_BY_ID: Map<String, LampSpec> = LAMPS.associateBy { it.id }

	/**
	 * The walls a switch can hang on. Declared before any block is
	 * registered: a Kotlin object initialises top to bottom, and a value read
	 * by a registration above its own declaration is still null - which is
	 * exactly how the first switch build took survival down at boot.
	 */
	private val WALLS = setOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

	/** Every face a fixture can be mounted on. */
	private val ALL_FACES = setOf(
		BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN,
	)

	/** The node: the battery that pays for every fixture in its reach. */
	val NODE: NovaBlock = registerNode()

	/** The street lights' block ids: furniture pieces that run off a node. */
	val STREET_IDS: Set<String> = LAMPS.filter { it.kind == Kind.STREET }.mapTo(HashSet()) { it.id }

	/** Every fixture block this catalog owns, by its id; the street lights are the furniture's. */
	val LAMP_BLOCKS: Map<String, NovaBlock> = LAMPS.filter { it.kind != Kind.STREET }.associate {
		it.id to when (it.kind) {
			Kind.BLOCK -> registerCube(it)
			Kind.POLE -> registerPole(it)
			else -> register(it)
		}
	}

	/** The light switch: a rocker on a wall that toggles a channel on the nodes around it. */
	val SWITCH: NovaBlock = registerSwitch()

	/** The push button: the same control with a spring-back button and a locator LED. */
	val BUTTON: NovaBlock = registerButton()

	/**
	 * The node fills its block, so it backs onto a barrier: full collision,
	 * and non-occluding, because a display entity inside an occluding block
	 * takes light level zero and renders pitch black.
	 */
	private fun registerNode(): NovaBlock =
		LunaSmp.tileEntity(NODE_ID, ::WirelessNodeTile) {
			stateProperties(GaugeCatalog.ON.scope(setOf(false, true)) { false })

			// every tick: the node pays its fixtures the way a furnace burns
			// fuel, a twentieth of a second's worth at a time, so a meter on
			// it reads a flat load instead of a spike once a second
			tickrate(20)

			entityBacked(stateSelector = { Blocks.BARRIER.defaultBlockState() }) {
				if (getPropertyValueOrNull(GaugeCatalog.ON) == false) {
					getModel("lunasmp:block/wireless_power_node_off")
				} else {
					defaultModel
				}
			}

			behaviors(
				Breakable(hardness = 2.0),
				BlockSounds(SoundGroup.METAL),
				TileEntityDrops,
				TileEntityInteractive,
			)
		}

	/**
	 * The full block: no facing at all, because a cube is the same from every
	 * side and it lights from inside its own block rather than through a face.
	 *
	 * Lit, the backing block IS the light: a reserved copper bulb, the one
	 * vanilla block that is solid, full-cube and carries its own light level,
	 * so one sunk flush into a floor lights the room with no companion light
	 * block hidden anywhere. Dark, it falls back to a barrier - full collision
	 * but non-occluding, so the unlit model is drawn by the room's own light
	 * instead of coming back pitch black the way an unpowered wired panel
	 * does. Occlusion is safe on the lit state because the model is itself a
	 * full cube, covering every seam its backing culls.
	 */
	private fun registerCube(spec: LampSpec): NovaBlock =
		LunaSmp.tileEntity(spec.id, ::WirelessLampTile) {
			stateProperties(GaugeCatalog.ON.scope(setOf(false, true)) { false })

			// once a second: a fixture only asks whether it was paid for
			tickrate(1)

			// always a barrier: full collision, non-occluding, and invisible
			// without hiding any block a player owns. The light comes from a
			// companion light block above, not from the backing.
			entityBacked(stateSelector = { Blocks.BARRIER.defaultBlockState() }) {
				if (getPropertyValueOrNull(GaugeCatalog.ON) == false) {
					getModel("lunasmp:block/${spec.id}_off")
				} else {
					defaultModel
				}
			}

			behaviors(
				Breakable(hardness = 0.5),
				BlockSounds(SoundGroup.GLASS),
				TileEntityDrops,
				TileEntityInteractive,
			)
		}

	/**
	 * The light pole: a bollard standing in the line post - the centred
	 * eight-pixel column its own post and head are drawn around. No facing;
	 * a bollard is the same from every side.
	 */
	private fun registerPole(spec: LampSpec): NovaBlock =
		LunaSmp.tileEntity(spec.id, ::WirelessLampTile) {
			stateProperties(GaugeCatalog.ON.scope(setOf(false, true)) { false })

			// once a second: a fixture only asks whether it was paid for
			tickrate(1)

			entityBacked(stateSelector = { Hitboxes.linePost() }) {
				if (getPropertyValueOrNull(GaugeCatalog.ON) == false) {
					getModel("lunasmp:block/${spec.id}_off")
				} else {
					defaultModel
				}
			}

			behaviors(
				Breakable(hardness = 1.0),
				BlockSounds(SoundGroup.METAL),
				TileEntityDrops,
				TileEntityInteractive,
			)
		}

	/**
	 * The light switch: a wall plate hung on the wall the placer clicked,
	 * the rocker up (ON) or down (OFF) following the channel's state on the
	 * nodes whose reach covers it. The clump is its hitbox, like every flat
	 * fitting here.
	 */
	private fun registerSwitch(): NovaBlock =
		LunaSmp.tileEntity(SWITCH_ID, ::WirelessSwitchTile) {
			stateProperties(
				DefaultBlockStateProperties.FACING.scope(WALLS) { ctx -> facingOnPlace(ctx) },
				GaugeCatalog.ON.scope(setOf(false, true)) { false },
			)

			// once a second the rocker re-reads the channel off the nodes
			tickrate(1)

			entityBacked(stateSelector = {
				Hitboxes.clumpAgainst(getPropertyValueOrNull(DefaultBlockStateProperties.FACING) ?: BlockFace.NORTH)
			}) {
				val model = if (getPropertyValueOrNull(GaugeCatalog.ON) == false) {
					getModel("lunasmp:block/wireless_light_switch_off")
				} else {
					defaultModel
				}

				model.rotated()
			}

			behaviors(
				Breakable(hardness = 0.5),
				BlockSounds(SoundGroup.STONE),
				TileEntityDrops,
				TileEntityInteractive,
			)
		}

	/**
	 * The push button: the rocker's plate with a button that springs back.
	 * ON is the channel's state, shown by the LED; POWERED is the press
	 * itself, held for a few ticks by the tile.
	 */
	private fun registerButton(): NovaBlock =
		LunaSmp.tileEntity(BUTTON_ID, ::WirelessSwitchTile) {
			stateProperties(
				DefaultBlockStateProperties.FACING.scope(WALLS) { ctx -> facingOnPlace(ctx) },
				GaugeCatalog.ON.scope(setOf(false, true)) { false },
				DefaultBlockStateProperties.POWERED.scope(setOf(false, true)) { false },
			)

			// once a second the LED re-reads the channel off the nodes
			tickrate(1)

			entityBacked(stateSelector = {
				Hitboxes.clumpAgainst(getPropertyValueOrNull(DefaultBlockStateProperties.FACING) ?: BlockFace.NORTH)
			}) {
				val on = getPropertyValueOrNull(GaugeCatalog.ON) != false
				val pressed = getPropertyValueOrNull(DefaultBlockStateProperties.POWERED) == true

				val model = when {
					on && !pressed -> defaultModel
					!on && !pressed -> getModel("lunasmp:block/wireless_light_button_off")
					on -> getModel("lunasmp:block/wireless_light_button_pressed")
					else -> getModel("lunasmp:block/wireless_light_button_pressed_off")
				}

				model.rotated()
			}

			behaviors(
				Breakable(hardness = 0.5),
				BlockSounds(SoundGroup.STONE),
				TileEntityDrops,
				TileEntityInteractive,
			)
		}

	/** The wall a flat fitting hangs on: the one clicked, else the one the placer faces. */
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

	/**
	 * A fixture mounts on the face the placer clicked, like the small siren:
	 * up off a floor, out of a wall, hanging under a ceiling. The model is
	 * authored standing off the south wall and pointing north, which
	 * lineRotated() lands on that face; the hitbox follows the face too, a
	 * plate on floors and ceilings and a clump against a wall.
	 */
	private fun register(spec: LampSpec): NovaBlock =
		LunaSmp.tileEntity(spec.id, ::WirelessLampTile) {
			stateProperties(
				DefaultBlockStateProperties.FACING.scope(ALL_FACES) { ctx ->
					ctx[DefaultContextParamTypes.CLICKED_BLOCK_FACE] ?: BlockFace.UP
				},
				GaugeCatalog.ON.scope(setOf(false, true)) { false },
			)

			// once a second: a fixture only asks whether it was paid for
			tickrate(1)

			entityBacked(stateSelector = {
				hitboxOf(spec, getPropertyValueOrNull(DefaultBlockStateProperties.FACING) ?: BlockFace.UP)
			}) {
				val model = if (getPropertyValueOrNull(GaugeCatalog.ON) == false) {
					getModel("lunasmp:block/${spec.id}_off")
				} else {
					defaultModel
				}

				lineRotated(model)
			}

			behaviors(
				Breakable(hardness = 0.5),
				BlockSounds(SoundGroup.GLASS),
				TileEntityDrops,
				TileEntityInteractive,
			)
		}

	/**
	 * The vanilla block a mounted fixture stands in. A bulb on a floor gets
	 * the eight-pixel core its plate and cage fit inside; a panel on a floor
	 * or a ceiling gets the three-pixel plate; anything on a wall gets the
	 * clump hugging that wall. The full block never reaches this - it fills
	 * its block and picks its backing from its lit state instead.
	 */
	private fun hitboxOf(spec: LampSpec, facing: BlockFace): BlockState =
		when (facing) {
			BlockFace.UP -> if (spec.kind == Kind.BULB) Blocks.HEAVY_CORE.defaultBlockState() else Hitboxes.plate(false)
			BlockFace.DOWN -> if (spec.kind == Kind.BULB) Hitboxes.clumpOnCeiling() else Hitboxes.plate(true)
			else -> Hitboxes.clumpAgainst(facing)
		}

	/**
	 * Rotates a north-authored fixture onto its FACING; Nova's own rotated()
	 * walks its vertical ring backwards (see GaugeCatalog), so the two
	 * vertical cases are turned explicitly, model-north onto FACING.
	 */
	private fun BlockModelSelectorScope.lineRotated(model: ModelBuilder): ModelBuilder =
		when (getPropertyValueOrNull(DefaultBlockStateProperties.FACING)) {
			BlockFace.UP -> model.rotateX(90.0)
			BlockFace.DOWN -> model.rotateX(-90.0)
			else -> model.rotated()
		}
}
