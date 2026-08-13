package dev.belikhun.luna.core.velocity.permissions;

import dev.belikhun.luna.core.api.heartbeat.HeartbeatFormCodec;
import dev.belikhun.luna.core.api.http.HttpRequest;
import dev.belikhun.luna.core.api.http.HttpResponse;
import dev.belikhun.luna.core.api.http.LunaJson;
import dev.belikhun.luna.core.api.http.RequestAuthorizer;
import dev.belikhun.luna.core.api.http.Router;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.velocity.players.VelocityPlayerRecordStore;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.matcher.NodeMatcher;
import net.luckperms.api.node.types.DisplayNameNode;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PrefixNode;
import net.luckperms.api.node.types.SuffixNode;
import net.luckperms.api.node.types.WeightNode;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.query.QueryOptions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * LuckPerms management over HTTP: groups, their nodes and meta, and each
 * player's memberships — the API half of the console's permission editor.
 *
 * The proxy is the right place for this: every backend and the proxy share one
 * LuckPerms MariaDB storage, so a change made here through the LuckPerms API is
 * saved once and pushed to every server via the messaging service. Nothing in
 * this class touches the database directly.
 */
public final class VelocityPermissionHttpEndpoints {
	/** How long a LuckPerms storage round trip may take before the caller times out. */
	private static final long LUCKPERMS_TIMEOUT_MILLIS = 8000L;

	/** Group names LuckPerms cannot function without; deletion is refused. */
	private static final String DEFAULT_GROUP = "default";

	private final LunaLogger logger;
	private final VelocityPlayerRecordStore recordStore;
	private final RequestAuthorizer authorizer;

	public VelocityPermissionHttpEndpoints(
		LunaLogger logger,
		VelocityPlayerRecordStore recordStore,
		RequestAuthorizer authorizer
	) {
		this.logger = logger.scope("PermissionHttp");
		this.recordStore = recordStore;
		this.authorizer = authorizer;
	}

	public void register(Router router) {
		router.get("/permissions/groups", request -> withLuckPerms(request, "list groups", (api, startedAt) -> {
			// Refresh from storage so groups created elsewhere (in-game, other
			// servers) appear without a proxy restart.
			join(api.getGroupManager().loadAllGroups());

			Map<String, Long> memberCounts = countGroupMembers(api);
			List<Map<String, Object>> groups = new ArrayList<>();

			for (Group group : api.getGroupManager().getLoadedGroups()) {
				groups.add(buildGroupSummary(group, memberCounts));
			}

			groups.sort((left, right) -> {
				long weightLeft = longOf(left.get("weight"));
				long weightRight = longOf(right.get("weight"));

				if (weightLeft != weightRight) {
					return Long.compare(weightRight, weightLeft);
				}

				return String.valueOf(left.get("name")).compareTo(String.valueOf(right.get("name")));
			});

			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("generatedAtEpochMillis", System.currentTimeMillis());
			payload.put("groups", groups);
			return LunaJson.envelope(200, payload, startedAt);
		}));

		router.get("/permissions/groups/{group}", request -> withGroup(request, "inspect group", (api, group, startedAt) -> {
			Map<String, Long> memberCounts = countGroupMembers(api);
			Map<String, Object> payload = buildGroupSummary(group, memberCounts);

			payload.put("nodes", buildNodes(group.getNodes()));
			payload.put("members", groupMembers(api, group.getName()));
			return LunaJson.envelope(200, payload, startedAt);
		}));

		router.post("/permissions/groups", request -> withLuckPerms(request, "create group", (api, startedAt) -> {
			Map<String, String> body = HeartbeatFormCodec.decode(request.body());
			String name = normalizeGroupName(body.getOrDefault("name", ""));

			if (name.isBlank()) {
				return LunaJson.error(400, "name is required");
			}

			if (api.getGroupManager().getGroup(name) != null) {
				return LunaJson.error(409, "group already exists: " + name);
			}

			Group group = join(api.getGroupManager().createAndLoadGroup(name));

			int weight = parseInt(body.getOrDefault("weight", ""), 0);
			if (weight != 0) {
				group.data().add(WeightNode.builder(weight).build());
			}

			String displayName = body.getOrDefault("displayName", "").trim();
			if (!displayName.isBlank()) {
				group.data().add(DisplayNameNode.builder(displayName).build());
			}

			saveGroup(api, group);
			logger.audit("Console tạo permission group: " + name);

			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("name", name);
			return LunaJson.envelope(200, payload, startedAt);
		}));

		router.delete("/permissions/groups/{group}", request -> withGroup(request, "delete group", (api, group, startedAt) -> {
			if (DEFAULT_GROUP.equals(group.getName())) {
				return LunaJson.error(400, "the default group cannot be deleted");
			}

			join(api.getGroupManager().deleteGroup(group));
			pushUpdate(api);
			logger.audit("Console xoá permission group: " + group.getName());

			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("name", group.getName());
			return LunaJson.envelope(200, payload, startedAt);
		}));

		router.post("/permissions/groups/{group}/nodes", request -> withGroup(request, "edit group nodes", (api, group, startedAt) -> {
			Map<String, String> body = HeartbeatFormCodec.decode(request.body());
			HttpResponse failure = applyNodeAction(group.data()::add, matcher -> group.data().clear(matcher), body);

			if (failure != null) {
				return failure;
			}

			saveGroup(api, group);
			logger.audit("Console sửa node của group " + group.getName() + ": "
				+ body.getOrDefault("action", "") + " " + body.getOrDefault("key", ""));

			return LunaJson.envelope(200, nodesPayload(group.getNodes()), startedAt);
		}));

		router.post("/permissions/groups/{group}/meta", request -> withGroup(request, "edit group meta", (api, group, startedAt) -> {
			Map<String, String> body = HeartbeatFormCodec.decode(request.body());
			String field = body.getOrDefault("field", "").trim().toLowerCase(Locale.ROOT);
			String value = body.getOrDefault("value", "").trim();
			int priority = parseInt(body.getOrDefault("priority", ""), currentMetaPriority(group, field));

			switch (field) {
				case "weight" -> {
					group.data().clear(NodeType.WEIGHT::matches);
					int weight = parseInt(value, Integer.MIN_VALUE);
					if (weight != Integer.MIN_VALUE) {
						group.data().add(WeightNode.builder(weight).build());
					}
				}
				case "prefix" -> {
					group.data().clear(NodeType.PREFIX::matches);
					if (!value.isBlank()) {
						group.data().add(PrefixNode.builder(value, priority).build());
					}
				}
				case "suffix" -> {
					group.data().clear(NodeType.SUFFIX::matches);
					if (!value.isBlank()) {
						group.data().add(SuffixNode.builder(value, priority).build());
					}
				}
				case "displayname" -> {
					group.data().clear(NodeType.DISPLAY_NAME::matches);
					if (!value.isBlank()) {
						group.data().add(DisplayNameNode.builder(value).build());
					}
				}
				default -> {
					return LunaJson.error(400, "unknown meta field: " + field);
				}
			}

			saveGroup(api, group);
			logger.audit("Console sửa meta của group " + group.getName() + ": " + field + " = " + value);

			Map<String, Long> memberCounts = countGroupMembers(api);
			return LunaJson.envelope(200, buildGroupSummary(group, memberCounts), startedAt);
		}));

		router.get("/permissions/users/{player}", request -> withUser(request, "inspect user", (api, user, startedAt) -> {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("uuid", user.getUniqueId().toString());
			payload.put("username", user.getUsername() == null ? "" : user.getUsername());
			payload.put("primaryGroup", user.getPrimaryGroup());
			payload.put("groups", userGroups(user));
			payload.put("nodes", buildNodes(user.getNodes()));
			return LunaJson.envelope(200, payload, startedAt);
		}));

		router.get("/permissions/resolve/{player}", request -> withUser(request, "resolve user", (api, user, startedAt) -> {
			String server = request.queryParam("server", "").trim();
			QueryOptions options = queryOptionsFor(server);

			return resolvedSnapshot(user, options);
		}));

		router.post("/permissions/users/{player}/nodes", request -> withUser(request, "edit user nodes", (api, user, startedAt) -> {
			Map<String, String> body = HeartbeatFormCodec.decode(request.body());
			HttpResponse failure = applyNodeAction(user.data()::add, matcher -> user.data().clear(matcher), body);

			if (failure != null) {
				return failure;
			}

			saveUser(api, user);
			logger.audit("Console sửa node của user " + user.getUsername() + ": "
				+ body.getOrDefault("action", "") + " " + body.getOrDefault("key", ""));

			return LunaJson.envelope(200, nodesPayload(user.getNodes()), startedAt);
		}));

		router.post("/permissions/users/{player}/groups", request -> withUser(request, "edit user groups", (api, user, startedAt) -> {
			Map<String, String> body = HeartbeatFormCodec.decode(request.body());
			String action = body.getOrDefault("action", "").trim().toLowerCase(Locale.ROOT);
			String groupName = normalizeGroupName(body.getOrDefault("group", ""));

			if (groupName.isBlank()) {
				return LunaJson.error(400, "group is required");
			}

			if (api.getGroupManager().getGroup(groupName) == null) {
				return LunaJson.error(404, "group not found: " + groupName);
			}

			switch (action) {
				case "add" -> user.data().add(InheritanceNode.builder(groupName).build());
				case "remove" -> {
					String cleared = groupName;
					user.data().clear(node -> node instanceof InheritanceNode inheritance
						&& inheritance.getGroupName().equals(cleared));
				}
				case "set" -> {
					// The editor's "make primary": one inheritance node, nothing else.
					user.data().clear(node -> node instanceof InheritanceNode);
					user.data().add(InheritanceNode.builder(groupName).build());
				}
				default -> {
					return LunaJson.error(400, "unknown action: " + action);
				}
			}

			saveUser(api, user);
			logger.audit("Console sửa nhóm của user " + user.getUsername() + ": " + action + " " + groupName);

			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("primaryGroup", user.getPrimaryGroup());
			payload.put("groups", userGroups(user));
			return LunaJson.envelope(200, payload, startedAt);
		}));
	}

	// ------------------------------------------------------------------ shared route shapes

	private HttpResponse withLuckPerms(HttpRequest request, String what, LuckPermsAction handler) {
		if (!authorizer.authorized(request)) {
			logger.warn("Từ chối truy vấn /permissions (" + what + ") do sai token hoặc thiếu token.");
			return authorizer.unauthorized();
		}

		long startedAt = System.nanoTime();
		Optional<LuckPerms> api = luckPerms();

		if (api.isEmpty()) {
			return LunaJson.error(503, "LuckPerms is not available on the proxy");
		}

		try {
			return handler.apply(api.get(), startedAt);
		} catch (RuntimeException exception) {
			logger.error("Thao tác LuckPerms thất bại (" + what + "): " + exception.getMessage(), exception);
			return LunaJson.error(500, "LuckPerms operation failed: " + rootMessage(exception));
		}
	}

	private HttpResponse withGroup(HttpRequest request, String what, GroupAction handler) {
		return withLuckPerms(request, what, (api, startedAt) -> {
			String name = normalizeGroupName(request.pathParam("group", ""));
			Group group = api.getGroupManager().getGroup(name);

			if (group == null) {
				group = join(api.getGroupManager().loadGroup(name)).orElse(null);
			}

			if (group == null) {
				return LunaJson.error(404, "group not found: " + name);
			}

			return handler.apply(api, group, startedAt);
		});
	}

	private HttpResponse withUser(HttpRequest request, String what, UserAction handler) {
		return withLuckPerms(request, what, (api, startedAt) -> {
			String reference = request.pathParam("player", "").trim();
			Optional<UUID> uuid = resolveUuid(api, reference);

			if (uuid.isEmpty()) {
				return LunaJson.error(404, "player not found: " + reference);
			}

			User user = join(api.getUserManager().loadUser(uuid.get()));

			if (user == null) {
				return LunaJson.error(404, "LuckPerms has no data for: " + reference);
			}

			return handler.apply(api, user, startedAt);
		});
	}

	@FunctionalInterface
	private interface LuckPermsAction {
		HttpResponse apply(LuckPerms api, long startedAt);
	}

	@FunctionalInterface
	private interface GroupAction {
		HttpResponse apply(LuckPerms api, Group group, long startedAt);
	}

	@FunctionalInterface
	private interface UserAction {
		HttpResponse apply(LuckPerms api, User user, long startedAt);
	}

	// ------------------------------------------------------------------ node editing

	/**
	 * Apply an add/remove node action described by a form body to a node holder.
	 * Returns an error response, or {@code null} when the action was applied.
	 */
	private HttpResponse applyNodeAction(
		java.util.function.Consumer<Node> add,
		java.util.function.Consumer<java.util.function.Predicate<? super Node>> clear,
		Map<String, String> body
	) {
		String action = body.getOrDefault("action", "").trim().toLowerCase(Locale.ROOT);
		String key = body.getOrDefault("key", "").trim();

		if (key.isBlank()) {
			return LunaJson.error(400, "key is required");
		}

		ImmutableContextSet contexts = parseContexts(body);

		if ("add".equals(action)) {
			boolean value = !"false".equalsIgnoreCase(body.getOrDefault("value", "true"));
			long expirySeconds = parseLong(body.getOrDefault("expirySeconds", ""), 0L);

			net.luckperms.api.node.NodeBuilder<?, ?> builder = Node.builder(key).value(value);

			if (expirySeconds > 0L) {
				builder.expiry(Duration.ofSeconds(expirySeconds));
			}

			if (!contexts.isEmpty()) {
				builder.context(contexts);
			}

			add.accept(builder.build());
			return null;
		}

		if ("remove".equals(action)) {
			// Remove by key + contexts, ignoring value/expiry: the editor removes the
			// row it displays, and a stale expiry must not make the removal miss.
			clear.accept(node -> node.getKey().equalsIgnoreCase(key)
				&& node.getContexts().equals(contexts));
			return null;
		}

		return LunaJson.error(400, "unknown action: " + action);
	}

	/** Context entries arrive as {@code context.<key>=<value>} form fields. */
	private ImmutableContextSet parseContexts(Map<String, String> body) {
		ImmutableContextSet.Builder builder = ImmutableContextSet.builder();

		for (Map.Entry<String, String> entry : body.entrySet()) {
			if (!entry.getKey().startsWith("context.")) {
				continue;
			}

			String contextKey = entry.getKey().substring("context.".length()).trim();
			String contextValue = entry.getValue() == null ? "" : entry.getValue().trim();

			if (!contextKey.isBlank() && !contextValue.isBlank()) {
				builder.add(contextKey, contextValue);
			}
		}

		return builder.build();
	}

	// ------------------------------------------------------------------ payload shapes

	private Map<String, Object> buildGroupSummary(Group group, Map<String, Long> memberCounts) {
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("name", group.getName());
		summary.put("displayName", group.getDisplayName() == null ? "" : group.getDisplayName());
		summary.put("weight", group.getWeight().orElse(0));
		summary.put("prefix", metaValue(group, NodeType.PREFIX::matches));
		summary.put("suffix", metaValue(group, NodeType.SUFFIX::matches));
		summary.put("parents", parentGroups(group));
		summary.put("nodeCount", group.getNodes().size());
		summary.put("memberCount", memberCounts.getOrDefault(group.getName(), 0L));
		return summary;
	}

	// ------------------------------------------------------------------ the resolved mirror

	/**
	 * A backend's view of one player, already resolved.
	 *
	 * The editor routes above return what a user *has set* - their own nodes, their own
	 * group memberships - because that is what an editor edits. A backend asking
	 * "may this player do X" needs the opposite: inheritance walked, contexts applied,
	 * and a flat map it can answer from without a round trip per check. That is what
	 * this returns, and it exists for the 1.12.2 line, where no build of LuckPerms
	 * exists to ask locally.
	 *
	 * **Form-encoded, not JSON**, because the caller is `luna-legacy-api` on Java 8 and
	 * carries no JSON parser. `HeartbeatFormCodec` is already the shared encoding
	 * between a backend and this proxy; `PermissionSnapshotCodec` on the other side
	 * decodes exactly these field names.
	 */
	private HttpResponse resolvedSnapshot(User user, QueryOptions options) {
		CachedPermissionData permissionData = user.getCachedData().getPermissionData(options);
		CachedMetaData metaData = user.getCachedData().getMetaData(options);

		Map<String, String> out = new LinkedHashMap<>();
		out.put("protocol", "1");
		out.put("uuid", user.getUniqueId().toString());
		out.put("username", user.getUsername() == null ? "" : user.getUsername());
		out.put("primaryGroup", metaData.getPrimaryGroup() == null ? user.getPrimaryGroup() : metaData.getPrimaryGroup());
		out.put("prefix", metaData.getPrefix() == null ? "" : metaData.getPrefix());
		out.put("suffix", metaData.getSuffix() == null ? "" : metaData.getSuffix());
		out.put("generatedAtEpochMillis", String.valueOf(System.currentTimeMillis()));

		// the inherited set, not the user's own nodes: a backend needs the groups the
		// player effectively is in, including the ones reached through another group
		List<String> groups = new ArrayList<>();
		for (Group group : user.getInheritedGroups(options)) {
			groups.add(group.getName());
		}

		out.put("groupCount", String.valueOf(groups.size()));
		for (int index = 0; index < groups.size(); index += 1) {
			out.put("group." + index, groups.get(index));
		}

		// sorted so two fetches of an unchanged player produce identical bytes, which
		// is what lets the fixtures under luna-legacy-api's tests mean anything
		Map<String, Boolean> permissions = new TreeMap<>(permissionData.getPermissionMap());
		out.put("permissionCount", String.valueOf(permissions.size()));

		int index = 0;
		for (Map.Entry<String, Boolean> entry : permissions.entrySet()) {
			out.put("perm." + index + ".key", entry.getKey());
			out.put("perm." + index + ".value", String.valueOf(entry.getValue()));
			index += 1;
		}

		byte[] body = HeartbeatFormCodec.encode(out);
		return HttpResponse.bytes(200, body, "application/x-www-form-urlencoded; charset=utf-8");
	}

	/**
	 * Resolve as the named backend would see it.
	 *
	 * LuckPerms scopes nodes by a `server` context, and this proxy's own context is
	 * "the proxy" - so resolving without an override would hand a backend the proxy's
	 * permissions and quietly drop every node an operator scoped to that server.
	 *
	 * **Not `QueryOptions.nonContextual()` for the unnamed case**, which reads like the
	 * neutral choice and is the opposite: non-contextual mode ignores contexts
	 * altogether, so it returns every node including ones scoped to *other* servers. A
	 * caller that names no server gets contextual mode with an empty set instead -
	 * context-free nodes only, which is the answer that cannot over-grant.
	 */
	private QueryOptions queryOptionsFor(String serverName) {
		if (serverName.isBlank()) {
			return QueryOptions.contextual(ImmutableContextSet.empty());
		}

		return QueryOptions.contextual(ImmutableContextSet.of("server", serverName.toLowerCase(Locale.ROOT)));
	}

	private Map<String, Object> nodesPayload(Collection<Node> nodes) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("nodes", buildNodes(nodes));
		return payload;
	}

	private List<Map<String, Object>> buildNodes(Collection<Node> nodes) {
		List<Map<String, Object>> out = new ArrayList<>(nodes.size());

		for (Node node : nodes) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("key", node.getKey());
			entry.put("value", node.getValue());
			entry.put("type", node.getType().name().toLowerCase(Locale.ROOT));
			entry.put("expiryEpochMillis", node.getExpiry() == null ? 0L : node.getExpiry().toEpochMilli());

			List<Map<String, Object>> contexts = new ArrayList<>();
			node.getContexts().forEach(context -> {
				Map<String, Object> pair = new LinkedHashMap<>();
				pair.put("key", context.getKey());
				pair.put("value", context.getValue());
				contexts.add(pair);
			});
			entry.put("contexts", contexts);

			out.add(entry);
		}

		out.sort((left, right) -> String.valueOf(left.get("key")).compareTo(String.valueOf(right.get("key"))));
		return out;
	}

	private List<String> parentGroups(Group group) {
		List<String> parents = new ArrayList<>();

		for (Node node : group.getNodes(NodeType.INHERITANCE)) {
			InheritanceNode inheritance = (InheritanceNode) node;
			parents.add(inheritance.getGroupName());
		}

		return parents;
	}

	private List<String> userGroups(User user) {
		List<String> groups = new ArrayList<>();

		for (Node node : user.getNodes(NodeType.INHERITANCE)) {
			InheritanceNode inheritance = (InheritanceNode) node;
			groups.add(inheritance.getGroupName());
		}

		return groups;
	}

	private List<Map<String, Object>> groupMembers(LuckPerms api, String groupName) {
		Map<UUID, Collection<Node>> matches = join(
			api.getUserManager().searchAll(NodeMatcher.key(InheritanceNode.builder(groupName).build()))
		);

		List<String> uuids = new ArrayList<>();
		for (UUID uuid : matches.keySet()) {
			uuids.add(uuid.toString());
		}

		Map<String, String> usernames = recordStore.available()
			? recordStore.usernames(uuids)
			: Map.of();

		List<Map<String, Object>> members = new ArrayList<>(uuids.size());
		for (String uuid : uuids) {
			Map<String, Object> member = new LinkedHashMap<>();
			member.put("uuid", uuid);
			member.put("username", usernames.getOrDefault(uuid, ""));
			members.add(member);
		}

		members.sort((left, right) -> String.valueOf(left.get("username"))
			.compareToIgnoreCase(String.valueOf(right.get("username"))));
		return members;
	}

	/** Direct members per group, from one sweep over every user's inheritance nodes. */
	private Map<String, Long> countGroupMembers(LuckPerms api) {
		Map<String, Long> counts = new TreeMap<>();

		try {
			Map<UUID, Collection<Node>> matches = join(
				api.getUserManager().searchAll(NodeMatcher.type(NodeType.INHERITANCE))
			);

			for (Collection<Node> nodes : matches.values()) {
				for (Node node : nodes) {
					if (node instanceof InheritanceNode inheritance && node.getValue()) {
						counts.merge(inheritance.getGroupName(), 1L, Long::sum);
					}
				}
			}
		} catch (RuntimeException exception) {
			// Counts are decoration; the group list must not fail because of them.
			logger.warn("Không thể đếm thành viên các permission group: " + exception.getMessage());
		}

		return counts;
	}

	private String metaValue(Group group, java.util.function.Predicate<Node> matcher) {
		String best = "";
		int bestPriority = Integer.MIN_VALUE;

		for (Node node : group.getNodes()) {
			if (!matcher.test(node) || !node.getValue()) {
				continue;
			}

			if (node instanceof PrefixNode prefix && prefix.getPriority() > bestPriority) {
				best = prefix.getMetaValue();
				bestPriority = prefix.getPriority();
			}

			if (node instanceof SuffixNode suffix && suffix.getPriority() > bestPriority) {
				best = suffix.getMetaValue();
				bestPriority = suffix.getPriority();
			}
		}

		return best;
	}

	private int currentMetaPriority(Group group, String field) {
		int priority = 0;

		for (Node node : group.getNodes()) {
			if ("prefix".equals(field) && node instanceof PrefixNode prefix) {
				priority = Math.max(priority, prefix.getPriority());
			}

			if ("suffix".equals(field) && node instanceof SuffixNode suffix) {
				priority = Math.max(priority, suffix.getPriority());
			}
		}

		return priority;
	}

	// ------------------------------------------------------------------ luckperms plumbing

	private void saveGroup(LuckPerms api, Group group) {
		join(api.getGroupManager().saveGroup(group));
		pushUpdate(api);
	}

	private void saveUser(LuckPerms api, User user) {
		join(api.getUserManager().saveUser(user));
		api.getMessagingService().ifPresent(messaging -> messaging.pushUserUpdate(user));
	}

	/** Tell every server sharing the storage to re-sync after a group change. */
	private void pushUpdate(LuckPerms api) {
		api.getMessagingService().ifPresent(messaging -> messaging.pushUpdate());
	}

	/** Resolve a username or UUID to the player's UUID, directory first. */
	private Optional<UUID> resolveUuid(LuckPerms api, String reference) {
		if (reference == null || reference.isBlank()) {
			return Optional.empty();
		}

		String trimmed = reference.trim();

		try {
			return Optional.of(UUID.fromString(trimmed));
		} catch (IllegalArgumentException notAUuid) {
			// Fall through to name resolution.
		}

		if (recordStore.available()) {
			Optional<UUID> fromDirectory = recordStore.findProfile(trimmed)
				.map(profile -> String.valueOf(profile.get("uuid")))
				.flatMap(this::parseUuid);

			if (fromDirectory.isPresent()) {
				return fromDirectory;
			}
		}

		try {
			return Optional.ofNullable(join(api.getUserManager().lookupUniqueId(trimmed)));
		} catch (RuntimeException ignored) {
			return Optional.empty();
		}
	}

	private Optional<UUID> parseUuid(String value) {
		try {
			return Optional.of(UUID.fromString(value));
		} catch (IllegalArgumentException ignored) {
			return Optional.empty();
		}
	}

	private Optional<LuckPerms> luckPerms() {
		try {
			return Optional.ofNullable(LuckPermsProvider.get());
		} catch (IllegalStateException | NoClassDefFoundError ignored) {
			return Optional.empty();
		}
	}

	/** Await a LuckPerms future, folding timeouts into runtime failures. */
	private <T> T join(java.util.concurrent.CompletableFuture<T> future) {
		try {
			return future.get(LUCKPERMS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.TimeoutException timeout) {
			throw new IllegalStateException("LuckPerms storage did not answer within " + LUCKPERMS_TIMEOUT_MILLIS + "ms");
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("LuckPerms operation interrupted");
		} catch (java.util.concurrent.ExecutionException failure) {
			throw new IllegalStateException(rootMessage(failure), failure);
		}
	}

	private String normalizeGroupName(String name) {
		return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
	}

	private String rootMessage(Throwable throwable) {
		Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
		String message = cause.getMessage();
		return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
	}

	private int parseInt(String raw, int fallback) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}

		try {
			return Integer.parseInt(raw.trim());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private long parseLong(String raw, long fallback) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}

		try {
			return Long.parseLong(raw.trim());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private long longOf(Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}

		return 0L;
	}
}
