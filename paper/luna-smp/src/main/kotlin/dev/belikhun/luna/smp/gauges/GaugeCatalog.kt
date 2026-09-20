package dev.belikhun.luna.smp.gauges

import dev.belikhun.luna.smp.Hitboxes
import dev.belikhun.luna.smp.LunaSmp
import net.kyori.adventure.key.Key
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.bukkit.block.BlockFace
import org.bukkit.block.Container
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.resources.builder.layout.block.BlockSelectorScope
import xyz.xenondevs.nova.resources.builder.layout.block.BlockModelSelectorScope
import xyz.xenondevs.nova.resources.builder.model.ModelBuilder
import xyz.xenondevs.nova.world.block.AbstractNovaBlockBuilder
import xyz.xenondevs.nova.world.block.NovaBlock
import xyz.xenondevs.nova.world.block.behavior.BlockSounds
import xyz.xenondevs.nova.world.block.behavior.Breakable
import xyz.xenondevs.nova.world.block.behavior.TileEntityDrops
import xyz.xenondevs.nova.world.block.behavior.TileEntityInteractive
import xyz.xenondevs.nova.world.block.sound.SoundGroup
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.block.state.property.DefaultScopedBlockStateProperties
import xyz.xenondevs.nova.world.block.state.property.impl.BooleanProperty
import xyz.xenondevs.nova.world.block.tileentity.NetworkedTileEntity
import xyz.xenondevs.nova.world.format.WorldDataManager
import kotlin.math.abs

/**
 * The generated instrument table.
 *
 * GENERATED FILE - do not edit by hand. Change tools/furniture-gen/gauges.ts
 * and re-run the generator; the dial faces, the models and the language files
 * come from the same pass, so editing one of them alone drifts the set.
 *
 * Every number here was used to paint the matching face texture: the pivot,
 * the sweep and the scale radius are the same figures, which is what keeps
 * the live needle and the painted arc agreeing about where full scale is.
 * The dial convention is a clock's: zero at twelve, positive clockwise, as
 * the viewer sees the face.
 */
@Suppress("unused")
object GaugeCatalog {

	/** Whether the instrument is screwed flat onto the block it reads. */
	val ATTACHED = BooleanProperty(Key.key("lunasmp", "attached"))

	/** Whether the isolator conducts. */
	val ON = BooleanProperty(Key.key("lunasmp", "on"))

	/** The walls an instrument can hang on. */
	private val WALLS = setOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

	enum class Metric {
		ENERGY_STORED, ENERGY_FLOW, ENERGY_LOAD, ENERGY_GEN, ENERGY_TOTAL,
		ITEM_FLOW, ITEM_STORED, FLUID_STORED, FLUID_FLOW, MULTI, MULTI_FLOW,
		ITEM_IN, ITEM_OUT, FLUID_IN, FLUID_OUT, ITEM_TOTAL, FLUID_TOTAL,
	}

	/** How the live reading is drawn in front of the painted face. */
	enum class Style {
		NEEDLE, BAR, DIGITAL,
	}

	/** What a switch's handle cuts; the rest keeps bridging through it. */
	enum class Toggles {
		ALL, FLUID, ENERGY,
	}

	/**
	 * The three bodies an instrument comes in. 'faceDepth' is where the dial
	 * plane sits, in blocks from the north edge of a north-facing block;
	 * 'faceSpan' is how much of a block the 64-pixel face covers, and
	 * 'faceCenterY' is where the face's vertical centre sits in the block -
	 * the unit's housing rides low over its feet, so its centre is not 0.5.
	 * The needle and the range plate hang off these three numbers.
	 */
	enum class Form(val faceDepth: Double, val faceSpan: Double, val faceCenterY: Double) {
		PANEL(0.9000, 0.7500, 0.5000),
		UNIT(0.3063, 0.6250, 0.5000),
		BLOCK(0.0250, 1.0000, 0.5000),
	}

	data class Spec(
		val id: String,
		val metric: Metric,
		/** Needle pivot, in face pixels of the 64-pixel dial. */
		val pivotX: Int,
		val pivotY: Int,
		/** Dial angle at zero and at full scale, clockwise from twelve. */
		val startDeg: Double,
		val endDeg: Double,
		/** Blade length, in face pixels; zero on the totaliser. */
		val needleLen: Int,
		/** The item whose model swings on the dial; null on the totaliser. */
		val needle: String? = null,
		/** Where the live range plate (or digit window) sits, in face pixels. */
		val windowX: Int? = null,
		val windowY: Int? = null,
		/** The full-block dashboard form of the same instrument. */
		val dashboard: Boolean = false,
		/** Needle, LED bar or LCD digits. */
		val style: Style = Style.NEEDLE,
	)

	/**
	 * One switch-family device: what it toggles, whether it snaps back on a
	 * timer, and whether a redstone coil drives it instead of a hand.
	 */
	data class SwitchSpec(
		val id: String,
		val toggles: Toggles,
		/** A pulse device holds for this many ticks, then lets go; zero holds. */
		val momentaryTicks: Int,
		/** Driven by redstone power at its own block, not by clicks. */
		val redstone: Boolean,
		/** Which pair of noises the state change makes. */
		val sound: String,
		val defaultOn: Boolean,
	)

	private val SWITCH_SPECS = listOf(
		SwitchSpec(id = "network_switch", toggles = Toggles.ALL, momentaryTicks = 0, redstone = false, sound = "switch", defaultOn = true),
		SwitchSpec(id = "network_lever", toggles = Toggles.ALL, momentaryTicks = 0, redstone = false, sound = "knife", defaultOn = true),
		SwitchSpec(id = "network_button", toggles = Toggles.ALL, momentaryTicks = 60, redstone = false, sound = "button", defaultOn = false),
		SwitchSpec(id = "network_relay", toggles = Toggles.ALL, momentaryTicks = 0, redstone = true, sound = "relay", defaultOn = false),
		SwitchSpec(id = "network_valve", toggles = Toggles.FLUID, momentaryTicks = 0, redstone = false, sound = "wheel", defaultOn = true),
		SwitchSpec(id = "network_breaker", toggles = Toggles.ENERGY, momentaryTicks = 0, redstone = false, sound = "breaker", defaultOn = true),
	)

	/** Every switch-family spec, by block id. */
	val SWITCH_BY_ID: Map<String, SwitchSpec> = SWITCH_SPECS.associateBy { it.id }

	private val SPECS = listOf(
		Spec(id = "gauge_energy_stored", metric = Metric.ENERGY_STORED, pivotX = 32, pivotY = 32, startDeg = -120.0, endDeg = 120.0, needleLen = 21, needle = "gauge_needle_red"),
		Spec(id = "gauge_energy_stored_block", metric = Metric.ENERGY_STORED, pivotX = 32, pivotY = 32, startDeg = -120.0, endDeg = 120.0, needleLen = 21, needle = "gauge_needle_red", dashboard = true),
		Spec(id = "gauge_energy_flow", metric = Metric.ENERGY_FLOW, pivotX = 32, pivotY = 42, startDeg = -40.0, endDeg = 40.0, needleLen = 25, needle = "gauge_needle_black", windowX = 42, windowY = 53),
		Spec(id = "gauge_energy_flow_block", metric = Metric.ENERGY_FLOW, pivotX = 32, pivotY = 42, startDeg = -40.0, endDeg = 40.0, needleLen = 25, needle = "gauge_needle_black", windowX = 42, windowY = 53, dashboard = true),
		Spec(id = "gauge_energy_load", metric = Metric.ENERGY_LOAD, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_black", windowX = 44, windowY = 53),
		Spec(id = "gauge_energy_load_block", metric = Metric.ENERGY_LOAD, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_black", windowX = 44, windowY = 53, dashboard = true),
		Spec(id = "gauge_energy_gen", metric = Metric.ENERGY_GEN, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_black", windowX = 44, windowY = 53),
		Spec(id = "gauge_energy_gen_block", metric = Metric.ENERGY_GEN, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_black", windowX = 44, windowY = 53, dashboard = true),
		Spec(id = "gauge_item_flow", metric = Metric.ITEM_FLOW, pivotX = 32, pivotY = 42, startDeg = -50.0, endDeg = 50.0, needleLen = 25, needle = "gauge_needle_orange", windowX = 42, windowY = 53),
		Spec(id = "gauge_item_flow_block", metric = Metric.ITEM_FLOW, pivotX = 32, pivotY = 42, startDeg = -50.0, endDeg = 50.0, needleLen = 25, needle = "gauge_needle_orange", windowX = 42, windowY = 53, dashboard = true),
		Spec(id = "gauge_item_stored", metric = Metric.ITEM_STORED, pivotX = 32, pivotY = 32, startDeg = -120.0, endDeg = 120.0, needleLen = 21, needle = "gauge_needle_red"),
		Spec(id = "gauge_item_stored_block", metric = Metric.ITEM_STORED, pivotX = 32, pivotY = 32, startDeg = -120.0, endDeg = 120.0, needleLen = 21, needle = "gauge_needle_red", dashboard = true),
		Spec(id = "gauge_fluid_stored", metric = Metric.FLUID_STORED, pivotX = 32, pivotY = 32, startDeg = -135.0, endDeg = 135.0, needleLen = 20, needle = "gauge_needle_red"),
		Spec(id = "gauge_fluid_stored_block", metric = Metric.FLUID_STORED, pivotX = 32, pivotY = 32, startDeg = -135.0, endDeg = 135.0, needleLen = 20, needle = "gauge_needle_red", dashboard = true),
		Spec(id = "gauge_fluid_flow", metric = Metric.FLUID_FLOW, pivotX = 32, pivotY = 42, startDeg = -55.0, endDeg = 55.0, needleLen = 25, needle = "gauge_needle_black", windowX = 42, windowY = 53),
		Spec(id = "gauge_fluid_flow_block", metric = Metric.FLUID_FLOW, pivotX = 32, pivotY = 42, startDeg = -55.0, endDeg = 55.0, needleLen = 25, needle = "gauge_needle_black", windowX = 42, windowY = 53, dashboard = true),
		Spec(id = "gauge_energy_meter", metric = Metric.ENERGY_TOTAL, pivotX = 32, pivotY = 46, startDeg = 0.0, endDeg = 0.0, needleLen = 0, windowX = 32, windowY = 32),
		Spec(id = "gauge_energy_meter_block", metric = Metric.ENERGY_TOTAL, pivotX = 32, pivotY = 46, startDeg = 0.0, endDeg = 0.0, needleLen = 0, windowX = 32, windowY = 32, dashboard = true),
		Spec(id = "gauge_item_meter", metric = Metric.ITEM_TOTAL, pivotX = 32, pivotY = 46, startDeg = 0.0, endDeg = 0.0, needleLen = 0, windowX = 32, windowY = 32),
		Spec(id = "gauge_item_meter_block", metric = Metric.ITEM_TOTAL, pivotX = 32, pivotY = 46, startDeg = 0.0, endDeg = 0.0, needleLen = 0, windowX = 32, windowY = 32, dashboard = true),
		Spec(id = "gauge_fluid_meter", metric = Metric.FLUID_TOTAL, pivotX = 32, pivotY = 46, startDeg = 0.0, endDeg = 0.0, needleLen = 0, windowX = 32, windowY = 32),
		Spec(id = "gauge_fluid_meter_block", metric = Metric.FLUID_TOTAL, pivotX = 32, pivotY = 46, startDeg = 0.0, endDeg = 0.0, needleLen = 0, windowX = 32, windowY = 32, dashboard = true),
		Spec(id = "gauge_multi", metric = Metric.MULTI, pivotX = 32, pivotY = 32, startDeg = -120.0, endDeg = 120.0, needleLen = 21, needle = "gauge_needle_red", windowX = 32, windowY = 54),
		Spec(id = "gauge_multi_block", metric = Metric.MULTI, pivotX = 32, pivotY = 32, startDeg = -120.0, endDeg = 120.0, needleLen = 21, needle = "gauge_needle_red", windowX = 32, windowY = 54, dashboard = true),
		Spec(id = "gauge_multi_square", metric = Metric.MULTI, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_red", windowX = 44, windowY = 53),
		Spec(id = "gauge_multi_square_block", metric = Metric.MULTI, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_red", windowX = 44, windowY = 53, dashboard = true),
		Spec(id = "gauge_multi_flow_square", metric = Metric.MULTI_FLOW, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_orange", windowX = 44, windowY = 53),
		Spec(id = "gauge_multi_flow_square_block", metric = Metric.MULTI_FLOW, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_orange", windowX = 44, windowY = 53, dashboard = true),
		Spec(id = "gauge_energy_load_corner", metric = Metric.ENERGY_LOAD, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 58),
		Spec(id = "gauge_energy_load_corner_block", metric = Metric.ENERGY_LOAD, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 58, dashboard = true),
		Spec(id = "gauge_energy_gen_corner", metric = Metric.ENERGY_GEN, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 58),
		Spec(id = "gauge_energy_gen_corner_block", metric = Metric.ENERGY_GEN, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 58, dashboard = true),
		Spec(id = "gauge_energy_stored_corner", metric = Metric.ENERGY_STORED, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_red"),
		Spec(id = "gauge_energy_stored_corner_block", metric = Metric.ENERGY_STORED, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_red", dashboard = true),
		Spec(id = "gauge_corner_energy_load_bl", metric = Metric.ENERGY_LOAD, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 58),
		Spec(id = "gauge_corner_energy_load_bl_block", metric = Metric.ENERGY_LOAD, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 58, dashboard = true),
		Spec(id = "gauge_corner_energy_load_tr", metric = Metric.ENERGY_LOAD, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 9),
		Spec(id = "gauge_corner_energy_load_tr_block", metric = Metric.ENERGY_LOAD, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 9, dashboard = true),
		Spec(id = "gauge_corner_energy_gen_br", metric = Metric.ENERGY_GEN, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 58),
		Spec(id = "gauge_corner_energy_gen_br_block", metric = Metric.ENERGY_GEN, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 58, dashboard = true),
		Spec(id = "gauge_corner_energy_gen_tr", metric = Metric.ENERGY_GEN, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 9),
		Spec(id = "gauge_corner_energy_gen_tr_block", metric = Metric.ENERGY_GEN, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 9, dashboard = true),
		Spec(id = "gauge_corner_energy_stored_br", metric = Metric.ENERGY_STORED, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_red"),
		Spec(id = "gauge_corner_energy_stored_br_block", metric = Metric.ENERGY_STORED, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_red", dashboard = true),
		Spec(id = "gauge_corner_energy_stored_bl", metric = Metric.ENERGY_STORED, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_red"),
		Spec(id = "gauge_corner_energy_stored_bl_block", metric = Metric.ENERGY_STORED, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_red", dashboard = true),
		Spec(id = "gauge_corner_item_stored_br", metric = Metric.ITEM_STORED, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_red"),
		Spec(id = "gauge_corner_item_stored_br_block", metric = Metric.ITEM_STORED, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_red", dashboard = true),
		Spec(id = "gauge_corner_item_stored_bl", metric = Metric.ITEM_STORED, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_red"),
		Spec(id = "gauge_corner_item_stored_bl_block", metric = Metric.ITEM_STORED, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_red", dashboard = true),
		Spec(id = "gauge_corner_item_stored_tr", metric = Metric.ITEM_STORED, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_red"),
		Spec(id = "gauge_corner_item_stored_tr_block", metric = Metric.ITEM_STORED, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_red", dashboard = true),
		Spec(id = "gauge_corner_fluid_stored_br", metric = Metric.FLUID_STORED, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_red"),
		Spec(id = "gauge_corner_fluid_stored_br_block", metric = Metric.FLUID_STORED, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_red", dashboard = true),
		Spec(id = "gauge_corner_fluid_stored_bl", metric = Metric.FLUID_STORED, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_red"),
		Spec(id = "gauge_corner_fluid_stored_bl_block", metric = Metric.FLUID_STORED, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_red", dashboard = true),
		Spec(id = "gauge_corner_fluid_stored_tr", metric = Metric.FLUID_STORED, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_red"),
		Spec(id = "gauge_corner_fluid_stored_tr_block", metric = Metric.FLUID_STORED, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_red", dashboard = true),
		Spec(id = "gauge_corner_energy_flow_br", metric = Metric.ENERGY_FLOW, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 58),
		Spec(id = "gauge_corner_energy_flow_br_block", metric = Metric.ENERGY_FLOW, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 58, dashboard = true),
		Spec(id = "gauge_corner_energy_flow_bl", metric = Metric.ENERGY_FLOW, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 58),
		Spec(id = "gauge_corner_energy_flow_bl_block", metric = Metric.ENERGY_FLOW, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 58, dashboard = true),
		Spec(id = "gauge_corner_energy_flow_tr", metric = Metric.ENERGY_FLOW, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 9),
		Spec(id = "gauge_corner_energy_flow_tr_block", metric = Metric.ENERGY_FLOW, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 9, dashboard = true),
		Spec(id = "gauge_corner_item_flow_br", metric = Metric.ITEM_FLOW, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_orange", windowX = 32, windowY = 58),
		Spec(id = "gauge_corner_item_flow_br_block", metric = Metric.ITEM_FLOW, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_orange", windowX = 32, windowY = 58, dashboard = true),
		Spec(id = "gauge_corner_item_flow_bl", metric = Metric.ITEM_FLOW, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_orange", windowX = 32, windowY = 58),
		Spec(id = "gauge_corner_item_flow_bl_block", metric = Metric.ITEM_FLOW, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_orange", windowX = 32, windowY = 58, dashboard = true),
		Spec(id = "gauge_corner_item_flow_tr", metric = Metric.ITEM_FLOW, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_orange", windowX = 32, windowY = 9),
		Spec(id = "gauge_corner_item_flow_tr_block", metric = Metric.ITEM_FLOW, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_orange", windowX = 32, windowY = 9, dashboard = true),
		Spec(id = "gauge_corner_fluid_flow_br", metric = Metric.FLUID_FLOW, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 58),
		Spec(id = "gauge_corner_fluid_flow_br_block", metric = Metric.FLUID_FLOW, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 58, dashboard = true),
		Spec(id = "gauge_corner_fluid_flow_bl", metric = Metric.FLUID_FLOW, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 58),
		Spec(id = "gauge_corner_fluid_flow_bl_block", metric = Metric.FLUID_FLOW, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 58, dashboard = true),
		Spec(id = "gauge_corner_fluid_flow_tr", metric = Metric.FLUID_FLOW, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 9),
		Spec(id = "gauge_corner_fluid_flow_tr_block", metric = Metric.FLUID_FLOW, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 9, dashboard = true),
		Spec(id = "gauge_corner_multi_br", metric = Metric.MULTI, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_red"),
		Spec(id = "gauge_corner_multi_br_block", metric = Metric.MULTI, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_red", dashboard = true),
		Spec(id = "gauge_corner_multi_bl", metric = Metric.MULTI, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_red"),
		Spec(id = "gauge_corner_multi_bl_block", metric = Metric.MULTI, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_red", dashboard = true),
		Spec(id = "gauge_corner_multi_tr", metric = Metric.MULTI, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_red"),
		Spec(id = "gauge_corner_multi_tr_block", metric = Metric.MULTI, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_red", dashboard = true),
		Spec(id = "gauge_corner_multi_flow_br", metric = Metric.MULTI_FLOW, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_orange", windowX = 32, windowY = 58),
		Spec(id = "gauge_corner_multi_flow_br_block", metric = Metric.MULTI_FLOW, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_orange", windowX = 32, windowY = 58, dashboard = true),
		Spec(id = "gauge_corner_multi_flow_bl", metric = Metric.MULTI_FLOW, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_orange", windowX = 32, windowY = 58),
		Spec(id = "gauge_corner_multi_flow_bl_block", metric = Metric.MULTI_FLOW, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_orange", windowX = 32, windowY = 58, dashboard = true),
		Spec(id = "gauge_corner_multi_flow_tr", metric = Metric.MULTI_FLOW, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_orange", windowX = 32, windowY = 9),
		Spec(id = "gauge_corner_multi_flow_tr_block", metric = Metric.MULTI_FLOW, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_orange", windowX = 32, windowY = 9, dashboard = true),
		Spec(id = "gauge_energy_stored_square", metric = Metric.ENERGY_STORED, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_red"),
		Spec(id = "gauge_energy_stored_square_block", metric = Metric.ENERGY_STORED, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_red", dashboard = true),
		Spec(id = "gauge_item_stored_square", metric = Metric.ITEM_STORED, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_red"),
		Spec(id = "gauge_item_stored_square_block", metric = Metric.ITEM_STORED, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_red", dashboard = true),
		Spec(id = "gauge_fluid_stored_square", metric = Metric.FLUID_STORED, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_red"),
		Spec(id = "gauge_fluid_stored_square_block", metric = Metric.FLUID_STORED, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_red", dashboard = true),
		Spec(id = "gauge_energy_flow_square", metric = Metric.ENERGY_FLOW, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_black", windowX = 44, windowY = 53),
		Spec(id = "gauge_energy_flow_square_block", metric = Metric.ENERGY_FLOW, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_black", windowX = 44, windowY = 53, dashboard = true),
		Spec(id = "gauge_item_flow_square", metric = Metric.ITEM_FLOW, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_orange", windowX = 44, windowY = 53),
		Spec(id = "gauge_item_flow_square_block", metric = Metric.ITEM_FLOW, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_orange", windowX = 44, windowY = 53, dashboard = true),
		Spec(id = "gauge_fluid_flow_square", metric = Metric.FLUID_FLOW, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_black", windowX = 44, windowY = 53),
		Spec(id = "gauge_fluid_flow_square_block", metric = Metric.FLUID_FLOW, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_black", windowX = 44, windowY = 53, dashboard = true),
		Spec(id = "gauge_item_in", metric = Metric.ITEM_IN, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_orange", windowX = 44, windowY = 53),
		Spec(id = "gauge_item_in_block", metric = Metric.ITEM_IN, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_orange", windowX = 44, windowY = 53, dashboard = true),
		Spec(id = "gauge_corner_item_in_br", metric = Metric.ITEM_IN, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_orange", windowX = 32, windowY = 58),
		Spec(id = "gauge_corner_item_in_br_block", metric = Metric.ITEM_IN, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_orange", windowX = 32, windowY = 58, dashboard = true),
		Spec(id = "gauge_item_in_corner", metric = Metric.ITEM_IN, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_orange", windowX = 32, windowY = 58),
		Spec(id = "gauge_item_in_corner_block", metric = Metric.ITEM_IN, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_orange", windowX = 32, windowY = 58, dashboard = true),
		Spec(id = "gauge_corner_item_in_tr", metric = Metric.ITEM_IN, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_orange", windowX = 32, windowY = 9),
		Spec(id = "gauge_corner_item_in_tr_block", metric = Metric.ITEM_IN, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_orange", windowX = 32, windowY = 9, dashboard = true),
		Spec(id = "gauge_item_out", metric = Metric.ITEM_OUT, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_orange", windowX = 44, windowY = 53),
		Spec(id = "gauge_item_out_block", metric = Metric.ITEM_OUT, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_orange", windowX = 44, windowY = 53, dashboard = true),
		Spec(id = "gauge_item_out_corner", metric = Metric.ITEM_OUT, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_orange", windowX = 32, windowY = 58),
		Spec(id = "gauge_item_out_corner_block", metric = Metric.ITEM_OUT, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_orange", windowX = 32, windowY = 58, dashboard = true),
		Spec(id = "gauge_corner_item_out_bl", metric = Metric.ITEM_OUT, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_orange", windowX = 32, windowY = 58),
		Spec(id = "gauge_corner_item_out_bl_block", metric = Metric.ITEM_OUT, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_orange", windowX = 32, windowY = 58, dashboard = true),
		Spec(id = "gauge_corner_item_out_tr", metric = Metric.ITEM_OUT, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_orange", windowX = 32, windowY = 9),
		Spec(id = "gauge_corner_item_out_tr_block", metric = Metric.ITEM_OUT, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_orange", windowX = 32, windowY = 9, dashboard = true),
		Spec(id = "gauge_fluid_in", metric = Metric.FLUID_IN, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_black", windowX = 44, windowY = 53),
		Spec(id = "gauge_fluid_in_block", metric = Metric.FLUID_IN, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_black", windowX = 44, windowY = 53, dashboard = true),
		Spec(id = "gauge_corner_fluid_in_br", metric = Metric.FLUID_IN, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 58),
		Spec(id = "gauge_corner_fluid_in_br_block", metric = Metric.FLUID_IN, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 58, dashboard = true),
		Spec(id = "gauge_fluid_in_corner", metric = Metric.FLUID_IN, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 58),
		Spec(id = "gauge_fluid_in_corner_block", metric = Metric.FLUID_IN, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 58, dashboard = true),
		Spec(id = "gauge_corner_fluid_in_tr", metric = Metric.FLUID_IN, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 9),
		Spec(id = "gauge_corner_fluid_in_tr_block", metric = Metric.FLUID_IN, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 9, dashboard = true),
		Spec(id = "gauge_fluid_out", metric = Metric.FLUID_OUT, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_black", windowX = 44, windowY = 53),
		Spec(id = "gauge_fluid_out_block", metric = Metric.FLUID_OUT, pivotX = 32, pivotY = 50, startDeg = -45.0, endDeg = 45.0, needleLen = 33, needle = "gauge_needle_black", windowX = 44, windowY = 53, dashboard = true),
		Spec(id = "gauge_fluid_out_corner", metric = Metric.FLUID_OUT, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 58),
		Spec(id = "gauge_fluid_out_corner_block", metric = Metric.FLUID_OUT, pivotX = 52, pivotY = 52, startDeg = -90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 58, dashboard = true),
		Spec(id = "gauge_corner_fluid_out_bl", metric = Metric.FLUID_OUT, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 58),
		Spec(id = "gauge_corner_fluid_out_bl_block", metric = Metric.FLUID_OUT, pivotX = 12, pivotY = 52, startDeg = 90.0, endDeg = 0.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 58, dashboard = true),
		Spec(id = "gauge_corner_fluid_out_tr", metric = Metric.FLUID_OUT, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 9),
		Spec(id = "gauge_corner_fluid_out_tr_block", metric = Metric.FLUID_OUT, pivotX = 52, pivotY = 12, startDeg = -90.0, endDeg = -180.0, needleLen = 33, needle = "gauge_needle_corner_black", windowX = 32, windowY = 9, dashboard = true),
		Spec(id = "gauge_energy_bar", metric = Metric.ENERGY_STORED, pivotX = 32, pivotY = 54, startDeg = 0.0, endDeg = 0.0, needleLen = 44, style = Style.BAR),
		Spec(id = "gauge_energy_bar_block", metric = Metric.ENERGY_STORED, pivotX = 32, pivotY = 54, startDeg = 0.0, endDeg = 0.0, needleLen = 44, style = Style.BAR, dashboard = true),
		Spec(id = "gauge_fluid_bar", metric = Metric.FLUID_STORED, pivotX = 32, pivotY = 54, startDeg = 0.0, endDeg = 0.0, needleLen = 44, style = Style.BAR),
		Spec(id = "gauge_fluid_bar_block", metric = Metric.FLUID_STORED, pivotX = 32, pivotY = 54, startDeg = 0.0, endDeg = 0.0, needleLen = 44, style = Style.BAR, dashboard = true),
		Spec(id = "gauge_digital", metric = Metric.MULTI, pivotX = 32, pivotY = 31, startDeg = 0.0, endDeg = 0.0, needleLen = 0, windowX = 32, windowY = 31, style = Style.DIGITAL),
		Spec(id = "gauge_digital_flow", metric = Metric.MULTI_FLOW, pivotX = 32, pivotY = 31, startDeg = 0.0, endDeg = 0.0, needleLen = 0, windowX = 32, windowY = 31, style = Style.DIGITAL),
		Spec(id = "gauge_multi_flow", metric = Metric.MULTI_FLOW, pivotX = 32, pivotY = 42, startDeg = -50.0, endDeg = 50.0, needleLen = 25, needle = "gauge_needle_orange", windowX = 42, windowY = 53),
		Spec(id = "gauge_multi_flow_block", metric = Metric.MULTI_FLOW, pivotX = 32, pivotY = 42, startDeg = -50.0, endDeg = 50.0, needleLen = 25, needle = "gauge_needle_orange", windowX = 42, windowY = 53, dashboard = true),
	)

	/** Every instrument's spec, by block id. */
	val BY_ID: Map<String, Spec> = SPECS.associateBy { it.id }

	/** Every instrument block, by its id. */
	val BLOCKS: Map<String, NovaBlock> = SPECS.associate { it.id to register(it) }

	/** The switch family: every device that cuts or restores a line. */
	val SWITCH_BLOCKS: Map<String, NovaBlock> = SWITCH_SPECS.associate { it.id to registerSwitch(it) }

	/** The one-way bridge: two separated networks, and a flow between them. */
	val DIODE: NovaBlock = registerDiode()

	/** The same bridge filling its block, for a machinery wall. */
	val DIODE_BLOCK: NovaBlock = registerDiodeBlock()

	/** The activity indicator: the port lights of a network. */
	val LED: NovaBlock = registerLed()

	/** The indicator as a full block: a status wall's building brick. */
	val LED_BLOCK: NovaBlock = registerLedBlock()

	/** The alarm beacon: sweeps and wails while its condition holds. */
	val ALARM: NovaBlock = registerAlarm()

	/** The beacon on a full-block base, its dome on the face that was clicked. */
	val ALARM_BLOCK: NovaBlock = registerAlarmBlock()

	/** The powered light panel: a lit cube that is its own wiring. */
	val LIGHT_PANEL: NovaBlock = registerLightPanel()

	private fun register(spec: Spec): NovaBlock =
		LunaSmp.tileEntity(spec.id, ::GaugeTile) {
			stateProperties(DefaultBlockStateProperties.FACING.scope(WALLS) { ctx -> facingOnPlace(ctx) })

			if (!spec.dashboard) {
				stateProperties(ATTACHED.scope(setOf(false, true)) { ctx -> attachedOnPlace(ctx) })
			}

			// the needle moves once a second: any faster is packets for a
			// twitch nobody can read, any slower reads as a stuck meter
			tickrate(1)

			entityBacked(stateSelector = { hitboxOf(spec, this) }) {
				val model = if (spec.dashboard || getPropertyValueOrNull(ATTACHED) == true) {
					defaultModel
				} else {
					getModel("lunasmp:block/${spec.id}_unit")
				}

				model.rotated()
			}

			behaviors(
				Breakable(hardness = 1.0),
				BlockSounds(SoundGroup.STONE),
				TileEntityDrops,
				TileEntityInteractive,
			)
		}

	/**
	 * Rotates a north-authored line device onto its FACING. Nova's own
	 * rotated() walks its vertical ring backwards (FACING = UP applies
	 * rotateX(-90), which lands model-north on DOWN), so a diode placed
	 * while looking up wore its arrows against the actual flow; the two
	 * vertical cases are rotated explicitly, model-north onto FACING.
	 */
	private fun BlockModelSelectorScope.lineRotated(model: ModelBuilder): ModelBuilder =
		when (getPropertyValueOrNull(DefaultBlockStateProperties.FACING)) {
			BlockFace.UP -> model.rotateX(90.0)
			BlockFace.DOWN -> model.rotateX(-90.0)
			else -> model.rotated()
		}

	private fun registerSwitch(spec: SwitchSpec): NovaBlock =
		LunaSmp.tileEntity(spec.id, ::NetworkSwitchTile) {
			// cartesian, not horizontal: aimed steeply up or down (the piston
			// gesture) the device stands vertical and its line runs up-down,
			// so a riser can carry a switch. Widening the scope is safe for
			// placed blocks: Nova's block state id map is persistent and only
			// appends ids for the new permutations.
			stateProperties(
				DefaultScopedBlockStateProperties.FACING_CARTESIAN,
				ON.scope(setOf(false, true)) { spec.defaultOn },
			)

			// once a second the switch re-checks which cable line it sits in:
			// bridges only link to bridges with an equal type id, so it has to
			// keep impersonating its neighbours (see NetworkSwitchTile). The
			// timed and redstone-driven ones tick every tick, because a pulse
			// that lets go on whole seconds and a contactor that answers its
			// coil a second late both read as broken.
			tickrate(if (spec.momentaryTicks > 0 || spec.redstone) 20 else 1)

			// the line devices are authored around the cable at block centre,
			// so the backing is the centred wall post, not a floor-hugging
			// core: an 8px column through the middle, whatever the facing
			entityBacked(stateSelector = { Hitboxes.linePost() }) {
				val model = if (getPropertyValueOrNull(ON) == false) {
					getModel("lunasmp:block/${spec.id}_off")
				} else {
					defaultModel
				}

				lineRotated(model)
			}

			behaviors(
				Breakable(hardness = 1.5),
				BlockSounds(SoundGroup.STONE),
				TileEntityDrops,
				TileEntityInteractive,
			)
		}

	private fun registerLed(): NovaBlock =
		LunaSmp.tileEntity("network_led", ::LedTile) {
			stateProperties(DefaultBlockStateProperties.FACING.scope(WALLS) { ctx -> facingOnPlace(ctx) })

			// every tick, for the blink; the actual network read is 1/s
			tickrate(20)

			entityBacked(stateSelector = {
				val facing = getPropertyValueOrNull(DefaultBlockStateProperties.FACING) ?: BlockFace.NORTH

				Hitboxes.clumpAgainst(facing)
			}) {
				defaultModel.rotated()
			}

			behaviors(
				Breakable(hardness = 0.8),
				BlockSounds(SoundGroup.STONE),
				TileEntityDrops,
				TileEntityInteractive,
			)
		}

	private fun registerAlarm(): NovaBlock =
		LunaSmp.tileEntity("alarm_light", ::AlarmTile) {
			// mounted on the face the placer clicked, like the block form's
			// dome: up on a floor, out of a wall, hanging under a ceiling
			stateProperties(
				DefaultBlockStateProperties.FACING.scope(
					setOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN),
				) { ctx -> ctx[DefaultContextParamTypes.CLICKED_BLOCK_FACE] ?: BlockFace.UP },
			)

			// every tick: the beam sweep and the horn are timed in ticks
			tickrate(20)

			entityBacked(stateSelector = {
				when (getPropertyValueOrNull(DefaultBlockStateProperties.FACING) ?: BlockFace.UP) {
					BlockFace.UP -> Blocks.HEAVY_CORE.defaultBlockState()
					BlockFace.DOWN -> Hitboxes.clumpOnCeiling()
					else -> Hitboxes.clumpAgainst(
						getPropertyValueOrNull(DefaultBlockStateProperties.FACING) ?: BlockFace.NORTH,
					)
				}
			}) {
				lineRotated(defaultModel)
			}

			behaviors(
				Breakable(hardness = 1.0),
				BlockSounds(SoundGroup.STONE),
				TileEntityDrops,
				TileEntityInteractive,
			)
		}

	private fun registerLedBlock(): NovaBlock =
		LunaSmp.tileEntity("network_led_block", ::LedTile) {
			stateProperties(DefaultScopedBlockStateProperties.FACING_HORIZONTAL)

			// every tick, for the blink; the actual network read is 1/s
			tickrate(20)

			// barrier, like the gauge dashboards: an OCCLUDING backing starves
			// the display entity of light (level 0 inside a solid block) and
			// the whole cube renders pitch black - the first deploy's bug
			entityBacked(stateSelector = { Blocks.BARRIER.defaultBlockState() }) {
				defaultModel.rotated()
			}

			behaviors(
				Breakable(hardness = 1.0),
				BlockSounds(SoundGroup.STONE),
				TileEntityDrops,
				TileEntityInteractive,
			)
		}

	private fun registerAlarmBlock(): NovaBlock =
		LunaSmp.tileEntity("alarm_light_block", ::AlarmTile) {
			// the dome rides the face the placer clicked: placing on a floor
			// points it up, under a ceiling down, against a wall outward
			stateProperties(
				DefaultBlockStateProperties.FACING.scope(
					setOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN),
				) { ctx -> ctx[DefaultContextParamTypes.CLICKED_BLOCK_FACE] ?: BlockFace.UP },
				ON.scope(setOf(false, true)) { false },
			)

			// every tick: the beam sweep and the horn are timed in ticks
			tickrate(20)

			// always a barrier, so room light reaches the display (a solid
			// backing renders it black); alarming, the floodlight is a
			// companion light block thrown the way the dome points
			entityBacked(stateSelector = { Blocks.BARRIER.defaultBlockState() }) {
				lineRotated(defaultModel)
			}

			behaviors(
				Breakable(hardness = 1.0),
				BlockSounds(SoundGroup.STONE),
				TileEntityDrops,
				TileEntityInteractive,
			)
		}

	private fun registerLightPanel(): NovaBlock =
		LunaSmp.tileEntity("light_panel", ::LightPanelTile) {
			stateProperties(ON.scope(setOf(false, true)) { false })

			// once a second: the panel draws its joules and settles lit or dark
			tickrate(1)

			// a barrier, like every fixture here: the light is a companion
			// light block under the panel, so no vanilla block is hidden
			entityBacked(stateSelector = { Blocks.BARRIER.defaultBlockState() }) {
				if (getPropertyValueOrNull(ON) == false) {
					getModel("lunasmp:block/light_panel_off")
				} else {
					defaultModel
				}
			}

			behaviors(
				Breakable(hardness = 0.5),
				BlockSounds(SoundGroup.GLASS),
				TileEntityDrops,
			)
		}

	private fun registerDiode(): NovaBlock =
		LunaSmp.tileEntity("network_diode", ::NetworkDiodeTile) {
			// cartesian like the switches: placed while aiming steeply down,
			// the inlet faces UP (towards the placer) and the flow runs down
			stateProperties(DefaultScopedBlockStateProperties.FACING_CARTESIAN)

			// every tick: the bridge sums what actually crossed it off its
			// own holders, and once-a-second totals would alias burst traffic
			tickrate(20)

			entityBacked(stateSelector = { Hitboxes.linePost() }) {
				lineRotated(defaultModel)
			}

			behaviors(
				Breakable(hardness = 1.5),
				BlockSounds(SoundGroup.STONE),
				TileEntityDrops,
				TileEntityInteractive,
			)
		}

	/**
	 * The one-way bridge as a full block: the same tile and the same buffers,
	 * on a body that fills its space.
	 *
	 * The backing is a BARRIER, as every full-block device here is. A display
	 * entity takes its light from its own position, and inside an occluding
	 * block that is level zero - a full-block device backed onto anything
	 * solid renders as a pitch-black cube with perfectly good textures.
	 */
	private fun registerDiodeBlock(): NovaBlock =
		LunaSmp.tileEntity("network_diode_block", ::NetworkDiodeTile) {
			stateProperties(DefaultScopedBlockStateProperties.FACING_CARTESIAN)
			tickrate(20)

			entityBacked(stateSelector = { Blocks.BARRIER.defaultBlockState() }) {
				lineRotated(defaultModel)
			}

			behaviors(
				Breakable(hardness = 1.5),
				BlockSounds(SoundGroup.STONE),
				TileEntityDrops,
				TileEntityInteractive,
			)
		}

	/**
	 * Which way an instrument looks when placed: along the wall face that was
	 * clicked, or back at whoever set it down anywhere else.
	 */
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
	 * Flat on the host only when the host is something worth reading: a
	 * networked machine, tank or cell, or a plain container. Anywhere else
	 * the instrument stands in its own housing.
	 */
	private fun attachedOnPlace(ctx: Context<BlockPlace>): Boolean {
		val pos = ctx[DefaultContextParamTypes.BLOCK_POS] ?: return false
		val clicked = ctx[DefaultContextParamTypes.CLICKED_BLOCK_FACE] ?: return false

		if (clicked !in WALLS) {
			return false
		}

		val host = pos.advance(clicked.oppositeFace, 1)

		if (WorldDataManager.getTileEntity(host) is NetworkedTileEntity) {
			return true
		}

		return host.block.state is Container
	}

	private fun hitboxOf(spec: Spec, scope: BlockSelectorScope): BlockState {
		if (spec.dashboard) {
			return Blocks.BARRIER.defaultBlockState()
		}

		if (scope.getPropertyValueOrNull(ATTACHED) == true) {
			val facing = scope.getPropertyValueOrNull(DefaultBlockStateProperties.FACING) ?: BlockFace.NORTH

			return Hitboxes.clumpAgainst(facing)
		}

		return Blocks.STRUCTURE_VOID.defaultBlockState()
	}
}
