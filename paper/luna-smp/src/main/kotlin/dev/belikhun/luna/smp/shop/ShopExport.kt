package dev.belikhun.luna.smp.shop

import dev.belikhun.luna.smp.flora.FloraItems
import dev.belikhun.luna.smp.furniture.FurnitureItems
import dev.belikhun.luna.smp.gauges.GaugeItems
import dev.belikhun.luna.smp.signs.SignItems
import dev.belikhun.luna.smp.weapons.NeneSpear
import dev.belikhun.luna.smp.weapons.NeneWeapons
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitFun
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.world.item.NovaItem
import java.io.File
import java.util.Base64
import java.util.logging.Logger

/**
 * Dumps every registered item as LunaShop needs it, so shop entries never have
 * to be built by hand.
 *
 * LunaShop stores an item as base64(serializeAsBytes()) - and serializeAsBytes
 * is ALREADY gzip-compressed NBT, so nothing here may compress again (a second
 * gzip layer broke /shop with "Invalid tag id: 31", the gzip magic byte read
 * as an NBT tag). Only the running server can produce those bytes for a Nova
 * item: the stack's components are Nova's own. Registering hundreds of pieces through the
 * in-game commands is not a thing anybody should do, so this writes
 * `plugins/LunaSmp/shop-export.json` - id, kind and encoded stack per item -
 * and an offline script turns that into priced `items.yml` entries.
 *
 * Gated on a marker file so a normal boot does nothing: touch
 * `plugins/LunaSmp/.shopexport` and restart to export. The marker is consumed.
 */
@Init(stage = InitStage.POST_WORLD)
object ShopExport {

	@InitFun
	private fun run() {
		val marker = File("plugins/LunaSmp/.shopexport")

		if (!marker.exists()) {
			return
		}

		marker.delete()

		// an addon init that throws sinks the whole server: this is a dev tool
		// and never worth that
		try {
			export()
		} catch (error: Exception) {
			Logger.getLogger("LunaSmp").warning("shop export failed: $error")
		}
	}

	private fun export() {
		val entries = StringBuilder("[\n")
		var first = true

		val add = { kind: String, id: String, item: NovaItem ->
			if (!first) {
				entries.append(",\n")
			}

			first = false
			entries.append("\t{\"kind\": \"$kind\", \"id\": \"$id\", \"data\": \"${encode(item)}\"}")
		}

		for ((id, item) in FurnitureItems.BLOCK_ITEMS) {
			add("furniture", id, item)
		}

		for ((id, item) in FloraItems.BLOCK_ITEMS) {
			add("flora", id, item)
		}

		for ((id, item) in SignItems.BLOCK_ITEMS) {
			add("sign", id, item)
		}

		for ((id, item) in GaugeItems.BLOCK_ITEMS) {
			add("gauge", id, item)
		}

		for ((id, item) in GaugeItems.SWITCH_ITEMS) {
			add("gauge", id, item)
		}

		add("gauge", "network_diode", GaugeItems.DIODE_ITEM)
		add("gauge", "network_led", GaugeItems.LED_BLOCK_ITEM)
		add("gauge", "network_led_block", GaugeItems.LED_CUBE_ITEM)
		add("gauge", "alarm_light", GaugeItems.ALARM_ITEM)
		add("gauge", "alarm_light_block", GaugeItems.ALARM_BLOCK_ITEM)
		add("gauge", "light_panel", GaugeItems.LIGHT_PANEL_ITEM)

		for ((id, item) in FurnitureItems.CROWNS) {
			add("crown", id, item)
		}

		add("crown", "vong_hoa", FurnitureItems.VONG_HOA)

		// the shop sells them enchanted, which the registry item is not: what
		// `/nova give` hands out is the plain weapon
		val weapons = NeneWeapons.ITEMS.keys.associateWith { NeneWeapons.stackOf(it) } +
			mapOf(NeneSpear.ID to NeneSpear.stack())

		for ((id, stack) in weapons) {
			if (!first) {
				entries.append(",\n")
			}

			first = false

			val encoded = Base64.getEncoder().encodeToString(stack.serializeAsBytes())
			entries.append("\t{\"kind\": \"weapon\", \"id\": \"$id\", \"data\": \"$encoded\"}")
		}

		entries.append("\n]\n")

		val out = File("plugins/LunaSmp/shop-export.json")
		out.parentFile.mkdirs()
		out.writeText(entries.toString())
		Logger.getLogger("LunaSmp").info("shop export written: ${out.path}")
	}

	private fun encode(item: NovaItem): String {
		return Base64.getEncoder().encodeToString(item.createItemStack(1).serializeAsBytes())
	}
}
