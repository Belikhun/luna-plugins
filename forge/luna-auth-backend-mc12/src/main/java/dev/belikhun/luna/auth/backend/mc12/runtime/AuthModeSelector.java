package dev.belikhun.luna.auth.backend.mc12.runtime;

import dev.belikhun.luna.core.mc12.text.LunaTextComponents;
import dev.belikhun.luna.core.mc12.ui.LunaChestMenu;
import dev.belikhun.luna.core.mc12.ui.LunaItems;
import dev.belikhun.luna.core.mc12.ui.LunaMenuHost;
import dev.belikhun.luna.legacy.auth.AuthMessages;
import dev.belikhun.luna.legacy.logging.LunaLogger;

import net.minecraft.entity.player.EntityPlayerMP;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * "Premium or offline?", asked before a name that could be either logs in.
 *
 * The choice decides how the proxy authenticates them, so it has to be made
 * before `/login` means anything - which is why this screen re-opens itself
 * rather than offering a way out. It is the one luna menu with no close button,
 * and that is deliberate.
 *
 * Layout, items and every string come from {@link AuthMessages}, shared with the
 * modern builds; only the placement into a 1.12.2 container is here.
 */
final class AuthModeSelector {
	/** One row, and the frame fills what the three buttons do not use. */
	private static final int ROWS = 1;

	private final LegacyAuthRestrictionController controller;
	private final LunaLogger logger;
	private final LunaMenuHost menuHost;
	private final ConcurrentMap<UUID, Boolean> eligible;
	private final ConcurrentMap<UUID, Boolean> preferencePresent;
	private final ConcurrentMap<UUID, Boolean> rememberSelection;
	private final ConcurrentMap<UUID, Long> nextOpenAt;
	private final java.util.Set<UUID> chosen;

	AuthModeSelector(LegacyAuthRestrictionController controller, LunaLogger logger) {
		this.controller = controller;
		this.logger = logger;
		this.menuHost = new LunaMenuHost(ROWS);
		this.eligible = new ConcurrentHashMap<UUID, Boolean>();
		this.preferencePresent = new ConcurrentHashMap<UUID, Boolean>();
		this.rememberSelection = new ConcurrentHashMap<UUID, Boolean>();
		this.nextOpenAt = new ConcurrentHashMap<UUID, Long>();
		this.chosen = ConcurrentHashMap.newKeySet();
	}

	/** What the proxy last said about this player's name and stored preference. */
	void updateEligibility(UUID playerId, boolean premiumNameCandidate, boolean hasModePreference) {
		eligible.put(playerId, Boolean.valueOf(premiumNameCandidate));
		preferencePresent.put(playerId, Boolean.valueOf(hasModePreference));
	}

	/**
	 * Open it if this player needs it and the grace period has passed.
	 *
	 * The delay exists because a window opened in the same tick as the join packet
	 * is routinely dropped by the client; the modern builds wait the same 1500ms.
	 */
	void showIfDue(EntityPlayerMP player, long now) {
		UUID playerId = controller.players().idOf(player);

		if (!shouldShow(playerId)) {
			return;
		}

		Long openAt = nextOpenAt.get(playerId);

		if (openAt == null) {
			nextOpenAt.put(playerId, Long.valueOf(now + controller.modeSelectorDelayMillis()));

			return;
		}

		if (now < openAt.longValue() || menuHost.isOpen(playerId)) {
			return;
		}

		open(player);
	}

	private boolean shouldShow(UUID playerId) {
		if (!controller.config().modeSelectorGuiEnabled() || controller.isAuthenticated(playerId)) {
			return false;
		}

		if (chosen.contains(playerId) || Boolean.TRUE.equals(preferencePresent.get(playerId))) {
			return false;
		}

		return Boolean.TRUE.equals(eligible.get(playerId));
	}

	private void open(final EntityPlayerMP player) {
		menuHost.open(
			player,
			LunaTextComponents.mini(AuthMessages.modeSelectorTitle()),
			menu -> render(player, menu)
		);

		controller.flow("ShowModeSelector player=" + player.getName() + " uuid=" + controller.players().idOf(player));
	}

	private void render(final EntityPlayerMP player, LunaChestMenu menu) {
		UUID playerId = controller.players().idOf(player);
		final boolean remember = Boolean.TRUE.equals(rememberSelection.get(playerId));

		menu.clearTopSlots();

		for (Integer slot : AuthMessages.MODE_SELECTOR_FRAME_SLOTS) {
			menu.setDecoration(slot.intValue(), LunaItems.of(
				AuthMessages.ITEM_FRAME,
				AuthMessages.frameItemName(),
				AuthMessages.frameItemLore()
			));
		}

		menu.setDecoration(AuthMessages.MODE_SELECTOR_SLOT_INFO, LunaItems.of(
			AuthMessages.ITEM_INFO,
			AuthMessages.infoItemName(),
			AuthMessages.infoItemLore()
		));

		menu.setTopSlot(
			AuthMessages.MODE_SELECTOR_SLOT_PREMIUM,
			LunaItems.of(AuthMessages.ITEM_PREMIUM, AuthMessages.premiumItemName(), AuthMessages.premiumItemLore()),
			() -> choose(player, true, remember)
		);

		menu.setTopSlot(
			AuthMessages.MODE_SELECTOR_SLOT_OFFLINE,
			LunaItems.of(AuthMessages.ITEM_OFFLINE, AuthMessages.offlineItemName(), AuthMessages.offlineItemLore()),
			() -> choose(player, false, remember)
		);

		menu.setTopSlot(
			AuthMessages.MODE_SELECTOR_SLOT_REMEMBER,
			LunaItems.of(
				remember ? AuthMessages.ITEM_REMEMBER_ON : AuthMessages.ITEM_REMEMBER_OFF,
				AuthMessages.rememberItem(remember),
				AuthMessages.rememberItemLore(remember)
			),
			() -> toggleRemember(player)
		);
	}

	private void toggleRemember(EntityPlayerMP player) {
		UUID playerId = controller.players().idOf(player);
		boolean next = !Boolean.TRUE.equals(rememberSelection.get(playerId));

		rememberSelection.put(playerId, Boolean.valueOf(next));
		LegacyAuthPlayerCompat.actionBar(player, LegacyAuthRestrictionController.mini(AuthMessages.rememberToggled(next)));

		// redraw rather than reopen: reopening would flicker the window shut and
		// hand the client a new one for a change of a single slot
		menuHost.redraw(player, menu -> render(player, menu));
	}

	private void choose(EntityPlayerMP player, boolean premium, boolean remember) {
		UUID playerId = controller.players().idOf(player);
		String mode = premium
			? (remember ? "online_permanent" : "online")
			: (remember ? "offline_permanent" : "offline");

		if (!controller.sendProbePreference(player, mode)) {
			LegacyAuthPlayerCompat.actionBar(player, LegacyAuthRestrictionController.mini(AuthMessages.modeChoiceSendFailed()));

			return;
		}

		chosen.add(playerId);
		player.sendMessage(LegacyAuthRestrictionController.mini(premium
			? AuthMessages.modePremiumChosen(remember)
			: AuthMessages.modeOfflineChosen(remember)));

		menuHost.close(player);
		controller.flow("ModeChosen player=" + player.getName() + " uuid=" + playerId + " mode=" + mode);
	}

	/** Close it because the player no longer needs it. */
	void closeFor(EntityPlayerMP player) {
		UUID playerId = controller.players().idOf(player);

		if (menuHost.isOpen(playerId)) {
			menuHost.close(player);
			menuHost.forget(playerId);
		}
	}

	void forget(UUID playerId) {
		menuHost.forget(playerId);
		eligible.remove(playerId);
		preferencePresent.remove(playerId);
		rememberSelection.remove(playerId);
		nextOpenAt.remove(playerId);
		chosen.remove(playerId);
	}

	void close() {
		menuHost.closeAll();
		eligible.clear();
		preferencePresent.clear();
		rememberSelection.clear();
		nextOpenAt.clear();
		chosen.clear();
	}
}
