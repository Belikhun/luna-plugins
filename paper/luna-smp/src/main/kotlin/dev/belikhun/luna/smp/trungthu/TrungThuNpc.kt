package dev.belikhun.luna.smp.trungthu

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitFun
import xyz.xenondevs.nova.initialize.InitStage
import java.util.logging.Logger

/**
 * The village's people. A villager (or any entity) carrying a scoreboard tag
 * `tt_shop:<category>` is a stall keeper: a right-click opens that shop
 * category instead of the trade screen. One tagged `tt_say:<line>` answers
 * with a line of chat, underscores standing in for spaces.
 */
@Init(stage = InitStage.POST_WORLD)
object TrungThuNpc : Listener {

	private val log: Logger = Logger.getLogger("LunaSmp")

	@InitFun
	private fun register() {
		val plugin = Bukkit.getPluginManager().getPlugin("LunaSmp")

		if (plugin == null || !plugin.isEnabled) {
			log.warning("[TrungThu npc] LunaSmp is not enabled, villagers stay plain")

			return
		}

		Bukkit.getPluginManager().registerEvents(this, plugin)
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	private fun onInteract(event: PlayerInteractEntityEvent) {
		if (event.hand != EquipmentSlot.HAND) {
			return
		}

		val player: Player = event.player
		val tags = event.rightClicked.scoreboardTags

		tags.firstOrNull { it.startsWith("tt_shop:") }?.let { tag ->
			event.isCancelled = true
			player.performCommand("shop category ${tag.removePrefix("tt_shop:")}")

			return
		}

		tags.firstOrNull { it.startsWith("tt_say:") }?.let { tag ->
			event.isCancelled = true
			val name = event.rightClicked.customName() ?: event.rightClicked.name()
			player.sendMessage(
				net.kyori.adventure.text.Component.text()
					.append(name)
					.append(net.kyori.adventure.text.Component.text(": " + tag.removePrefix("tt_say:").replace('_', ' '))),
			)
		}
	}
}
