package dev.belikhun.luna.tabbridge.mc.runtime;

public record TabBridgePlayerState(boolean vanished, boolean disguised) {
	public static final TabBridgePlayerState DEFAULT = new TabBridgePlayerState(false, false);

	public TabBridgePlayerState {
	}
}