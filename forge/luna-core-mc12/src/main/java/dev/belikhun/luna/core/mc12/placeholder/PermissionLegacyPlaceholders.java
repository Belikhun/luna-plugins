package dev.belikhun.luna.core.mc12.placeholder;

import dev.belikhun.luna.core.mc12.placeholder.LegacyPlaceholderService.LegacyPlaceholderProvider;
import dev.belikhun.luna.legacy.permission.PermissionService;
import dev.belikhun.luna.legacy.placeholder.PlaceholderSnapshot;
import dev.belikhun.luna.legacy.string.Strings;

import net.minecraft.entity.player.EntityPlayerMP;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Rank, prefix and suffix, from wherever this backend gets its permissions.
 *
 * On 1.12.2 that is the mirror of the proxy's LuckPerms rather than LuckPerms
 * itself, but the identifiers are the ones a tab list already asks for
 * (`%luckperms_prefix%`), because the layout is written once for the whole fleet
 * and does not know which backend is answering.
 *
 * A cold or unavailable mirror answers with an empty string rather than null. The
 * difference matters: null means "not mine", which would send the identifier on
 * to the next provider and, finding none, leave the raw `%luckperms_prefix%`
 * printed in the tab list.
 */
public final class PermissionLegacyPlaceholders implements LegacyPlaceholderProvider {
	private final PermissionService permissions;

	public PermissionLegacyPlaceholders(PermissionService permissions) {
		this.permissions = permissions;
	}

	@Override
	public Set<String> namespaces() {
		return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList("luckperms", "vault", "player")));
	}

	@Override
	public void contributeSnapshot(
		LegacyPlaceholderService support,
		EntityPlayerMP player,
		PlaceholderSnapshot snapshot,
		Map<String, String> values
	) {
		if (!available()) {
			return;
		}

		String prefix = safe(permissions.prefix(player.getUniqueID()));
		String suffix = safe(permissions.suffix(player.getUniqueID()));
		String group = safe(permissions.groupName(player.getUniqueID()));

		values.put("luckperms_prefix", prefix);
		values.put("luckperms_suffix", suffix);
		values.put("luckperms_primary_group_name", group);
		values.put("vault_prefix", prefix);
		values.put("vault_suffix", suffix);
		values.put("player_group", group);
	}

	@Override
	public String resolve(
		LegacyPlaceholderService support,
		EntityPlayerMP player,
		String rawNamespace,
		String normalizedNamespace,
		String rawParams,
		String normalizedParams,
		PlaceholderSnapshot snapshot
	) {
		if (!available() || Strings.isBlank(normalizedParams)) {
			return null;
		}

		if ("prefix".equals(normalizedParams)) {
			return safe(permissions.prefix(player.getUniqueID()));
		}

		if ("suffix".equals(normalizedParams)) {
			return safe(permissions.suffix(player.getUniqueID()));
		}

		if ("primary_group_name".equals(normalizedParams) || "group".equals(normalizedParams)) {
			return safe(permissions.groupName(player.getUniqueID()));
		}

		return null;
	}

	private boolean available() {
		return permissions != null && permissions.isAvailable();
	}

	private String safe(String value) {
		return value == null ? "" : value;
	}
}
