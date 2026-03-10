package dev.belikhun.luna.messenger.paper.service;

import dev.belikhun.luna.core.api.messenger.BackendPlaceholderResolver;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionRequest;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionResult;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PaperBackendPlaceholderResolver implements BackendPlaceholderResolver {
	private static final Pattern TOKEN_PATTERN = Pattern.compile("%([a-zA-Z0-9_:.\\-]+)%");
	private static final List<String> DEFAULT_EXPORT_KEYS = List.of(
		"vault_prefix",
		"vault_suffix",
		"vault_primary_group",
		"player_displayname"
	);

	private final JavaPlugin plugin;
	private final Method papiSetPlaceholdersMethod;

	public PaperBackendPlaceholderResolver(JavaPlugin plugin) {
		this.plugin = plugin;
		this.papiSetPlaceholdersMethod = lookupPlaceholderApiMethod();
	}

	@Override
	public PlaceholderResolutionResult resolve(PlaceholderResolutionRequest request) {
		Map<String, String> exported = new LinkedHashMap<>(request.internalValues());
		exported.putIfAbsent("sender_name", request.playerName());
		exported.putIfAbsent("player_name", request.playerName());
		exported.putIfAbsent("sender_server", request.sourceServer());
		exported.putIfAbsent("server_name", request.sourceServer());
		exported.putIfAbsent("player_uuid", request.playerId().toString());

		Player player = plugin.getServer().getPlayer(request.playerId());
		String resolvedContent = applyInternal(request.content(), exported);
		if (player != null && papiSetPlaceholdersMethod != null) {
			resolvedContent = resolveWithPlaceholderApi(player, resolvedContent);
			for (String exportKey : DEFAULT_EXPORT_KEYS) {
				exported.put(exportKey, resolveWithPlaceholderApi(player, "%" + exportKey + "%"));
			}
			for (String token : extractTokens(resolvedContent)) {
				exported.put(token, resolveWithPlaceholderApi(player, "%" + token + "%"));
			}
		}

		return new PlaceholderResolutionResult(resolvedContent, exported);
	}

	private Method lookupPlaceholderApiMethod() {
		if (!plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
			return null;
		}

		try {
			Class<?> placeholderApiClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
			return placeholderApiClass.getMethod("setPlaceholders", Player.class, String.class);
		} catch (ReflectiveOperationException exception) {
			return null;
		}
	}

	private String applyInternal(String content, Map<String, String> values) {
		String output = content == null ? "" : content;
		for (Map.Entry<String, String> entry : values.entrySet()) {
			String key = entry.getKey();
			String value = entry.getValue() == null ? "" : entry.getValue();
			output = output.replace("{" + key + "}", value);
			output = output.replace("%" + key + "%", value);
		}
		return output;
	}

	private String resolveWithPlaceholderApi(Player player, String text) {
		if (papiSetPlaceholdersMethod == null) {
			return text == null ? "" : text;
		}

		try {
			Object resolved = papiSetPlaceholdersMethod.invoke(null, player, text == null ? "" : text);
			return resolved instanceof String string ? string : (text == null ? "" : text);
		} catch (ReflectiveOperationException exception) {
			return text == null ? "" : text;
		}
	}

	private List<String> extractTokens(String text) {
		if (text == null || text.isBlank()) {
			return List.of();
		}

		Matcher matcher = TOKEN_PATTERN.matcher(text);
		java.util.LinkedHashSet<String> tokens = new java.util.LinkedHashSet<>();
		while (matcher.find()) {
			tokens.add(matcher.group(1));
		}
		return List.copyOf(tokens);
	}
}
