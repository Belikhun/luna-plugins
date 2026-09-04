package dev.belikhun.luna.smp.weapons

import org.bukkit.Bukkit
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.inventory.EquipmentSlot
import xyz.xenondevs.nova.util.item.novaItem
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitFun
import xyz.xenondevs.nova.initialize.InitStage

/**
 * The sound a Nene weapon makes when it lands.
 *
 * The recording came as a pack that replaces vanilla's own sweep sound for
 * everybody; this plays it only for the collection instead, so an iron sword
 * still sounds like an iron sword.
 */
@Init(stage = InitStage.POST_WORLD)
object NeneSwing : Listener {

	/** The sound event our pack defines, played at the swinger, not the target. */
	private const val SWING = "lunasmp:weapon.nene.swing"

	@InitFun
	private fun init() {
		val owner = Bukkit.getPluginManager().getPlugin("LunaSmp")

		if (owner == null || !owner.isEnabled) {
			return
		}

		Bukkit.getPluginManager().registerEvents(this, owner)
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	private fun onHit(event: EntityDamageByEntityEvent) {
		val player = event.damager as? Player ?: return
		val held = player.inventory.getItem(EquipmentSlot.HAND)

		if (held.type.isAir) {
			return
		}

		// the collection is a set of nova items, so what marks one is simply
		// which item it is
		val id = held.novaItem?.id ?: return

		if (id.namespace() != "lunasmp" || id.value() !in NeneWeapons.ITEMS) {
			return
		}

		// everybody nearby hears it, since the point of it is the theatre
		player.world.playSound(player.location, SWING, SoundCategory.PLAYERS, 1.2f, 1f)
	}
}
