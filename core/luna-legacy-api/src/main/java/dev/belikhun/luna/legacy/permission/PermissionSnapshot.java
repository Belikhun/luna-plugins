package dev.belikhun.luna.legacy.permission;

import dev.belikhun.luna.legacy.string.Strings;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One player's permissions as LuckPerms on the proxy resolved them.
 *
 * This is a *resolved* set, not an editable one: inheritance has already been walked,
 * contexts have already been applied, and what is left is a flat map of node to
 * boolean. That is what makes local evaluation possible on a backend with no
 * LuckPerms of its own - the 1.12.2 line, where no build of it exists.
 *
 * Wildcards survive resolution (`luna.admin.*` stays a key), so this class still has
 * to answer `luna.admin.kick` from them, and the ordering it uses is the same one the
 * pumpkin port settled on: **the most specific key that matches wins**. `luna.admin.kick`
 * beats `luna.admin.*`, which beats `luna.*`, which beats `*`. There is at most one
 * key at each level, so there are no ties to break - a denial only "wins" because
 * something set it more specifically, which is the behaviour an operator expects when
 * they grant a group `luna.admin.*` and take one verb back off one person.
 */
public final class PermissionSnapshot {
	private final UUID uniqueId;
	private final String username;
	private final String primaryGroup;
	private final String prefix;
	private final String suffix;
	private final List<String> groups;
	private final Map<String, Boolean> permissions;
	private final long fetchedAtEpochMillis;

	public PermissionSnapshot(
		UUID uniqueId,
		String username,
		String primaryGroup,
		String prefix,
		String suffix,
		List<String> groups,
		Map<String, Boolean> permissions,
		long fetchedAtEpochMillis
	) {
		this.uniqueId = uniqueId;
		this.username = Strings.trimmed(username);
		this.primaryGroup = Strings.trimmed(primaryGroup);
		this.prefix = prefix == null ? "" : prefix;
		this.suffix = suffix == null ? "" : suffix;
		this.groups = Collections.unmodifiableList(new ArrayList<String>(groups == null ? new ArrayList<String>() : groups));
		this.permissions = Collections.unmodifiableMap(normalizeKeys(permissions));
		this.fetchedAtEpochMillis = fetchedAtEpochMillis;
	}

	/**
	 * Does this player have the node?
	 *
	 * Never returns UNDEFINED for a node a wildcard covers - only for one nothing in
	 * the snapshot speaks to at all.
	 */
	public Tristate check(String permission) {
		if (Strings.isBlank(permission)) {
			return Tristate.UNDEFINED;
		}

		String node = permission.trim().toLowerCase(Locale.ROOT);
		Boolean exact = permissions.get(node);

		if (exact != null) {
			return Tristate.of(exact);
		}

		// walk up the dotted path, most specific first: a.b.c -> a.b.* -> a.* -> *
		int cut = node.lastIndexOf('.');

		while (cut > 0) {
			Boolean wildcard = permissions.get(node.substring(0, cut) + ".*");

			if (wildcard != null) {
				return Tristate.of(wildcard);
			}

			cut = node.lastIndexOf('.', cut - 1);
		}

		return Tristate.of(permissions.get("*"));
	}

	public UUID uniqueId() {
		return uniqueId;
	}

	public String username() {
		return username;
	}

	public String primaryGroup() {
		return primaryGroup;
	}

	public String prefix() {
		return prefix;
	}

	public String suffix() {
		return suffix;
	}

	public List<String> groups() {
		return groups;
	}

	public Map<String, Boolean> permissions() {
		return permissions;
	}

	public long fetchedAtEpochMillis() {
		return fetchedAtEpochMillis;
	}

	public boolean inGroup(String groupName) {
		if (Strings.isBlank(groupName)) {
			return false;
		}

		String wanted = groupName.trim().toLowerCase(Locale.ROOT);

		for (String group : groups) {
			if (wanted.equals(group.trim().toLowerCase(Locale.ROOT))) {
				return true;
			}
		}

		return false;
	}

	/** Older than this many milliseconds, measured against the clock that fetched it. */
	public boolean olderThan(long ageMillis) {
		return System.currentTimeMillis() - fetchedAtEpochMillis > ageMillis;
	}

	/**
	 * LuckPerms stores node keys lowercased, but a snapshot arrives over the wire and
	 * nothing on the way guarantees that survived - so lookups are case-insensitive on
	 * both sides rather than on trust.
	 */
	private static Map<String, Boolean> normalizeKeys(Map<String, Boolean> source) {
		Map<String, Boolean> out = new LinkedHashMap<String, Boolean>();

		if (source == null) {
			return out;
		}

		for (Map.Entry<String, Boolean> entry : source.entrySet()) {
			if (Strings.isBlank(entry.getKey()) || entry.getValue() == null) {
				continue;
			}

			out.put(entry.getKey().trim().toLowerCase(Locale.ROOT), entry.getValue());
		}

		return out;
	}

	/** The node names this snapshot carries, for a diagnostic listing. */
	public Set<String> nodeNames() {
		return Collections.unmodifiableSet(new LinkedHashSet<String>(permissions.keySet()));
	}
}
