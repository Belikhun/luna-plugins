package dev.belikhun.luna.core.paper.compat.voicechat;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import dev.belikhun.luna.core.api.compat.SimpleVoiceChatCompat;
import dev.belikhun.luna.core.api.placeholder.LunaImportedPlaceholderSupport;

import java.util.UUID;

final class PaperSimpleVoiceChatProvider implements SimpleVoiceChatCompat.Provider {
	private volatile VoicechatServerApi serverApi;

	void attach(VoicechatServerApi serverApi) {
		this.serverApi = serverApi;
	}

	void clear() {
		this.serverApi = null;
	}

	@Override
	public LunaImportedPlaceholderSupport.VoiceChatStatus playerStatus(UUID playerId) {
		if (playerId == null) {
			return LunaImportedPlaceholderSupport.VoiceChatStatus.UNKNOWN;
		}

		VoicechatServerApi currentApi = serverApi;
		if (currentApi == null) {
			return LunaImportedPlaceholderSupport.VoiceChatStatus.UNKNOWN;
		}

		VoicechatConnection connection = currentApi.getConnectionOf(playerId);
		if (connection == null) {
			return LunaImportedPlaceholderSupport.VoiceChatStatus.DISCONNECTED;
		}

		if (!connection.isInstalled()) {
			return LunaImportedPlaceholderSupport.VoiceChatStatus.NOT_INSTALLED;
		}

		if (connection.isDisabled()) {
			return LunaImportedPlaceholderSupport.VoiceChatStatus.MUTED;
		}

		if (connection.isConnected()) {
			return LunaImportedPlaceholderSupport.VoiceChatStatus.CONNECTED;
		}

		return LunaImportedPlaceholderSupport.VoiceChatStatus.UNKNOWN;
	}

	@Override
	public String playerGroup(UUID playerId) {
		if (playerId == null) {
			return "null";
		}

		VoicechatServerApi currentApi = serverApi;
		if (currentApi == null) {
			return "null";
		}

		VoicechatConnection connection = currentApi.getConnectionOf(playerId);
		if (connection == null) {
			return "null";
		}

		Group group = connection.getGroup();
		if (group == null) {
			return "main";
		}

		String groupName = group.getName();
		return groupName == null || groupName.isBlank() ? "main" : groupName;
	}
}
