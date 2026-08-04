package dev.belikhun.luna.core.velocity.skins;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.heartbeat.HeartbeatFormCodec;
import dev.belikhun.luna.core.api.http.HttpRequest;
import dev.belikhun.luna.core.api.http.HttpResponse;
import dev.belikhun.luna.core.api.http.LunaJson;
import dev.belikhun.luna.core.api.http.RequestAuthorizer;
import dev.belikhun.luna.core.api.http.Router;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.velocity.players.VelocityPlayerRecordStore;
import net.skinsrestorer.api.SkinsRestorer;
import net.skinsrestorer.api.SkinsRestorerProvider;
import net.skinsrestorer.api.connections.model.MineSkinResponse;
import net.skinsrestorer.api.property.SkinIdentifier;
import net.skinsrestorer.api.property.SkinProperty;
import net.skinsrestorer.api.property.SkinVariant;
import net.skinsrestorer.api.storage.PlayerStorage;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Skin administration through SkinsRestorer: read a player's stored skin,
 * change it (mirror another account, generate from a URL, or apply raw signed
 * texture data the console obtained itself) and reset it.
 *
 * SkinsRestorer runs on the proxy in proxy-mode, so its storage is the single
 * source of truth for every backend — going through its API keeps luna's
 * changes indistinguishable from an in-game /skin command. The plugin is an
 * optional dependency: every route answers 503 when it is not installed.
 */
public final class VelocitySkinHttpEndpoints {
	/** Stored-skin name luna uses for per-player custom textures. */
	private static final String CUSTOM_SKIN_PREFIX = "luna-";

	private final LunaLogger logger;
	private final ProxyServer proxyServer;
	private final VelocityPlayerRecordStore recordStore;
	private final RequestAuthorizer authorizer;

	public VelocitySkinHttpEndpoints(
		LunaLogger logger,
		ProxyServer proxyServer,
		VelocityPlayerRecordStore recordStore,
		RequestAuthorizer authorizer
	) {
		this.logger = logger.scope("SkinHttp");
		this.proxyServer = proxyServer;
		this.recordStore = recordStore;
		this.authorizer = authorizer;
	}

	public void register(Router router) {
		router.get("/skins/{player}", request -> withSkinsRestorer(request, "inspect skin", (api, startedAt) -> {
			Optional<UUID> uuid = resolveUuid(request.pathParam("player", ""));

			if (uuid.isEmpty()) {
				return LunaJson.error(404, "player not found: " + request.pathParam("player", ""));
			}

			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("uuid", uuid.get().toString());

			Optional<SkinIdentifier> identifier = api.getPlayerStorage().getSkinIdOfPlayer(uuid.get());
			payload.put("hasStoredSkin", identifier.isPresent());

			if (identifier.isPresent()) {
				payload.put("skinIdentifier", identifier.get().getIdentifier());
				payload.put("skinType", identifier.get().getSkinType().name().toLowerCase(Locale.ROOT));
			}

			return LunaJson.envelope(200, payload, startedAt);
		}));

		router.post("/skins/{player}", request -> withSkinsRestorer(request, "set skin", (api, startedAt) -> {
			String reference = request.pathParam("player", "").trim();
			Optional<UUID> uuid = resolveUuid(reference);

			if (uuid.isEmpty()) {
				return LunaJson.error(404, "player not found: " + reference);
			}

			Map<String, String> body = HeartbeatFormCodec.decode(request.body());
			String mode = body.getOrDefault("mode", "").trim().toLowerCase(Locale.ROOT);

			try {
				SkinProperty applied = switch (mode) {
					case "name" -> setFromName(api, uuid.get(), body.getOrDefault("skin", "").trim());
					case "url" -> setFromUrl(api, uuid.get(), body);
					case "texture" -> setFromTexture(api, uuid.get(), body);
					case "reset" -> resetSkin(api, uuid.get());
					default -> throw new IllegalArgumentException("unknown mode: " + mode);
				};

				applyIfOnline(api, uuid.get());

				// Keep the console's own directory in step, so the profile screen
				// shows the new skin without waiting for the next login.
				if (applied != null) {
					recordStore.updateSkin(uuid.get(), applied.getValue(), applied.getSignature());
				}

				recordStore.recordModeration(
					uuid.get().toString(),
					"",
					"reset".equals(mode) ? "skin-reset" : "skin-set",
					body.getOrDefault("actor", "console"),
					"reset".equals(mode) ? "" : describe(mode, body),
					"",
					""
				);

				logger.audit("Console đổi skin cho " + reference + " (mode=" + mode + ").");

				Map<String, Object> payload = new LinkedHashMap<>();
				payload.put("uuid", uuid.get().toString());
				payload.put("mode", mode);
				payload.put("applied", applied != null);

				if (applied != null) {
					payload.put("skinTexture", applied.getValue());
					payload.put("skinSignature", applied.getSignature());
				}

				return LunaJson.envelope(200, payload, startedAt);
			} catch (IllegalArgumentException invalid) {
				return LunaJson.error(400, invalid.getMessage());
			} catch (Exception failure) {
				logger.error("Đổi skin thất bại: " + failure.getMessage(), failure);
				return LunaJson.error(502, "skin change failed: " + rootMessage(failure));
			}
		}));
	}

	// ------------------------------------------------------------------ modes

	/** Mirror another account's skin, resolved through Mojang by name. */
	private SkinProperty setFromName(SkinsRestorer api, UUID target, String skinName) throws Exception {
		if (skinName.isBlank()) {
			throw new IllegalArgumentException("skin is required for mode=name");
		}

		Optional<UUID> premium = api.getMojangAPI().getUUID(skinName);

		if (premium.isEmpty()) {
			throw new IllegalArgumentException("no Mojang account named " + skinName);
		}

		// fetches from Mojang and caches under the premium account's identifier
		Optional<SkinProperty> property = api.getSkinStorage().updatePlayerSkinData(premium.get());

		if (property.isEmpty()) {
			throw new IllegalArgumentException("Mojang served no skin for " + skinName);
		}

		api.getPlayerStorage().setSkinIdOfPlayer(target, SkinIdentifier.ofPlayer(premium.get()));
		return property.get();
	}

	/** Generate signed texture data from a public image URL via MineSkin. */
	private SkinProperty setFromUrl(SkinsRestorer api, UUID target, Map<String, String> body) throws Exception {
		String url = body.getOrDefault("url", "").trim();

		if (url.isBlank()) {
			throw new IllegalArgumentException("url is required for mode=url");
		}

		SkinVariant variant = parseVariant(body.getOrDefault("variant", ""));
		MineSkinResponse response = api.getMineSkinAPI().genSkin(url, variant);
		SkinProperty property = response.getProperty();

		storeCustom(api, target, property);
		return property;
	}

	/** Apply raw signed texture data the console already holds. */
	private SkinProperty setFromTexture(SkinsRestorer api, UUID target, Map<String, String> body) {
		String value = body.getOrDefault("value", "").trim();
		String signature = body.getOrDefault("signature", "").trim();

		if (value.isBlank() || signature.isBlank()) {
			throw new IllegalArgumentException("value and signature are required for mode=texture");
		}

		SkinProperty property = SkinProperty.of(value, signature);

		storeCustom(api, target, property);
		return property;
	}

	/** Drop the stored skin so the player reverts to their own. */
	private SkinProperty resetSkin(SkinsRestorer api, UUID target) {
		api.getPlayerStorage().removeSkinIdOfPlayer(target);
		return null;
	}

	/** Store texture data under luna's per-player custom skin and assign it. */
	private void storeCustom(SkinsRestorer api, UUID target, SkinProperty property) {
		String name = CUSTOM_SKIN_PREFIX + target;

		api.getSkinStorage().setCustomSkinData(name, property);
		api.getPlayerStorage().setSkinIdOfPlayer(target, SkinIdentifier.ofCustom(name));
	}

	/** Re-send the stored skin to the player when they are connected right now. */
	private void applyIfOnline(SkinsRestorer api, UUID target) {
		Optional<Player> online = proxyServer.getPlayer(target);

		if (online.isEmpty()) {
			return;
		}

		try {
			api.getSkinApplier(Player.class).applySkin(online.get());
		} catch (Exception failure) {
			// the stored skin is saved either way; it applies on their next login
			logger.warn("Không thể áp dụng skin ngay cho người chơi đang online: " + failure.getMessage());
		}
	}

	// ------------------------------------------------------------------ shared

	private HttpResponse withSkinsRestorer(HttpRequest request, String what, SkinAction handler) {
		if (!authorizer.authorized(request)) {
			logger.warn("Từ chối truy vấn /skins (" + what + ") do sai token hoặc thiếu token.");
			return authorizer.unauthorized();
		}

		long startedAt = System.nanoTime();
		Optional<SkinsRestorer> api = skinsRestorer();

		if (api.isEmpty()) {
			return LunaJson.error(503, "SkinsRestorer is not available on the proxy");
		}

		try {
			return handler.apply(api.get(), startedAt);
		} catch (RuntimeException exception) {
			logger.error("Thao tác SkinsRestorer thất bại (" + what + "): " + exception.getMessage(), exception);
			return LunaJson.error(500, "SkinsRestorer operation failed: " + rootMessage(exception));
		}
	}

	@FunctionalInterface
	private interface SkinAction {
		HttpResponse apply(SkinsRestorer api, long startedAt);
	}

	private Optional<SkinsRestorer> skinsRestorer() {
		try {
			return Optional.ofNullable(SkinsRestorerProvider.get());
		} catch (IllegalStateException | NoClassDefFoundError ignored) {
			return Optional.empty();
		}
	}

	/** Console-supplied reference: a UUID, the directory, or an online player. */
	private Optional<UUID> resolveUuid(String reference) {
		String trimmed = reference == null ? "" : reference.trim();

		if (trimmed.isBlank()) {
			return Optional.empty();
		}

		try {
			return Optional.of(UUID.fromString(trimmed));
		} catch (IllegalArgumentException notAUuid) {
			// fall through to name resolution
		}

		if (recordStore.available()) {
			Optional<Map<String, Object>> profile = recordStore.findProfile(trimmed);

			if (profile.isPresent()) {
				try {
					return Optional.of(UUID.fromString(String.valueOf(profile.get().get("uuid"))));
				} catch (IllegalArgumentException ignored) {
					// a malformed directory row must not stop online resolution
				}
			}
		}

		return proxyServer.getPlayer(trimmed).map(Player::getUniqueId);
	}

	private SkinVariant parseVariant(String raw) {
		String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);

		if ("slim".equals(normalized)) {
			return SkinVariant.SLIM;
		}

		if ("classic".equals(normalized)) {
			return SkinVariant.CLASSIC;
		}

		return null;
	}

	private String describe(String mode, Map<String, String> body) {
		if ("name".equals(mode)) {
			return "mirrored from " + body.getOrDefault("skin", "");
		}

		if ("url".equals(mode)) {
			return "generated from " + body.getOrDefault("url", "");
		}

		return "custom texture data";
	}

	private String rootMessage(Throwable throwable) {
		Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
		String message = cause.getMessage();
		return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
	}
}
