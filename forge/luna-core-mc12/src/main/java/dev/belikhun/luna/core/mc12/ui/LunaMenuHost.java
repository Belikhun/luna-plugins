package dev.belikhun.luna.core.mc12.ui;

import dev.belikhun.luna.core.mc12.runtime.ServerThreadTasks;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.ITextComponent;

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
 * A host owns one kind of screen. A module drawing two - a catalogue and its
 * confirmation prompt - takes two hosts rather than one keyed by screen, so a
 * redraw of one cannot repaint the other.
 */
public final class LunaMenuHost {
	private final int rows;
	private final Map<UUID, LunaChestMenu> openMenus;

	/**
	 * @param rows how tall this host's screens are, 1 to 6
	 */
	public LunaMenuHost(int rows) {
		this.rows = Math.max(1, Math.min(6, rows));
		this.openMenus = new ConcurrentHashMap<UUID, LunaChestMenu>();
	}

	/**
	 * Open the screen for a player, replacing whatever this host had open for them.
	 *
	 * The renderer runs **before** the window is opened, unlike the modern builds
	 * where it runs inside the menu factory. Same reason, different mechanism: the
	 * contents have to be in place before the client is told about the window, and
	 * on this line `displayGUIChest` sends that packet immediately.
	 *
	 * A call from inside a click handler is held until that click is finished; see
	 * {@link ServerThreadTasks} for what swapping the window mid-click does to the
	 * window being opened. Since almost every button on a luna screen opens another screen,
	 * this is the normal path rather than the exception.
	 */
	public void open(final EntityPlayerMP player, final ITextComponent title, final Consumer<LunaChestMenu> renderer) {
		if (player == null) {
			return;
		}

		if (LunaChestMenu.isDispatchingClick()) {
			ServerThreadTasks.later(new Runnable() {
				@Override
				public void run() {
					openNow(player, title, renderer);
				}
			});

			return;
		}

		openNow(player, title, renderer);
	}

	/**
	 * Close whatever this player has open, from inside a click handler or outside it.
	 *
	 * Never call `closeScreen` from a button directly: `player.openContainer` becomes
	 * the player's own inventory, and the rest of `processClickWindow` then locks
	 * *that* - one object per session, so it stays locked for the session.
	 */
	public void close(final EntityPlayerMP player) {
		if (player == null) {
			return;
		}

		if (LunaChestMenu.isDispatchingClick()) {
			ServerThreadTasks.later(new Runnable() {
				@Override
				public void run() {
					player.closeScreen();
				}
			});

			return;
		}

		player.closeScreen();
	}

	private void openNow(EntityPlayerMP player, ITextComponent title, Consumer<LunaChestMenu> renderer) {
		final UUID playerId = player.getUniqueID();
		LunaChestMenu previous = openMenus.get(playerId);

		// opening the new one closes the old one, and that close is the same event a
		// player pressing Escape produces; without this the host would forget the
		// entry it is in the middle of replacing
		if (previous != null) {
			previous.suppressCloseCallbackOnce();
		}

		LunaChestMenu menu = new LunaChestMenu(player.inventory, rows, new Runnable() {
			@Override
			public void run() {
				openMenus.remove(playerId);
			}
		});

		renderer.accept(menu);
		openMenus.put(playerId, menu);

		player.displayGUIChest(new LunaChestInventory(menu, title));

		// displayGUIChest assigns the window id and attaches the listener, so the
		// contents only reach the client once that has happened
		menu.detectAndSendChanges();
	}

	/**
	 * Redraw the screen this player has open, if they still have it open.
	 *
	 * The menu is only handed over when it is the one the player is actually
	 * looking at: a stale entry would otherwise repaint a container the client
	 * closed and never sees again.
	 *
	 * @return whether the player still had it open and it was redrawn, so a caller
	 *         tracking what each player is looking at can drop the ones that closed
	 */
	public boolean redraw(EntityPlayerMP player, Consumer<LunaChestMenu> renderer) {
		if (player == null) {
			return false;
		}

		LunaChestMenu menu = openMenus.get(player.getUniqueID());

		if (menu == null || player.openContainer != menu) {
			return false;
		}

		renderer.accept(menu);
		menu.detectAndSendChanges();

		return true;
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
		for (LunaChestMenu menu : openMenus.values()) {
			menu.suppressCloseCallbackOnce();
		}

		openMenus.clear();
	}
}
