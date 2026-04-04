package dev.belikhun.luna.core.fabric.compat;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import net.fabricmc.loader.api.FabricLoader;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.nio.file.Path;

public final class FabricCompatibilityDiagnostics {

	private static final List<String> FABRIC_PROXY_LITE_CANDIDATES = List.of(
		"fabricproxy-lite",
		"FabricProxy-Lite",
		"me.devnatan.fabricproxy_lite.FabricProxyLite",
		"me.devnatan.fabricproxy_lite.api.FabricProxyLiteAPI"
	);
	private static final List<String> TEXT_PLACEHOLDER_API_CANDIDATES = List.of(
		"placeholder-api",
		"Placeholder API",
		"eu.pb4.placeholders.api.PlaceholderAPI",
		"eu.pb4.placeholders.api.Placeholders",
		"eu.pb4.placeholders.api.PlaceholderResult"
	);
	private static final List<String> LUCKPERMS_CANDIDATES = List.of(
		"luckperms",
		"LuckPerms",
		"net.luckperms.api.LuckPerms",
		"net.luckperms.api.LuckPermsProvider"
	);
	private static final List<String> SPARK_CANDIDATES = List.of(
		"spark",
		"me.lucko.spark.api.Spark",
		"me.lucko.spark.api.SparkProvider"
	);
	private static final List<String> SKINS_RESTORER_CANDIDATES = List.of(
		"skinsrestorer",
		"SkinsRestorer",
		"net.skinsrestorer.api.SkinsRestorer",
		"net.skinsrestorer.api.SkinsRestorerProvider"
	);
	private static final List<String> VOICECHAT_CANDIDATES = List.of(
		"voicechat",
		"Simple Voice Chat",
		"de.maxhenkel.voicechat.api.VoicechatApi",
		"de.maxhenkel.voicechat.api.BukkitVoicechatService"
	);

	private FabricCompatibilityDiagnostics() {
	}

	public static CompatibilitySnapshot scan() {
		return scan(FabricCompatibilityDiagnostics::isModMarkerAvailable, Path.of("."), System::getenv);
	}

	static CompatibilitySnapshot scan(Function<String, Boolean> classCheck) {
		return scan(classCheck, Path.of("."), key -> null);
	}

	static CompatibilitySnapshot scan(Function<String, Boolean> classCheck, Path serverRoot, Function<String, String> envLookup) {
		Map<String, Boolean> statuses = new LinkedHashMap<>();
		boolean fabricProxyLite = isAnyAvailable(FABRIC_PROXY_LITE_CANDIDATES, classCheck);
		statuses.put("fabricProxyLite", fabricProxyLite);
		statuses.put("textPlaceholderApi", isAnyAvailable(TEXT_PLACEHOLDER_API_CANDIDATES, classCheck));
		statuses.put("luckPerms", isAnyAvailable(LUCKPERMS_CANDIDATES, classCheck));
		statuses.put("spark", isAnyAvailable(SPARK_CANDIDATES, classCheck));
		statuses.put("skinsRestorer", isAnyAvailable(SKINS_RESTORER_CANDIDATES, classCheck));
		statuses.put("voicechat", isAnyAvailable(VOICECHAT_CANDIDATES, classCheck));

		FabricProxyLiteForwardingDiagnostics.Snapshot forwarding = fabricProxyLite
			? FabricProxyLiteForwardingDiagnostics.inspect(serverRoot, envLookup)
			: new FabricProxyLiteForwardingDiagnostics.Snapshot(false, false, false, false, false, "", "");
		return new CompatibilitySnapshot(statuses, forwarding);
	}

	public static void logSnapshot(LunaLogger logger, CompatibilitySnapshot snapshot) {
		if (logger == null || snapshot == null) {
			return;
		}

		logger.audit("Fabric compatibility scan: FabricProxy-Lite=" + flag(snapshot.fabricProxyLite())
			+ ", ForwardingSecret=" + flag(snapshot.fabricProxyLiteSecretConfigured())
			+ ", PlaceholderAPI=" + flag(snapshot.textPlaceholderApi())
			+ ", LuckPerms=" + flag(snapshot.luckPerms())
			+ ", spark=" + flag(snapshot.spark())
			+ ", SkinsRestorer=" + flag(snapshot.skinsRestorer())
			+ ", voicechat=" + flag(snapshot.voicechat()));
		if (!snapshot.fabricProxyLite()) {
			logger.warn("FabricProxy-Lite not detected; proxy forwarding diagnostics may be limited.");
			return;
		}

		if (!snapshot.fabricProxyLiteSecretConfigured()) {
			String configPath = snapshot.fabricProxyLiteConfigPath();
			if (configPath.isBlank()) {
				logger.warn("FabricProxy-Lite detected nhưng chưa tìm thấy config/secret forwarding. Hãy kiểm tra FABRIC_PROXY_SECRET, FABRIC_PROXY_SECRET_FILE hoặc config/FabricProxy-Lite.toml.");
				return;
			}
			logger.warn("FabricProxy-Lite detected nhưng chưa cấu hình secret forwarding trong " + configPath + ".");
			return;
		}

		logger.audit("FabricProxy-Lite forwarding secret source=" + snapshot.fabricProxyLiteSecretSource()
			+ ", hackEarlySend=" + flag(snapshot.fabricProxyLiteHackEarlySend())
			+ ", hackMessageChain=" + flag(snapshot.fabricProxyLiteHackMessageChain()));
		if (!snapshot.fabricProxyLiteHackMessageChain()) {
			logger.audit("FabricProxy-Lite hackMessageChain đang tắt; nếu gặp kick chat-signature khi chuyển server, nên cân nhắc bật tùy chọn này.");
		}
	}

	private static String flag(boolean value) {
		return value ? "yes" : "no";
	}

	private static boolean isAnyAvailable(List<String> classNames, Function<String, Boolean> classCheck) {
		for (String className : classNames) {
			if (classCheck.apply(className)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isModMarkerAvailable(String marker) {
		if (marker == null || marker.isBlank()) {
			return false;
		}

		FabricLoader loader = FabricLoader.getInstance();
		if (loader.isModLoaded(marker)) {
			return true;
		}

		String normalized = marker.trim().toLowerCase();
		return loader.getAllMods().stream().anyMatch(container -> {
			String modId = container.getMetadata().getId();
			if (modId != null && modId.equalsIgnoreCase(normalized)) {
				return true;
			}

			String modName = container.getMetadata().getName();
			return modName != null && modName.equalsIgnoreCase(marker);
		});
	}

	public record CompatibilitySnapshot(Map<String, Boolean> statuses, FabricProxyLiteForwardingDiagnostics.Snapshot fabricProxyLiteForwarding) {
		public CompatibilitySnapshot {
			statuses = statuses == null ? Map.of() : Map.copyOf(statuses);
			fabricProxyLiteForwarding = fabricProxyLiteForwarding == null
				? new FabricProxyLiteForwardingDiagnostics.Snapshot(false, false, false, false, false, "", "")
				: fabricProxyLiteForwarding;
		}

		public boolean fabricProxyLite() {
			return statuses.getOrDefault("fabricProxyLite", false);
		}

		public boolean textPlaceholderApi() {
			return statuses.getOrDefault("textPlaceholderApi", false);
		}

		public boolean fabricProxyLiteSecretConfigured() {
			return fabricProxyLiteForwarding.secretConfigured();
		}

		public boolean fabricProxyLiteHackEarlySend() {
			return fabricProxyLiteForwarding.hackEarlySend();
		}

		public boolean fabricProxyLiteHackMessageChain() {
			return fabricProxyLiteForwarding.hackMessageChain();
		}

		public String fabricProxyLiteSecretSource() {
			return fabricProxyLiteForwarding.secretSource();
		}

		public String fabricProxyLiteConfigPath() {
			return fabricProxyLiteForwarding.configPath();
		}

		public boolean luckPerms() {
			return statuses.getOrDefault("luckPerms", false);
		}

		public boolean spark() {
			return statuses.getOrDefault("spark", false);
		}

		public boolean skinsRestorer() {
			return statuses.getOrDefault("skinsRestorer", false);
		}

		public boolean voicechat() {
			return statuses.getOrDefault("voicechat", false);
		}
	}
}
