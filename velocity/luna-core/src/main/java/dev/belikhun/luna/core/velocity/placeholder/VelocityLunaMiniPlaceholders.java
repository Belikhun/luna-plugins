package dev.belikhun.luna.core.velocity.placeholder;

import com.velocitypowered.api.proxy.Player;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.server.ServerDisplayResolver;
import dev.belikhun.luna.core.velocity.BuildConstants;
import dev.belikhun.luna.core.velocity.VelocityPlayerDisplayFormat;
import dev.belikhun.luna.core.velocity.heartbeat.VelocityBackendStatusRegistry;
import dev.belikhun.luna.core.velocity.serverselector.VelocityServerSelectorConfig;
import io.github.miniplaceholders.api.Expansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;

public final class VelocityLunaMiniPlaceholders {
	private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

	private final LunaLogger logger;
	private final VelocityLunaPlaceholderValues values;
	private Expansion expansion;
	private Expansion legacyExpansion;

	public VelocityLunaMiniPlaceholders(
		LunaLogger logger,
		VelocityBackendStatusRegistry statusRegistry,
		VelocityServerSelectorConfig selectorConfig,
		ServerDisplayResolver serverDisplayResolver,
		VelocityPlayerDisplayFormat playerDisplayFormat
	) {
		this.logger = logger.scope("MiniPlaceholders");
		this.values = new VelocityLunaPlaceholderValues(statusRegistry, selectorConfig, serverDisplayResolver, playerDisplayFormat);
	}

	public void register() {
		if (expansion != null && expansion.registered() && legacyExpansion != null && legacyExpansion.registered()) {
			return;
		}

		expansion = createExpansion("lunav");
		expansion.register();
		legacyExpansion = createExpansion("luna");
		legacyExpansion.register();
		logger.success("Đã đăng ký MiniPlaceholders namespace <lunav> và <luna> cho Velocity.");
	}

	public void unregister() {
		if (expansion != null && expansion.registered()) {
			expansion.unregister();
		}
		if (legacyExpansion != null && legacyExpansion.registered()) {
			legacyExpansion.unregister();
		}
		expansion = null;
		legacyExpansion = null;
	}

	private Expansion createExpansion(String namespace) {
		Expansion.Builder builder = Expansion.builder(namespace)
			.author("Belikhun")
			.version(BuildConstants.VERSION)
			.globalPlaceholder("online_servers", (queue, context) -> textTag(Integer.toString(values.onlineServers())))
			.globalPlaceholder("registered_servers", (queue, context) -> textTag(Integer.toString(values.registeredServers())))
			.globalPlaceholder("total_servers", (queue, context) -> textTag(Integer.toString(values.registeredServers())))
			.globalPlaceholder("total_players", (queue, context) -> textTag(Integer.toString(values.totalPlayers())))
			.audiencePlaceholder(Player.class, "player_name", (player, queue, context) -> textTag(values.playerName(player)))
			.audiencePlaceholder(Player.class, "player_status", (player, queue, context) -> textTag(values.playerStatus(player, null)))
			.audiencePlaceholder(Player.class, "player_status_⏺", (player, queue, context) -> textTag(values.playerStatus(player, "⏺")))
			.audiencePlaceholder(Player.class, "player_group_name", (player, queue, context) -> textTag(values.playerGroupName(player)))
			.audiencePlaceholder(Player.class, "player_group_display", (player, queue, context) -> textTag(values.playerGroupDisplay(player)))
			.audiencePlaceholder(Player.class, "player_prefix", (player, queue, context) -> textTag(values.playerPrefix(player)))
			.audiencePlaceholder(Player.class, "player_suffix", (player, queue, context) -> textTag(values.playerSuffix(player)))
			.audiencePlaceholder(Player.class, "player_display", (player, queue, context) -> textTag(values.playerDisplay(player)));

		for (VelocityServerSelectorConfig.ServerDefinition definition : values.servers().values()) {
			String key = values.normalize(definition.backendName());
			if (key.isBlank()) {
				continue;
			}

			builder
				.globalPlaceholder("server_status_" + key, (queue, context) -> textTag(values.serverStatus(key)))
				.globalPlaceholder("server_online_" + key, (queue, context) -> textTag(Integer.toString(values.serverOnline(key))))
				.globalPlaceholder("server_max_" + key, (queue, context) -> textTag(Integer.toString(values.serverMax(key))))
				.globalPlaceholder("server_tps_" + key, (queue, context) -> textTag(values.serverTps(key)))
				.globalPlaceholder("server_version_" + key, (queue, context) -> textTag(values.serverVersion(key)))
				.globalPlaceholder("server_display_" + key, (queue, context) -> textTag(values.serverDisplay(key)))
				.globalPlaceholder("server_color_" + key, (queue, context) -> textTag(values.serverColor(key)))
				.globalPlaceholder("server_whitelist_" + key, (queue, context) -> textTag(Boolean.toString(values.serverWhitelist(key))));
		}

		return builder.build();
	}

	private Tag textTag(String value) {
		if (value == null || value.isEmpty()) {
			return Tag.inserting(Component.empty());
		}

		return Tag.inserting(MINI_MESSAGE.deserialize(value));
	}
}
