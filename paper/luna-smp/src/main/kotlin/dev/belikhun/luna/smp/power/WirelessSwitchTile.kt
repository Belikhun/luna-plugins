package dev.belikhun.luna.smp.power

import dev.belikhun.luna.smp.gauges.GaugeCatalog
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.item.Item
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockInteract
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.util.runTaskLater
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.block.tileentity.TileEntity
import xyz.xenondevs.nova.world.block.tileentity.menu.TileEntityMenuClass

private val MM = MiniMessage.miniMessage()

/**
 * The light switch: a rocker on a wall plate that toggles one lamp channel
 * on every power node whose reach covers it. The push button is the same
 * tile on a block with a POWERED state: it presses in and springs back.
 *
 * The switch owns nothing but its channel. The on/off state of a channel is
 * the node's, so the rocker only mirrors what the nodes around it say - once
 * a second, and immediately after a click - and a switch outside any node's
 * reach has nothing to flip and says so. A click toggles; a sneaking click
 * opens the channel button instead, the same Nova colour cycle every
 * fixture uses.
 */
class WirelessSwitchTile(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data) {

	/** The channel this switch flips; persisted. */
	private var channel = 0

	private val on: Boolean
		get() = blockState[GaugeCatalog.ON] == true

	/** The push button springs back; the rocker stays where it was put. */
	private val momentary: Boolean
		get() = blockState.block.id.value() == LampCatalog.BUTTON_ID

	override fun handleEnable() {
		super.handleEnable()

		channel = (retrieveDataOrNull<Int>(CHANNEL) ?: 0).coerceIn(0, LightChannels.COUNT - 1)
	}

	override fun handleTick() {
		mirror()
	}

	/** Sets the rocker to the channel's state on the nodes covering the switch. */
	private fun mirror() {
		val nodes = WirelessNodes.covering(pos)

		// no node in reach: the rocker rests off, since nothing it would
		// switch is listening
		val state = nodes.isNotEmpty() && nodes.all { it.channelOn(channel) }

		if (state != on) {
			updateBlockState(blockState.with(GaugeCatalog.ON, state))
		}
	}

	override fun handleRightClick(ctx: Context<BlockInteract>): Boolean {
		val player = ctx[DefaultContextParamTypes.SOURCE_ENTITY] as? Player ?: return false
		val hand = ctx[DefaultContextParamTypes.INTERACTION_HAND] ?: EquipmentSlot.HAND

		if (hand != EquipmentSlot.HAND) {
			return false
		}

		// sneaking is configuration here rather than the build gesture: a
		// switch is the one block whose plain click is spoken for
		if (player.isSneaking) {
			menuContainer.openWindow(player)

			return true
		}

		val nodes = WirelessNodes.covering(pos)

		if (nodes.isEmpty()) {
			player.sendMessage(MM.deserialize("<red>Không có trạm điện không dây nào phủ tới công tắc này.</red>"))

			return true
		}

		// every node in reach follows the switch, so a room fed by two nodes
		// still has one switch by the door
		val target = !nodes.all { it.channelOn(channel) }

		for (node in nodes) {
			if (node.channelOn(channel) != target) {
				node.toggleChannel(channel)
			}
		}

		mirror()

		if (momentary) {
			press()
			pos.world.playSound(pos.location.add(0.5, 0.5, 0.5), Sound.BLOCK_STONE_BUTTON_CLICK_ON, 0.5f, if (target) 1.5f else 1.2f)
		} else {
			pos.world.playSound(pos.location.add(0.5, 0.5, 0.5), Sound.BLOCK_LEVER_CLICK, 0.6f, if (target) 1.2f else 0.9f)
		}

		return true
	}

	/** Shows the button pressed in for a moment, then lets it spring back. */
	private fun press() {
		updateBlockState(blockState.with(DefaultBlockStateProperties.POWERED, true))

		runTaskLater(6) {
			if (isEnabled && blockState[DefaultBlockStateProperties.POWERED] == true) {
				updateBlockState(blockState.with(DefaultBlockStateProperties.POWERED, false))
			}
		}
	}

	/** The channel picker, plus a line saying what the switch is doing. */
	@TileEntityMenuClass
	inner class SwitchMenu : GlobalTileEntityMenu() {

		private val status: Item = Item.builder()
			.updatePeriodically(20)
			.setItemProvider {
				val nodes = WirelessNodes.covering(pos)
				val builder = ItemBuilder(Material.LEVER)
					.setName("<yellow>Công tắc kênh ${LightChannels.coloured(channel)}</yellow>")

				if (nodes.isEmpty()) {
					builder.addLoreLines("<red>Không có trạm nào phủ tới đây</red>")
				} else {
					builder.addLoreLines(
						"<gray>Trạm trong tầm: <white>${nodes.size}</white>",
						if (on) "<green>Kênh đang BẬT</green>" else "<dark_gray>Kênh đang TẮT</dark_gray>",
					)
				}

				builder
			}
			.build()

		override val gui: Gui = Gui.builder()
			.setStructure(
				"1 - - - - - - - 2",
				"| s # # c # # # |",
				"3 - - - - - - - 4",
			)
			.addIngredient('s', status)
			.addIngredient('c', LightChannels.button(
				{ channel },
				{ chosen ->
					channel = chosen
					storeData(CHANNEL, chosen)
					mirror()
				},
				{ chosen ->
					val nodes = WirelessNodes.covering(pos)

					when {
						nodes.isEmpty() -> null
						nodes.all { it.channelOn(chosen) } -> "<green>Kênh này đang BẬT</green>"
						else -> "<dark_gray>Kênh này đang TẮT</dark_gray>"
					}
				},
			))
			.build()
	}

	private companion object {

		const val CHANNEL = "channel"
	}
}
