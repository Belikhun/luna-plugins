package dev.belikhun.luna.tabbridge.fabric.runtime;

public record FabricTabBridgePlayerState(boolean vanished, boolean disguised) {
	public static final FabricTabBridgePlayerState DEFAULT = new FabricTabBridgePlayerState(false, false);

	public FabricTabBridgePlayerState {
	}
}