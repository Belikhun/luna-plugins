package dev.belikhun.luna.core.paper.serverselector;

import dev.belikhun.luna.core.api.gui.GuiManager;
import dev.belikhun.luna.core.api.gui.GuiView;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.CoreServerSelectorMessageChannels;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.api.ui.LunaProgressBarPresets;
import dev.belikhun.luna.core.api.ui.LunaUi;
import dev.belikhun.luna.core.paper.heartbeat.PaperBackendStatusView;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PaperServerSelectorController implements Listener {
	private static final int GUI_SIZE = 54;
	private static final int PAGE_SIZE = 45;
	private static final int SLOT_PREV_PAGE = 52;
	private static final int SLOT_LOBBY = 46;
	private static final int SLOT_PREVIOUS_SERVER = 47;
	private static final int SLOT_DASHBOARD = 48;
	private static final int SLOT_CLOSE = 49;
	private static final int SLOT_NEXT_PAGE = 53;
	private static final Pattern MC_VERSION_PATTERN = Pattern.compile("\\(MC:\\s*([^)]+)\\)", Pattern.CASE_INSENSITIVE);
	private static final Pattern SEMVER_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+){1,3})");

	private final JavaPlugin plugin;
	private final PaperBackendStatusView statusView;
	private final PluginMessageBus<Player, Player> messaging;
	private final LunaLogger logger;
	private final boolean diagnosticsEnabled;
	private final long refreshWarnThresholdMs;
	private final GuiManager guiManager;
	private final Map<UUID, Integer> openPages;
	private final Map<UUID, SelectorPayload> payloadByPlayer;
	private final Map<UUID, Inventory> selectorInventoryByPlayer;
	private final Map<UUID, GuiView> selectorViewByPlayer;
	private final Set<UUID> suppressCloseCleanup;

	public PaperServerSelectorController(
		JavaPlugin plugin,
		PaperBackendStatusView statusView,
		PluginMessageBus<Player, Player> messaging,
		LunaLogger logger,
		boolean diagnosticsEnabled,
		long refreshWarnThresholdMs
	) {
		this.plugin = plugin;
		this.statusView = statusView;
		this.messaging = messaging;
		this.logger = logger.scope("ServerSelector");
		this.diagnosticsEnabled = diagnosticsEnabled;
		this.refreshWarnThresholdMs = Math.max(1L, refreshWarnThresholdMs);
		this.guiManager = new GuiManager();
		this.openPages = new ConcurrentHashMap<>();
		this.payloadByPlayer = new ConcurrentHashMap<>();
		this.selectorInventoryByPlayer = new ConcurrentHashMap<>();
		this.selectorViewByPlayer = new ConcurrentHashMap<>();
		this.suppressCloseCleanup = ConcurrentHashMap.newKeySet();

		plugin.getServer().getPluginManager().registerEvents(guiManager, plugin);
		plugin.getServer().getPluginManager().registerEvents(this, plugin);
		statusView.addUpdateListener(() -> plugin.getServer().getScheduler().runTask(plugin, this::refreshOpenMenus));

		messaging.registerOutgoing(CoreServerSelectorMessageChannels.CONNECT_REQUEST);
		messaging.registerIncoming(CoreServerSelectorMessageChannels.OPEN_MENU, context -> {
			Player player = context.source();
			PluginMessageReader reader = PluginMessageReader.of(context.payload());
			SelectorPayload payload = parsePayload(reader);
			payloadByPlayer.put(player.getUniqueId(), payload);
			plugin.getServer().getScheduler().runTask(plugin, () -> open(player, openPages.getOrDefault(player.getUniqueId(), 0)));
			return dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult.HANDLED;
		});
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		UUID playerId = event.getPlayer().getUniqueId();
		openPages.remove(playerId);
		payloadByPlayer.remove(playerId);
		selectorInventoryByPlayer.remove(playerId);
		selectorViewByPlayer.remove(playerId);
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

		Inventory trackedInventory = selectorInventoryByPlayer.get(playerId);
		if (trackedInventory != null && trackedInventory.equals(event.getInventory())) {
			openPages.remove(playerId);
			selectorInventoryByPlayer.remove(playerId);
			selectorViewByPlayer.remove(playerId);
		}
	}

	public void open(Player player, int page) {
		UUID playerId = player.getUniqueId();
		SelectorPayload payload = payloadByPlayer.get(player.getUniqueId());
		Map<Integer, Map<Integer, ServerRenderEntry>> layoutByPage = layoutByPage(payload);
		int maxPage = layoutByPage.isEmpty() ? 0 : layoutByPage.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
		int currentPage = Math.max(0, Math.min(page, maxPage));
		openPages.put(playerId, currentPage);

		GuiView view = selectorViewByPlayer.get(playerId);
		boolean canUpdateInPlace = view != null
			&& player.getOpenInventory() != null
			&& player.getOpenInventory().getTopInventory() != null
			&& view.getInventory().equals(player.getOpenInventory().getTopInventory());
		if (canUpdateInPlace && view != null) {
			renderSelectorPage(player, view, payload, layoutByPage, currentPage, maxPage);
			selectorInventoryByPlayer.put(playerId, view.getInventory());
			return;
		}

		String title = payload == null ? "Danh Sách Máy Chủ" : payload.guiTitle();
		view = new GuiView(GUI_SIZE, LunaUi.guiTitle(applyTemplate(title, Map.of("player_name", player.getName()))));
		guiManager.track(view);
		renderSelectorPage(player, view, payload, layoutByPage, currentPage, maxPage);
		selectorViewByPlayer.put(playerId, view);

		suppressCloseCleanup.add(playerId);
		player.openInventory(view.getInventory());
		selectorInventoryByPlayer.put(playerId, view.getInventory());
		plugin.getServer().getScheduler().runTask(plugin, () -> suppressCloseCleanup.remove(playerId));
	}

	private void renderSelectorPage(
		Player player,
		GuiView view,
		SelectorPayload payload,
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
		Map<String, BackendServerStatus> snapshot = statusView.snapshot();
		List<BackendServerStatus> all = new ArrayList<>(snapshot.values());
		all.sort(Comparator.comparing(status -> status.serverName().toLowerCase(Locale.ROOT)));

		double avgTps = average(all, status -> status.stats() == null ? 0D : status.stats().tps());
		double avgCpu = average(all, status -> status.stats() == null ? 0D : status.stats().systemCpuUsagePercent());
		double avgLatency = average(all, status -> status.stats() == null ? 0D : status.stats().heartbeatLatencyMillis());
		long totalRamUsed = sumLong(all, status -> status.stats() == null ? 0L : status.stats().ramUsedBytes());
		long totalRamMax = sumLong(all, status -> status.stats() == null ? 0L : status.stats().ramMaxBytes());
		long maxUptimeMillis = maxLong(all, status -> status.stats() == null ? 0L : status.stats().uptimeMillis());

		String title = "<gradient:#6DFFD4:#4EA3FF>Thống Kê Toàn Mạng</gradient>";
		GuiView view = new GuiView(GUI_SIZE, LunaUi.guiTitle(title));
		guiManager.track(view);

		fillDashboardBackground(view);
		view.setItem(10, LunaUi.item(Material.CLOCK, "<yellow>TPS Tổng Thể</yellow>", List.of(
			LunaUi.mini("<gray>Giá trị trung bình toàn mạng</gray>"),
			LunaUi.mini(LunaProgressBarPresets.tps("TPS", avgTps).render())
		)));
		view.setItem(12, LunaUi.item(Material.REDSTONE, "<color:#FF9A4D>CPU Trung Bình</color>", List.of(
			LunaUi.mini("<gray>Tải CPU theo heartbeat backend</gray>"),
			LunaUi.mini(LunaProgressBarPresets.cpu("CPU", avgCpu).render())
		)));
		view.setItem(14, LunaUi.item(Material.IRON_BLOCK, "<color:#7FDBFF>RAM Tổng</color>", List.of(
			LunaUi.mini("<gray>Sử dụng bộ nhớ toàn mạng</gray>"),
			LunaUi.mini(LunaProgressBarPresets.ram("RAM", totalRamUsed, totalRamMax).render()),
			LunaUi.mini("<gray>" + formatMb(totalRamUsed) + "MB / " + formatMb(totalRamMax) + "MB</gray>")
		)));
		view.setItem(16, LunaUi.item(Material.REPEATER, "<aqua>Latency Heartbeat</aqua>", List.of(
			LunaUi.mini("<gray>Độ trễ backend → proxy</gray>"),
			LunaUi.mini(LunaProgressBarPresets.latency("Latency", avgLatency).render())
		)));
		view.setItem(31, LunaUi.item(Material.CHEST, "<gold>Uptime Cao Nhất</gold>", List.of(
			LunaUi.mini("<gray>Máy chủ chạy lâu nhất</gray>"),
			LunaUi.mini("<white>" + Formatters.duration(Duration.ofMillis(Math.max(0L, maxUptimeMillis))) + "</white>")
		)));

		int onlineServers = (int) all.stream().filter(BackendServerStatus::online).count();
		view.setItem(30, LunaUi.item(Material.EMERALD, "<green>Online Servers</green>", List.of(
			LunaUi.mini("<white>" + onlineServers + "</white><gray>/</gray><white>" + all.size() + "</white>")
		)));
		view.setItem(32, LunaUi.item(Material.PLAYER_HEAD, "<color:#9EE6A3>Người Chơi Toàn Mạng</color>", List.of(
			LunaUi.mini("<white>" + sumLong(all, status -> status.stats() == null ? 0L : status.stats().onlinePlayers()) + "</white>")
		)));

		view.setItem(49, LunaUi.item(Material.ARROW, "<yellow>Quay Lại Danh Sách Server</yellow>", List.of(
			LunaUi.mini("<gray>Trở về trang trước đó</gray>")
		)), (clicker, event, gui) -> open(clicker, returnPage));
		openPages.remove(player.getUniqueId());
		selectorViewByPlayer.remove(player.getUniqueId());

		suppressCloseCleanup.add(player.getUniqueId());
		player.openInventory(view.getInventory());
		selectorInventoryByPlayer.put(player.getUniqueId(), view.getInventory());
		plugin.getServer().getScheduler().runTask(plugin, () -> suppressCloseCleanup.remove(player.getUniqueId()));
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

	private double average(List<BackendServerStatus> values, java.util.function.ToDoubleFunction<BackendServerStatus> mapper) {
		if (values.isEmpty()) {
			return 0D;
		}
		double total = 0D;
		int count = 0;
		for (BackendServerStatus value : values) {
			total += mapper.applyAsDouble(value);
			count++;
		}
		return count == 0 ? 0D : total / count;
	}

	private long sumLong(List<BackendServerStatus> values, java.util.function.ToLongFunction<BackendServerStatus> mapper) {
		long total = 0L;
		for (BackendServerStatus value : values) {
			total += mapper.applyAsLong(value);
		}
		return total;
	}

	private long maxLong(List<BackendServerStatus> values, java.util.function.ToLongFunction<BackendServerStatus> mapper) {
		long max = 0L;
		for (BackendServerStatus value : values) {
			max = Math.max(max, mapper.applyAsLong(value));
		}
		return max;
	}

	private long formatMb(long bytes) {
		if (bytes <= 0L) {
			return 0L;
		}
		return Math.max(0L, bytes / 1024L / 1024L);
	}

	private String shortVersion(String full) {
		if (full == null || full.isBlank()) {
			return "unknown";
		}

		Matcher mcMatcher = MC_VERSION_PATTERN.matcher(full);
		if (mcMatcher.find()) {
			return mcMatcher.group(1).trim();
		}

		Matcher semverMatcher = SEMVER_PATTERN.matcher(full);
		if (semverMatcher.find()) {
			return semverMatcher.group(1).trim();
		}

		return full.trim();
	}

	private void refreshOpenMenus() {
		long startedAt = System.currentTimeMillis();
		int refreshed = 0;
		int openCount = openPages.size();
		for (Map.Entry<UUID, Integer> entry : new LinkedHashMap<>(openPages).entrySet()) {
			Player player = plugin.getServer().getPlayer(entry.getKey());
			if (player == null || !player.isOnline()) {
				openPages.remove(entry.getKey());
				selectorInventoryByPlayer.remove(entry.getKey());
				selectorViewByPlayer.remove(entry.getKey());
				continue;
			}

			Inventory trackedInventory = selectorInventoryByPlayer.get(entry.getKey());
			if (trackedInventory == null) {
				openPages.remove(entry.getKey());
				selectorViewByPlayer.remove(entry.getKey());
				continue;
			}

			if (player.getOpenInventory() == null || player.getOpenInventory().getTopInventory() == null) {
				openPages.remove(entry.getKey());
				selectorInventoryByPlayer.remove(entry.getKey());
				selectorViewByPlayer.remove(entry.getKey());
				continue;
			}

			if (!trackedInventory.equals(player.getOpenInventory().getTopInventory())) {
				openPages.remove(entry.getKey());
				selectorInventoryByPlayer.remove(entry.getKey());
				selectorViewByPlayer.remove(entry.getKey());
				continue;
			}

			GuiView view = selectorViewByPlayer.get(entry.getKey());
			if (view == null || !view.getInventory().equals(trackedInventory)) {
				openPages.remove(entry.getKey());
				selectorInventoryByPlayer.remove(entry.getKey());
				selectorViewByPlayer.remove(entry.getKey());
				continue;
			}

			SelectorPayload payload = payloadByPlayer.get(entry.getKey());
			Map<Integer, Map<Integer, ServerRenderEntry>> layoutByPage = layoutByPage(payload);
			int maxPage = layoutByPage.isEmpty() ? 0 : layoutByPage.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
			int currentPage = Math.max(0, Math.min(entry.getValue(), maxPage));
			openPages.put(entry.getKey(), currentPage);
			renderSelectorPage(player, view, payload, layoutByPage, currentPage, maxPage);
			refreshed++;
		}

		if (diagnosticsEnabled) {
			long elapsedMs = Math.max(0L, System.currentTimeMillis() - startedAt);
			if (elapsedMs > refreshWarnThresholdMs) {
				logger.warn("Selector diagnostics: refresh mất " + elapsedMs + "ms, viewers=" + refreshed + "/" + openCount);
			}
		}
	}

	private Map<Integer, Map<Integer, ServerRenderEntry>> layoutByPage(SelectorPayload payload) {
		List<ServerRenderEntry> entries = sortedEntries(payload);
		Map<Integer, Map<Integer, ServerRenderEntry>> byPage = new LinkedHashMap<>();
		List<ServerRenderEntry> unresolved = new ArrayList<>();

		for (ServerRenderEntry entry : entries) {
			ServerPayload payloadEntry = entry.payload();
			if (payloadEntry == null || payloadEntry.slot() == null || payloadEntry.page() == null) {
				unresolved.add(entry);
				continue;
			}

			int slot = payloadEntry.slot();
			int page = payloadEntry.page() - 1;
			if (slot < 0 || slot >= PAGE_SIZE || page < 0) {
				unresolved.add(entry);
				continue;
			}
			byPage.computeIfAbsent(page, ignored -> new LinkedHashMap<>()).put(slot, entry);
		}

		int pagePointer = 0;
		for (ServerRenderEntry entry : unresolved) {
			while (true) {
				Map<Integer, ServerRenderEntry> pageLayout = byPage.computeIfAbsent(pagePointer, ignored -> new LinkedHashMap<>());
				int slot = firstFreeSlot(pageLayout);
				if (slot == -1) {
					pagePointer++;
					continue;
				}
				pageLayout.put(slot, entry);
				break;
			}
		}

		return byPage;
	}

	private List<ServerRenderEntry> sortedEntries(SelectorPayload payload) {
		List<ServerRenderEntry> entries = new ArrayList<>();
		if (payload != null && !payload.servers().isEmpty()) {
			for (ServerPayload item : payload.servers().values()) {
				BackendServerStatus status = statusView.status(item.backendName()).orElseGet(() -> new BackendServerStatus(
					item.backendName(),
					item.displayName(),
					item.accentColor(),
					false,
					0L,
					null
				));
				entries.add(new ServerRenderEntry(status, item));
			}
		} else {
			for (BackendServerStatus status : statusView.snapshot().values()) {
				entries.add(new ServerRenderEntry(status, null));
			}
		}
		entries.sort(Comparator.comparing(entry -> entry.status().serverName().toLowerCase(Locale.ROOT)));
		return entries;
	}

	private int firstFreeSlot(Map<Integer, ServerRenderEntry> pageLayout) {
		for (int slot = 0; slot < PAGE_SIZE; slot++) {
			if (!pageLayout.containsKey(slot)) {
				return slot;
			}
		}
		return -1;
	}

	private org.bukkit.inventory.ItemStack buildServerItem(
		BackendServerStatus status,
		ServerPayload serverPayload,
		SelectorPayload payload,
		boolean noPermission
	) {
		String statusText = resolveStatus(status, noPermission);
		String statusColor = payload == null ? "<white>" : payload.statusColor(statusText);
		String statusIcon = payload == null ? "●" : payload.statusIcon(statusText);
		Material material = resolveMaterial(statusText);

		int onlinePlayers = status.stats() == null ? 0 : status.stats().onlinePlayers();
		int maxPlayers = status.stats() == null ? 0 : status.stats().maxPlayers();
		String display = serverPayload != null && !serverPayload.displayName().isBlank()
			? serverPayload.displayName()
			: (status.serverDisplay() == null || status.serverDisplay().isBlank() ? status.serverName() : status.serverDisplay());

		TemplatePayload template = payload == null
			? TemplatePayload.defaultTemplate()
			: payload.resolveTemplate(serverPayload, statusText);

		Map<String, String> values = placeholderValues(status, display, statusText, statusColor, statusIcon, onlinePlayers, maxPlayers);
		List<Component> lore = new ArrayList<>();
		for (String headerLine : template.headerLines()) {
			lore.add(LunaUi.mini(applyTemplate(headerLine == null ? "" : headerLine, values)));
		}

		List<String> description = serverPayload == null ? List.of() : serverPayload.description(statusText);
		for (String line : description) {
			Map<String, String> withLine = new LinkedHashMap<>(values);
			withLine.put("line", line == null ? "" : line);
			lore.add(LunaUi.mini(applyTemplate(template.bodyLineTemplate(), withLine)));
		}

		for (String footerLine : template.footerLines()) {
			lore.add(LunaUi.mini(applyTemplate(footerLine == null ? "" : footerLine, values)));
		}

		return LunaUi.item(material, applyTemplate(template.nameTemplate(), values), lore);
	}

	private Material resolveMaterial(String statusText) {
		if ("NOP".equals(statusText)) {
			return Material.GRAY_CONCRETE;
		}
		if ("OFFLINE".equals(statusText)) {
			return Material.RED_CONCRETE;
		}
		if ("MAINT".equals(statusText)) {
			return Material.YELLOW_CONCRETE;
		}
		return Material.LIME_CONCRETE;
	}

	private String resolveStatus(BackendServerStatus status, boolean noPermission) {
		if (noPermission) {
			return "NOP";
		}
		if (!status.online()) {
			return "OFFLINE";
		}
		if (status.stats() != null && status.stats().whitelistEnabled()) {
			return "MAINT";
		}
		return "ONLINE";
	}

	private Map<String, String> placeholderValues(
		BackendServerStatus status,
		String display,
		String statusText,
		String statusColor,
		String statusIcon,
		int online,
		int max
	) {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("server_name", status.serverName());
		values.put("server_display", display);
		values.put("server_accent_color", status.serverAccentColor() == null ? "" : status.serverAccentColor());
		values.put("server_status", statusText);
		values.put("server_status_color", statusColor);
		values.put("server_status_icon", statusIcon);
		values.put("online", String.valueOf(online));
		values.put("max", String.valueOf(max));
		long ramUsedBytes = status.stats() == null ? 0L : Math.max(0L, status.stats().ramUsedBytes());
		long ramMaxBytes = status.stats() == null ? 0L : Math.max(0L, status.stats().ramMaxBytes());
		String versionFull = status.stats() == null ? "unknown" : status.stats().version();
		String versionShort = shortVersion(versionFull);
		values.put("version", versionShort);
		values.put("server_version", versionShort);
		values.put("server_version_full", versionFull);
		values.put("tps", status.stats() == null ? "0.00" : String.format(Locale.US, "%.2f", status.stats().tps()));
		values.put("uptime", Formatters.duration(Duration.ofMillis(status.stats() == null ? 0L : Math.max(0L, status.stats().uptimeMillis()))));
		values.put("cpu_usage", status.stats() == null ? "0.0" : String.format(Locale.US, "%.1f", Math.max(0D, status.stats().systemCpuUsagePercent())));
		values.put("ram_used_mb", String.valueOf(ramUsedBytes / 1024L / 1024L));
		values.put("ram_max_mb", String.valueOf(ramMaxBytes / 1024L / 1024L));
		values.put("ram_percent", String.format(Locale.US, "%.1f", ramMaxBytes <= 0L ? 0D : Math.min(100D, (ramUsedBytes * 100D) / ramMaxBytes)));
		values.put("latency_ms", String.valueOf(status.stats() == null ? 0L : Math.max(0L, status.stats().heartbeatLatencyMillis())));
		return values;
	}

	private String applyTemplate(String template, Map<String, String> values) {
		String output = template == null ? "" : template;
		for (Map.Entry<String, String> entry : values.entrySet()) {
			output = output.replace("%" + entry.getKey() + "%", entry.getValue() == null ? "" : entry.getValue());
		}
		return output;
	}

	private SelectorPayload parsePayload(PluginMessageReader reader) {
		try {
			String mode = reader.readUtf();
			boolean v3 = "open-v3".equalsIgnoreCase(mode);
			boolean v4 = "open-v4".equalsIgnoreCase(mode);
			if (!v3 && !v4) {
				return SelectorPayload.empty();
			}

			String guiTitle = reader.readUtf();
			String name = reader.readUtf();
			List<String> header = List.copyOf(readLines(reader));
			String bodyLine = reader.readUtf();
			List<String> footer = List.copyOf(readLines(reader));
			Map<String, TemplateOverridePayload> globalTemplateByStatus = readTemplateOverrides(reader);

			TemplatePayload baseTemplate = new TemplatePayload(name, header, bodyLine, footer, globalTemplateByStatus);
			Map<String, String> statusColors = defaultStatusColors();
			Map<String, String> statusIcons = defaultStatusIcons();
			if (v4) {
				Map<String, String> payloadColors = new LinkedHashMap<>();
				Map<String, String> payloadIcons = new LinkedHashMap<>();
				int count = Math.max(0, reader.readInt());
				for (int i = 0; i < count; i++) {
					String statusKey = reader.readUtf().toUpperCase(Locale.ROOT);
					payloadColors.put(statusKey, reader.readUtf());
					payloadIcons.put(statusKey, reader.readUtf());
				}
				if (!payloadColors.isEmpty()) {
					statusColors = Map.copyOf(payloadColors);
				}
				if (!payloadIcons.isEmpty()) {
					statusIcons = Map.copyOf(payloadIcons);
				}
			}

			int serverCount = Math.max(0, reader.readInt());
			Map<String, ServerPayload> servers = new LinkedHashMap<>();
			for (int i = 0; i < serverCount; i++) {
				String backendName = reader.readUtf();
				String display = reader.readUtf();
				String accent = reader.readUtf();
				String permission = reader.readUtf();
				int rawSlot = reader.readInt();
				int rawPage = reader.readInt();
				Integer slot = rawSlot < 0 ? null : rawSlot;
				Integer page = rawPage < 0 ? null : rawPage;
				List<String> description = List.copyOf(readLines(reader));

				int statusDescriptionCount = Math.max(0, reader.readInt());
				Map<String, List<String>> descriptionByStatus = new LinkedHashMap<>();
				for (int index = 0; index < statusDescriptionCount; index++) {
					String statusKey = reader.readUtf().toUpperCase(Locale.ROOT);
					descriptionByStatus.put(statusKey, List.copyOf(readLines(reader)));
				}

				TemplatePayload serverTemplate = null;
				boolean hasServerTemplate = reader.readBoolean();
				if (hasServerTemplate) {
					serverTemplate = new TemplatePayload(
						reader.readUtf(),
						List.copyOf(readLines(reader)),
						reader.readUtf(),
						List.copyOf(readLines(reader)),
						readTemplateOverrides(reader)
					);
				}

				servers.put(backendName.toLowerCase(Locale.ROOT), new ServerPayload(
					backendName,
					display,
					accent,
					permission,
					slot,
					page,
					description,
					Map.copyOf(descriptionByStatus),
					serverTemplate
				));
			}
			return new SelectorPayload(guiTitle, baseTemplate, statusColors, statusIcons, servers);
		} catch (Exception ignored) {
			return SelectorPayload.empty();
		}
	}

	private static Map<String, String> defaultStatusColors() {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("ONLINE", "<green>");
		values.put("OFFLINE", "<red>");
		values.put("MAINT", "<yellow>");
		values.put("NOP", "<gray>");
		return Map.copyOf(values);
	}

	private static Map<String, String> defaultStatusIcons() {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("ONLINE", "✔");
		values.put("OFFLINE", "✘");
		values.put("MAINT", "⚠");
		values.put("NOP", "🔒");
		return Map.copyOf(values);
	}

	private List<String> readLines(PluginMessageReader reader) {
		int lineCount = Math.max(0, reader.readInt());
		List<String> lines = new ArrayList<>(lineCount);
		for (int i = 0; i < lineCount; i++) {
			lines.add(reader.readUtf());
		}
		return lines;
	}

	private Map<String, TemplateOverridePayload> readTemplateOverrides(PluginMessageReader reader) {
		int overrideCount = Math.max(0, reader.readInt());
		Map<String, TemplateOverridePayload> overrides = new LinkedHashMap<>();
		for (int i = 0; i < overrideCount; i++) {
			String status = reader.readUtf().toUpperCase(Locale.ROOT);
			String name = null;
			List<String> header = null;
			String bodyLine = null;
			List<String> footer = null;

			if (reader.readBoolean()) {
				name = reader.readUtf();
			}
			if (reader.readBoolean()) {
				header = List.copyOf(readLines(reader));
			}
			if (reader.readBoolean()) {
				bodyLine = reader.readUtf();
			}
			if (reader.readBoolean()) {
				footer = List.copyOf(readLines(reader));
			}

			overrides.put(status, new TemplateOverridePayload(name, header, bodyLine, footer));
		}
		return Map.copyOf(overrides);
	}

	private record SelectorPayload(
		String guiTitle,
		TemplatePayload template,
		Map<String, String> statusColors,
		Map<String, String> statusIcons,
		Map<String, ServerPayload> servers
	) {
		static SelectorPayload empty() {
			return new SelectorPayload("Danh Sách Máy Chủ", TemplatePayload.defaultTemplate(), defaultStatusColors(), defaultStatusIcons(), Map.of());
		}

		TemplatePayload resolveTemplate(ServerPayload serverPayload, String status) {
			TemplatePayload resolved = template.applyOverride(template.byStatus().get(status));
			if (serverPayload == null || serverPayload.template() == null) {
				return resolved;
			}
			resolved = resolved.merge(serverPayload.template());
			return resolved.applyOverride(serverPayload.template().byStatus().get(status));
		}

		String statusColor(String status) {
			if (status == null) {
				return "<white>";
			}
			return statusColors.getOrDefault(status.toUpperCase(Locale.ROOT), "<white>");
		}

		String statusIcon(String status) {
			if (status == null) {
				return "●";
			}
			return statusIcons.getOrDefault(status.toUpperCase(Locale.ROOT), "●");
		}
	}

	private record TemplatePayload(
		String nameTemplate,
		List<String> headerLines,
		String bodyLineTemplate,
		List<String> footerLines,
		Map<String, TemplateOverridePayload> byStatus
	) {
		static TemplatePayload defaultTemplate() {
			return new TemplatePayload("<b>%server_display%</b>", List.of(), "%line%", List.of(), Map.of());
		}

		TemplatePayload applyOverride(TemplateOverridePayload override) {
			if (override == null) {
				return this;
			}
			return new TemplatePayload(
				override.nameTemplate() != null ? override.nameTemplate() : nameTemplate,
				override.headerLines() != null ? override.headerLines() : headerLines,
				override.bodyLineTemplate() != null ? override.bodyLineTemplate() : bodyLineTemplate,
				override.footerLines() != null ? override.footerLines() : footerLines,
				byStatus
			);
		}

		TemplatePayload merge(TemplatePayload serverTemplate) {
			return new TemplatePayload(
				serverTemplate.nameTemplate(),
				serverTemplate.headerLines(),
				serverTemplate.bodyLineTemplate(),
				serverTemplate.footerLines(),
				serverTemplate.byStatus()
			);
		}
	}

	private record TemplateOverridePayload(
		String nameTemplate,
		List<String> headerLines,
		String bodyLineTemplate,
		List<String> footerLines
	) {
	}

	private record ServerPayload(
		String backendName,
		String displayName,
		String accentColor,
		String permission,
		Integer slot,
		Integer page,
		List<String> description,
		Map<String, List<String>> descriptionByStatus,
		TemplatePayload template
	) {
		List<String> description(String status) {
			if (status == null) {
				return description;
			}
			List<String> override = descriptionByStatus.get(status.toUpperCase(Locale.ROOT));
			return override == null ? description : override;
		}
	}

	private record ServerRenderEntry(
		BackendServerStatus status,
		ServerPayload payload
	) {
	}
}
