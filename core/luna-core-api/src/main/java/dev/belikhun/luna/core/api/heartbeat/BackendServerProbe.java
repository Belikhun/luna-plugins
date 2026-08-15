package dev.belikhun.luna.core.api.heartbeat;

import java.nio.file.Path;

/**
 * The handful of facts a heartbeat carries that only the running platform can
 * answer, and the two thread primitives collecting them needs.
 *
 * These are declared per platform rather than read reflectively off the server
 * object because the loaders do not agree on what the server object is called at
 * runtime: NeoForge keeps Mojang's names, Fabric remaps to intermediary, so a
 * lookup by readable method name works on one and silently fails on the other.
 * An implementation returns the fallback instead of throwing when the game
 * version it is running on no longer offers a value.
 */
public interface BackendServerProbe {
	/** Where the platform keeps mod configs; the forwarding secret is found here. */
	Path configDir();

	/** Run a task on the server thread. */
	void execute(Runnable task);

	/** Whether the calling thread is the server thread. */
	boolean isServerThread();

	/** Name of the server software, e.g. "NeoForge" or "Fabric". */
	String serverModName();

	/** Game version, e.g. "1.21.1". */
	String gameVersion();

	int port();

	int onlinePlayers();

	int maxPlayers();

	String motd();

	boolean whitelistEnforced();

	/**
	 * Ticks per second as the platform measures it, or 20 when it cannot say.
	 * Spark answers this far better when it is installed, and the publisher
	 * prefers it; this is the fallback.
	 */
	double tps();

	/**
	 * Per-world chunk and entity counts.
	 *
	 * Defaulted to nothing rather than to zeroes: a platform that has not been
	 * taught to walk its worlds has not measured them, and an empty list says
	 * that, where a zero would claim an empty server.
	 */
	default java.util.List<ServerWorldStats> worlds() {
		return java.util.List.of();
	}
}
