package dev.belikhun.luna.core.api.profile;

import java.util.Optional;
import java.util.UUID;

public interface PermissionService {
	boolean isAvailable();

	boolean hasPermission(UUID uniqueId, String permission);

	boolean hasPermission(String username, String permission);

	/**
	 * A permission whose absence is not a denial.
	 *
	 * {@link #hasPermission} answers false for a node nobody has set, which is the
	 * right reading for an admin verb. It is the wrong reading for one Bukkit would
	 * declare {@code PermissionDefault.TRUE} - a permission everybody has until it
	 * is taken away - and a plugin ported off Paper has to keep that distinction or
	 * it silently locks every player out of something they had.
	 *
	 * @param fallback what an unset node means
	 */
	default boolean hasPermissionOrDefault(UUID uniqueId, String permission, boolean fallback) {
		return hasPermission(uniqueId, permission);
	}

	String getGroupName(UUID uniqueId);

	String getGroupName(String username);

	String getGroupDisplayName(UUID uniqueId);

	String getGroupDisplayName(String username);

	String getPlayerPrefix(UUID uniqueId);

	String getPlayerPrefix(String username);

	String getPlayerSuffix(UUID uniqueId);

	String getPlayerSuffix(String username);

	Optional<LuckPermsUserInfo> getUserInfo(UUID uniqueId);

	Optional<LuckPermsUserInfo> getUserInfo(String username);

	boolean setUserPrimaryGroup(UUID uniqueId, String groupName);

	boolean clearUserPrimaryGroup(UUID uniqueId);
}
