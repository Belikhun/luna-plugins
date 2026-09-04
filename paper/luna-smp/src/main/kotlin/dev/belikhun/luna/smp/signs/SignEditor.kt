package dev.belikhun.luna.smp.signs

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitFun
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.format.WorldDataManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * How a player writes on a board.
 *
 * Minecraft gives a plugin no free-text field of its own, so the board asks for
 * its words in chat: right-click it, type the line, and the next thing typed
 * becomes the sign instead of being sent to everybody. That is the only route
 * that takes Vietnamese cleanly - an anvil field mangles the diacritics, and a
 * chest full of letter buttons is not a way to write a sentence.
 */
@Init(stage = InitStage.POST_WORLD)
object SignEditor : Listener {

	/** How long a board waits for the line it asked for. */
	private const val TIMEOUT_MS = 60_000L

	/** What a player types instead of a line when they change their mind. */
	private val CANCELS = setOf("huỷ", "hủy", "cancel", "thoát", "thoat")

	private val MM = MiniMessage.miniMessage()
	private val PLAIN = PlainTextComponentSerializer.plainText()

	/**
	 * The same `&` grammar the board renders with. The typed line is taken
	 * back OUT of the chat component with this rather than as plain text,
	 * because a chat plugin may already have turned the player's `&a` into a
	 * real colour by the time the event arrives - plain-serialising that
	 * strips the colour and the board came out monochrome. Legacy-serialising
	 * turns real colours back into codes, and literal codes pass through.
	 */
	private val LEGACY: LegacyComponentSerializer = LegacyComponentSerializer.builder()
		.character('&')
		.hexColors()
		.build()

	private const val PROMPT =
		"<yellow>✎ Nhập nội dung cho biển vào khung chat.</yellow>"
	private const val PROMPT_HINT =
		"<gray>Dùng <white>|</white> để xuống dòng, <white>&a</white> để đổi màu, gõ <white>huỷ</white> để bỏ qua.</gray>"
	private const val WRITTEN = "<green>✔ Đã ghi nội dung lên biển.</green>"
	private const val WIPED = "<green>✔ Đã xoá nội dung trên biển.</green>"
	private const val CANCELLED = "<gray>Đã bỏ qua.</gray>"
	private const val EXPIRED = "<red>⚠ Đã quá thời gian nhập nội dung cho biển.</red>"
	private const val GONE = "<red>⚠ Biển đó không còn ở đó nữa.</red>"

	/** A board waiting on somebody, held as a position so it can be re-read. */
	private class Pending(val pos: BlockPos, val deadline: Long)

	private val waiting = ConcurrentHashMap<UUID, Pending>()

	@InitFun
	private fun init() {
		val owner = Bukkit.getPluginManager().getPlugin("LunaSmp")

		if (owner == null || !owner.isEnabled) {
			return
		}

		Bukkit.getPluginManager().registerEvents(this, owner)
	}

	/** Asks a player for the board's words. */
	fun begin(player: Player, tile: SignTile) {
		waiting[player.uniqueId] = Pending(tile.pos, System.currentTimeMillis() + TIMEOUT_MS)

		player.sendMessage(MM.deserialize(PROMPT))
		player.sendMessage(MM.deserialize(PROMPT_HINT))
	}

	/**
	 * A click on a board that is already waiting on this player takes the
	 * request back instead of asking again: click to edit, click to change
	 * your mind. A click on a DIFFERENT board moves the pending edit there.
	 */
	fun toggle(player: Player, tile: SignTile) {
		val pending = waiting[player.uniqueId]

		if (pending != null && pending.pos == tile.pos) {
			waiting.remove(player.uniqueId)
			player.sendMessage(MM.deserialize(CANCELLED))
			return
		}

		begin(player, tile)
	}

	/** Tells a player the board they just cleared is clear. */
	fun wiped(player: Player) {
		waiting.remove(player.uniqueId)
		player.sendMessage(MM.deserialize(WIPED))
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	private fun onChat(event: AsyncChatEvent) {
		val player = event.player
		val pending = waiting.remove(player.uniqueId) ?: return

		// whatever they typed was meant for the board, not for the server
		event.isCancelled = true

		if (System.currentTimeMillis() > pending.deadline) {
			player.sendMessage(MM.deserialize(EXPIRED))
			return
		}

		// literal codes still in the text mean no chat plugin touched them:
		// take the plain text as typed. Otherwise the colours were already
		// baked into the component, and legacy-serialising wins them back -
		// minus a leading &f some chat formats wrap every message in, which
		// would otherwise overpaint a whiteboard's black.
		val plain = PLAIN.serialize(event.message()).trim()
		val typed = if (plain.contains('&')) {
			plain
		} else {
			LEGACY.serialize(event.message()).trim().removePrefix("&f")
		}

		if (plain.lowercase() in CANCELS) {
			player.sendMessage(MM.deserialize(CANCELLED))
			return
		}

		// chat arrives off the main thread, and everything a board does - the
		// block lookup, the display, the stored text - belongs on it
		runTask {
			val tile = WorldDataManager.getTileEntity(pending.pos) as? SignTile

			if (tile == null) {
				player.sendMessage(MM.deserialize(GONE))
				return@runTask
			}

			tile.write(typed)
			tile.pos.playSound("block.wool.place", 0.7f, 1.4f)
			player.sendMessage(MM.deserialize(WRITTEN))
		}
	}

	@EventHandler
	private fun onQuit(event: PlayerQuitEvent) {
		waiting.remove(event.player.uniqueId)
	}
}
