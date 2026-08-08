package dev.belikhun.luna.tabbridge.neoforge.runtime;

public record NeoForgeTabBridgePlayerState(boolean vanished, boolean disguised) {
	public static final NeoForgeTabBridgePlayerState DEFAULT = new NeoForgeTabBridgePlayerState(false, false);

	public NeoForgeTabBridgePlayerState {
	}
}