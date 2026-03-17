package dev.belikhun.luna.vault.placeholder;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.vault.service.VelocityVaultService;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.placeholder.PlaceholderManager;

public final class VelocityVaultTabPlaceholders {
	private static final String BALANCE_PLACEHOLDER = "%lunavaultv-balance%";
	private static final int REFRESH_INTERVAL = -1;

	private final LunaLogger logger;
	private final VelocityVaultPlaceholderValues values;
	private boolean registered;

	public VelocityVaultTabPlaceholders(LunaLogger logger, VelocityVaultService vaultService) {
		this.logger = logger.scope("TAB");
		this.values = new VelocityVaultPlaceholderValues(vaultService);
	}

	public void register() {
		unregister();

		PlaceholderManager manager = TabAPI.getInstance().getPlaceholderManager();
		manager.registerPlayerPlaceholder(BALANCE_PLACEHOLDER, REFRESH_INTERVAL, player -> values.balance(player.getUniqueId(), player.getName()));
		registered = true;
		logger.success("Đã đăng ký TAB placeholder %lunavaultv-balance% cho Velocity.");
	}

	public void unregister() {
		if (!registered) {
			return;
		}

		try {
			TabAPI.getInstance().getPlaceholderManager().unregisterPlaceholder(BALANCE_PLACEHOLDER);
		} catch (Throwable ignored) {
		}

		registered = false;
	}
}
