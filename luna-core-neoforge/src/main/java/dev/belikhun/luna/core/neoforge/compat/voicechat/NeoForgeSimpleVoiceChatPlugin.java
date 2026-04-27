package dev.belikhun.luna.core.neoforge.compat.voicechat;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.PlayerStateChangedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import dev.belikhun.luna.core.api.compat.SimpleVoiceChatCompat;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.neoforge.logging.NeoForgeLunaLoggers;

@ForgeVoicechatPlugin
public final class NeoForgeSimpleVoiceChatPlugin implements VoicechatPlugin {
	private static final NeoForgeSimpleVoiceChatProvider PROVIDER = new NeoForgeSimpleVoiceChatProvider();
	private static final LunaLogger LOGGER = NeoForgeLunaLoggers.create("LunaCoreNeoForge", true).scope("VoiceChat");

	@Override
	public String getPluginId() {
		return "lunacore";
	}

	@Override
	public void initialize(VoicechatApi api) {
		if (api instanceof VoicechatServerApi serverApi) {
			PROVIDER.attach(serverApi);
			SimpleVoiceChatCompat.installProvider(PROVIDER);
		}
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(VoicechatServerStartedEvent.class, event -> {
			PROVIDER.attach(event.getVoicechat());
			SimpleVoiceChatCompat.installProvider(PROVIDER);
		});
		registration.registerEvent(PlayerStateChangedEvent.class, NeoForgeSimpleVoiceChatPlugin::logStateChange);
		registration.registerEvent(VoicechatServerStoppedEvent.class, event -> {
			PROVIDER.clear();
			SimpleVoiceChatCompat.clearProvider(PROVIDER);
		});
	}

	private static void logStateChange(PlayerStateChangedEvent event) {
		VoicechatConnection connection = event.getConnection();
		String groupName = "-";
		if (connection != null) {
			Group group = connection.getGroup();
			if (group != null && group.getName() != null && !group.getName().isBlank()) {
				groupName = group.getName();
			}
		}

		LOGGER.info(
			"Voicechat state đổi: player=" + event.getPlayerUuid()
				+ ", status=" + describeStatus(event, connection)
				+ ", installed=" + (connection != null && connection.isInstalled())
				+ ", disabled=" + event.isDisabled()
				+ ", group=" + groupName
		);
	}

	private static String describeStatus(PlayerStateChangedEvent event, VoicechatConnection connection) {
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
