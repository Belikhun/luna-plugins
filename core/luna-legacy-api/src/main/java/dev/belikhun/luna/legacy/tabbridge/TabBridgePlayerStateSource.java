package dev.belikhun.luna.legacy.tabbridge;

/** Where vanish and disguise come from, when anything on this backend knows. */
public interface TabBridgePlayerStateSource<P> {
	TabBridgePlayerState resolve(P player);
}
