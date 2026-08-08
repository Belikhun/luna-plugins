package dev.belikhun.luna.core.paper.serverselector;

import dev.belikhun.luna.core.api.gui.GuiManager;
import dev.belikhun.luna.core.api.gui.GuiView;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.CoreServerSelectorMessageChannels;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.serverselector.ServerSelectorEngine;
import dev.belikhun.luna.core.api.serverselector.ServerSelectorEngine.DashboardStats;
import dev.belikhun.luna.core.api.serverselector.ServerSelectorEngine.RenderedServerItem;
import dev.belikhun.luna.core.api.serverselector.ServerSelectorEngine.ServerPayload;
import dev.belikhun.luna.core.api.serverselector.ServerSelectorEngine.ServerRenderEntry;
import dev.belikhun.luna.core.api.serverselector.ServerSelectorEngine.ServerSelectorPayload;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.api.ui.LunaProgressBarPresets;
import dev.belikhun.luna.core.api.ui.LunaUi;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusStore;
import dev.belikhun.luna.core.paper.heartbeat.PaperHeartbeatPublisher;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PaperServerSelectorController implements Listener {
	private static final int GUI_SIZE = 54;
	private static final int PAGE_SIZE = 45;
	private static final int SLOT_PREV_PAGE = 52;
	private static final int SLOT_LOBBY = 46;
	private static final int SLOT_PREVIOUS_SERVER = 47;
	private static final int SLOT_DASHBOARD = 48;
	private static final int SLOT_CLOSE = 49;
	private static final int SLOT_NEXT_PAGE = 53;

	/** How long changes are allowed to pile up before open menus are redrawn. */
	private static final long REFRESH_COALESCE_TICKS = 10L;

	/** What a viewer currently has open, in one object. */
	private static final class SelectorSession {
		private final GuiView view;
		private boolean dashboard;
		private int page;

		private SelectorSession(GuiView view, boolean dashboard, int page) {
			this.view = view;
			this.dashboard = dashboard;
			this.page = page;
		}

		/** Whether this session is still the inventory the player is looking at. */
		private boolean isCurrent(Player player) {
			return player.getOpenInventory() != null
				&& player.getOpenInventory().getTopInventory() != null
				&& view.getInventory().equals(player.getOpenInventory().getTopInventory());
		}
	}

	private final JavaPlugin plugin;
	private final BackendStatusStore statusStore;
	private final PaperHeartbeatPublisher heartbeatPublisher;
	private final PluginMessageBus<Player, Player> messaging;
	private final LunaLogger logger;
	private final boolean diagnosticsEnabled;
	private final long refreshWarnThresholdMs;
	private final GuiManager guiManager;
	/**
	 * One entry per viewer. This used to be four parallel maps, and any moment
	 * where they disagreed — another plugin briefly swapping the open inventory,
	 * a close event racing an update — unregistered the viewer for good, so their
	 * menu simply stopped updating until they reopened it.
	 */
	private final Map<UUID, SelectorSession> sessions;
	private final Set<UUID> suppressCloseCleanup;
	private final AtomicBoolean refreshScheduled;
	private volatile ServerSelectorPayload syncedPayload;

	public PaperServerSelectorController(
		JavaPlugin plugin,
		BackendStatusStore statusStore,
		PaperHeartbeatPublisher heartbeatPublisher,
		PluginMessageBus<Player, Player> messaging,
		LunaLogger logger,
		boolean diagnosticsEnabled,
		long refreshWarnThresholdMs
	) {
		this.plugin = plugin;
		this.statusStore = statusStore;
		this.heartbeatPublisher = heartbeatPublisher;
		this.messaging = messaging;
		this.logger = logger.scope("ServerSelector");
		this.diagnosticsEnabled = diagnosticsEnabled;
		this.refreshWarnThresholdMs = Math.max(1L, refreshWarnThresholdMs);
		this.guiManager = new GuiManager();
		this.sessions = new ConcurrentHashMap<>();
		this.suppressCloseCleanup = ConcurrentHashMap.newKeySet();
		this.refreshScheduled = new AtomicBoolean(false);
		this.syncedPayload = ServerSelectorPayload.empty();

		plugin.getServer().getPluginManager().registerEvents(guiManager, plugin);
		plugin.getServer().getPluginManager().registerEvents(this, plugin);
		statusStore.addUpdateListener(this::scheduleRefresh);

		messaging.registerOutgoing(CoreServerSelectorMessageChannels.CONNECT_REQUEST);
		messaging.registerIncoming(CoreServerSelectorMessageChannels.OPEN_MENU, context -> {
			Player player = context.source();
			SelectorSession session = sessions.get(player.getUniqueId());
			int page = session == null ? 0 : session.page;
			plugin.getServer().getScheduler().runTask(plugin, () -> open(player, page));
			return dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult.HANDLED;
		});
	}

	public void updateSyncedPayload(byte[] payloadBytes) {
		if (payloadBytes == null || payloadBytes.length == 0) {
			return;
		}

		ServerSelectorPayload payload = parsePayload(PluginMessageReader.of(payloadBytes));
		syncedPayload = payload;
		debug("synced selector payload updated: servers=" + payload.servers().size() + ", title=" + payload.guiTitle());

		// a layout change is as visible as a status change, so menus that are open
		// when the proxy reloads must redraw rather than wait for the next status
		scheduleRefresh();
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		UUID playerId = event.getPlayer().getUniqueId();
		sessions.remove(playerId);
		suppressCloseCleanup.remove(playerId);
	}

	@EventHandler
	public void onClose(InventoryCloseEvent event) {
		if (!(event.getPlayer() instanceof Player player)) {
			return;
		}

		UUID playerId = player.getUniqueId();
		if (suppressCloseCleanup.remove(playerId)) {
			return;
		}

		SelectorSession session = sessions.get(playerId);
		if (session != null && session.view.getInventory().equals(event.getInventory())) {
			sessions.remove(playerId);
		}
	}

	public void open(Player player, int page) {
		UUID playerId = player.getUniqueId();

		// with the stream down this mirror is only as fresh as the last poll. The
		// menu still opens from what we have — the round trip is asynchronous — but
		// the result lands within a moment and redraws it.
		if (!heartbeatPublisher.registryClient().streamLive()) {
			heartbeatPublisher.publishNow();
		}

		ServerSelectorPayload payload = syncedPayload;
		debug("open selector for " + player.getName() + ", requestedPage=" + page + ", payloadServers=" + payload.servers().size());
		Map<Integer, Map<Integer, ServerRenderEntry>> layoutByPage = layoutByPage(payload);
		int maxPage = layoutByPage.isEmpty() ? 0 : layoutByPage.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
		int currentPage = Math.max(0, Math.min(page, maxPage));

		SelectorSession session = sessions.get(playerId);
		if (session != null && !session.dashboard && session.isCurrent(player)) {
			session.page = currentPage;
			renderSelectorPage(player, session.view, payload, layoutByPage, currentPage, maxPage);
			return;
		}

		String title = payload == null ? "Danh Sách Máy Chủ" : payload.guiTitle();
		GuiView view = new GuiView(GUI_SIZE, LunaUi.guiTitle(applyTemplate(title, Map.of("player_name", player.getName()))));
		guiManager.track(view);
		renderSelectorPage(player, view, payload, layoutByPage, currentPage, maxPage);
		sessions.put(playerId, new SelectorSession(view, false, currentPage));

		suppressCloseCleanup.add(playerId);
		player.openInventory(view.getInventory());
		plugin.getServer().getScheduler().runTask(plugin, () -> suppressCloseCleanup.remove(playerId));
	}

	private void renderSelectorPage(
		Player player,
		GuiView view,
		ServerSelectorPayload payload,
		Map<Integer, Map<Integer, ServerRenderEntry>> layoutByPage,
		int currentPage,
		int maxPage
	) {
		for (int slot = 0; slot < GUI_SIZE; slot++) {
			view.setItem(slot, null, (clicker, event, gui) -> {
			});
		}

		Map<Integer, ServerRenderEntry> pageLayout = layoutByPage.getOrDefault(currentPage, Map.of());
		Set<Integer> occupiedSlots = ConcurrentHashMap.newKeySet();
		occupiedSlots.addAll(pageLayout.keySet());
		for (Map.Entry<Integer, ServerRenderEntry> entry : pageLayout.entrySet()) {
			int slot = entry.getKey();
			ServerRenderEntry renderEntry = entry.getValue();
			BackendServerStatus status = renderEntry.status();
			ServerPayload serverPayload = renderEntry.payload();
			String permission = serverPayload == null ? "" : serverPayload.permission();
			boolean noPermission = permission != null && !permission.isBlank() && !player.hasPermission(permission);
			view.setItem(slot, buildServerItem(status, serverPayload, payload, noPermission), (clicker, event, gui) -> {
				if (noPermission) {
					clicker.sendMessage(LunaUi.mini("<red>❌ Bạn không có quyền vào máy chủ này.</red>"));
					return;
				}
				sendConnectRequest(clicker, status.serverName());
				clicker.closeInventory();
			});
		}

		decorateServerGrid(view, occupiedSlots);
		decorateFooter(view);

		if (currentPage > 0) {
			view.setItem(SLOT_PREV_PAGE, LunaUi.item(Material.MAP, "<yellow>← Trang trước</yellow>", List.of(
				LunaUi.mini("<gray>Lùi về trang danh sách trước đó</gray>")
			)), (clicker, event, gui) -> open(clicker, currentPage - 1));
		} else {
			view.setItem(SLOT_PREV_PAGE, LunaUi.item(Material.BLACK_STAINED_GLASS_PANE, "<dark_gray>Trang trước</dark_gray>", List.of(
				LunaUi.mini("<gray>Bạn đang ở trang đầu</gray>")
			)), (clicker, event, gui) -> {
			});
		}
		view.setItem(SLOT_LOBBY, LunaUi.item(Material.OAK_DOOR, "<aqua>Về Sảnh</aqua>", List.of(
			LunaUi.mini("<gray>Kết nối về lobby</gray>"),
			LunaUi.mini("<yellow>Nhấn để chuyển máy chủ</yellow>")
		)), (clicker, event, gui) -> {
			sendConnectRequest(clicker, "__lobby__");
			clicker.closeInventory();
		});
		view.setItem(SLOT_PREVIOUS_SERVER, LunaUi.item(Material.COMPASS, "<gold>Quay Lại Server Trước</gold>", List.of(
			LunaUi.mini("<gray>Khôi phục server gần nhất</gray>"),
			LunaUi.mini("<yellow>Nhấn để quay lại</yellow>")
		)), (clicker, event, gui) -> {
			sendConnectRequest(clicker, "__previous__");
			clicker.closeInventory();
		});
		view.setItem(SLOT_DASHBOARD, LunaUi.item(Material.CLOCK, "<color:#6DFFD4>Bảng Điều Khiển Hệ Thống</color>", List.of(
			LunaUi.mini("<gray>TPS, CPU, RAM, latency, uptime</gray>"),
			LunaUi.mini("<yellow>Nhấn để mở dashboard</yellow>")
		)), (clicker, event, gui) -> openDashboard(clicker, currentPage));
		view.setItem(SLOT_CLOSE, LunaUi.item(Material.BARRIER, "<red>Đóng</red>", List.of()), (clicker, event, gui) -> clicker.closeInventory());

		if (currentPage < maxPage) {
			view.setItem(SLOT_NEXT_PAGE, LunaUi.item(Material.PAPER, "<yellow>Trang sau →</yellow>", List.of(
				LunaUi.mini("<gray>Chuyển sang trang danh sách kế tiếp</gray>")
			)), (clicker, event, gui) -> open(clicker, currentPage + 1));
		} else {
			view.setItem(SLOT_NEXT_PAGE, LunaUi.item(Material.BLACK_STAINED_GLASS_PANE, "<dark_gray>Trang sau</dark_gray>", List.of(
				LunaUi.mini("<gray>Bạn đang ở trang cuối</gray>")
			)), (clicker, event, gui) -> {
			});
		}
	}

	private void openDashboard(Player player, int returnPage) {
		UUID playerId = player.getUniqueId();
		String title = "<gradient:#6DFFD4:#4EA3FF>Thống Kê Toàn Mạng</gradient>";
		GuiView view = new GuiView(GUI_SIZE, LunaUi.guiTitle(title));
		guiManager.track(view);
		renderDashboardPage(player, view, returnPage);
		debug("open dashboard for " + player.getName() + ", returnPage=" + returnPage);

		sessions.put(playerId, new SelectorSession(view, true, returnPage));

		suppressCloseCleanup.add(playerId);
		player.openInventory(view.getInventory());
		plugin.getServer().getScheduler().runTask(plugin, () -> suppressCloseCleanup.remove(playerId));
	}

	private void renderDashboardPage(Player player, GuiView view, int returnPage) {
		DashboardStats stats = ServerSelectorEngine.dashboardStats(statusStore.snapshot());
		debug("render dashboard for " + player.getName() + ": online=" + stats.onlineServersOnly().size() + "/" + stats.allServers().size() + ", avgTps=" + String.format(Locale.US, "%.2f", stats.averageTps()) + ", avgCpu=" + String.format(Locale.US, "%.1f", stats.averageCpu()) + ", avgLatency=" + String.format(Locale.US, "%.0f", stats.averageLatency()));

		fillDashboardBackground(view);
		view.setItem(10, LunaUi.item(Material.CLOCK, "<yellow>TPS Tổng Thể</yellow>", List.of(
			LunaUi.mini("<gray>Giá trị trung bình toàn mạng</gray>"),
			LunaUi.mini(LunaProgressBarPresets.tps("TPS", stats.averageTps()).render())
		)));
		view.setItem(12, LunaUi.item(Material.REDSTONE, "<color:#FF9A4D>CPU Trung Bình</color>", List.of(
			LunaUi.mini("<gray>Tải CPU theo heartbeat backend</gray>"),
			LunaUi.mini(LunaProgressBarPresets.cpu("CPU", stats.averageCpu()).render())
		)));
		view.setItem(14, LunaUi.item(Material.IRON_BLOCK, "<color:#7FDBFF>RAM Tổng</color>", List.of(
			LunaUi.mini("<gray>Sử dụng bộ nhớ toàn mạng</gray>"),
			LunaUi.mini(LunaProgressBarPresets.ram("RAM", stats.totalRamUsedBytes(), stats.totalRamMaxBytes()).render()),
			LunaUi.mini("<gray>" + formatMb(stats.totalRamUsedBytes()) + "MB / " + formatMb(stats.totalRamMaxBytes()) + "MB</gray>")
		)));
		view.setItem(16, LunaUi.item(Material.REPEATER, "<aqua>Latency Heartbeat</aqua>", List.of(
			LunaUi.mini("<gray>Độ trễ backend → proxy</gray>"),
			LunaUi.mini(LunaProgressBarPresets.latency("Latency", stats.averageLatency()).render())
		)));
		view.setItem(31, LunaUi.item(Material.CHEST, "<gold>Uptime Cao Nhất</gold>", List.of(
			LunaUi.mini("<gray>Máy chủ chạy lâu nhất</gray>"),
			LunaUi.mini("<white>" + Formatters.duration(Duration.ofMillis(Math.max(0L, stats.maxUptimeMillis()))) + "</white>")
		)));

		view.setItem(30, LunaUi.item(Material.EMERALD, "<green>Online Servers</green>", List.of(
			LunaUi.mini("<white>" + stats.onlineServerCount() + "</white><gray>/</gray><white>" + stats.allServers().size() + "</white>")
		)));
		view.setItem(32, LunaUi.item(Material.PLAYER_HEAD, "<color:#9EE6A3>Người Chơi Toàn Mạng</color>", List.of(
			LunaUi.mini("<white>" + stats.totalOnlinePlayers() + "</white>")
		)));

		view.setItem(49, LunaUi.item(Material.ARROW, "<yellow>Quay Lại Danh Sách Server</yellow>", List.of(
			LunaUi.mini("<gray>Trở về trang trước đó</gray>")
		)), (clicker, event, gui) -> open(clicker, returnPage));
	}

	private void sendConnectRequest(Player player, String backendName) {
		messaging.send(player, CoreServerSelectorMessageChannels.CONNECT_REQUEST, writer -> {
			writer.writeUtf(player.getUniqueId().toString());
			writer.writeUtf(backendName == null ? "" : backendName);
		});
	}

	private void decorateServerGrid(GuiView view, Set<Integer> occupiedSlots) {
		Material[] gradientBorder = new Material[] {
			Material.PURPLE_STAINED_GLASS_PANE,
			Material.MAGENTA_STAINED_GLASS_PANE,
			Material.PINK_STAINED_GLASS_PANE,
			Material.RED_STAINED_GLASS_PANE,
			Material.ORANGE_STAINED_GLASS_PANE,
			Material.YELLOW_STAINED_GLASS_PANE,
			Material.LIME_STAINED_GLASS_PANE,
			Material.GREEN_STAINED_GLASS_PANE,
			Material.CYAN_STAINED_GLASS_PANE,
			Material.LIGHT_BLUE_STAINED_GLASS_PANE,
			Material.BLUE_STAINED_GLASS_PANE
		};
		List<Integer> borderSlots = borderSlots();
		int borderIndex = 0;
		for (int slot : borderSlots) {
			if (occupiedSlots.contains(slot)) {
				continue;
			}
			Material pane = gradientBorder[borderIndex % gradientBorder.length];
			view.setItem(slot, LunaUi.item(pane, "<gradient:#6DFFD4:#4EA3FF>◈</gradient>", List.of()), (clicker, event, gui) -> {
			});
			borderIndex++;
		}
	}

	private void decorateFooter(GuiView view) {
		for (int slot = 45; slot <= 53; slot++) {
			view.setItem(slot, LunaUi.item(Material.BLACK_STAINED_GLASS_PANE, "<dark_gray> </dark_gray>", List.of()), (clicker, event, gui) -> {
			});
		}
	}

	private void fillDashboardBackground(GuiView view) {
		for (int slot = 0; slot < GUI_SIZE; slot++) {
			view.setItem(slot, LunaUi.item(Material.GRAY_STAINED_GLASS_PANE, "<gray> </gray>", List.of()), (clicker, event, gui) -> {
			});
		}
	}

	private List<Integer> borderSlots() {
		List<Integer> slots = new ArrayList<>();
		for (int slot = 0; slot <= 44; slot++) {
			int row = slot / 9;
			int col = slot % 9;
			if (row == 0 || row == 4 || col == 0 || col == 8) {
				slots.add(slot);
			}
		}
		return slots;
	}

	private long formatMb(long bytes) {
		if (bytes <= 0L) {
			return 0L;
		}
		return Math.max(0L, bytes / 1024L / 1024L);
	}

	/**
	 * Ask for a redraw of every open menu.
	 *
	 * Rows now arrive as they change rather than once per heartbeat, so a busy
	 * cluster can produce several updates a second. Coalescing them keeps the
	 * redraw — which rebuilds 54 items per viewer on the main thread — bounded,
	 * while still landing well inside what a player perceives as immediate.
	 */
	private void scheduleRefresh() {
		if (sessions.isEmpty() || !refreshScheduled.compareAndSet(false, true)) {
			return;
		}

		plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
			refreshScheduled.set(false);
			refreshOpenMenus();
		}, REFRESH_COALESCE_TICKS);
	}

	private void refreshOpenMenus() {
		long startedAt = System.currentTimeMillis();
		int refreshed = 0;
		int openCount = sessions.size();

		for (Map.Entry<UUID, SelectorSession> entry : new LinkedHashMap<>(sessions).entrySet()) {
			UUID playerId = entry.getKey();
			SelectorSession session = entry.getValue();
			Player player = plugin.getServer().getPlayer(playerId);

			// the only two reasons to forget a viewer: they left, or what they are
			// looking at is no longer the menu we drew
			if (player == null || !player.isOnline() || !session.isCurrent(player)) {
				sessions.remove(playerId, session);
				continue;
			}

			if (session.dashboard) {
				renderDashboardPage(player, session.view, session.page);
				refreshed++;
				continue;
			}

			ServerSelectorPayload payload = syncedPayload;
			Map<Integer, Map<Integer, ServerRenderEntry>> layoutByPage = layoutByPage(payload);
			int maxPage = layoutByPage.isEmpty() ? 0 : layoutByPage.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
			int currentPage = Math.max(0, Math.min(session.page, maxPage));
			session.page = currentPage;
			renderSelectorPage(player, session.view, payload, layoutByPage, currentPage, maxPage);
			refreshed++;
		}

		if (diagnosticsEnabled) {
			long elapsedMs = Math.max(0L, System.currentTimeMillis() - startedAt);
			debug("refresh cycle complete: refreshed=" + refreshed + "/" + openCount + ", elapsedMs=" + elapsedMs);
			if (elapsedMs > refreshWarnThresholdMs) {
				logger.warn("Selector diagnostics: refresh mất " + elapsedMs + "ms, viewers=" + refreshed + "/" + openCount);
			}
		}
	}

	private Map<Integer, Map<Integer, ServerRenderEntry>> layoutByPage(ServerSelectorPayload payload) {
		return ServerSelectorEngine.layoutByPage(payload, statusStore.snapshot(), this::debug);
	}

	private org.bukkit.inventory.ItemStack buildServerItem(
		BackendServerStatus status,
		ServerPayload serverPayload,
		ServerSelectorPayload payload,
		boolean noPermission
	) {
		RenderedServerItem renderedItem = ServerSelectorEngine.renderServerItem(status, serverPayload, payload, noPermission);
		Material material = parseMaterial(renderedItem.materialName());
		List<Component> lore = new ArrayList<>();
		for (String line : renderedItem.lore()) {
			lore.add(LunaUi.mini(line));
		}
		org.bukkit.inventory.ItemStack item = LunaUi.item(material == null ? Material.BARRIER : material, renderedItem.title(), lore);
		if (renderedItem.glint() != null) {
			ItemMeta meta = item.getItemMeta();
			meta.setEnchantmentGlintOverride(renderedItem.glint());
			item.setItemMeta(meta);
		}
		return item;
	}

	private Material parseMaterial(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}

		return Material.matchMaterial(value.trim().toUpperCase(Locale.ROOT));
	}

	private String applyTemplate(String template, Map<String, String> values) {
		return ServerSelectorEngine.applyTemplate(template, values);
	}

	private ServerSelectorPayload parsePayload(PluginMessageReader reader) {
		return ServerSelectorEngine.parsePayload(reader);
	}

	private void debug(String message) {
		if (!diagnosticsEnabled) {
			return;
		}
		logger.debug("Selector diagnostics: " + message);
	}
}
