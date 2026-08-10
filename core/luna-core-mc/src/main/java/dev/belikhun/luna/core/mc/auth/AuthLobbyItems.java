package dev.belikhun.luna.core.mc.auth;

import dev.belikhun.luna.core.api.auth.AuthMessages;
import dev.belikhun.luna.core.mc.compat.ItemDecor;
import dev.belikhun.luna.core.mc.compat.ItemLookup;
import dev.belikhun.luna.core.mc.text.LunaTextComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * The hotbar an authenticated player is handed on a lobby backend.
 *
 * Paper's auth backend has done this from the start: while a player is locked
 * its inventory is empty, and the moment it authenticates the inventory is
 * replaced with the server-selector compass. This is the same thing for the mod
 * loaders, so the three platforms hand out the same item in the same slot with
 * the same name and lore.
 *
 * It clears inventories, so it is opt-in: `auth.lobby-items.enabled`, off by
 * default, because a backend that is not a lobby would lose its players' items.
 * The item's click action is not here - each platform resolves its own server
 * selector - only what the item is and where it goes.
 */
public final class AuthLobbyItems {
	/**
	 * The selector's own command, which is what the item runs.
	 *
	 * Going through the command rather than the controller keeps this free of
	 * the loaders: fabric and the forge family each register their own selector
	 * class, but both register it under this name.
	 */
	public static final String OPEN_SELECTOR_COMMAND = "lunaservers";

	private AuthLobbyItems() {
	}

	/**
	 * Open the server selector for this player, as the item's right-click does.
	 *
	 * The server is handed in rather than read off the player: the field is
	 * private again on the 26.x mappings, and every caller already holds one.
	 */
	public static void openServerSelector(MinecraftServer server, ServerPlayer player) {
		if (server == null || player == null) {
			return;
		}

		server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), OPEN_SELECTOR_COMMAND);
	}

	/** The server-selector compass, named and described as on paper. */
	public static ItemStack serverSelector() {
		Item item = ItemLookup.byName("minecraft", AuthMessages.ITEM_LOBBY_SELECTOR);
		ItemStack stack = new ItemStack(item == null ? Items.COMPASS : item);
		List<Component> lore = new ArrayList<>();

		for (String line : AuthMessages.lobbySelectorLore()) {
			lore.add(LunaTextComponents.mini(line));
		}

		ItemDecor.name(stack, LunaTextComponents.mini(AuthMessages.lobbySelectorName()));
		ItemDecor.lore(stack, lore);

		return stack;
	}

	/**
	 * Whether this is the selector item.
	 *
	 * Matching is by item and decoration rather than a tag, because writing a
	 * custom tag is one of the things that changed shape in the 1.20.5 component
	 * rewrite; the name and lore are enough to tell it from a compass a player
	 * brought along.
	 */
	public static boolean isServerSelector(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}

		return ItemDecor.sameItemAndData(stack, serverSelector());
	}

	/** Empty the player's inventory: main, armour and offhand alike. */
	public static void clearInventory(ServerPlayer player) {
		if (player == null) {
			return;
		}

		Inventory inventory = player.getInventory();

		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			inventory.setItem(slot, ItemStack.EMPTY);
		}

		player.containerMenu.broadcastChanges();
		player.inventoryMenu.broadcastChanges();
	}

	/** Clear the inventory and lay out the lobby hotbar. */
	public static void applyLobbyItems(ServerPlayer player) {
		if (player == null) {
			return;
		}

		clearInventory(player);
		player.getInventory().setItem(AuthMessages.LOBBY_SELECTOR_SLOT, serverSelector());
		player.inventoryMenu.broadcastChanges();
	}
}
