package dev.belikhun.luna.smp.power

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import xyz.xenondevs.commons.collections.firstInstanceOfOrNull
import xyz.xenondevs.invui.Click
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.gui.TabGui
import xyz.xenondevs.invui.item.AbstractItem
import xyz.xenondevs.invui.item.ItemProvider
import xyz.xenondevs.invui.window.Window
import xyz.xenondevs.nova.ui.menu.item.ClickyTabItem
import xyz.xenondevs.nova.util.playClickSound
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.world.block.tileentity.TileEntity
import xyz.xenondevs.nova.world.block.tileentity.network.NetworkManager
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkEndPoint
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkNode
import xyz.xenondevs.nova.world.block.tileentity.network.type.DefaultNetworkTypes
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkType
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.holder.FluidHolder
import xyz.xenondevs.nova.world.block.tileentity.network.type.item.holder.ItemHolder
import xyz.xenondevs.nova.world.format.NetworkState
import xyz.xenondevs.nova.world.item.DefaultGuiItems
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * The side menu of a wire cover, built the way Nova builds every machine's:
 * an energy, an item and a fluid tab along the top, and in each tab the six
 * faces laid out top / left front right / bottom back, so a player who has
 * set up a power cell already knows the screen.
 *
 * A face's button is green while the cover carries that tab's network type
 * through it and grey once it has been cut; a left click flips it, for that
 * type alone. Where a face meets a machine, the button also names the
 * machine, and on the item and fluid tabs a right click opens the cable's
 * own insert/extract menu for it, which is the only way to reach that menu
 * on a block with no cable arms to click.
 *
 * Everything the buttons show is read from the network state, never from
 * memory: a machine next door may come or go while the menu is open, so the
 * tile asks for a refresh on every network update it receives.
 */
class WireCoverMenu(private val tile: WireCoverTile) {

	private val faceItems: Map<NetworkType<*>, Map<BlockFace, FaceItem>> = WireCoverTile.TYPES.associateWith { type ->
		WireCoverTile.FACES.associateWith { face -> FaceItem(type, face) }
	}

	private val gui: TabGui = TabGui.builder()
		.setStructure(
			"# # # e i f # # #",
			"- - - - - - - - -",
			"x x x x x x x x x",
			"x x x x x x x x x",
			"x x x x x x x x x",
		)
		.addIngredient('e', ClickyTabItem(0) { tabs ->
			(if (tabs.tab == 0) DefaultGuiItems.ENERGY_BTN_SELECTED else DefaultGuiItems.ENERGY_BTN_ON).clientsideProvider
		})
		.addIngredient('i', ClickyTabItem(1) { tabs ->
			(if (tabs.tab == 1) DefaultGuiItems.ITEM_BTN_SELECTED else DefaultGuiItems.ITEM_BTN_ON).clientsideProvider
		})
		.addIngredient('f', ClickyTabItem(2) { tabs ->
			(if (tabs.tab == 2) DefaultGuiItems.FLUID_BTN_SELECTED else DefaultGuiItems.FLUID_BTN_ON).clientsideProvider
		})
		.setTabs(WireCoverTile.TYPES.map { type -> facesGui(faceItems.getValue(type)) })
		.build()

	/** The windows showing this menu right now, so a vanishing cover can close them. */
	private val windows = CopyOnWriteArrayList<Window>()

	fun openWindow(player: Player) {
		val window = Window.builder()
			.setViewer(player)
			.setTitle(tile.block.name)
			.setUpperGui(gui)
			.build()

		window.addCloseHandler { windows.remove(window) }
		windows += window

		refresh()
		window.open()
	}

	fun closeAll() {
		for (window in windows.toList()) {
			window.close()
		}

		windows.clear()
	}

	/** Re-reads every face off the network state, on the network thread. */
	fun refresh() {
		if (windows.isEmpty()) {
			return
		}

		NetworkManager.queueRead(tile.pos.chunkPos) { state ->
			refresh(state)
		}
	}

	/**
	 * Re-reads every face off [state]; for use from a network context that
	 * already holds it, such as the tile's own network update.
	 */
	suspend fun refresh(state: NetworkState) {
		if (windows.isEmpty()) {
			return
		}

		val connected = runCatching { state.getConnectedNodes(tile) }.getOrNull()

		// whatever stands next door, linked or not: a cut face still says
		// what it is cut off from
		val nearby = state.getNearbyNodes(tile.pos, WireCoverTile.FACES.toSet())

		for ((type, items) in faceItems) {
			for ((face, item) in items) {
				item.show(tile.conducts(type, face), connected?.get(type, face) != null, nearby[face])
			}
		}

		runTask {
			for (items in faceItems.values) {
				for (item in items.values) {
					item.notifyWindows()
				}
			}
		}
	}

	/** One tab: the six faces of one network type, in Nova's arrangement. */
	private fun facesGui(items: Map<BlockFace, FaceItem>): Gui =
		Gui.builder()
			.setStructure(
				"# # # # t # # # #",
				"# # # w n e # # #",
				"# # # # b s # # #",
			)
			.addIngredient('t', items.getValue(BlockFace.UP))
			.addIngredient('w', items.getValue(BlockFace.WEST))
			.addIngredient('n', items.getValue(BlockFace.NORTH))
			.addIngredient('e', items.getValue(BlockFace.EAST))
			.addIngredient('b', items.getValue(BlockFace.DOWN))
			.addIngredient('s', items.getValue(BlockFace.SOUTH))
			.build()

	private inner class FaceItem(private val type: NetworkType<*>, private val face: BlockFace) : AbstractItem() {

		private val provider = AtomicReference<ItemProvider>(ItemProvider.EMPTY)

		override fun getItemProvider(player: Player): ItemProvider = provider.get()

		/**
		 * Draws the button for a face the cover carries this type through
		 * (or not), [linked] to something over it (or not), meeting
		 * [neighbour] (or nothing). Safe off the main thread: it only
		 * prepares the item, the caller notifies the windows.
		 */
		fun show(conducting: Boolean, linked: Boolean, neighbour: NetworkNode?) {
			val button = if (conducting) DefaultGuiItems.GREEN_BTN else DefaultGuiItems.GRAY_BTN
			val builder = button.createClientsideItemBuilder()
				.setName(faceName(face))

			if (conducting) {
				builder.addLoreLines(line(if (linked) "Đang nối" else "Mở, chưa có gì để nối", NamedTextColor.GREEN))
			} else {
				builder.addLoreLines(line("Đã ngắt", NamedTextColor.RED))
			}

			builder.addLoreLines(line("Chuột trái: nối hoặc ngắt mặt này", NamedTextColor.DARK_GRAY))

			if (neighbour != null) {
				builder.addLoreLines(
					Component.text()
						.append(line("Kề: ", NamedTextColor.GRAY))
						.append(nameOf(neighbour).color(NamedTextColor.WHITE))
						.decoration(TextDecoration.ITALIC, false)
						.build(),
				)

				if (type != DefaultNetworkTypes.ENERGY && configurable(neighbour)) {
					builder.addLoreLines(line("Chuột phải: cấu hình vào/ra của máy", NamedTextColor.DARK_GRAY))
				}
			}

			provider.set(builder)
		}

		override fun handleClick(clickType: ClickType, player: Player, click: Click) {
			player.playClickSound()

			if (clickType.isRightClick) {
				if (type != DefaultNetworkTypes.ENERGY) {
					tile.openFaceMenu(player, face)
				}

				return
			}

			tile.setFace(type, face, !tile.conducts(type, face)) {
				refresh()
			}
		}
	}

	private companion object {

		/** Nova's own word for the face, in the player's language. */
		fun faceName(face: BlockFace): Component =
			Component.translatable("menu.nova.side_config.${face.name.lowercase()}", NamedTextColor.GRAY)
				.decoration(TextDecoration.ITALIC, false)

		fun line(text: String, colour: NamedTextColor): Component =
			Component.text(text, colour).decoration(TextDecoration.ITALIC, false)

		/** What stands next door: a Nova block by its name, anything else by its vanilla one. */
		fun nameOf(node: NetworkNode): Component {
			if (node is TileEntity) {
				return node.block.name
			}

			return Component.translatable(node.pos.block.type.translationKey())
		}

		/** Whether the cable's insert/extract menu has anything to show for [node]. */
		fun configurable(node: NetworkNode): Boolean {
			val endPoint = node as? NetworkEndPoint ?: return false

			return endPoint.holders.firstInstanceOfOrNull<ItemHolder>() != null
				|| endPoint.holders.firstInstanceOfOrNull<FluidHolder>() != null
		}
	}
}
