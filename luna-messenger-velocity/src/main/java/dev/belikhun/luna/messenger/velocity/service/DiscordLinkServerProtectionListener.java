package dev.belikhun.luna.messenger.velocity.service;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.ui.LunaPalette;
import net.kyori.adventure.text.Component;

import java.util.function.Supplier;

public final class DiscordLinkServerProtectionListener {
	private static final String NOTICE_ICON_COLOR = LunaPalette.WARNING_300;
	private static final String NOTICE_SERVER_COLOR = LunaPalette.INFO_300;

	private final LunaLogger logger;
	private final Supplier<VelocityMessengerConfig> configSupplier;
	private final Supplier<DiscordAccountLinkService> linkServiceSupplier;

	public DiscordLinkServerProtectionListener(
		LunaLogger logger,
		Supplier<VelocityMessengerConfig> configSupplier,
		Supplier<DiscordAccountLinkService> linkServiceSupplier
	) {
		this.logger = logger.scope("DiscordLinkProtection");
		this.configSupplier = configSupplier;
		this.linkServiceSupplier = linkServiceSupplier;
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

		Player player = event.getPlayer();
		DiscordAccountLinkService linkService = linkServiceSupplier.get();
		if (linkService == null || !linkService.isDatabaseEnabled()) {
			denyAndNotify(event, player, targetServerName, "", true);
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
			denyAndNotify(event, player, targetServerName, "", beginLink.databaseDisabled());
			return;
		}

		denyAndNotify(event, player, targetServerName, beginLink.code(), false);
	}

	private void denyAndNotify(ServerPreConnectEvent event, Player player, String serverName, String code, boolean databaseDisabled) {
		event.setResult(ServerPreConnectEvent.ServerResult.denied());
		boolean initialConnection = event.getPreviousServer() == null;

		String noticePlain = "Bạn phải liên kết tài khoản minecraft của bạn với tài khoản discord để có thể tham gia máy chủ "
			+ serverName + "!";
		String noticeRich = "<color:" + NOTICE_ICON_COLOR + ">⚠</color> <white>Bạn phải liên kết tài khoản Minecraft với tài khoản Discord để có thể tham gia máy chủ </white>"
			+ "<color:" + NOTICE_SERVER_COLOR + "><bold>" + escape(serverName) + "</bold></color><white>!</white>";
		if (databaseDisabled) {
			String message = noticePlain + " Hệ thống liên kết Discord hiện chưa sẵn sàng, vui lòng thử lại sau.";
			if (initialConnection) {
				player.disconnect(Component.text(message));
			} else {
				player.sendRichMessage(noticeRich);
				player.sendRichMessage("<red>❌ Hệ thống liên kết Discord hiện chưa sẵn sàng, vui lòng thử lại sau.</red>");
			}
			logger.warn("Chặn vào server yêu cầu Discord link nhưng database chưa sẵn sàng: player=" + player.getUsername() + " server=" + serverName);
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

	private String escape(String value) {
		if (value == null) {
			return "";
		}
		return value.replace("<", "&lt;").replace(">", "&gt;");
	}
}
