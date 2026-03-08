package dev.belikhun.luna.pack.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.pack.model.PackCatalogSnapshot;
import dev.belikhun.luna.pack.model.PlayerPackSession;
import dev.belikhun.luna.pack.service.PackCatalogService;
import dev.belikhun.luna.pack.service.PackDispatchService;
import dev.belikhun.luna.pack.service.PlayerPackSessionStore;

public final class PlayerConnectionListener {
	private final LunaLogger logger;
	private final PlayerPackSessionStore sessionStore;
	private final PackCatalogService catalogService;
	private final PackDispatchService dispatchService;

	public PlayerConnectionListener(
		LunaLogger logger,
		PlayerPackSessionStore sessionStore,
		PackCatalogService catalogService,
		PackDispatchService dispatchService
	) {
		this.logger = logger.scope("Connection");
		this.sessionStore = sessionStore;
		this.catalogService = catalogService;
		this.dispatchService = dispatchService;
	}

	@Subscribe
	public void onPostLogin(PostLoginEvent event) {
		Player player = event.getPlayer();
		sessionStore.init(player.getUniqueId());
		logger.audit("Đã khởi tạo session pack cho " + player.getUsername());
		PlayerPackSession session = sessionStore.getOrCreate(player.getUniqueId());
		if (session.debugEnabled()) {
			player.sendRichMessage("<gray>[DEBUG] Khởi tạo session pack.</gray>");
		}
	}

	@Subscribe
	public void onDisconnect(DisconnectEvent event) {
		sessionStore.remove(event.getPlayer().getUniqueId());
	}

	@Subscribe
	public void onServerPostConnect(ServerPostConnectEvent event) {
		Player player = event.getPlayer();
		PlayerPackSession session = sessionStore.getOrCreate(player.getUniqueId());
		PackCatalogSnapshot snapshot = catalogService.snapshot();
		RegisteredServer previous = event.getPreviousServer();
		String previousName = previous == null ? null : previous.getServerInfo().getName();
		if (session.debugEnabled()) {
			String currentName = player.getCurrentServer().map(conn -> conn.getServerInfo().getName()).orElse("-");
			String prevText = previousName == null || previousName.isBlank() ? "-" : previousName;
			player.sendRichMessage("<gray>[DEBUG] Chuyển server <white>" + prevText + "</white> -> <white>" + currentName + "</white>.</gray>");
		}
		dispatchService.applyForCurrentServer(player, session, snapshot, previousName);
	}
}
