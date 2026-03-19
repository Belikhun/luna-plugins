package dev.belikhun.luna.core.fabric.compat;

import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class FabricCompatibilityDiagnostics {

	private static final List<String> FABRIC_PROXY_LITE_CANDIDATES = List.of(
		"me.devnatan.fabricproxy_lite.FabricProxyLite",
		"me.devnatan.fabricproxy_lite.api.FabricProxyLiteAPI"
	);
	private static final List<String> TEXT_PLACEHOLDER_API_CANDIDATES = List.of(
		"eu.pb4.placeholders.api.PlaceholderAPI",
		"eu.pb4.placeholders.api.PlaceholderResult"
	);
	private static final List<String> LUCKPERMS_CANDIDATES = List.of(
		"net.luckperms.api.LuckPerms",
		"net.luckperms.api.LuckPermsProvider"
	);
	private static final List<String> SPARK_CANDIDATES = List.of(
		"me.lucko.spark.api.Spark",
		"me.lucko.spark.api.SparkProvider"
	);
	private static final List<String> SKINS_RESTORER_CANDIDATES = List.of(
		"net.skinsrestorer.api.SkinsRestorer",
		"net.skinsrestorer.api.SkinsRestorerProvider"
	);
	private static final List<String> VOICECHAT_CANDIDATES = List.of(
		"de.maxhenkel.voicechat.api.VoicechatApi",
		"de.maxhenkel.voicechat.api.BukkitVoicechatService"
	);

	private FabricCompatibilityDiagnostics() {
	}

	public static CompatibilitySnapshot scan() {
		return scan(FabricCompatibilityDiagnostics::isClassAvailable);
	}

	static CompatibilitySnapshot scan(Function<String, Boolean> classCheck) {
		Map<String, Boolean> statuses = new LinkedHashMap<>();
		statuses.put("fabricProxyLite", isAnyAvailable(FABRIC_PROXY_LITE_CANDIDATES, classCheck));
		statuses.put("textPlaceholderApi", isAnyAvailable(TEXT_PLACEHOLDER_API_CANDIDATES, classCheck));
		statuses.put("luckPerms", isAnyAvailable(LUCKPERMS_CANDIDATES, classCheck));
		statuses.put("spark", isAnyAvailable(SPARK_CANDIDATES, classCheck));
		statuses.put("skinsRestorer", isAnyAvailable(SKINS_RESTORER_CANDIDATES, classCheck));
		statuses.put("voicechat", isAnyAvailable(VOICECHAT_CANDIDATES, classCheck));
		return new CompatibilitySnapshot(statuses);
	}

	public static void logSnapshot(LunaLogger logger, CompatibilitySnapshot snapshot) {
		if (logger == null || snapshot == null) {
			return;
		}

		logger.audit("Fabric compatibility scan: FabricProxy-Lite=" + flag(snapshot.fabricProxyLite())
			+ ", PlaceholderAPI=" + flag(snapshot.textPlaceholderApi())
			+ ", LuckPerms=" + flag(snapshot.luckPerms())
			+ ", spark=" + flag(snapshot.spark())
			+ ", SkinsRestorer=" + flag(snapshot.skinsRestorer())
			+ ", voicechat=" + flag(snapshot.voicechat()));
		if (!snapshot.fabricProxyLite()) {
			logger.warn("FabricProxy-Lite not detected; proxy forwarding diagnostics may be limited.");
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

	private static boolean isClassAvailable(String className) {
		try {
			Class.forName(className, false, FabricCompatibilityDiagnostics.class.getClassLoader());
			return true;
		} catch (ClassNotFoundException exception) {
			return false;
		}
	}

	public record CompatibilitySnapshot(Map<String, Boolean> statuses) {
		public CompatibilitySnapshot {
			statuses = statuses == null ? Map.of() : Map.copyOf(statuses);
		}

		public boolean fabricProxyLite() {
			return statuses.getOrDefault("fabricProxyLite", false);
		}

		public boolean textPlaceholderApi() {
			return statuses.getOrDefault("textPlaceholderApi", false);
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
