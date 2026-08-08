package dev.belikhun.luna.core.api.profile;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;


public final class LuckPermsService implements PermissionService {
	@Override
	public boolean isAvailable() {
		return luckPerms().isPresent();
	}

	@Override
	public boolean hasPermission(UUID uniqueId, String permission) {
		if (uniqueId == null || permission == null || permission.isBlank()) {
			return false;
		}

		return getUser(uniqueId)
			.map(user -> user.getCachedData().getPermissionData().checkPermission(permission).asBoolean())
			.orElse(false);
	}

	@Override
	public boolean hasPermission(String username, String permission) {
		if (username == null || username.isBlank() || permission == null || permission.isBlank()) {
			return false;
		}

		return getUser(username)
			.map(user -> user.getCachedData().getPermissionData().checkPermission(permission).asBoolean())
			.orElse(false);
	}

	public Optional<User> getUser(UUID uniqueId) {
		if (uniqueId == null) {
			return Optional.empty();
		}

		return luckPerms().map(api -> api.getUserManager().getUser(uniqueId));
	}

	public Optional<User> getUser(String username) {
		if (username == null || username.isBlank()) {
			return Optional.empty();
		}

		return luckPerms().map(api -> api.getUserManager().getUser(username));
	}

	public Optional<LuckPermsGroupInfo> getPrimaryGroupInfo(UUID uniqueId) {
		return getUser(uniqueId).flatMap(this::getPrimaryGroupInfo);
	}

	public Optional<LuckPermsGroupInfo> getPrimaryGroupInfo(String username) {
		return getUser(username).flatMap(this::getPrimaryGroupInfo);
	}

	public Optional<LuckPermsGroupInfo> getPrimaryGroupInfo(User user) {
		if (user == null) {
			return Optional.empty();
		}

		String groupName = normalize(primaryGroupName(user));
		if (groupName.isBlank()) {
			return Optional.empty();
		}

		String displayName = resolveGroupDisplayName(groupName).orElse(groupName);
		return Optional.of(new LuckPermsGroupInfo(groupName, displayName));
	}

	@Override
	public String getGroupName(UUID uniqueId) {
		return getUser(uniqueId)
			.map(this::primaryGroupName)
			.map(this::normalize)
			.orElse("");
	}

	@Override
	public String getGroupName(String username) {
		return getUser(username)
			.map(this::primaryGroupName)
			.map(this::normalize)
			.orElse("");
	}

	@Override
	public String getGroupDisplayName(UUID uniqueId) {
		return getPrimaryGroupInfo(uniqueId)
			.map(LuckPermsGroupInfo::displayName)
			.orElse("");
	}

	@Override
	public String getGroupDisplayName(String username) {
		return getPrimaryGroupInfo(username)
			.map(LuckPermsGroupInfo::displayName)
			.orElse("");
	}

	@Override
	public String getPlayerPrefix(UUID uniqueId) {
		return getUser(uniqueId)
			.map(this::userPrefix)
			.map(this::normalize)
			.orElse("");
	}

	@Override
	public String getPlayerPrefix(String username) {
		return getUser(username)
			.map(this::userPrefix)
			.map(this::normalize)
			.orElse("");
	}

	@Override
	public String getPlayerSuffix(UUID uniqueId) {
		return getUser(uniqueId)
			.map(this::userSuffix)
			.map(this::normalize)
			.orElse("");
	}

	@Override
	public String getPlayerSuffix(String username) {
		return getUser(username)
			.map(this::userSuffix)
			.map(this::normalize)
			.orElse("");
	}

	@Override
	public Optional<LuckPermsUserInfo> getUserInfo(UUID uniqueId) {
		return getUser(uniqueId).map(this::toUserInfo);
	}

	@Override
	public Optional<LuckPermsUserInfo> getUserInfo(String username) {
		return getUser(username).map(this::toUserInfo);
	}

	@Override
	public boolean setUserPrimaryGroup(UUID uniqueId, String groupName) {
		if (uniqueId == null) {
			return false;
		}

		String normalizedGroup = normalizeGroupName(groupName);
		if (normalizedGroup.isBlank()) {
			return false;
		}

		Optional<LuckPerms> apiOptional = luckPerms();
		if (apiOptional.isEmpty()) {
			return false;
		}

		LuckPerms api = apiOptional.get();
		if (api.getGroupManager().getGroup(normalizedGroup) == null) {
			return false;
		}

		Optional<User> userOptional = resolveUser(api, uniqueId);
		if (userOptional.isEmpty()) {
			return false;
		}

		User user = userOptional.get();
		user.data().clear(node -> node instanceof InheritanceNode);
		user.data().add(InheritanceNode.builder(normalizedGroup).build());
		api.getUserManager().saveUser(user).join();
		return true;
	}

	@Override
	public boolean clearUserPrimaryGroup(UUID uniqueId) {
		if (uniqueId == null) {
			return false;
		}

		Optional<LuckPerms> apiOptional = luckPerms();
		if (apiOptional.isEmpty()) {
			return false;
		}

		LuckPerms api = apiOptional.get();
		Optional<User> userOptional = resolveUser(api, uniqueId);
		if (userOptional.isEmpty()) {
			return false;
		}

		User user = userOptional.get();
		user.data().clear(node -> node instanceof InheritanceNode);
		api.getUserManager().saveUser(user).join();
		return true;
	}

	private LuckPermsUserInfo toUserInfo(User user) {
		LuckPermsGroupInfo groupInfo = getPrimaryGroupInfo(user)
			.orElse(new LuckPermsGroupInfo("", ""));
		return new LuckPermsUserInfo(
			groupInfo.name(),
			groupInfo.displayName(),
			normalize(userPrefix(user)),
			normalize(userSuffix(user))
		);
	}

	private String primaryGroupName(User user) {
		String groupName = normalize(user.getPrimaryGroup());
		if (!groupName.isBlank()) {
			return groupName;
		}

		CachedMetaData metaData = user.getCachedData().getMetaData();
		return normalize(metaData == null ? null : metaData.getPrimaryGroup());
	}

	private String userPrefix(User user) {
		CachedMetaData metaData = user.getCachedData().getMetaData();
		return metaData == null ? "" : normalize(metaData.getPrefix());
	}

	private String userSuffix(User user) {
		CachedMetaData metaData = user.getCachedData().getMetaData();
		return metaData == null ? "" : normalize(metaData.getSuffix());
	}

	private Optional<String> resolveGroupDisplayName(String groupName) {
		return luckPerms()
			.map(api -> api.getGroupManager().getGroup(groupName))
			.map(group -> groupDisplayName(group, groupName));
	}

	private String groupDisplayName(Group group, String fallbackGroupName) {
		if (group == null) {
			return fallbackGroupName;
		}

		String displayName = normalize(group.getDisplayName());
		if (displayName.isBlank()) {
			return normalize(group.getName());
		}
		return displayName;
	}

	private Optional<LuckPerms> luckPerms() {
		try {
			return Optional.ofNullable(LuckPermsProvider.get());
		} catch (IllegalStateException | NoClassDefFoundError ignored) {
			return Optional.empty();
		}
	}

	private Optional<User> resolveUser(LuckPerms api, UUID uniqueId) {
		if (api == null || uniqueId == null) {
			return Optional.empty();
		}

		User loaded = api.getUserManager().getUser(uniqueId);
		if (loaded != null) {
			return Optional.of(loaded);
		}

		try {
			return Optional.ofNullable(api.getUserManager().loadUser(uniqueId).join());
		} catch (Exception ignored) {
			return Optional.empty();
		}
	}

	private String normalizeGroupName(String groupName) {
		String value = normalize(groupName).trim();
		return value.toLowerCase(Locale.ROOT);
	}

	private String normalize(String value) {
		return value == null ? "" : value;
	}
}
