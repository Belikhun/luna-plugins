package dev.belikhun.luna.legacy.permission;

import java.util.UUID;

/**
 * What a feature module asks about a player's permissions.
 *
 * The modern api's interface of the same name also carries the write half -
 * setting a primary group, clearing one - because on those platforms LuckPerms
 * is right there. On this line it is not: the only implementation is
 * {@link MirroredPermissionService}, a read-through mirror of the proxy's, and a
 * write would have to travel back over HTTP and then race the proxy's own copy.
 * So this is deliberately read-only, and a 1.12.2 feature that wants to *change*
 * a permission asks the console or the proxy instead.
 *
 * Every method has to answer without blocking: these are called from command
 * handlers on the server thread.
 */
public interface PermissionService {
	/** Whether this service can answer at all; a false here means fall back. */
	boolean isAvailable();

	/**
	 * An admin verb: false unless someone was granted it.
	 */
	boolean hasPermission(UUID uniqueId, String permission);

	/**
	 * A permission whose absence is not a denial.
	 *
	 * Bukkit calls this `PermissionDefault.TRUE` - a node everybody has until it
	 * is taken away. A plugin ported off Paper has to keep the distinction or it
	 * silently locks every player out of something they always had, and on this
	 * line there is a second reason: a snapshot that has not arrived yet reads as
	 * unset, so a cold cache must not read as "no".
	 *
	 * @param fallback what an unset node means
	 */
	boolean hasPermissionOrDefault(UUID uniqueId, String permission, boolean fallback);

	/** The player's primary group, or an empty string when unknown. */
	String groupName(UUID uniqueId);

	/** The player's chat prefix, or an empty string when unset. */
	String prefix(UUID uniqueId);

	/** The player's chat suffix, or an empty string when unset. */
	String suffix(UUID uniqueId);
}
