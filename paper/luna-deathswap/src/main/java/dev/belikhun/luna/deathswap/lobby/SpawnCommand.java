package dev.belikhun.luna.deathswap.lobby;

import dev.belikhun.luna.deathswap.DeathSwapText;
import dev.belikhun.luna.deathswap.gui.DeathSwapMenus;
import dev.belikhun.luna.deathswap.room.Room;
import dev.belikhun.luna.deathswap.room.RoomManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;

/**
 * /spawn: back to the lobby, staying in the room. A player still alive in a
 * round is asked first (the confirm screen), since leaving is a forfeit.
 */
public final class SpawnCommand implements BasicCommand {
	private final RoomManager rooms;
	private final DeathSwapMenus menus;

	public SpawnCommand(RoomManager rooms, DeathSwapMenus menus) {
		this.rooms = rooms;
		this.menus = menus;
	}

	@Override
	public void execute(CommandSourceStack source, String[] args) {
		if (!(source.getSender() instanceof Player player)) {
			DeathSwapText.send(source.getSender(), DeathSwapText.ERROR + "Chỉ người chơi mới dùng được lệnh này.");
			return;
		}

		Room room = rooms.roomFor(player);
		if (room != null && room.state() == Room.State.PLAYING && room.isAlive(player.getUniqueId())) {
			menus.confirmForfeit(player);
			return;
		}
		confirm(player);
	}

	/** Called directly, or by the confirm screen. */
	public void confirm(Player player) {
		Room room = rooms.roomFor(player);
		if (room != null) {
			room.returnToLobby(player);
		} else {
			rooms.sendToLobby(player);
		}
		DeathSwapText.send(player, DeathSwapText.SUCCESS + "Đã về sảnh.");
		rooms.refreshDisplay(player);
	}
}
