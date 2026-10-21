package dev.belikhun.luna.deathswap;

import dev.belikhun.luna.deathswap.room.Room;
import dev.belikhun.luna.deathswap.room.RoomManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * %deathswap_...% for TAB designs and anything else on PlaceholderAPI, for
 * the room the player is in (or watching): room, room_id, state,
 * state_display, alive, participants, swaps, next_swap, timer, status,
 * winner, rooms, and any sidebar chip by name (e.g. %deathswap_alive_chip%).
 * Outside a room the room values are empty.
 */
public final class DeathSwapPlaceholders extends PlaceholderExpansion {
	private final LunaDeathSwapPlugin plugin;
	private final RoomManager rooms;

	public DeathSwapPlaceholders(LunaDeathSwapPlugin plugin, RoomManager rooms) {
		this.plugin = plugin;
		this.rooms = rooms;
	}

	@Override
	public String getIdentifier() {
		return "deathswap";
	}

	@Override
	public String getAuthor() {
		return "Belikhun";
	}

	@Override
	public String getVersion() {
		return plugin.getPluginMeta().getVersion();
	}

	@Override
	public boolean persist() {
		return true;
	}

	@Override
	public String onRequest(OfflinePlayer offline, String params) {
		if (params.equals("rooms")) {
			return String.valueOf(rooms.rooms().size());
		}
		Player player = offline == null ? null : offline.getPlayer();
		if (player == null) {
			return "";
		}

		Room room = rooms.roomFor(player);
		if (room == null) {
			return switch (params) {
				case "room", "room_id", "winner", "next_swap", "timer" -> "";
				case "state" -> "none";
				case "state_display" -> "<gray>Sảnh</gray>";
				case "status" -> "lobby";
				case "alive", "participants", "swaps" -> "0";
				case "hearts", "hearts_chip" -> "";
				default -> chip(player, null, params);
			};
		}

		return switch (params) {
			case "room" -> room.displayName();
			case "room_id" -> room.id();
			case "state" -> room.stateKey();
			case "state_display" -> room.stateDisplay();
			case "alive" -> String.valueOf(room.aliveCount());
			case "participants" -> String.valueOf(room.participantCount());
			case "swaps" -> String.valueOf(room.swaps());
			case "next_swap" -> room.swapText();
			case "timer" -> DeathSwapText.clock(room.elapsedSeconds());
			// max hearts of a survivor (LifeSwap changes it); empty when not playing
			case "hearts" -> {
				double max = room.maxHeartsOf(player.getUniqueId());
				yield max < 0 ? "" : (max == Math.floor(max) ? String.valueOf((long) max) : String.valueOf(max));
			}
			case "hearts_chip" -> room.heartsChip(player.getUniqueId());
			case "winner" -> room.winnerName() == null ? "" : room.winnerName();
			case "status" -> room.isAlive(player.getUniqueId()) ? "alive"
				: room.isParticipant(player.getUniqueId()) ? "eliminated"
				: room.isSittingOut(player.getUniqueId()) ? "sitting_out" : "ready";
			default -> chip(player, room, params);
		};
	}

	private String chip(Player player, Room room, String params) {
		// player_display resolves through PAPI itself; never hand it back here, so nothing can recurse
		if (params.equals("player_display")) {
			return null;
		}
		Map<String, String> values = rooms.placeholders(player, room);
		return values.get(params);
	}
}
