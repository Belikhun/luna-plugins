package dev.belikhun.luna.messenger.velocity.service;

import com.velocitypowered.api.proxy.Player;
import io.github.miniplaceholders.api.MiniPlaceholders;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

public final class VelocityMiniPlaceholderResolver {
	private static final MiniMessage MM = MiniMessage.miniMessage();

	private volatile boolean enabled = true;

	public String resolve(Player player, String template) {
		if (!enabled || template == null || template.isBlank()) {
			return template == null ? "" : template;
		}

		try {
			TagResolver global = MiniPlaceholders.globalPlaceholders();
			TagResolver audience = MiniPlaceholders.audiencePlaceholders();

			Component component = player == null
				? MM.deserialize(template, global, audience)
				: MM.deserialize(template, player, global, audience);
			return MM.serialize(component);
		} catch (NoClassDefFoundError | Exception ignored) {
			// If MiniPlaceholders is missing or errors unexpectedly, disable further attempts.
			enabled = false;
			return template;
		}
	}
}
