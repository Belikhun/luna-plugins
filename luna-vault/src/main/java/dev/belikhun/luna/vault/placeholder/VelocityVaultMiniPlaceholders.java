package dev.belikhun.luna.vault.placeholder;

import com.velocitypowered.api.proxy.Player;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.vault.BuildConstants;
import dev.belikhun.luna.vault.service.VelocityVaultService;
import io.github.miniplaceholders.api.Expansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;

public final class VelocityVaultMiniPlaceholders {
	private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

	private final LunaLogger logger;
	private final VelocityVaultPlaceholderValues values;
	private Expansion expansion;

	public VelocityVaultMiniPlaceholders(LunaLogger logger, VelocityVaultService vaultService) {
		this.logger = logger.scope("MiniPlaceholders");
		this.values = new VelocityVaultPlaceholderValues(vaultService);
	}

	public void register() {
		if (expansion != null && expansion.registered()) {
			return;
		}

		expansion = Expansion.builder("lunavaultv")
			.author("Belikhun")
			.version(BuildConstants.VERSION)
			.audiencePlaceholder(Player.class, "balance", (player, queue, context) -> textTag(balance(player)))
			.build();
		expansion.register();
		logger.success("Đã đăng ký MiniPlaceholders namespace <lunavaultv> cho Velocity.");
	}

	public void unregister() {
		if (expansion == null) {
			return;
		}

		if (expansion.registered()) {
			expansion.unregister();
		}
		expansion = null;
	}

	private String balance(Player player) {
		if (player == null) {
			return "";
		}

		return values.balance(player.getUniqueId(), player.getUsername());
	}

	private Tag textTag(String value) {
		if (value == null || value.isEmpty()) {
			return Tag.inserting(Component.empty());
		}

		return Tag.inserting(MINI_MESSAGE.deserialize(value));
	}
}
