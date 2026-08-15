package dev.belikhun.luna.legacy.tabbridge;

/**
 * What a vanish or disguise plugin says about a player.
 *
 * Separate from the state the bridge reads for itself because nothing on a
 * backend agrees on it: invisibility is a potion effect vanilla owns, but vanish
 * and disguise are whatever plugin happens to be installed. The bridge takes
 * both as an answer rather than looking for them.
 */
public final class TabBridgePlayerState {
	public static final TabBridgePlayerState DEFAULT = new TabBridgePlayerState(false, false);

	private final boolean vanished;
	private final boolean disguised;

	public TabBridgePlayerState(boolean vanished, boolean disguised) {
		this.vanished = vanished;
		this.disguised = disguised;
	}

	public boolean vanished() {
		return vanished;
	}

	public boolean disguised() {
		return disguised;
	}
}
