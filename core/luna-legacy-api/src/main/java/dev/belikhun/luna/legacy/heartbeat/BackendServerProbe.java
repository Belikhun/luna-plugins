package dev.belikhun.luna.legacy.heartbeat;

import java.nio.file.Path;

/**
 * The handful of facts a heartbeat carries that only the running platform can
 * answer, and the two thread primitives collecting them needs.
 *
 * These are declared per platform rather than read reflectively off the server object
 * because the loaders do not agree on what the server object is called at runtime. On
 * this line the reason is sharper still: 1.12.2 is MCP-named at compile time and SRG-
 * named at runtime, so a lookup by readable method name does not merely fail on some
 * platforms, it fails on every live server while working perfectly in a dev
 * workspace.
 *
 * An implementation returns the fallback instead of throwing when the game version it
 * is running on no longer offers a value.
 */
public interface BackendServerProbe {
	/** Where the platform keeps mod configs; the forwarding secret is found here. */
	Path configDir();

	/** Run a task on the server thread. */
	void execute(Runnable task);

	/** Whether the calling thread is the server thread. */
	boolean isServerThread();

	/** Name of the server software, e.g. "Forge". */
	String serverModName();

	/** Game version, e.g. "1.12.2". */
	String gameVersion();

	int port();

	int onlinePlayers();

	int maxPlayers();

	String motd();

	boolean whitelistEnforced();

	/** Ticks per second as the platform measures it, or 20 when it cannot say. */
	double tps();

	/**
	 * Per-world chunk and entity counts.
	 *
	 * Empty rather than zeroed: a platform that has not been taught to walk its
	 * worlds has not measured them, and an empty list says that where a zero would
	 * claim an empty server.
	 */
	default java.util.List<ServerWorldStats> worlds() {
		return java.util.Collections.<ServerWorldStats>emptyList();
	}
}
