package dev.belikhun.luna.vault.api;

import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;

public final class VaultChannels {
	public static final PluginMessageChannel RPC = PluginMessageChannel.of("luna:vault_rpc");
	public static final PluginMessageChannel CACHE_SYNC = PluginMessageChannel.of("luna:vault_cache_sync");

	private VaultChannels() {
	}
}
