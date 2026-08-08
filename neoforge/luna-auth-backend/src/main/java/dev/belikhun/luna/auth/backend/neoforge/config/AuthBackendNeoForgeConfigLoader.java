package dev.belikhun.luna.auth.backend.neoforge.config;

import dev.belikhun.luna.core.api.config.ConfigValues;
import dev.belikhun.luna.core.api.config.LunaYamlConfig;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import net.neoforged.fml.loading.FMLPaths;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class AuthBackendNeoForgeConfigLoader {
	private static final String CONFIG_RESOURCE = "config.yml";
	private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("lunaauthbackend").resolve("config.yml");

	private AuthBackendNeoForgeConfigLoader() {
	}

	public static AuthBackendNeoForgeConfig load(Class<?> resourceAnchor, LunaLogger logger) {
		try {
			LunaYamlConfig.ensureFile(CONFIG_PATH, () -> resourceAnchor.getClassLoader().getResourceAsStream(CONFIG_RESOURCE));
			Map<String, Object> current = new LinkedHashMap<>(LunaYamlConfig.loadMap(CONFIG_PATH));
			Map<String, Object> defaults = loadDefaults(resourceAnchor);
			if (LunaYamlConfig.mergeMissing(current, defaults)) {
				LunaYamlConfig.dumpMap(CONFIG_PATH, current);
				logger.audit("Đã cập nhật config LunaAuth Backend NeoForge tại " + CONFIG_PATH.toAbsolutePath() + ".");
			}
			return parse(current);
		} catch (RuntimeException exception) {
			logger.error("Không thể nạp config LunaAuth Backend NeoForge. Dùng mặc định tối thiểu.", exception);
			return fallback();
		}
	}

	public static Path configPath() {
		return CONFIG_PATH;
	}

	private static AuthBackendNeoForgeConfig parse(Map<String, Object> rootConfig) {
		Map<String, Object> loggingConfig = ConfigValues.map(rootConfig, "logging");
		Map<String, Object> authConfig = ConfigValues.map(rootConfig, "auth");
		Map<String, Object> modeSelectorGuiConfig = ConfigValues.map(authConfig, "mode-selector-gui");
		Map<String, Object> promptConfig = ConfigValues.map(rootConfig, "prompt");
		Map<String, Object> authenticatedConfig = ConfigValues.map(promptConfig, "authenticated");
		Map<String, Object> byMethodConfig = ConfigValues.map(authenticatedConfig, "by-method");

		Map<String, AuthBackendNeoForgeConfig.MethodFeedback> byMethod = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : byMethodConfig.entrySet()) {
			Map<String, Object> methodConfig = ConfigValues.map(entry.getValue());
			byMethod.put(entry.getKey().trim().toLowerCase(Locale.ROOT), new AuthBackendNeoForgeConfig.MethodFeedback(
				ConfigValues.stringPreserveWhitespace(methodConfig.get("actionbar"), ""),
				ConfigValues.stringPreserveWhitespace(methodConfig.get("chat"), "")
			));
		}

		return new AuthBackendNeoForgeConfig(
			ConfigValues.booleanValue(loggingConfig, "auth-flow", true),
			ConfigValues.booleanValue(modeSelectorGuiConfig, "enabled", true),
			ConfigValues.booleanValue(authConfig, "teleport-to-spawn-on-connect", true),
			readAllowedCommands(rootConfig.get("allowedCommands")),
			prompt(promptConfig, "pending"),
			prompt(promptConfig, "login"),
			prompt(promptConfig, "register"),
			new AuthBackendNeoForgeConfig.AuthenticatedPrompt(
				ConfigValues.stringPreserveWhitespace(authenticatedConfig.get("actionbar"), "<green>✔ Đã xác thực thành công</green>"),
				ConfigValues.stringPreserveWhitespace(authenticatedConfig.get("chat"), "<green>✔ Bạn đã xác thực thành công. Chúc bạn chơi vui vẻ!</green>"),
				byMethod
			)
		);
	}

	private static AuthBackendNeoForgeConfig fallback() {
		return new AuthBackendNeoForgeConfig(
			true,
			true,
			true,
			Set.of("login", "register", "l", "reg", "help"),
			new AuthBackendNeoForgeConfig.PromptTemplate(
				"<yellow><b>⏳ Đang tải trạng thái xác thực...</b></yellow>",
				"<yellow>Đang kiểm tra trạng thái tài khoản...</yellow>",
				"<yellow>ℹ Đang kiểm tra trạng thái xác thực, vui lòng chờ một chút.</yellow>"
			),
			new AuthBackendNeoForgeConfig.PromptTemplate(
				"<yellow><b>⚠ Vui lòng đăng nhập để tiếp tục</b></yellow>",
				"<yellow>Dùng <white>/login <mật_khẩu></white> để đăng nhập</yellow>",
				"<yellow>ℹ Tài khoản đã đăng ký. Dùng <white>/login <mật_khẩu></white> để tiếp tục.</yellow>"
			),
			new AuthBackendNeoForgeConfig.PromptTemplate(
				"<yellow><b>⚠ Tài khoản chưa đăng ký</b></yellow>",
				"<yellow>Dùng <white>/register <mật_khẩu> <nhập_lại></white> để tạo tài khoản</yellow>",
				"<yellow>ℹ Tài khoản chưa đăng ký. Dùng <white>/register <mật_khẩu> <nhập_lại></white> để tiếp tục.</yellow>"
			),
			new AuthBackendNeoForgeConfig.AuthenticatedPrompt(
				"<green>✔ Đã xác thực thành công</green>",
				"<green>✔ Bạn đã xác thực thành công. Chúc bạn chơi vui vẻ!</green>",
				Map.of()
			)
		);
	}

	private static AuthBackendNeoForgeConfig.PromptTemplate prompt(Map<String, Object> promptConfig, String key) {
		Map<String, Object> values = ConfigValues.map(promptConfig, key);
		return new AuthBackendNeoForgeConfig.PromptTemplate(
			ConfigValues.stringPreserveWhitespace(values.get("bossbar"), ""),
			ConfigValues.stringPreserveWhitespace(values.get("actionbar"), ""),
			ConfigValues.stringPreserveWhitespace(values.get("chat"), "")
		);
	}

	private static Set<String> readAllowedCommands(Object rawValue) {
		if (!(rawValue instanceof List<?> values)) {
			return Set.of("login", "register", "l", "reg", "help");
		}

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
		return commands.isEmpty() ? Set.of("login", "register", "l", "reg", "help") : Set.copyOf(commands);
	}

	private static Map<String, Object> loadDefaults(Class<?> resourceAnchor) {
		try (InputStream stream = resourceAnchor.getClassLoader().getResourceAsStream(CONFIG_RESOURCE)) {
			if (stream == null) {
				return Map.of();
			}
			return LunaYamlConfig.loadMap(stream);
		} catch (Exception exception) {
			return Map.of();
		}
	}
}
