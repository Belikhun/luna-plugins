package dev.belikhun.luna.pack.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.pack.api.LunaPackDynamicContext;
import dev.belikhun.luna.pack.api.LunaPackDynamicProvider;
import dev.belikhun.luna.pack.api.LunaPackRegistration;
import dev.belikhun.luna.pack.config.LoaderConfig;
import dev.belikhun.luna.pack.config.PackDefinition;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PackDynamicRegistry {
	private final LunaLogger logger;
	private final Map<String, LunaPackDynamicProvider> providers;

	public PackDynamicRegistry(LunaLogger logger) {
		this.logger = logger.scope("DynamicRegistry");
		this.providers = new ConcurrentHashMap<>();
	}

	public void register(String providerId, LunaPackDynamicProvider provider) {
		String key = normalizeProviderId(providerId);
		if (key == null) {
			throw new IllegalArgumentException("providerId không hợp lệ");
		}
		if (provider == null) {
			throw new IllegalArgumentException("provider không được null");
		}

		providers.put(key, provider);
	}

	public void unregister(String providerId) {
		String key = normalizeProviderId(providerId);
		if (key == null) {
			return;
		}
		providers.remove(key);
	}

	public CollectResult collect(LoaderConfig config) {
		Map<String, PackDefinition> output = new LinkedHashMap<>();
		int skipped = 0;

		LunaPackDynamicContext context = new LunaPackDynamicContext(config.baseUrl(), config.packPath());
		for (Map.Entry<String, LunaPackDynamicProvider> entry : providers.entrySet()) {
			String providerId = entry.getKey();
			Collection<LunaPackRegistration> registrations;
			try {
				registrations = entry.getValue().provide(context);
			} catch (RuntimeException exception) {
				logger.error("Provider động '" + providerId + "' lỗi khi cung cấp pack.", exception);
				skipped++;
				continue;
			}

			if (registrations == null || registrations.isEmpty()) {
				continue;
			}

			for (LunaPackRegistration registration : registrations) {
				PackDefinition definition = toDefinition(providerId, registration);
				if (definition == null) {
					skipped++;
					continue;
				}

				String key = definition.normalizedName();
				if (output.containsKey(key)) {
					logger.warn("Tên pack động bị trùng trong runtime: '" + definition.name() + "', bỏ qua.");
					skipped++;
					continue;
				}

				output.put(key, definition);
			}
		}

		return new CollectResult(output, skipped);
	}

	private PackDefinition toDefinition(String providerId, LunaPackRegistration registration) {
		if (registration == null) {
			return null;
		}

		String name = normalizeName(registration.name());
		if (name == null) {
			logger.warn("Provider '" + providerId + "' trả về name không hợp lệ: " + registration.name());
			return null;
		}

		String filename = normalizeFilename(registration.filename());
		if (filename == null) {
			logger.warn("Provider '" + providerId + "' trả về filename không hợp lệ cho pack '" + name + "'.");
			return null;
		}

		List<String> servers = normalizeServers(registration.servers());
		if (servers.isEmpty()) {
			servers = List.of("*");
		}

		return new PackDefinition(
			name,
			filename,
			registration.priority(),
			registration.required(),
			registration.enabled(),
			servers,
			Path.of("[dynamic]", providerId + ".yml")
		);
	}

	private String normalizeProviderId(String value) {
		if (value == null) {
			return null;
		}

		String normalized = value.trim().toLowerCase(Locale.ROOT);
		if (normalized.isBlank() || normalized.length() > 64) {
			return null;
		}
		if (!normalized.matches("[a-z0-9_-]+")) {
			return null;
		}
		return normalized;
	}

	private String normalizeName(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		if (normalized.isBlank() || normalized.length() > 64) {
			return null;
		}
		if (!normalized.matches("[a-z0-9_-]+")) {
			return null;
		}
		return normalized;
	}

	private String normalizeFilename(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.trim();
		if (normalized.isBlank()) {
			return null;
		}
		if (normalized.contains("..") || normalized.contains("/") || normalized.contains("\\")) {
			return null;
		}
		if (!normalized.toLowerCase(Locale.ROOT).endsWith(".zip")) {
			return null;
		}
		return normalized;
	}

	private List<String> normalizeServers(List<String> values) {
		if (values == null || values.isEmpty()) {
			return List.of("*");
		}

		Set<String> output = new LinkedHashSet<>();
		for (String value : values) {
			if (value == null) {
				continue;
			}

			String normalized = value.trim().toLowerCase(Locale.ROOT);
			if (normalized.isBlank()) {
				continue;
			}

			if (normalized.equals("all")) {
				normalized = "*";
			}
			output.add(normalized);
		}

		if (output.isEmpty()) {
			return List.of("*");
		}
		return new ArrayList<>(output);
	}

	public record CollectResult(
		Map<String, PackDefinition> definitions,
		int skippedRegistrations
	) {
	}
}
