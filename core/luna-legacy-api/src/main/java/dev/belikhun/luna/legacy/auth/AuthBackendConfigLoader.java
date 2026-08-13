package dev.belikhun.luna.legacy.auth;

import dev.belikhun.luna.legacy.auth.AuthMessages;
import dev.belikhun.luna.legacy.config.ConfigValues;
import dev.belikhun.luna.legacy.config.LunaYamlConfig;
import dev.belikhun.luna.legacy.logging.LunaLogger;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The mod's config file, in the same shape and with the same defaults the paper
 * build uses. Every mod loader reads this one class; only the directory it is
 * written into differs, and that arrives through {@link #useConfigDirectory}.
 */
public final class AuthBackendConfigLoader {
	// scoped by mod id: every loader shares one class loader, so a bare
	// "config.yml" resolves to whichever luna jar happens to come first
	private static final String CONFIG_RESOURCE = "lunaauthbackend/config.yml";

	/**
	 * Where the file lands, once a platform has said where its config directory
	 * is. Fabric names that directory itself, FML names it for the forge family,
	 * so the path arrives from the bootstrap rather than being resolved here.
	 */
	private static volatile Path configPath;

	private AuthBackendConfigLoader() {
	}

	/** Point the loader at this platform's config directory. Call before {@link #load}. */
	public static void useConfigDirectory(Path configDirectory) {
		configPath = configDirectory
			.toAbsolutePath()
			.normalize()
			.resolve("lunaauthbackend")
			.resolve("config.yml");
	}

	public static AuthBackendConfig load(Class<?> resourceAnchor, LunaLogger logger) {
		Path path = configPath();

		try {
			LunaYamlConfig.ensureFile(path, () -> resourceAnchor.getClassLoader().getResourceAsStream(CONFIG_RESOURCE));
			Map<String, Object> current = new LinkedHashMap<>(LunaYamlConfig.loadMap(path));
			Map<String, Object> defaults = loadDefaults(resourceAnchor);

			if (LunaYamlConfig.mergeMissing(current, defaults)) {
				LunaYamlConfig.dumpMap(path, current);
				logger.audit("Đã cập nhật config LunaAuth Backend tại " + path + ".");
			}

			return parse(current);
		} catch (RuntimeException exception) {
			logger.error("Không thể nạp config LunaAuth Backend. Dùng mặc định tối thiểu.", exception);
			return fallback();
		}
	}

	public static Path configPath() {
		Path path = configPath;

		if (path == null) {
			throw new IllegalStateException("Chưa gọi useConfigDirectory() trước khi nạp config LunaAuth Backend.");
		}

		return path;
	}

	private static AuthBackendConfig parse(Map<String, Object> rootConfig) {
		Map<String, Object> loggingConfig = ConfigValues.map(rootConfig, "logging");
		Map<String, Object> authConfig = ConfigValues.map(rootConfig, "auth");
		Map<String, Object> modeSelectorGuiConfig = ConfigValues.map(authConfig, "mode-selector-gui");
		Map<String, Object> lobbyItemsConfig = ConfigValues.map(authConfig, "lobby-items");
		Map<String, Object> promptConfig = ConfigValues.map(rootConfig, "prompt");
		Map<String, Object> authenticatedConfig = ConfigValues.map(promptConfig, "authenticated");
		Map<String, Object> byMethodConfig = ConfigValues.map(authenticatedConfig, "by-method");

		Map<String, AuthBackendConfig.MethodFeedback> byMethod = new LinkedHashMap<>();

		for (Map.Entry<String, Object> entry : byMethodConfig.entrySet()) {
			Map<String, Object> methodConfig = ConfigValues.map(entry.getValue());
			byMethod.put(entry.getKey().trim().toLowerCase(Locale.ROOT), new AuthBackendConfig.MethodFeedback(
				ConfigValues.stringPreserveWhitespace(methodConfig.get("actionbar"), ""),
				ConfigValues.stringPreserveWhitespace(methodConfig.get("chat"), "")
			));
		}

		return new AuthBackendConfig(
			ConfigValues.booleanValue(loggingConfig, "auth-flow", true),
			ConfigValues.booleanValue(modeSelectorGuiConfig, "enabled", true),
			ConfigValues.booleanValue(lobbyItemsConfig, "enabled", false),
			ConfigValues.booleanValue(authConfig, "teleport-to-spawn-on-connect", true),
			readAllowedCommands(rootConfig.get("allowedCommands")),
			prompt(promptConfig, "pending", AuthMessages.pendingPrompt()),
			prompt(promptConfig, "login", AuthMessages.loginPrompt()),
			prompt(promptConfig, "register", AuthMessages.registerPrompt()),
			new AuthBackendConfig.AuthenticatedPrompt(
				ConfigValues.stringPreserveWhitespace(authenticatedConfig.get("actionbar"), AuthMessages.authenticatedPrompt().actionbar()),
				ConfigValues.stringPreserveWhitespace(authenticatedConfig.get("chat"), AuthMessages.authenticatedPrompt().chat()),
				byMethod
			)
		);
	}

	private static AuthBackendConfig fallback() {
		return new AuthBackendConfig(
			true,
			true,
			false,
			true,
			defaultAllowedCommands(),
			template(AuthMessages.pendingPrompt()),
			template(AuthMessages.loginPrompt()),
			template(AuthMessages.registerPrompt()),
			new AuthBackendConfig.AuthenticatedPrompt(
				AuthMessages.authenticatedPrompt().actionbar(),
				AuthMessages.authenticatedPrompt().chat(),
				Collections.emptyMap()
			)
		);
	}

	/** The shared text as this module's own prompt type. */
	private static AuthBackendConfig.PromptTemplate template(AuthMessages.PromptText text) {
		return new AuthBackendConfig.PromptTemplate(text.bossbar(), text.actionbar(), text.chat());
	}

	/**
	 * One prompt from the config, falling back to the shared text per surface.
	 *
	 * A missing key used to yield `""`, which shows the player nothing at all -
	 * while paper, reading the same gap, showed them its own hardcoded default.
	 * Both sides now land on {@link AuthMessages}.
	 */
	private static AuthBackendConfig.PromptTemplate prompt(
		Map<String, Object> promptConfig,
		String key,
		AuthMessages.PromptText fallback
	) {
		Map<String, Object> values = ConfigValues.map(promptConfig, key);

		return new AuthBackendConfig.PromptTemplate(
			ConfigValues.stringPreserveWhitespace(values.get("bossbar"), fallback.bossbar()),
			ConfigValues.stringPreserveWhitespace(values.get("actionbar"), fallback.actionbar()),
			ConfigValues.stringPreserveWhitespace(values.get("chat"), fallback.chat())
		);
	}

	/** The commands an unauthenticated player may still run. */
	private static Set<String> defaultAllowedCommands() {
		return Collections.unmodifiableSet(new LinkedHashSet<String>(
			Arrays.asList("login", "register", "l", "reg", "help")));
	}

	private static Set<String> readAllowedCommands(Object rawValue) {
		if (!(rawValue instanceof List)) {
			return defaultAllowedCommands();
		}

		List<?> values = (List<?>) rawValue;

		Set<String> commands = new LinkedHashSet<>();

		for (Object value : values) {
			if (value == null) {
				continue;
			}

			String command = String.valueOf(value).trim().toLowerCase(Locale.ROOT);

			if (!command.isEmpty()) {
				commands.add(command);
			}
		}

		return commands.isEmpty() ? defaultAllowedCommands() : Collections.unmodifiableSet(commands);
	}

	private static Map<String, Object> loadDefaults(Class<?> resourceAnchor) {
		try (InputStream stream = resourceAnchor.getClassLoader().getResourceAsStream(CONFIG_RESOURCE)) {
			if (stream == null) {
				return Collections.emptyMap();
			}

			return LunaYamlConfig.loadMap(stream);
		} catch (Exception exception) {
			return Collections.emptyMap();
		}
	}
}
