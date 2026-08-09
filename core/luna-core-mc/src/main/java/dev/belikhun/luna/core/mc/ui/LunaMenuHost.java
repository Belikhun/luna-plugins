package dev.belikhun.luna.core.mc.ui;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Who has which luna screen open, and how a screen is opened at all.
 *
 * Every module that draws a chest menu needs the same three things: open one and
 * remember it, redraw the one a player is already looking at when the data behind
 * it changes, and forget it when the player closes it or leaves. Doing that per
 * module is how two of them end up disagreeing about whether a menu is still
 * open, so it is done once here.
 *
 * A host owns one kind of screen. A module drawing two - the shop's catalogue and
 * its confirmation prompt - takes two hosts rather than one keyed by screen, so a
 * redraw of one cannot repaint the other.
 *
 * The concrete menu is built by {@link LunaChestMenu}, which each platform and
 * game line supplies under that one name: its {@code clicked} override is the
 * only member whose signature the game has moved.
 */
public final class LunaMenuHost {
	private final int rows;
	private final Map<UUID, LunaChestMenuBase> openMenus;

	/**
	 * @param rows how tall this host's screens are, 1 to 6
	 */
	public LunaMenuHost(int rows) {
		this.rows = Math.max(1, Math.min(6, rows));
		this.openMenus = new ConcurrentHashMap<>();
	}

	/**
	 * Open the screen for a player, replacing whatever this host had open for them.
	 *
	 * The renderer runs inside the menu factory, before the menu is handed back to
	 * the game. That ordering is not cosmetic: the game sends the container's
	 * contents when it attaches its slot listener, which happens the moment the
	 * factory returns, so a screen filled in afterwards would open empty.
	 */
	public void open(ServerPlayer player, Component title, Consumer<LunaChestMenuBase> renderer) {
		if (player == null) {
			return;
		}

		UUID playerId = player.getUUID();
		LunaChestMenuBase previous = openMenus.get(playerId);

		// opening the new one closes the old one, and that close is the same event a
		// player pressing Escape produces; without this the host would forget the
		// entry it is in the middle of replacing
		if (previous != null) {
			previous.suppressCloseCallbackOnce();
		}

		player.openMenu(new SimpleMenuProvider((containerId, inventory, ignored) -> {
			LunaChestMenu menu = new LunaChestMenu(containerId, inventory, rows, () -> openMenus.remove(playerId));
			openMenus.put(playerId, menu);
			renderer.accept(menu);
			menu.broadcastChanges();
			return menu;
		}, title));
	}

	/**
	 * Redraw the screen this player has open, if they still have it open.
	 *
	 * The menu is only handed over when it is the one the player is actually
	 * looking at: a stale entry would otherwise repaint a container the client
	 * closed and never sees again.
	 */
	public void redraw(ServerPlayer player, Consumer<LunaChestMenuBase> renderer) {
		if (player == null) {
			return;
		}

		LunaChestMenuBase menu = openMenus.get(player.getUUID());

		if (menu == null || player.containerMenu != menu) {
			return;
		}

		renderer.accept(menu);
		menu.broadcastChanges();
	}

	/** Whether this host has a screen open for the player. */
	public boolean isOpen(UUID playerId) {
		return playerId != null && openMenus.containsKey(playerId);
	}

	/** Drop the player's entry without closing anything; for a disconnect. */
	public void forget(UUID playerId) {
		if (playerId != null) {
			openMenus.remove(playerId);
		}
	}

	/** Close every open screen. Used when the owning module shuts down. */
	public void closeAll() {
		for (LunaChestMenuBase menu : openMenus.values()) {
			menu.suppressCloseCallbackOnce();
		}

		openMenus.clear();
	}
}
