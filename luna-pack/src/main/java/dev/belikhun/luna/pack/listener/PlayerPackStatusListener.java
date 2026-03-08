package dev.belikhun.luna.pack.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.pack.model.PackCatalogSnapshot;
import dev.belikhun.luna.pack.model.PlayerPackSession;
import dev.belikhun.luna.pack.model.ResolvedPack;
import dev.belikhun.luna.pack.service.PackCatalogService;
import dev.belikhun.luna.pack.service.PlayerPackSessionStore;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class PlayerPackStatusListener {
	private static final MiniMessage MM = MiniMessage.miniMessage();

	private final ProxyServer server;
	private final LunaLogger logger;
	private final PlayerPackSessionStore sessionStore;
	private final PackCatalogService catalogService;

	public PlayerPackStatusListener(
		ProxyServer server,
		LunaLogger logger,
		PlayerPackSessionStore sessionStore,
		PackCatalogService catalogService
	) {
		this.server = server;
		this.logger = logger.scope("PackStatus");
		this.sessionStore = sessionStore;
		this.catalogService = catalogService;
	}

	@Subscribe
	public void onPackStatus(PlayerResourcePackStatusEvent event) {
		Player player = event.getPlayer();
		PlayerPackSession session = sessionStore.getOrCreate(player.getUniqueId());
		PackCatalogSnapshot snapshot = catalogService.snapshot();

		String normalizedName = resolvePackName(event, session, snapshot);
		if (normalizedName == null || normalizedName.isBlank()) {
			return;
		}

		ResolvedPack resolved = snapshot.findResolved(normalizedName);
		if (resolved == null) {
			return;
		}

		PlayerResourcePackStatusEvent.Status status = event.getStatus();
		if (status == PlayerResourcePackStatusEvent.Status.SUCCESSFUL) {
			handleSuccess(session, normalizedName, event.getPackId());
			debug(session, player, "<gray>[DEBUG] Pack <white>" + resolved.definition().name() + "</white> tải thành công.</gray>");
			return;
		}

		if (status == PlayerResourcePackStatusEvent.Status.ACCEPTED || status == PlayerResourcePackStatusEvent.Status.DOWNLOADED) {
			debug(session, player, "<gray>[DEBUG] Trạng thái pack <white>" + resolved.definition().name() + "</white>: <white>" + status.name() + "</white>.</gray>");
			logger.debug("Pack " + resolved.definition().name() + " đang tải cho " + player.getUsername() + ": " + status.name());
			return;
		}

		if (isFailure(status)) {
			handleFailure(player, session, resolved, status, event.getPackId());
		}
	}

	private void handleSuccess(PlayerPackSession session, String normalizedName, UUID packId) {
		if (packId != null) {
			session.removePending(packId);
		}
		session.lastFailure(null);
		logger.debug("Pack " + normalizedName + " đã áp dụng thành công.");
	}

	private void handleFailure(
		Player player,
		PlayerPackSession session,
		ResolvedPack pack,
		PlayerResourcePackStatusEvent.Status status,
		UUID packId
	) {
		if (packId != null) {
			session.removePending(packId);
		}
		session.removeLoaded(pack.definition().normalizedName());
		session.lastFailure(pack.definition().name() + ": " + status.name());
		debug(session, player, "<gray>[DEBUG] Pack <white>" + pack.definition().name() + "</white> lỗi: <white>" + status.name() + "</white>.</gray>");

		player.sendMessage(MM.deserialize("<red>❌ Tải pack <white>" + pack.definition().name() + "</white> thất bại: <white>" + status.name() + "</white>.</red>"));
		logger.warn(player.getUsername() + " lỗi pack " + pack.definition().name() + ": " + status.name());

		if (!pack.definition().required()) {
			return;
		}

		if (session.isFailureHandledForCurrentTransition()) {
			return;
		}
		session.markFailureHandledForCurrentTransition();

		String previousServer = session.previousServer();
		if (previousServer != null && !previousServer.isBlank()) {
			Optional<RegisteredServer> target = server.getServer(previousServer);
			if (target.isPresent()) {
				player.sendMessage(MM.deserialize("<yellow>⚠ Pack bắt buộc lỗi, đang chuyển bạn về server trước đó...</yellow>"));
				player.createConnectionRequest(target.get()).connect().whenComplete((result, throwable) -> {
					if (throwable != null || !result.isSuccessful()) {
						disconnectRequiredPackFailed(player, pack.definition().name());
					}
				});
				return;
			}
		}

		disconnectRequiredPackFailed(player, pack.definition().name());
	}

	private void disconnectRequiredPackFailed(Player player, String packName) {
		player.disconnect(MM.deserialize("<red>❌ Bạn cần tải pack bắt buộc <white>" + packName + "</white> để tiếp tục.</red>"));
	}

	private boolean isFailure(PlayerResourcePackStatusEvent.Status status) {
		return status == PlayerResourcePackStatusEvent.Status.DECLINED
			|| status == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD
			|| status == PlayerResourcePackStatusEvent.Status.FAILED_RELOAD
			|| status == PlayerResourcePackStatusEvent.Status.INVALID_URL
			|| status == PlayerResourcePackStatusEvent.Status.DISCARDED;
	}

	private String resolvePackName(PlayerResourcePackStatusEvent event, PlayerPackSession session, PackCatalogSnapshot snapshot) {
		UUID packId = event.getPackId();
		if (packId != null) {
			String pending = session.pendingByPackId().get(packId);
			if (pending != null) {
				return pending;
			}
		}

		if (event.getPackInfo() != null && event.getPackInfo().getHash() != null) {
			String hash = bytesToHex(event.getPackInfo().getHash());
			if (hash.length() != 40) {
				return null;
			}
			for (ResolvedPack pack : snapshot.resolvedPacks()) {
				if (pack.sha1().equalsIgnoreCase(hash)) {
					return pack.definition().normalizedName();
				}
			}
		}

		return null;
	}

	private String bytesToHex(byte[] hash) {
		StringBuilder builder = new StringBuilder(hash.length * 2);
		for (byte value : hash) {
			builder.append(String.format(Locale.ROOT, "%02x", value));
		}
		return builder.toString();
	}

	private void debug(PlayerPackSession session, Player player, String message) {
		if (!session.debugEnabled()) {
			return;
		}
		player.sendMessage(MM.deserialize(message));
	}
}
