package dev.belikhun.luna.smp.gauges

import dev.belikhun.luna.smp.LunaSmp
import org.bukkit.block.BlockFace
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.world.item.NovaItem

/**
 * The items that place the instruments, and the needles.
 *
 * This object is also what pulls [GaugeCatalog] in - a catalog nobody touches
 * never registers its blocks, exactly as with the furniture, the flora and
 * the signs.
 *
 * The needles are items only because an item display can only wear a model
 * that some item owns: they place nothing, they stack to one, and the only
 * copies anyone sees are the ones swinging on a dial.
 */
@Init(stage = InitStage.PRE_PACK)
object GaugeItems {

	/** The six directions a coupling can point. */
	private val JOINT_FACES = listOf(
		BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH,
		BlockFace.WEST, BlockFace.UP, BlockFace.DOWN,
	)

	/** The item that places each instrument, keyed by block id. */
	val BLOCK_ITEMS: Map<String, NovaItem> = GaugeCatalog.BLOCKS.mapValues { (_, block) ->
		LunaSmp.item(block) {}
	}

	/** The items that place the switch family, keyed by block id. */
	val SWITCH_ITEMS: Map<String, NovaItem> = GaugeCatalog.SWITCH_BLOCKS.mapValues { (_, block) ->
		LunaSmp.item(block) {}
	}

	/** The item that places the one-way bridge. */
	val DIODE_ITEM: NovaItem = LunaSmp.item(GaugeCatalog.DIODE) {}

	/** And the same bridge as a full block. */
	val DIODE_CUBE_ITEM: NovaItem = LunaSmp.item(GaugeCatalog.DIODE_BLOCK) {}

	/** The items that place the indicator, the alarm and the light panel. */
	val LED_BLOCK_ITEM: NovaItem = LunaSmp.item(GaugeCatalog.LED) {}
	val LED_CUBE_ITEM: NovaItem = LunaSmp.item(GaugeCatalog.LED_BLOCK) {}
	val ALARM_ITEM: NovaItem = LunaSmp.item(GaugeCatalog.ALARM) {}
	val ALARM_BLOCK_ITEM: NovaItem = LunaSmp.item(GaugeCatalog.ALARM_BLOCK) {}
	val LIGHT_PANEL_ITEM: NovaItem = LunaSmp.item(GaugeCatalog.LIGHT_PANEL) {}

	/**
	 * The wire couplings a housing grows toward each connected cable, one
	 * prebaked item per direction: the generator bakes the geometry, so no
	 * runtime rotation (and no quaternion convention) is involved.
	 */
	val JOINTS: Map<BlockFace, NovaItem> = JOINT_FACES.associateWith { face ->
		LunaSmp.item("gauge_joint_${face.name.lowercase()}") {
			maxStackSize(1)
		}
	}

	/**
	 * The wall clock's live hands. They live here rather than in the
	 * generated furniture items because this object is where every hidden
	 * display-entity item is registered by hand - needles, bars, couplings.
	 */
	val CLOCK_HANDS: Map<String, NovaItem> = listOf("clock_hand_hour", "clock_hand_minute")
		.associateWith { id ->
			LunaSmp.item(id) {
				maxStackSize(1)
			}
		}

	/** The isolator's dead-line sleeves, bridging the gap while it is open. */
	val JOINTS_LONG: Map<BlockFace, NovaItem> = JOINT_FACES.associateWith { face ->
		LunaSmp.item("gauge_joint_long_${face.name.lowercase()}") {
			maxStackSize(1)
		}
	}

	/** The needle models, one item per sprite, shared by every dial. */
	val NEEDLES: Map<String, NovaItem> = listOf(
		"gauge_needle_red",
		"gauge_needle_black",
		"gauge_needle_orange",
		"gauge_needle_corner_red",
		"gauge_needle_corner_black",
		"gauge_needle_blue",
		"gauge_needle_corner_blue",
		"gauge_needle_corner_orange",
	).associateWith { id ->
		LunaSmp.item(id) {
			maxStackSize(1)
		}
	}

	/** The LED bar models the level columns climb, one item per colour. */
	val BARS: Map<String, NovaItem> = listOf(
		"gauge_bar_amber",
		"gauge_bar_blue",
		"gauge_bar_red",
	).associateWith { id ->
		LunaSmp.item(id) {
			maxStackSize(1)
		}
	}

	/** The indicator's LED chips, one item per colour. */
	val LED_CHIPS: Map<String, NovaItem> = listOf(
		"gauge_led_amber",
		"gauge_led_green",
		"gauge_led_blue",
	).associateWith { id ->
		LunaSmp.item(id) {
			maxStackSize(1)
		}
	}

	/** The alarm's sweeping beam. */
	val ALARM_BEAM: NovaItem = LunaSmp.item("alarm_beam") {
		maxStackSize(1)
	}
}
