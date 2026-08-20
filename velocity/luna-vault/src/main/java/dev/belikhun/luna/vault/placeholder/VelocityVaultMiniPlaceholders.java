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
			.audiencePlaceholder(Player.class, "rank", (player, queue, context) -> textTag(rank(player)))
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

	private String rank(Player player) {
		if (player == null) {
			return "";
		}

		return values.rank(player.getUniqueId(), player.getUsername());
	}

	/**
	 * Wrap a placeholder's value as a tag the surrounding message can survive.
	 *
	 * The empty parent is load-bearing. MiniMessage writes an inserted component's
	 * style as an *unclosed* tag when the component carries that style at its root,
	 * so on the serialize/re-parse round trip the rest of the line becomes a child
	 * of it and inherits the colour: a server-coloured status dot at the start of a
	 * chat format ends up tinting the player's name and everything after it. Giving
	 * the value a styleless parent hands the serializer a boundary to close at, and
	 * the value's colour then reaches exactly the value.
	 *
	 * @param value MiniMessage source for the placeholder's value
	 * @return an inserting tag whose style cannot escape into its siblings
	 */
	private Tag textTag(String value) {
		if (value == null || value.isEmpty()) {
			return Tag.inserting(Component.empty());
		}

		return Tag.inserting(Component.empty().append(MINI_MESSAGE.deserialize(value)));
	}
}
