package dev.belikhun.luna.core.fabric.compat.voicechat;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.PlayerStateChangedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import dev.belikhun.luna.core.api.compat.SimpleVoiceChatCompat;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.fabric.logging.FabricLunaLoggers;

/**
 * What tells {@code %luna_voicechat_status%} and {@code %luna_voicechat_group%}
 * what to say.
 *
 * Simple Voice Chat finds this through the {@code voicechat} entrypoint in
 * fabric.mod.json, which is the one difference from the NeoForge copy - there the
 * same class is found by an {@code @ForgeVoicechatPlugin} annotation. That
 * entrypoint is only ever read by the voice chat mod itself, so on a server
 * without it nothing here is loaded and the two placeholders answer the same
 * "unknown" they answered before.
 */
public final class FabricSimpleVoiceChatPlugin implements VoicechatPlugin {
	private static final FabricSimpleVoiceChatProvider PROVIDER = new FabricSimpleVoiceChatProvider();
	private static final LunaLogger LOGGER = FabricLunaLoggers.create("LunaCore", true).scope("VoiceChat");

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
		registration.registerEvent(PlayerStateChangedEvent.class, FabricSimpleVoiceChatPlugin::logStateChange);
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
