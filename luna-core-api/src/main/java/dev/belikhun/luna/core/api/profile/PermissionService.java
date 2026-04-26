package dev.belikhun.luna.core.api.profile;

import java.util.Optional;
import java.util.UUID;

public interface PermissionService {
	boolean isAvailable();

	boolean hasPermission(UUID uniqueId, String permission);

	boolean hasPermission(String username, String permission);

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
