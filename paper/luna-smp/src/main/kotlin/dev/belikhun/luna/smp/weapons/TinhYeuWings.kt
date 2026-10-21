package dev.belikhun.luna.smp.weapons

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.joml.Matrix4f
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitFun
import xyz.xenondevs.nova.initialize.InitStage
import java.util.UUID

/**
 * The 3D wings of the Sức Mạnh Tình Yêu set.
 *
 * "Vượt Ngàn Dặm Nhớ" is a real elytra (it glides from the chest slot), but a
 * worn elytra can only paint a flat texture onto the vanilla wing mesh, and
 * the art the set came with is an animated 3D model. So the worn elytra is
 * drawn invisible (its equipment asset is a transparent texture) and the model
 * rides on the player as an item display instead.
 *
 * It is a passenger, not something teleported after the player every tick: a
 * follower lags a tick behind, which at elytra speed leaves the wings a block
 * or two behind their owner. Vanilla's /ride refuses players as vehicles, which
 * is why this is plugin code rather than a datapack.
 *
 * The two matrices are the pack's own off-hand pose (the model was authored to
 * be held in the off hand, where its display transform throws it onto the
 * back) folded through the player model with the arm swing taken out: one for
 * standing (passenger seat 1.8 above the feet) and one for gliding, where the
 * body lies flat and the seat drops to 0.6. While gliding the display also
 * takes the player's pitch, as the body does.
 */
@Init(stage = InitStage.POST_WORLD)
object TinhYeuWings : Listener {

	/** The item model that marks the wings elytra, and that the display wears. */
	private val MODEL = NamespacedKey("lunasmp", "tinhyeu_totem")

	private val STAND = rowMajor(1.28357f,0.0f,-0.0f,0.0f,-0.0f,1.23385f,-0.3538f,-0.12914f,0.0f,0.3538f,1.23385f,-0.20508f,0.0f,0.0f,0.0f,1.0f)
	private val GLIDE = rowMajor(1.28357f,-0.0f,-0.0f,-0.0f,-0.0f,-0.3538f,-1.23385f,-0.39492f,-0.0f,1.23385f,-0.3538f,1.67086f,0.0f,0.0f,0.0f,1.0f)

	private class Wings(val display: ItemDisplay, var gliding: Boolean?)

	private val worn = HashMap<UUID, Wings>()

	@InitFun
	private fun init() {
		val owner = Bukkit.getPluginManager().getPlugin("LunaSmp")

		if (owner == null || !owner.isEnabled) {
			return
		}

		Bukkit.getPluginManager().registerEvents(this, owner)
		Bukkit.getScheduler().runTaskTimer(owner, Runnable { tick() }, 1L, 1L)
	}

	private fun rowMajor(vararg m: Float): Matrix4f = Matrix4f().set(m).transpose()

	private fun wearsWings(player: Player): Boolean {
		if (player.gameMode == GameMode.SPECTATOR || player.isDead) {
			return false
		}

		val chest = player.inventory.chestplate ?: return false

		if (chest.type != Material.ELYTRA || !chest.hasItemMeta()) {
			return false
		}

		return chest.itemMeta.itemModel == MODEL
	}

	private fun tick() {
		val online = HashSet<UUID>()

		for (player in Bukkit.getOnlinePlayers()) {
			online.add(player.uniqueId)

			if (!wearsWings(player)) {
				drop(player.uniqueId)
				continue
			}

			var wings = worn[player.uniqueId]

			// a display that lost its seat (death, dimension change, a dismount)
			// is replaced rather than re-seated
			if (wings == null || !wings.display.isValid || wings.display.vehicle != player) {
				drop(player.uniqueId)
				wings = Wings(spawn(player), null)
				worn[player.uniqueId] = wings
			}

			val gliding = player.isGliding

			if (wings.gliding != gliding) {
				wings.display.setTransformationMatrix(if (gliding) GLIDE else STAND)
				wings.gliding = gliding
			}

			// the body's yaw, not the head's: the wings hang off the back
			wings.display.setRotation(player.bodyYaw, if (gliding) player.location.pitch else 0f)
		}

		// players who left without a quit event reaching us
		worn.keys.filter { it !in online }.forEach { drop(it) }
	}

	private fun spawn(player: Player): ItemDisplay {
		val stack = ItemStack(Material.PAPER)
		stack.editMeta { it.itemModel = MODEL }

		val display = player.world.spawn(player.location, ItemDisplay::class.java) { display ->
			display.setItemStack(stack)
			display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
			display.isPersistent = false
			display.teleportDuration = 2
			display.viewRange = 1.0f
			display.setTransformationMatrix(STAND)
		}

		player.addPassenger(display)
		return display
	}

	private fun drop(id: UUID) {
		worn.remove(id)?.display?.remove()
	}

	@EventHandler
	private fun onQuit(event: PlayerQuitEvent) {
		drop(event.player.uniqueId)
	}
}
