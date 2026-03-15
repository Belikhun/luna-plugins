package dev.belikhun.luna.messenger.velocity.service;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.server.ServerDisplayResolver;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.api.ui.LunaPalette;
import net.kyori.adventure.text.Component;

import java.util.function.Supplier;

public final class DiscordLinkServerProtectionListener {
	private static final String NOTICE_ICON_COLOR = LunaPalette.WARNING_300;
	private static final String NOTICE_SERVER_COLOR = LunaPalette.INFO_300;

	private final LunaLogger logger;
	private final Supplier<VelocityMessengerConfig> configSupplier;
	private final Supplier<DiscordAccountLinkService> linkServiceSupplier;
	private final Supplier<ServerDisplayResolver> serverDisplayResolverSupplier;

	public DiscordLinkServerProtectionListener(
		LunaLogger logger,
		Supplier<VelocityMessengerConfig> configSupplier,
		Supplier<DiscordAccountLinkService> linkServiceSupplier,
		Supplier<ServerDisplayResolver> serverDisplayResolverSupplier
	) {
		this.logger = logger.scope("DiscordLinkProtection");
		this.configSupplier = configSupplier;
		this.linkServiceSupplier = linkServiceSupplier;
		this.serverDisplayResolverSupplier = serverDisplayResolverSupplier;
	}

	@Subscribe
	public void onServerPreConnect(ServerPreConnectEvent event) {
		if (!event.getResult().isAllowed()) {
			return;
		}

		VelocityMessengerConfig config = configSupplier.get();
		if (config == null || config.serverProtection() == null || !config.serverProtection().enabled()) {
			return;
		}

		String targetServerName = event.getResult().getServer()
			.map(registeredServer -> registeredServer.getServerInfo().getName())
			.orElse("");
		if (!config.serverProtection().requiresDiscordLink(targetServerName)) {
			return;
		}
		ServerDisplayResolver displayResolver = serverDisplayResolverSupplier.get();
		String targetServerDisplayRich = displayResolver == null
			? config.serverDisplay(targetServerName)
			: displayResolver.serverDisplay(targetServerName);
		String targetServerDisplayPlain = Formatters.stripFormats(targetServerDisplayRich);
		if (targetServerDisplayPlain.isBlank()) {
			targetServerDisplayPlain = targetServerName;
		}

		Player player = event.getPlayer();
		DiscordAccountLinkService linkService = linkServiceSupplier.get();
		if (linkService == null || !linkService.isDatabaseEnabled()) {
			denyAndNotify(event, player, targetServerDisplayRich, targetServerDisplayPlain, "", true);
			return;
		}
		if (linkService.isServerProtectionBypassed(player.getUniqueId())) {
			return;
		}

		if (linkService.findByPlayerId(player.getUniqueId()).isPresent()) {
			return;
		}

		DiscordAccountLinkService.BeginLinkResult beginLink = linkService.beginLink(player.getUniqueId(), player.getUsername());
		if (!beginLink.success()) {
			denyAndNotify(event, player, targetServerDisplayRich, targetServerDisplayPlain, "", beginLink.databaseDisabled());
			return;
		}

		denyAndNotify(event, player, targetServerDisplayRich, targetServerDisplayPlain, beginLink.code(), false);
	}

	private void denyAndNotify(
		ServerPreConnectEvent event,
		Player player,
		String serverDisplayRich,
		String serverDisplayPlain,
		String code,
		boolean databaseDisabled
	) {
		event.setResult(ServerPreConnectEvent.ServerResult.denied());
		boolean initialConnection = event.getPreviousServer() == null;

		String safeServerRich = (serverDisplayRich == null || serverDisplayRich.isBlank()) ? serverDisplayPlain : serverDisplayRich;
		String safeServerPlain = (serverDisplayPlain == null || serverDisplayPlain.isBlank()) ? "Unknown" : serverDisplayPlain;
		String noticePlain = "Cần liên kết Discord để vào máy chủ " + safeServerPlain + "!";
		String noticeRich = "<color:" + NOTICE_ICON_COLOR + ">⚠</color> <white>Cần liên kết Discord để vào máy chủ </white>"
			+ "<color:" + NOTICE_SERVER_COLOR + "><bold>" + safeServerRich + "</bold></color><white>!</white>";
		if (databaseDisabled) {
			String message = noticePlain + " Hệ thống hiện chưa sẵn sàng.";
			if (initialConnection) {
				player.disconnect(Component.text(message));
			} else {
				player.sendRichMessage(noticeRich);
				player.sendRichMessage("<red>❌ Hệ thống liên kết hiện chưa sẵn sàng. Vui lòng thử lại sau.</red>");
			}
			logger.warn("Chặn vào server yêu cầu Discord link nhưng database chưa sẵn sàng: player=" + player.getUsername() + " server=" + safeServerPlain);
			return;
		}

		if (initialConnection) {
			String disconnectMessage = noticePlain
				+ "\n" + DiscordLinkInstructionMessages.plainInstructionLine(code)
				+ "\nSau đó quay lại máy chủ và thử kết nối lại.";
			player.disconnect(Component.text(disconnectMessage));
			return;
		}

		player.sendRichMessage(noticeRich);
		DiscordLinkInstructionMessages.sendInstruction(player::sendRichMessage, code, null);
	}
}
