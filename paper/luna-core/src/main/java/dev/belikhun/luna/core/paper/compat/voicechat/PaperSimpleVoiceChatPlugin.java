package dev.belikhun.luna.core.paper.compat.voicechat;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.PlayerStateChangedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import dev.belikhun.luna.core.api.logging.LunaLogger;

final class PaperSimpleVoiceChatPlugin implements VoicechatPlugin {
	private final PaperSimpleVoiceChatProvider provider;
	private final LunaLogger logger;

	PaperSimpleVoiceChatPlugin(PaperSimpleVoiceChatProvider provider, LunaLogger logger) {
		this.provider = provider;
		this.logger = logger;
	}

	@Override
	public String getPluginId() {
		return "lunacore";
	}

	@Override
	public void initialize(VoicechatApi api) {
		if (api instanceof VoicechatServerApi serverApi) {
			provider.attach(serverApi);
		}
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(VoicechatServerStartedEvent.class, event -> provider.attach(event.getVoicechat()));
		registration.registerEvent(VoicechatServerStoppedEvent.class, event -> provider.clear());
		registration.registerEvent(PlayerStateChangedEvent.class, this::logStateChange);
	}

	private void logStateChange(PlayerStateChangedEvent event) {
		VoicechatConnection connection = event.getConnection();
		String groupName = "-";
		if (connection != null) {
			Group group = connection.getGroup();
			if (group != null && group.getName() != null && !group.getName().isBlank()) {
				groupName = group.getName();
			}
		}

		logger.info(
			"Voicechat state đổi: player=" + event.getPlayerUuid()
				+ ", status=" + describeStatus(event, connection)
				+ ", installed=" + (connection != null && connection.isInstalled())
				+ ", disabled=" + event.isDisabled()
				+ ", group=" + groupName
		);
	}

	private String describeStatus(PlayerStateChangedEvent event, VoicechatConnection connection) {
		if (event.isDisconnected() || connection == null) {
			return "DISCONNECTED";
		}

		if (!connection.isInstalled()) {
			return "NOT_INSTALLED";
		}

		if (event.isDisabled()) {
			return "MUTED";
		}

		if (connection.isConnected()) {
			return "CONNECTED";
		}

		return "UNKNOWN";
	}
}
