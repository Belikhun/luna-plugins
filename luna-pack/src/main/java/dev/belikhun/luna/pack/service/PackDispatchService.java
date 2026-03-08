package dev.belikhun.luna.pack.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.pack.model.PackCatalogSnapshot;
import dev.belikhun.luna.pack.model.PlayerPackSession;
import dev.belikhun.luna.pack.model.ResolvedPack;
import dev.belikhun.luna.pack.util.SizeFormat;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PackDispatchService {
	private final ProxyServer server;
	private final LunaLogger logger;
	private final PackSelectionService selectionService;

	public PackDispatchService(ProxyServer server, LunaLogger logger) {
		this.server = server;
		this.logger = logger.scope("Dispatch");
		this.selectionService = new PackSelectionService();
	}

	public void applyForCurrentServer(Player player, PlayerPackSession session, PackCatalogSnapshot snapshot, String previousServer) {
		String currentServer = player.getCurrentServer()
			.map(conn -> conn.getServerInfo().getName())
			.orElse("");
		if (currentServer.isBlank()) {
			return;
		}

		session.beginTransition();
		session.previousServer(previousServer);
		session.lastKnownServer(currentServer);

		List<ResolvedPack> desired = selectionService.selectForServer(snapshot, currentServer);
		PackSelectionService.Delta delta = selectionService.computeDelta(
			desired,
			session.loadedByName()
		);

		if (delta.isEmpty()) {
			return;
		}

		unloadPacks(player, session, delta.toUnload());
		loadPacks(player, session, delta.toLoad(), currentServer);
	}

	private void unloadPacks(Player player, PlayerPackSession session, List<Map.Entry<String, ResourcePackInfo>> toUnload) {
		for (Map.Entry<String, ResourcePackInfo> entry : toUnload) {
			ResourcePackInfo info = entry.getValue();
			player.removeResourcePacks(info.getId());
			session.removeLoaded(entry.getKey());
			debug(session, player, "<gray>[DEBUG] Gỡ pack <white>" + entry.getKey() + "</white>.</gray>");
			logger.audit("Đã gỡ pack " + entry.getKey() + " cho " + player.getUsername());
		}
	}

	private void loadPacks(Player player, PlayerPackSession session, List<ResolvedPack> toLoad, String currentServer) {
		if (toLoad.isEmpty()) {
			return;
		}

		long totalBytes = 0L;
		for (ResolvedPack pack : toLoad) {
			totalBytes += pack.sizeBytes();
		}

		player.sendRichMessage("<gray>[<aqua>LunaPackLoader</aqua>]</gray> <yellow>⌛ Đang tải <white>" + toLoad.size() + "</white> resource pack cho server <white>" + currentServer + "</white> (<white>" + SizeFormat.humanBytes(totalBytes) + "</white>).</yellow>");

		for (ResolvedPack pack : toLoad) {
			ResourcePackInfo info = server.createResourcePackBuilder(pack.url().toString())
				.setShouldForce(pack.definition().required())
				.setHash(hexToBytes(pack.sha1()))
				.build();

			player.sendResourcePackOffer(info);
			session.addLoaded(pack.definition().normalizedName(), info);
			session.addPending(info.getId(), pack.definition().normalizedName());
			debug(session, player, "<gray>[DEBUG] Gửi pack <white>" + pack.definition().name() + "</white> (id=<white>" + info.getId() + "</white>).</gray>");
			logger.audit("Đã gửi yêu cầu pack " + pack.definition().name() + " cho " + player.getUsername());
		}
	}

	private byte[] hexToBytes(String hex) {
		String value = hex == null ? "" : hex.trim().toLowerCase(Locale.ROOT);
		if (value.length() != 40) {
			return new byte[20];
		}

		byte[] output = new byte[20];
		for (int i = 0; i < output.length; i++) {
			int index = i * 2;
			try {
				output[i] = (byte) Integer.parseInt(value.substring(index, index + 2), 16);
			} catch (NumberFormatException exception) {
				logger.warn("Hash pack không hợp lệ: " + value + ". Bỏ qua hash này.");
				return new byte[20];
			}
		}
		return output;
	}

	public void resend(Player player, PlayerPackSession session, PackCatalogSnapshot snapshot) {
		applyForCurrentServer(player, session, snapshot, session.lastKnownServer());
	}

	public boolean forceLoad(Player player, PlayerPackSession session, ResolvedPack pack) {
		if (pack == null || !pack.available()) {
			return false;
		}

		ResourcePackInfo old = session.loadedByName().get(pack.definition().normalizedName());
		if (old != null) {
			player.removeResourcePacks(old.getId());
			session.removePending(old.getId());
			session.removeLoaded(pack.definition().normalizedName());
		}

		ResourcePackInfo info = server.createResourcePackBuilder(pack.url().toString())
			.setShouldForce(pack.definition().required())
			.setHash(hexToBytes(pack.sha1()))
			.build();

		player.sendResourcePackOffer(info);
		session.addLoaded(pack.definition().normalizedName(), info);
		session.addPending(info.getId(), pack.definition().normalizedName());
		debug(session, player, "<gray>[DEBUG] Forceload pack <white>" + pack.definition().name() + "</white> (id=<white>" + info.getId() + "</white>).</gray>");
		logger.audit("Đã forceload pack " + pack.definition().name() + " cho " + player.getUsername());
		return true;
	}

	public boolean forceUnload(Player player, PlayerPackSession session, String packName) {
		String normalized = packName == null ? "" : packName.trim().toLowerCase(Locale.ROOT);
		if (normalized.isBlank()) {
			return false;
		}

		ResourcePackInfo info = session.loadedByName().get(normalized);
		if (info == null) {
			return false;
		}

		player.removeResourcePacks(info.getId());
		session.removeLoaded(normalized);
		session.removePending(info.getId());
		debug(session, player, "<gray>[DEBUG] Forceunload pack <white>" + normalized + "</white>.</gray>");
		logger.audit("Đã forceunload pack " + normalized + " cho " + player.getUsername());
		return true;
	}

	private void debug(PlayerPackSession session, Player player, String message) {
		if (!session.debugEnabled()) {
			return;
		}
		player.sendRichMessage("<gray>[<aqua>LunaPackLoader</aqua>]</gray> " + message);
	}
}
