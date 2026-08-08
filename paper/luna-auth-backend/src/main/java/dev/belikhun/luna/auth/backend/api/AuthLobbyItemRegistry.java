package dev.belikhun.luna.auth.backend.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public interface AuthLobbyItemRegistry {
	void registerLobbyItem(LobbyItem item);

	void unregisterLobbyItem(String key);

	Map<String, LobbyItem> lobbyItems();

	void applyLobbyItems(Player player);

	@FunctionalInterface
	interface LobbyItemAction {
		void onUse(Player player, ClickType clickType);
	}

	enum ClickType {
		LEFT,
		RIGHT
	}

	record LobbyItem(
		String key,
		int hotbarSlot,
		ItemStack item,
		LobbyItemAction action
	) {
	}
}
