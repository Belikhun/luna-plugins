package dev.belikhun.luna.core.mc12.serverselector;

import dev.belikhun.luna.core.mc12.text.LunaTextComponents;
import dev.belikhun.luna.core.mc12.ui.LunaChestMenu;
import dev.belikhun.luna.core.mc12.ui.LunaItems;
import dev.belikhun.luna.core.mc12.ui.LunaMenuHost;
import dev.belikhun.luna.legacy.dependency.DependencyManager;
import dev.belikhun.luna.legacy.heartbeat.BackendHeartbeatPublisher;
import dev.belikhun.luna.legacy.heartbeat.BackendServerStatus;
import dev.belikhun.luna.legacy.heartbeat.BackendStatusStore;
import dev.belikhun.luna.legacy.heartbeat.BackendStatusView;
import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.messaging.CoreServerSelectorMessageChannels;
import dev.belikhun.luna.legacy.messaging.PluginMessageBus;
import dev.belikhun.luna.legacy.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.legacy.messaging.bus.PlayerBridge;
import dev.belikhun.luna.legacy.permission.PermissionService;
import dev.belikhun.luna.legacy.serverselector.ServerSelectorEngine;
import dev.belikhun.luna.legacy.serverselector.ServerSelectorEngine.DashboardStats;
import dev.belikhun.luna.legacy.serverselector.ServerSelectorEngine.RenderedServerItem;
import dev.belikhun.luna.legacy.serverselector.ServerSelectorEngine.ServerPayload;
import dev.belikhun.luna.legacy.serverselector.ServerSelectorEngine.ServerRenderEntry;
import dev.belikhun.luna.legacy.serverselector.ServerSelectorEngine.ServerSelectorPayload;
import dev.belikhun.luna.legacy.string.Formatters;
import dev.belikhun.luna.legacy.string.Strings;
import dev.belikhun.luna.legacy.ui.LunaProgressBarPresets;

import net.minecraft.entity.player.EntityPlayerMP;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server selector on 1.12.2: `/servers` and the menu it opens.
 *
 * **The command is not what opens the menu.** A player types `/servers` on the
 * proxy, which sends this backend an `OPEN_MENU` plugin message carrying the
 * selector's configuration; the backend draws the chest GUI, and clicking a server
 * sends `CONNECT_REQUEST` back. That round trip is why a backend with no messaging
 * bus shows nothing at all - and why this class is mostly listeners rather than a
 * command handler.
 *
 * Every decision about what to draw comes from `ServerSelectorEngine`, shared with
 * the modern backends: which server lands in which slot, what item represents it,
 * what its lore says. What is here is the placement into a menu and the wiring of
 * the clicks - the same split every ported screen uses.
 */
public final class ServerSelectorController {
	private static final int GUI_SIZE = 54;
	private static final int SLOT_LOBBY = 46;
	private static final int SLOT_PREVIOUS_SERVER = 47;
	private static final int SLOT_DASHBOARD = 48;
	private static final int SLOT_CLOSE = 49;
	private static final int SLOT_PREV_PAGE = 52;
	private static final int SLOT_NEXT_PAGE = 53;

	/** The border runs through these in order, one pane per free edge slot. */
	private static final String[] BORDER_PANES = {
		"purple_stained_glass_pane", "magenta_stained_glass_pane", "pink_stained_glass_pane",
		"red_stained_glass_pane", "orange_stained_glass_pane", "yellow_stained_glass_pane",
		"lime_stained_glass_pane", "green_stained_glass_pane", "cyan_stained_glass_pane",
		"light_blue_stained_glass_pane", "blue_stained_glass_pane",
	};

	private final PlayerBridge<EntityPlayerMP> players;
	private final DependencyManager dependencyManager;
	private final LunaLogger logger;
	private final PermissionService permissionService;
	private final LunaMenuHost menuHost;
	private final Map<UUID, ServerSelectorPayload> payloadByPlayer;
	private final Map<UUID, OpenView> openViews;

	private volatile ServerSelectorPayload selectorPayload;
	private volatile boolean messagingAttached;
	private volatile PluginMessageBus<EntityPlayerMP, EntityPlayerMP> messagingBus;
	private volatile BackendHeartbeatPublisher heartbeatPublisher;
	private volatile BackendStatusView statusView;
	private volatile BackendStatusStore statusStore;
	private volatile Runnable statusListener;

	public ServerSelectorController(
		PlayerBridge<EntityPlayerMP> players,
		DependencyManager dependencyManager,
		LunaLogger logger,
		PermissionService permissionService
	) {
		this.players = players;
		this.dependencyManager = dependencyManager;
		this.logger = logger.scope("ServerSelector");
		this.permissionService = permissionService;
		this.menuHost = new LunaMenuHost(6);
		this.payloadByPlayer = new ConcurrentHashMap<UUID, ServerSelectorPayload>();
		this.openViews = new ConcurrentHashMap<UUID, OpenView>();
		this.selectorPayload = ServerSelectorPayload.empty();
		this.messagingAttached = false;
	}

	public void start(BackendHeartbeatPublisher heartbeatPublisher) {
		this.heartbeatPublisher = heartbeatPublisher;
		this.statusView = dependencyManager.find(BackendStatusView.class);

		BackendStatusStore store = dependencyManager.find(BackendStatusStore.class);

		// a backend going up or down reaches this mirror within a heartbeat, and the
		// player standing in the menu should see it there rather than having to close
		// and reopen the screen
		if (store != null) {
			Runnable listener = this::scheduleRefresh;

			store.addUpdateListener(listener);
			statusStore = store;
			statusListener = listener;
		} else {
			logger.warn("Không tìm thấy status store; danh sách máy chủ sẽ không tự cập nhật.");
		}

		ensureMessagingAttached();
	}

	public void close() {
		PluginMessageBus<EntityPlayerMP, EntityPlayerMP> bus = messagingBus;

		if (bus != null) {
			bus.unregisterIncoming(CoreServerSelectorMessageChannels.OPEN_MENU);
			bus.unregisterOutgoing(CoreServerSelectorMessageChannels.CONNECT_REQUEST);
		}

		BackendStatusStore store = statusStore;
		Runnable listener = statusListener;

		if (store != null && listener != null) {
			store.removeUpdateListener(listener);
		}

		statusStore = null;
		statusListener = null;
		statusView = null;

		menuHost.closeAll();
		payloadByPlayer.clear();
		openViews.clear();
		messagingAttached = false;
		messagingBus = null;
	}

	public void cleanupPlayer(UUID playerId) {
		if (playerId == null) {
			return;
		}

		menuHost.forget(playerId);
		payloadByPlayer.remove(playerId);
		openViews.remove(playerId);
	}

	/**
	 * Attach to the bus, once one exists.
	 *
	 * Called from more than one place on purpose: the messaging mod may still be
	 * starting when this one does, so every entry point that needs the bus asks
	 * again rather than assuming the first attempt succeeded.
	 */
	public void ensureMessagingAttached() {
		if (messagingAttached) {
			return;
		}

		@SuppressWarnings("unchecked")
		PluginMessageBus<EntityPlayerMP, EntityPlayerMP> resolved =
			(PluginMessageBus<EntityPlayerMP, EntityPlayerMP>) dependencyManager.find(PluginMessageBus.class);

		if (resolved == null) {
			return;
		}

		resolved.registerOutgoing(CoreServerSelectorMessageChannels.CONNECT_REQUEST);
		resolved.registerIncoming(CoreServerSelectorMessageChannels.OPEN_MENU, context -> {
			final EntityPlayerMP player = context.source();

			if (player == null) {
				return PluginMessageDispatchResult.HANDLED;
			}

			if (context.payload() != null && context.payload().length > 0) {
				payloadByPlayer.put(players.idOf(player), ServerSelectorEngine.parsePayload(context.payload()));
			}

			// the message arrives on the bus's thread; opening a window must not
			players.onServerThread(() -> openSelector(player, requestedPage(players.idOf(player))));

			return PluginMessageDispatchResult.HANDLED;
		});

		messagingBus = resolved;
		messagingAttached = true;
		logger.audit("Đã gắn server selector (Forge 1.12.2) vào plugin messaging bus.");
	}

	/** Whether the incoming listener is registered; the tick loop stops once it is. */
	public boolean isMessagingAttached() {
		return messagingAttached;
	}

	/** A fresh selector configuration from the proxy, off the heartbeat. */
	public void acceptSelectorPayload(byte[] payload) {
		ServerSelectorPayload parsed = ServerSelectorEngine.parsePayload(payload);

		if (parsed.isEmpty()) {
			return;
		}

		selectorPayload = parsed;
		scheduleRefresh();
	}

	/**
	 * Redraw every open screen, on the server thread.
	 *
	 * Both callers are off it: the status mirror dispatches its listeners through
	 * the probe's executor and the selector payload arrives on the heartbeat thread.
	 * `onServerThread` runs the task inline when it is already there, so this costs
	 * nothing in the case where the hop was unnecessary.
	 */
	private void scheduleRefresh() {
		players.onServerThread(this::refreshOpenMenus);
	}

	private void refreshOpenMenus() {
		for (Map.Entry<UUID, OpenView> entry : new HashMap<UUID, OpenView>(openViews).entrySet()) {
			UUID playerId = entry.getKey();
			final EntityPlayerMP player = players.byId(playerId);

			if (player == null) {
				cleanupPlayer(playerId);
				continue;
			}

			// the player closed it, or another screen owns their window now
			if (!redraw(player, playerId, entry.getValue())) {
				openViews.remove(playerId);
			}
		}
	}

	private boolean redraw(final EntityPlayerMP player, UUID playerId, final OpenView view) {
		if (view.kind() == ViewKind.DASHBOARD) {
			return menuHost.redraw(player, menu -> renderDashboardPage(player, menu, view.page()));
		}

		final ServerSelectorPayload payload = currentPayloadFor(playerId);
		final Map<Integer, Map<Integer, ServerRenderEntry>> layout = layoutByPage(payload);
		final int lastPage = lastPageOf(layout);
		final int currentPage = Math.max(0, Math.min(view.page(), lastPage));

		// a server leaving the list can take the page the player was on with it
		if (currentPage != view.page()) {
			openViews.put(playerId, new OpenView(ViewKind.SELECTOR, currentPage));
		}

		return menuHost.redraw(player, menu -> renderSelectorPage(player, menu, payload, layout, currentPage, lastPage));
	}

	public boolean openSelector(final EntityPlayerMP player, int page) {
		if (player == null) {
			return false;
		}

		ensureMessagingAttached();

		final ServerSelectorPayload payload = currentPayloadFor(players.idOf(player));

		if (payload.isEmpty()) {
			// the fetch is asynchronous, so it serves the player's next attempt rather
			// than this one
			syncSelectorPayload();
			LunaTextComponents.send(player, "<yellow>Danh sách máy chủ đang được đồng bộ. Hãy thử lại sau ít giây.</yellow>");

			return false;
		}

		final Map<Integer, Map<Integer, ServerRenderEntry>> layout = layoutByPage(payload);
		final int lastPage = lastPageOf(layout);
		final int currentPage = Math.max(0, Math.min(page, lastPage));

		openViews.put(players.idOf(player), new OpenView(ViewKind.SELECTOR, currentPage));

		menuHost.open(
			player,
			LunaTextComponents.mini(selectorTitle(payload, player)),
			menu -> renderSelectorPage(player, menu, payload, layout, currentPage, lastPage)
		);

		return true;
	}

	private void renderSelectorPage(
		final EntityPlayerMP player,
		LunaChestMenu menu,
		ServerSelectorPayload payload,
		Map<Integer, Map<Integer, ServerRenderEntry>> layoutByPage,
		final int currentPage,
		int maxPage
	) {
		menu.clearTopSlots();

		Map<Integer, ServerRenderEntry> pageLayout = layoutByPage.get(Integer.valueOf(currentPage));

		if (pageLayout == null) {
			pageLayout = Collections.emptyMap();
		}

		Set<Integer> occupied = new HashSet<Integer>(pageLayout.keySet());

		for (Map.Entry<Integer, ServerRenderEntry> entry : pageLayout.entrySet()) {
			ServerRenderEntry renderEntry = entry.getValue();
			final BackendServerStatus status = renderEntry.status();
			ServerPayload serverPayload = renderEntry.payload();
			String permission = serverPayload == null ? "" : serverPayload.permission();
			final boolean noPermission = !Strings.isBlank(permission) && !canUse(player, permission);

			menu.setTopSlot(entry.getKey().intValue(), serverItem(status, serverPayload, payload, noPermission), () -> {
				if (noPermission) {
					LunaTextComponents.send(player, "<red>Bạn không có quyền vào máy chủ này.</red>");
					return;
				}

				if (sendConnectRequest(player, status.serverName())) {
					player.closeScreen();
				}
			});
		}

		decorateServerGrid(menu, occupied);
		decorateFooter(menu);

		if (currentPage > 0) {
			menu.setTopSlot(SLOT_PREV_PAGE, item("map", "<yellow>← Trang trước</yellow>",
				"<gray>Lùi về trang danh sách trước đó</gray>"), () -> openSelector(player, currentPage - 1));
		} else {
			menu.setDecoration(SLOT_PREV_PAGE, item("black_stained_glass_pane", "<dark_gray>Trang trước</dark_gray>",
				"<gray>Bạn đang ở trang đầu</gray>"));
		}

		menu.setTopSlot(SLOT_LOBBY, item("oak_door", "<aqua>Về Sảnh</aqua>",
			"<gray>Kết nối về lobby</gray>", "<yellow>Nhấn để chuyển máy chủ</yellow>"), () -> {
			if (sendConnectRequest(player, "__lobby__")) {
				player.closeScreen();
			}
		});

		menu.setTopSlot(SLOT_PREVIOUS_SERVER, item("compass", "<gold>Quay Lại Server Trước</gold>",
			"<gray>Khôi phục server gần nhất</gray>", "<yellow>Nhấn để quay lại</yellow>"), () -> {
			if (sendConnectRequest(player, "__previous__")) {
				player.closeScreen();
			}
		});

		menu.setTopSlot(SLOT_DASHBOARD, item("clock", "<color:#6DFFD4>Bảng Điều Khiển Hệ Thống</color>",
			"<gray>TPS, CPU, RAM, latency, uptime</gray>", "<yellow>Nhấn để mở dashboard</yellow>"),
			() -> openDashboard(player, currentPage));

		menu.setTopSlot(SLOT_CLOSE, item("barrier", "<red>Đóng</red>"), () -> player.closeScreen());

		if (currentPage < maxPage) {
			menu.setTopSlot(SLOT_NEXT_PAGE, item("paper", "<yellow>Trang sau →</yellow>",
				"<gray>Chuyển sang trang danh sách kế tiếp</gray>"), () -> openSelector(player, currentPage + 1));
		} else {
			menu.setDecoration(SLOT_NEXT_PAGE, item("black_stained_glass_pane", "<dark_gray>Trang sau</dark_gray>",
				"<gray>Bạn đang ở trang cuối</gray>"));
		}
	}

	private void openDashboard(final EntityPlayerMP player, final int returnPage) {
		if (player == null) {
			return;
		}

		openViews.put(players.idOf(player), new OpenView(ViewKind.DASHBOARD, Math.max(0, returnPage)));

		menuHost.open(
			player,
			LunaTextComponents.mini("<color:#6DFFD4>Thống Kê Toàn Mạng</color>"),
			menu -> renderDashboardPage(player, menu, returnPage)
		);
	}

	private void renderDashboardPage(final EntityPlayerMP player, LunaChestMenu menu, final int returnPage) {
		menu.clearTopSlots();

		DashboardStats stats = ServerSelectorEngine.dashboardStats(snapshot());

		for (int slot = 0; slot < GUI_SIZE; slot += 1) {
			menu.setDecoration(slot, item("gray_stained_glass_pane", "<gray> </gray>"));
		}

		menu.setDecoration(10, item("clock", "<yellow>TPS Tổng Thể</yellow>",
			"<gray>Giá trị trung bình toàn mạng</gray>",
			LunaProgressBarPresets.tps("TPS", stats.averageTps()).render()));

		menu.setDecoration(12, item("redstone", "<color:#FF9A4D>CPU Trung Bình</color>",
			"<gray>Tải CPU theo heartbeat backend</gray>",
			LunaProgressBarPresets.cpu("CPU", stats.averageCpu()).render()));

		menu.setDecoration(14, item("iron_block", "<color:#7FDBFF>RAM Tổng</color>",
			"<gray>Sử dụng bộ nhớ toàn mạng</gray>",
			LunaProgressBarPresets.ram("RAM", stats.totalRamUsedBytes(), stats.totalRamMaxBytes()).render(),
			"<gray>" + formatMb(stats.totalRamUsedBytes()) + "MB / " + formatMb(stats.totalRamMaxBytes()) + "MB</gray>"));

		menu.setDecoration(16, item("repeater", "<aqua>Latency Heartbeat</aqua>",
			"<gray>Độ trễ backend → proxy</gray>",
			LunaProgressBarPresets.latency("Latency", stats.averageLatency()).render()));

		menu.setDecoration(30, item("emerald", "<green>Online Servers</green>",
			"<white>" + stats.onlineServerCount() + "</white><gray>/</gray><white>" + stats.allServers().size() + "</white>"));

		menu.setDecoration(31, item("chest", "<gold>Uptime Cao Nhất</gold>",
			"<gray>Máy chủ chạy lâu nhất</gray>",
			"<white>" + Formatters.duration(Duration.ofMillis(Math.max(0L, stats.maxUptimeMillis()))) + "</white>"));

		menu.setDecoration(32, item("player_head", "<color:#9EE6A3>Người Chơi Toàn Mạng</color>",
			"<white>" + stats.totalOnlinePlayers() + "</white>"));

		menu.setTopSlot(49, item("arrow", "<yellow>Quay Lại Danh Sách Server</yellow>",
			"<gray>Trở về trang trước đó</gray>"), () -> openSelector(player, returnPage));
	}

	/** The coloured frame around the grid, skipping anything a server occupies. */
	private void decorateServerGrid(LunaChestMenu menu, Set<Integer> occupied) {
		int paneIndex = 0;

		for (int slot = 0; slot <= 44; slot += 1) {
			int row = slot / 9;
			int col = slot % 9;

			if (row != 0 && row != 4 && col != 0 && col != 8) {
				continue;
			}

			if (occupied.contains(Integer.valueOf(slot))) {
				continue;
			}

			menu.setDecoration(slot, item(BORDER_PANES[paneIndex % BORDER_PANES.length], "<color:#6DFFD4>◈</color>"));
			paneIndex += 1;
		}
	}

	private void decorateFooter(LunaChestMenu menu) {
		for (int slot = 45; slot <= 53; slot += 1) {
			menu.setDecoration(slot, item("black_stained_glass_pane", "<dark_gray> </dark_gray>"));
		}
	}

	private net.minecraft.item.ItemStack serverItem(
		BackendServerStatus status,
		ServerPayload serverPayload,
		ServerSelectorPayload payload,
		boolean noPermission
	) {
		RenderedServerItem rendered = ServerSelectorEngine.renderServerItem(status, serverPayload, payload, noPermission);

		return LunaItems.of(rendered.materialName(), rendered.title(), rendered.lore(), rendered.glint());
	}

	private net.minecraft.item.ItemStack item(String material, String title, String... lore) {
		return LunaItems.of(material, title, lore.length == 0 ? Collections.<String>emptyList() : Arrays.asList(lore));
	}

	private Map<Integer, Map<Integer, ServerRenderEntry>> layoutByPage(ServerSelectorPayload payload) {
		return ServerSelectorEngine.layoutByPage(payload, snapshot(), null);
	}

	private Map<String, BackendServerStatus> snapshot() {
		BackendStatusView view = statusView;

		return view == null ? Collections.<String, BackendServerStatus>emptyMap() : view.snapshot();
	}

	/** The highest page the layout reaches; 0 when everything fits on one. */
	private static int lastPageOf(Map<Integer, Map<Integer, ServerRenderEntry>> layoutByPage) {
		int lastPage = 0;

		for (Integer page : layoutByPage.keySet()) {
			lastPage = Math.max(lastPage, page.intValue());
		}

		return lastPage;
	}

	/**
	 * The page a re-open lands on.
	 *
	 * Only while the menu is still open: a remembered page is where the player *is*,
	 * not where they last were, so a `/servers` typed after closing starts at the
	 * front the way it does on every other backend.
	 */
	private int requestedPage(UUID playerId) {
		OpenView view = playerId == null ? null : openViews.get(playerId);

		if (view == null || view.kind() != ViewKind.SELECTOR || !menuHost.isOpen(playerId)) {
			return 0;
		}

		return view.page();
	}

	private ServerSelectorPayload currentPayloadFor(UUID playerId) {
		ServerSelectorPayload payload = playerId == null ? null : payloadByPlayer.get(playerId);

		return payload != null && !payload.isEmpty() ? payload : selectorPayload;
	}

	private String selectorTitle(ServerSelectorPayload payload, EntityPlayerMP player) {
		String title = payload == null ? "Danh Sách Máy Chủ" : payload.guiTitle();
		Map<String, String> values = new HashMap<String, String>();

		values.put("player_name", players.nameOf(player));

		return ServerSelectorEngine.applyTemplate(title, values);
	}

	private boolean sendConnectRequest(final EntityPlayerMP player, final String backendName) {
		ensureMessagingAttached();

		PluginMessageBus<EntityPlayerMP, EntityPlayerMP> bus = messagingBus;

		if (bus == null || player == null) {
			return false;
		}

		return bus.send(player, CoreServerSelectorMessageChannels.CONNECT_REQUEST, writer -> {
			writer.writeUtf(players.idOf(player).toString());
			writer.writeUtf(backendName == null ? "" : backendName);
		});
	}

	private boolean canUse(EntityPlayerMP player, String permission) {
		if (Strings.isBlank(permission)) {
			return true;
		}

		// no mirror means no answer, and a selector that hides every gated server on a
		// backend whose permissions have not arrived is worse than one that shows them
		if (permissionService == null || !permissionService.isAvailable() || player == null) {
			return true;
		}

		return permissionService.hasPermission(players.idOf(player), permission);
	}

	private void syncSelectorPayload() {
		BackendHeartbeatPublisher publisher = heartbeatPublisher;

		if (publisher != null) {
			publisher.syncServerSelectorConfigNow();
		}
	}

	private static long formatMb(long bytes) {
		return Math.max(0L, bytes) / (1024L * 1024L);
	}

	/** Unused today; kept so a caller can list what the engine resolved. */
	List<String> serverNames(ServerSelectorPayload payload) {
		return payload == null ? Collections.<String>emptyList() : new ArrayList<String>(payload.servers().keySet());
	}

	private enum ViewKind {
		SELECTOR,
		DASHBOARD
	}

	/**
	 * Which of this controller's two screens a player has open, and where in it.
	 *
	 * Both are drawn into the same host, so a redraw has to know which one to render
	 * or a status update would replace the dashboard with the server grid. The page
	 * is the dashboard's return page when the kind is DASHBOARD.
	 */
	private static final class OpenView {
		private final ViewKind kind;
		private final int page;

		private OpenView(ViewKind kind, int page) {
			this.kind = kind;
			this.page = page;
		}

		private ViewKind kind() {
			return kind;
		}

		private int page() {
			return page;
		}
	}
}
