package dev.belikhun.luna.legacy.tabbridge;

/** Nobody is vanished or disguised, which is the truth on a backend with no such plugin. */
final class NoopTabBridgePlayerStateSource<P> implements TabBridgePlayerStateSource<P> {
	@Override
	public TabBridgePlayerState resolve(P player) {
		return TabBridgePlayerState.DEFAULT;
	}
}
