package dev.belikhun.luna.core.neoforge.serverselector;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.CoreServerSelectorMessageChannels;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.api.ui.LunaProgressBarPresets;
import dev.belikhun.luna.core.neoforge.heartbeat.NeoForgeBackendStatusView;
import dev.belikhun.luna.core.neoforge.heartbeat.NeoForgeHeartbeatPublisher;
import dev.belikhun.luna.core.neoforge.text.NeoForgeTextComponents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NeoForgeServerSelectorController {
	private static final String OPEN_COMMAND = "lunaservers";
	private static final String CONNECT_COMMAND = "lunacoreconnect";
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
	private static final Pattern CONDITION_COMPARISON_PATTERN = Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_]*)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$");

	private final MinecraftServer server;
	private final DependencyManager dependencyManager;
	private final LunaLogger logger;
	private final PermissionService permissionService;
	private final Map<UUID, SelectorPayload> payloadByPlayer;
	private final Map<UUID, NeoForgeServerSelectorMenu> openMenus;
	private final Map<UUID, OpenViewState> openViews;
	private final Set<UUID> suppressCloseCleanup;

	private volatile SelectorPayload selectorPayload;
	private volatile boolean messagingAttached;
	private volatile PluginMessageBus<ServerPlayer, ServerPlayer> messagingBus;
	private volatile NeoForgeHeartbeatPublisher heartbeatPublisher;
	private volatile BackendStatusView statusView;
	private volatile NeoForgeBackendStatusView concreteStatusView;
	private volatile Runnable statusUpdateListener;

	public NeoForgeServerSelectorController(
		MinecraftServer server,
		DependencyManager dependencyManager,
		LunaLogger logger,
		PermissionService permissionService
	) {
		this.server = server;
		this.dependencyManager = dependencyManager;
		this.logger = logger.scope("ServerSelector");
		this.permissionService = permissionService;
		this.payloadByPlayer = new ConcurrentHashMap<>();
		this.openMenus = new ConcurrentHashMap<>();
		this.openViews = new ConcurrentHashMap<>();
		this.suppressCloseCleanup = ConcurrentHashMap.newKeySet();
		this.selectorPayload = SelectorPayload.empty();
		this.messagingAttached = false;
		this.messagingBus = null;
		this.heartbeatPublisher = null;
		this.statusView = null;
		this.concreteStatusView = null;
		this.statusUpdateListener = null;
	}

	public void start(NeoForgeHeartbeatPublisher heartbeatPublisher) {
		this.heartbeatPublisher = heartbeatPublisher;
		this.statusView = dependencyManager.resolveOptional(BackendStatusView.class).orElse(null);
		NeoForgeBackendStatusView resolvedStatusView = dependencyManager.resolveOptional(NeoForgeBackendStatusView.class).orElse(null);
		this.concreteStatusView = resolvedStatusView;
		if (resolvedStatusView != null) {
			Runnable listener = this::scheduleRefreshOpenMenus;
			resolvedStatusView.addUpdateListener(listener);
			this.statusUpdateListener = listener;
		}
		if (heartbeatPublisher != null) {
			heartbeatPublisher.setSelectorPayloadConsumer(this::acceptSelectorPayload);
			heartbeatPublisher.syncServerSelectorConfigNow();
		}
		ensureMessagingAttached();
	}

	public void close() {
		NeoForgeBackendStatusView resolvedStatusView = concreteStatusView;
		Runnable listener = statusUpdateListener;
		if (resolvedStatusView != null && listener != null) {
			resolvedStatusView.removeUpdateListener(listener);
		}
		statusUpdateListener = null;
		statusView = null;
		concreteStatusView = null;
		if (heartbeatPublisher != null) {
			heartbeatPublisher.setSelectorPayloadConsumer(null);
			heartbeatPublisher = null;
		}

		if (messagingAttached && messagingBus != null) {
			messagingBus.unregisterIncoming(CoreServerSelectorMessageChannels.OPEN_MENU);
			messagingBus.unregisterOutgoing(CoreServerSelectorMessageChannels.CONNECT_REQUEST);
		}

		for (NeoForgeServerSelectorMenu menu : openMenus.values()) {
			menu.suppressCloseCallbackOnce();
		}
		openMenus.clear();
		openViews.clear();
		payloadByPlayer.clear();
		suppressCloseCleanup.clear();
		messagingAttached = false;
		messagingBus = null;
		selectorPayload = SelectorPayload.empty();
	}

	public void cleanupPlayer(UUID playerId) {
		if (playerId == null) {
			return;
		}
		openMenus.remove(playerId);
		openViews.remove(playerId);
		payloadByPlayer.remove(playerId);
		suppressCloseCleanup.remove(playerId);
	}

	public void registerCommands(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal(OPEN_COMMAND)
			.requires(source -> source.getEntity() instanceof ServerPlayer)
			.executes(this::executeOpen));

		event.getDispatcher().register(Commands.literal(CONNECT_COMMAND)
			.requires(source -> source.getEntity() instanceof ServerPlayer)
			.executes(this::sendConnectUsage)
			.then(Commands.argument("server", StringArgumentType.word())
				.suggests(this::suggestServers)
				.executes(context -> executeConnect(context.getSource(), StringArgumentType.getString(context, "server")))));
	}

	public void ensureMessagingAttached() {
		if (messagingAttached) {
			return;
		}

		PluginMessageBus<ServerPlayer, ServerPlayer> resolved = resolveMessagingBus();
		if (resolved == null) {
			return;
		}

		resolved.registerOutgoing(CoreServerSelectorMessageChannels.CONNECT_REQUEST);
		resolved.registerIncoming(CoreServerSelectorMessageChannels.OPEN_MENU, context -> {
			ServerPlayer player = context.source();
			if (player == null) {
				return PluginMessageDispatchResult.HANDLED;
			}

			if (context.payload() != null && context.payload().length > 0) {
				SelectorPayload payload = parsePayload(context.payload());
				payloadByPlayer.put(player.getUUID(), payload);
			}

			server.execute(() -> openSelector(player, requestedPage(player.getUUID())));
			return PluginMessageDispatchResult.HANDLED;
		});

		messagingBus = resolved;
		messagingAttached = true;
		logger.audit("Đã gắn NeoForge server selector vào plugin messaging bus.");
	}

	public void acceptSelectorPayload(byte[] payload) {
		SelectorPayload parsed = parsePayload(payload);
		if (parsed.isEmpty()) {
			return;
		}
		selectorPayload = parsed;
		scheduleRefreshOpenMenus();
	}

	private int executeOpen(CommandContext<CommandSourceStack> context) {
		ServerPlayer player = playerFrom(context.getSource());
		return player != null && openSelector(player, requestedPage(player.getUUID())) ? 1 : 0;
	}

	private int sendConnectUsage(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		source.sendSuccess(
			() -> NeoForgeTextComponents.mini(server, CommandStrings.usage(CONNECT_COMMAND, CommandStrings.required("server", "text"))),
			false
		);
		return 1;
	}

	private int executeConnect(CommandSourceStack source, String backendName) {
		ServerPlayer player = playerFrom(source);
		if (player == null) {
			return 0;
		}

		ensureMessagingAttached();
		if (messagingBus == null) {
			source.sendFailure(Component.literal("Hệ thống chuyển máy chủ chưa sẵn sàng."));
			return 0;
		}

		String normalizedBackend = backendName == null ? "" : backendName.trim();
		if (normalizedBackend.isBlank()) {
			source.sendFailure(Component.literal("Thiếu tên máy chủ cần kết nối."));
			return 0;
		}

		SelectorServerEntry entry = currentPayloadFor(player.getUUID()).server(normalizedBackend).orElse(null);
		if (entry != null && !canUse(player, entry.permission())) {
			source.sendFailure(Component.literal("Bạn không có quyền truy cập máy chủ này."));
			return 0;
		}

		if (!sendConnectRequest(player, normalizedBackend)) {
			source.sendFailure(Component.literal("Không thể gửi yêu cầu kết nối tới proxy."));
			return 0;
		}

		source.sendSuccess(() -> Component.literal("Đang chuyển tới máy chủ " + normalizedBackend + "..."), false);
		return 1;
	}

	private boolean openSelector(ServerPlayer player, int page) {
		if (player == null) {
			return false;
		}

		ensureMessagingAttached();
		SelectorPayload payload = currentPayloadFor(player.getUUID());
		if (payload.isEmpty()) {
			syncSelectorPayload();
			payload = currentPayloadFor(player.getUUID());
		}

		if (payload.isEmpty()) {
			player.sendSystemMessage(Component.literal("Danh sách máy chủ đang được đồng bộ. Hãy thử lại sau ít giây."));
			return false;
		}

		Map<Integer, Map<Integer, ServerRenderEntry>> layoutByPage = layoutByPage(payload, player);
		int maxPage = layoutByPage.isEmpty() ? 0 : layoutByPage.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
		int currentPage = Math.max(0, Math.min(page, maxPage));
		SelectorPayload renderPayload = payload;
		Map<Integer, Map<Integer, ServerRenderEntry>> renderLayoutByPage = layoutByPage;
		int renderCurrentPage = currentPage;
		int renderMaxPage = maxPage;
		openViews.put(player.getUUID(), new OpenViewState(ViewKind.SELECTOR, currentPage));
		openMenu(player, selectorTitle(renderPayload, player), menu -> renderSelectorPage(player, menu, renderPayload, renderLayoutByPage, renderCurrentPage, renderMaxPage));
		return true;
	}

	private void openDashboard(ServerPlayer player, int returnPage) {
		if (player == null) {
			return;
		}
		openViews.put(player.getUUID(), new OpenViewState(ViewKind.DASHBOARD, Math.max(0, returnPage)));
		openMenu(player, NeoForgeTextComponents.mini(server, "<gradient:#6DFFD4:#4EA3FF>Thống Kê Toàn Mạng</gradient>"), menu -> renderDashboardPage(player, menu, returnPage));
	}

	private void openMenu(ServerPlayer player, Component title, java.util.function.Consumer<NeoForgeServerSelectorMenu> renderer) {
		UUID playerId = player.getUUID();
		NeoForgeServerSelectorMenu previousMenu = openMenus.get(playerId);
		if (previousMenu != null) {
			previousMenu.suppressCloseCallbackOnce();
		}

		player.openMenu(new SimpleMenuProvider((containerId, inventory, ignoredPlayer) -> {
			NeoForgeServerSelectorMenu menu = new NeoForgeServerSelectorMenu(containerId, inventory, () -> handleMenuClosed(playerId));
			openMenus.put(playerId, menu);
			renderer.accept(menu);
			menu.broadcastChanges();
			return menu;
		}, title));
	}

	private void handleMenuClosed(UUID playerId) {
		if (playerId == null) {
			return;
		}
		if (suppressCloseCleanup.remove(playerId)) {
			return;
		}
		openMenus.remove(playerId);
		openViews.remove(playerId);
	}

	private void scheduleRefreshOpenMenus() {
		server.execute(this::refreshOpenMenus);
	}

	private void refreshOpenMenus() {
		for (Map.Entry<UUID, OpenViewState> entry : new LinkedHashMap<>(openViews).entrySet()) {
			UUID playerId = entry.getKey();
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			NeoForgeServerSelectorMenu menu = openMenus.get(playerId);
			if (player == null || menu == null || player.containerMenu != menu) {
				cleanupPlayer(playerId);
				continue;
			}

			OpenViewState viewState = entry.getValue();
			if (viewState.kind() == ViewKind.DASHBOARD) {
				renderDashboardPage(player, menu, viewState.page());
				menu.broadcastChanges();
				continue;
			}

			SelectorPayload payload = currentPayloadFor(playerId);
			Map<Integer, Map<Integer, ServerRenderEntry>> layoutByPage = layoutByPage(payload, player);
			int maxPage = layoutByPage.isEmpty() ? 0 : layoutByPage.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
			int currentPage = Math.max(0, Math.min(viewState.page(), maxPage));
			if (currentPage != viewState.page()) {
				openViews.put(playerId, new OpenViewState(ViewKind.SELECTOR, currentPage));
			}
			renderSelectorPage(player, menu, payload, layoutByPage, currentPage, maxPage);
			menu.broadcastChanges();
		}
	}

	private void renderSelectorPage(
		ServerPlayer player,
		NeoForgeServerSelectorMenu menu,
		SelectorPayload payload,
		Map<Integer, Map<Integer, ServerRenderEntry>> layoutByPage,
		int currentPage,
		int maxPage
	) {
		menu.clearTopSlots();
		Map<Integer, ServerRenderEntry> pageLayout = layoutByPage.getOrDefault(currentPage, Map.of());
		Set<Integer> occupiedSlots = ConcurrentHashMap.newKeySet();
		occupiedSlots.addAll(pageLayout.keySet());
		for (Map.Entry<Integer, ServerRenderEntry> entry : pageLayout.entrySet()) {
			int slot = entry.getKey();
			ServerRenderEntry renderEntry = entry.getValue();
			BackendServerStatus status = renderEntry.status();
			ServerPayload serverPayload = renderEntry.payload();
			String permission = serverPayload == null ? "" : serverPayload.permission();
			boolean noPermission = permission != null && !permission.isBlank() && !canUse(player, permission);
			menu.setTopSlot(slot, buildServerItem(status, serverPayload, payload, noPermission), () -> {
				if (noPermission) {
					player.sendSystemMessage(Component.literal("Bạn không có quyền vào máy chủ này."));
					return;
				}
				if (sendConnectRequest(player, status.serverName())) {
					player.closeContainer();
				}
			});
		}

		decorateServerGrid(menu, occupiedSlots);
		decorateFooter(menu);

		if (currentPage > 0) {
			menu.setTopSlot(SLOT_PREV_PAGE, itemStack("map", "<yellow>← Trang trước</yellow>", List.of(
				"<gray>Lùi về trang danh sách trước đó</gray>"
			), null), () -> openSelector(player, currentPage - 1));
		} else {
			menu.setTopSlot(SLOT_PREV_PAGE, itemStack("black_stained_glass_pane", "<dark_gray>Trang trước</dark_gray>", List.of(
				"<gray>Bạn đang ở trang đầu</gray>"
			), null), null);
		}

		menu.setTopSlot(SLOT_LOBBY, itemStack("oak_door", "<aqua>Về Sảnh</aqua>", List.of(
			"<gray>Kết nối về lobby</gray>",
			"<yellow>Nhấn để chuyển máy chủ</yellow>"
		), null), () -> {
			if (sendConnectRequest(player, "__lobby__")) {
				player.closeContainer();
			}
		});
		menu.setTopSlot(SLOT_PREVIOUS_SERVER, itemStack("compass", "<gold>Quay Lại Server Trước</gold>", List.of(
			"<gray>Khôi phục server gần nhất</gray>",
			"<yellow>Nhấn để quay lại</yellow>"
		), null), () -> {
			if (sendConnectRequest(player, "__previous__")) {
				player.closeContainer();
			}
		});
		menu.setTopSlot(SLOT_DASHBOARD, itemStack("clock", "<color:#6DFFD4>Bảng Điều Khiển Hệ Thống</color>", List.of(
			"<gray>TPS, CPU, RAM, latency, uptime</gray>",
			"<yellow>Nhấn để mở dashboard</yellow>"
		), null), () -> openDashboard(player, currentPage));
		menu.setTopSlot(SLOT_CLOSE, itemStack("barrier", "<red>Đóng</red>", List.of(), null), player::closeContainer);

		if (currentPage < maxPage) {
			menu.setTopSlot(SLOT_NEXT_PAGE, itemStack("paper", "<yellow>Trang sau →</yellow>", List.of(
				"<gray>Chuyển sang trang danh sách kế tiếp</gray>"
			), null), () -> openSelector(player, currentPage + 1));
		} else {
			menu.setTopSlot(SLOT_NEXT_PAGE, itemStack("black_stained_glass_pane", "<dark_gray>Trang sau</dark_gray>", List.of(
				"<gray>Bạn đang ở trang cuối</gray>"
			), null), null);
		}
	}

	private void renderDashboardPage(ServerPlayer player, NeoForgeServerSelectorMenu menu, int returnPage) {
		menu.clearTopSlots();
		Map<String, BackendServerStatus> snapshot = statusView == null ? Map.of() : statusView.snapshot();
		List<BackendServerStatus> all = new ArrayList<>(snapshot.values());
		all.sort(Comparator.comparing(status -> safe(status.serverName()).toLowerCase(Locale.ROOT)));
		List<BackendServerStatus> onlineOnly = all.stream().filter(BackendServerStatus::online).toList();

		double avgTps = average(onlineOnly, status -> status.stats() == null ? 0D : status.stats().tps());
		double avgCpu = average(onlineOnly, status -> status.stats() == null ? 0D : status.stats().systemCpuUsagePercent());
		double avgLatency = average(onlineOnly, status -> status.stats() == null ? 0D : status.stats().heartbeatLatencyMillis());
		long totalRamUsed = sumLong(onlineOnly, status -> status.stats() == null ? 0L : status.stats().ramUsedBytes());
		long totalRamMax = sumLong(onlineOnly, status -> status.stats() == null ? 0L : status.stats().ramMaxBytes());
		long maxUptimeMillis = maxLong(onlineOnly, status -> status.stats() == null ? 0L : status.stats().uptimeMillis());

		fillDashboardBackground(menu);
		menu.setTopSlot(10, itemStack("clock", "<yellow>TPS Tổng Thể</yellow>", List.of(
			"<gray>Giá trị trung bình toàn mạng</gray>",
			LunaProgressBarPresets.tps("TPS", avgTps).render()
		), null), null);
		menu.setTopSlot(12, itemStack("redstone", "<color:#FF9A4D>CPU Trung Bình</color>", List.of(
			"<gray>Tải CPU theo heartbeat backend</gray>",
			LunaProgressBarPresets.cpu("CPU", avgCpu).render()
		), null), null);
		menu.setTopSlot(14, itemStack("iron_block", "<color:#7FDBFF>RAM Tổng</color>", List.of(
			"<gray>Sử dụng bộ nhớ toàn mạng</gray>",
			LunaProgressBarPresets.ram("RAM", totalRamUsed, totalRamMax).render(),
			"<gray>" + formatMb(totalRamUsed) + "MB / " + formatMb(totalRamMax) + "MB</gray>"
		), null), null);
		menu.setTopSlot(16, itemStack("repeater", "<aqua>Latency Heartbeat</aqua>", List.of(
			"<gray>Độ trễ backend → proxy</gray>",
			LunaProgressBarPresets.latency("Latency", avgLatency).render()
		), null), null);
		menu.setTopSlot(31, itemStack("chest", "<gold>Uptime Cao Nhất</gold>", List.of(
			"<gray>Máy chủ chạy lâu nhất</gray>",
			"<white>" + Formatters.duration(Duration.ofMillis(Math.max(0L, maxUptimeMillis))) + "</white>"
		), null), null);

		int onlineServers = (int) all.stream().filter(BackendServerStatus::online).count();
		menu.setTopSlot(30, itemStack("emerald", "<green>Online Servers</green>", List.of(
			"<white>" + onlineServers + "</white><gray>/</gray><white>" + all.size() + "</white>"
		), null), null);
		menu.setTopSlot(32, itemStack("player_head", "<color:#9EE6A3>Người Chơi Toàn Mạng</color>", List.of(
			"<white>" + sumLong(onlineOnly, status -> status.stats() == null ? 0L : status.stats().onlinePlayers()) + "</white>"
		), null), null);
		menu.setTopSlot(49, itemStack("arrow", "<yellow>Quay Lại Danh Sách Server</yellow>", List.of(
			"<gray>Trở về trang trước đó</gray>"
		), null), () -> openSelector(player, returnPage));
	}

	private void decorateServerGrid(NeoForgeServerSelectorMenu menu, Set<Integer> occupiedSlots) {
		String[] gradientBorder = new String[] {
			"purple_stained_glass_pane",
			"magenta_stained_glass_pane",
			"pink_stained_glass_pane",
			"red_stained_glass_pane",
			"orange_stained_glass_pane",
			"yellow_stained_glass_pane",
			"lime_stained_glass_pane",
			"green_stained_glass_pane",
			"cyan_stained_glass_pane",
			"light_blue_stained_glass_pane",
			"blue_stained_glass_pane"
		};
		List<Integer> borderSlots = borderSlots();
		int borderIndex = 0;
		for (int slot : borderSlots) {
			if (occupiedSlots.contains(slot)) {
				continue;
			}
			String pane = gradientBorder[borderIndex % gradientBorder.length];
			menu.setTopSlot(slot, itemStack(pane, "<gradient:#6DFFD4:#4EA3FF>◈</gradient>", List.of(), null), null);
			borderIndex++;
		}
	}

	private void decorateFooter(NeoForgeServerSelectorMenu menu) {
		for (int slot = 45; slot <= 53; slot++) {
			menu.setTopSlot(slot, itemStack("black_stained_glass_pane", "<dark_gray> </dark_gray>", List.of(), null), null);
		}
	}

	private void fillDashboardBackground(NeoForgeServerSelectorMenu menu) {
		for (int slot = 0; slot < GUI_SIZE; slot++) {
			menu.setTopSlot(slot, itemStack("gray_stained_glass_pane", "<gray> </gray>", List.of(), null), null);
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

	private ItemStack buildServerItem(
		BackendServerStatus status,
		ServerPayload serverPayload,
		SelectorPayload payload,
		boolean noPermission
	) {
		String statusText = resolveStatus(status, noPermission);
		String statusColor = payload == null ? "<white>" : payload.statusColor(statusText);
		String statusIcon = payload == null ? "●" : payload.statusIcon(statusText);
		int onlinePlayers = status.stats() == null ? 0 : status.stats().onlinePlayers();
		int maxPlayers = status.stats() == null ? 0 : status.stats().maxPlayers();
		String display = serverPayload != null && !safe(serverPayload.displayName()).isBlank()
			? serverPayload.displayName()
			: (safe(status.serverDisplay()).isBlank() ? status.serverName() : status.serverDisplay());

		ConditionContext conditionContext = new ConditionContext(
			statusText,
			status.serverName(),
			serverPayload != null && !safe(serverPayload.hostName()).isBlank() ? serverPayload.hostName() : status.serverName(),
			display,
			onlinePlayers,
			maxPlayers,
			status.stats() != null && status.stats().whitelistEnabled(),
			noPermission,
			status.stats() == null ? 0D : status.stats().tps(),
			status.stats() == null ? 0D : status.stats().systemCpuUsagePercent(),
			status.stats() == null ? 0L : status.stats().heartbeatLatencyMillis(),
			status.stats() == null || status.stats().ramMaxBytes() <= 0L
				? 0D
				: Math.min(100D, (Math.max(0L, status.stats().ramUsedBytes()) * 100D) / Math.max(1L, status.stats().ramMaxBytes()))
		);

		ConditionalOverridePayload conditionalOverride = serverPayload == null ? null : serverPayload.resolveConditional(conditionContext, this::evaluateConditionExpression);
		TemplatePayload template = payload == null ? TemplatePayload.defaultTemplate() : payload.resolveTemplate(serverPayload, statusText);
		if (conditionalOverride != null && conditionalOverride.templateOverride() != null) {
			template = template.applyOverride(conditionalOverride.templateOverride());
		}

		String materialName = resolveMaterialName(statusText, serverPayload, conditionalOverride, template);
		Boolean glint = resolveGlint(statusText, serverPayload, conditionalOverride);
		Map<String, String> values = placeholderValues(status, serverPayload, display, statusText, statusColor, statusIcon, onlinePlayers, maxPlayers);
		List<String> lore = new ArrayList<>();
		for (String headerLine : template.headerLines()) {
			lore.add(applyTemplate(safe(headerLine), values));
		}

		List<String> description = serverPayload == null ? List.of() : serverPayload.description(statusText);
		if (conditionalOverride != null && conditionalOverride.description() != null) {
			description = conditionalOverride.description();
		}
		for (String line : description) {
			Map<String, String> withLine = new LinkedHashMap<>(values);
			withLine.put("line", safe(line));
			lore.add(applyTemplate(template.bodyLineTemplate(), withLine));
		}

		for (String footerLine : template.footerLines()) {
			lore.add(applyTemplate(safe(footerLine), values));
		}

		return itemStack(materialName, applyTemplate(template.nameTemplate(), values), lore, glint);
	}

	private String resolveMaterialName(
		String statusText,
		ServerPayload serverPayload,
		ConditionalOverridePayload conditionalOverride,
		TemplatePayload template
	) {
		if (conditionalOverride != null && !safe(conditionalOverride.material()).isBlank()) {
			return conditionalOverride.material();
		}
		if (serverPayload != null && !safe(serverPayload.material(statusText)).isBlank()) {
			return serverPayload.material(statusText);
		}
		if (template != null && !safe(template.material()).isBlank()) {
			return template.material();
		}
		if ("NOP".equals(statusText)) {
			return "gray_concrete";
		}
		if ("OFFLINE".equals(statusText)) {
			return "red_concrete";
		}
		if ("MAINT".equals(statusText)) {
			return "yellow_concrete";
		}
		return "lime_concrete";
	}

	private Boolean resolveGlint(String statusText, ServerPayload serverPayload, ConditionalOverridePayload conditionalOverride) {
		if (conditionalOverride != null && conditionalOverride.glint() != null) {
			return conditionalOverride.glint();
		}
		return serverPayload == null ? null : serverPayload.glint(statusText);
	}

	private boolean evaluateConditionExpression(String expression, ConditionContext context) {
		if (expression == null || expression.isBlank()) {
			return false;
		}
		for (String orClause : splitConditionExpression(expression, "||")) {
			boolean andResult = true;
			for (String andClause : splitConditionExpression(orClause, "&&")) {
				if (!evaluateConditionPredicate(andClause, context)) {
					andResult = false;
					break;
				}
			}
			if (andResult) {
				return true;
			}
		}
		return false;
	}

	private List<String> splitConditionExpression(String expression, String operator) {
		List<String> output = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		char quote = '\0';
		for (int index = 0; index < expression.length(); index++) {
			char ch = expression.charAt(index);
			if (quote == '\0' && (ch == '\'' || ch == '"')) {
				quote = ch;
				current.append(ch);
				continue;
			}
			if (quote != '\0') {
				current.append(ch);
				if (ch == quote) {
					quote = '\0';
				}
				continue;
			}
			if (index + operator.length() <= expression.length() && expression.substring(index, index + operator.length()).equals(operator)) {
				output.add(current.toString().trim());
				current.setLength(0);
				index += operator.length() - 1;
				continue;
			}
			current.append(ch);
		}
		output.add(current.toString().trim());
		return output;
	}

	private boolean evaluateConditionPredicate(String rawPredicate, ConditionContext context) {
		String predicate = trimBalancedParentheses(rawPredicate == null ? "" : rawPredicate.trim());
		if (predicate.isEmpty()) {
			return false;
		}
		if (predicate.startsWith("!")) {
			return !evaluateConditionPredicate(predicate.substring(1), context);
		}
		if ("true".equalsIgnoreCase(predicate)) {
			return true;
		}
		if ("false".equalsIgnoreCase(predicate)) {
			return false;
		}
		Matcher matcher = CONDITION_COMPARISON_PATTERN.matcher(predicate);
		if (!matcher.matches()) {
			Object value = resolveConditionVariable(predicate, context);
			return truthy(value);
		}

		String leftKey = matcher.group(1);
		String operator = matcher.group(2);
		String rightRaw = matcher.group(3);
		Object leftValue = resolveConditionVariable(leftKey, context);
		Object rightValue = parseConditionRightLiteral(rightRaw, context);
		return compareConditionValues(leftValue, operator, rightValue);
	}

	private String trimBalancedParentheses(String value) {
		String output = value;
		while (output.startsWith("(") && output.endsWith(")") && isWrappedBySingleBalancedPair(output)) {
			output = output.substring(1, output.length() - 1).trim();
		}
		return output;
	}

	private boolean isWrappedBySingleBalancedPair(String value) {
		int depth = 0;
		char quote = '\0';
		for (int index = 0; index < value.length(); index++) {
			char ch = value.charAt(index);
			if (quote == '\0' && (ch == '\'' || ch == '"')) {
				quote = ch;
				continue;
			}
			if (quote != '\0') {
				if (ch == quote) {
					quote = '\0';
				}
				continue;
			}
			if (ch == '(') {
				depth++;
			} else if (ch == ')') {
				depth--;
				if (depth == 0 && index < value.length() - 1) {
					return false;
				}
			}
		}
		return depth == 0;
	}

	private Object parseConditionRightLiteral(String raw, ConditionContext context) {
		String text = trimBalancedParentheses(raw == null ? "" : raw.trim());
		if ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'"))) {
			return text.substring(1, text.length() - 1);
		}
		if ("true".equalsIgnoreCase(text)) {
			return true;
		}
		if ("false".equalsIgnoreCase(text)) {
			return false;
		}
		try {
			return Double.parseDouble(text);
		} catch (NumberFormatException ignored) {
			Object variable = resolveConditionVariable(text, context);
			return variable != null ? variable : text;
		}
	}

	private boolean compareConditionValues(Object left, String operator, Object right) {
		Double leftNumber = toNumber(left);
		Double rightNumber = toNumber(right);
		if (leftNumber != null && rightNumber != null) {
			return switch (operator) {
				case "==" -> Double.compare(leftNumber, rightNumber) == 0;
				case "!=" -> Double.compare(leftNumber, rightNumber) != 0;
				case ">" -> leftNumber > rightNumber;
				case ">=" -> leftNumber >= rightNumber;
				case "<" -> leftNumber < rightNumber;
				case "<=" -> leftNumber <= rightNumber;
				default -> false;
			};
		}
		if (left instanceof Boolean || right instanceof Boolean) {
			boolean leftBool = toBoolean(left);
			boolean rightBool = toBoolean(right);
			return switch (operator) {
				case "==" -> leftBool == rightBool;
				case "!=" -> leftBool != rightBool;
				default -> false;
			};
		}

		String leftText = left == null ? "" : String.valueOf(left);
		String rightText = right == null ? "" : String.valueOf(right);
		int compare = leftText.compareToIgnoreCase(rightText);
		return switch (operator) {
			case "==" -> compare == 0;
			case "!=" -> compare != 0;
			case ">" -> compare > 0;
			case ">=" -> compare >= 0;
			case "<" -> compare < 0;
			case "<=" -> compare <= 0;
			default -> false;
		};
	}

	private boolean toBoolean(Object value) {
		if (value instanceof Boolean bool) {
			return bool;
		}
		return truthy(value);
	}

	private boolean truthy(Object value) {
		if (value instanceof Boolean bool) {
			return bool;
		}
		Double number = toNumber(value);
		if (number != null) {
			return Math.abs(number) > 0.0000001D;
		}
		if (value == null) {
			return false;
		}
		String text = String.valueOf(value).trim();
		return !text.isEmpty() && !"false".equalsIgnoreCase(text) && !"no".equalsIgnoreCase(text) && !"0".equals(text);
	}

	private Double toNumber(Object value) {
		if (value instanceof Number number) {
			return number.doubleValue();
		}
		if (value == null) {
			return null;
		}
		try {
			return Double.parseDouble(String.valueOf(value).trim());
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private Object resolveConditionVariable(String variable, ConditionContext context) {
		if (variable == null || variable.isBlank()) {
			return null;
		}
		String key = variable.trim().toLowerCase(Locale.ROOT);
		return switch (key) {
			case "status", "server_status" -> context.status();
			case "server_name" -> context.serverName();
			case "luna_host_name", "luna_server_name" -> context.hostName();
			case "server_display" -> context.serverDisplay();
			case "online" -> context.onlinePlayers();
			case "max" -> context.maxPlayers();
			case "whitelist", "maint" -> context.whitelistEnabled();
			case "no_permission", "nop" -> context.noPermission();
			case "has_permission" -> !context.noPermission();
			case "tps" -> context.tps();
			case "cpu_usage" -> context.cpuUsage();
			case "latency_ms" -> context.latencyMs();
			case "ram_percent" -> context.ramPercent();
			case "is_online" -> "ONLINE".equalsIgnoreCase(context.status());
			case "is_offline" -> "OFFLINE".equalsIgnoreCase(context.status());
			case "is_maint" -> "MAINT".equalsIgnoreCase(context.status());
			case "is_nop" -> "NOP".equalsIgnoreCase(context.status());
			default -> null;
		};
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
		ServerPayload serverPayload,
		String display,
		String statusText,
		String statusColor,
		String statusIcon,
		int online,
		int max
	) {
		Map<String, String> values = new LinkedHashMap<>();
		String hostName = serverPayload != null && !safe(serverPayload.hostName()).isBlank() ? serverPayload.hostName() : status.serverName();
		values.put("server_name", safe(status.serverName()));
		values.put("luna_host_name", hostName);
		values.put("luna_server_name", hostName);
		values.put("server_display", safe(display));
		values.put("server_accent_color", safe(status.serverAccentColor()));
		values.put("server_status", statusText);
		values.put("server_status_color", statusColor);
		values.put("server_status_icon", statusIcon);
		values.put("online", String.valueOf(online));
		values.put("max", String.valueOf(max));
		long ramUsedBytes = status.stats() == null ? 0L : Math.max(0L, status.stats().ramUsedBytes());
		long ramMaxBytes = status.stats() == null ? 0L : Math.max(0L, status.stats().ramMaxBytes());
		String versionFull = status.stats() == null ? "unknown" : safe(status.stats().version());
		String versionShort = shortVersion(versionFull);
		values.put("version", versionShort);
		values.put("server_version", versionShort);
		values.put("server_version_full", versionFull);
		values.put("tps", status.stats() == null ? "0.00" : String.format(Locale.US, "%.2f", status.stats().tps()));
		long uptimeMillis = status.stats() == null ? 0L : Math.max(0L, status.stats().uptimeMillis());
		values.put("uptime", Formatters.compactDuration(Duration.ofMillis(uptimeMillis)));
		values.put("uptime_long", Formatters.duration(Duration.ofMillis(uptimeMillis)));
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

	private Map<Integer, Map<Integer, ServerRenderEntry>> layoutByPage(SelectorPayload payload, ServerPlayer player) {
		List<ServerRenderEntry> entries = sortedEntries(payload, player);
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

	private List<ServerRenderEntry> sortedEntries(SelectorPayload payload, ServerPlayer player) {
		List<ServerRenderEntry> entries = new ArrayList<>();
		if (payload != null && !payload.servers().isEmpty()) {
			Map<String, BackendServerStatus> snapshot = statusView == null ? Map.of() : statusView.snapshot();
			for (ServerPayload item : payload.servers().values()) {
				if (!canUse(player, item.permission())) {
					continue;
				}
				BackendServerStatus status = resolveServerStatus(item, snapshot);
				entries.add(new ServerRenderEntry(status, item));
			}
		} else if (statusView != null) {
			for (BackendServerStatus status : statusView.snapshot().values()) {
				entries.add(new ServerRenderEntry(status, null));
			}
		}
		entries.sort(Comparator.comparing(entry -> safe(entry.status().serverName()).toLowerCase(Locale.ROOT)));
		return entries;
	}

	private BackendServerStatus resolveServerStatus(ServerPayload payload, Map<String, BackendServerStatus> snapshot) {
		String backendName = payload.backendName();
		if (!safe(backendName).isBlank()) {
			BackendServerStatus direct = snapshot.get(backendName.trim().toLowerCase(Locale.ROOT));
			if (direct != null) {
				return direct;
			}
			for (BackendServerStatus candidate : snapshot.values()) {
				if (candidate != null && safe(candidate.serverName()).equalsIgnoreCase(backendName.trim())) {
					return candidate;
				}
			}
		}

		String displayName = payload.displayName();
		if (!safe(displayName).isBlank()) {
			BackendServerStatus matchedByDisplay = null;
			for (BackendServerStatus candidate : snapshot.values()) {
				if (candidate == null || !safe(candidate.serverDisplay()).equalsIgnoreCase(displayName.trim())) {
					continue;
				}
				if (matchedByDisplay != null) {
					matchedByDisplay = null;
					break;
				}
				matchedByDisplay = candidate;
			}
			if (matchedByDisplay != null) {
				return matchedByDisplay;
			}
		}

		return new BackendServerStatus(
			payload.backendName(),
			payload.displayName(),
			payload.accentColor(),
			false,
			0L,
			null
		);
	}

	private int firstFreeSlot(Map<Integer, ServerRenderEntry> pageLayout) {
		for (int slot = 0; slot < PAGE_SIZE; slot++) {
			if (!pageLayout.containsKey(slot)) {
				return slot;
			}
		}
		return -1;
	}

	private int requestedPage(UUID playerId) {
		OpenViewState state = playerId == null ? null : openViews.get(playerId);
		if (state == null || state.kind() != ViewKind.SELECTOR) {
			return 0;
		}
		return state.page();
	}

	private SelectorPayload currentPayloadFor(UUID playerId) {
		SelectorPayload payload = playerId == null ? null : payloadByPlayer.get(playerId);
		if (payload != null && !payload.isEmpty()) {
			return payload;
		}
		return selectorPayload;
	}

	private Component selectorTitle(SelectorPayload payload, ServerPlayer player) {
		String title = payload == null ? "Danh Sách Máy Chủ" : payload.guiTitle();
		return NeoForgeTextComponents.mini(server, applyTemplate(title, Map.of("player_name", player.getGameProfile().getName())));
	}

	private boolean sendConnectRequest(ServerPlayer player, String backendName) {
		ensureMessagingAttached();
		PluginMessageBus<ServerPlayer, ServerPlayer> bus = messagingBus;
		if (bus == null || player == null) {
			return false;
		}
		return bus.send(player, CoreServerSelectorMessageChannels.CONNECT_REQUEST, writer -> {
			writer.writeUtf(player.getUUID().toString());
			writer.writeUtf(backendName == null ? "" : backendName);
		});
	}

	private boolean canUse(ServerPlayer player, String permission) {
		if (permission == null || permission.isBlank()) {
			return true;
		}
		if (permissionService == null || !permissionService.isAvailable() || player == null) {
			return true;
		}
		return permissionService.hasPermission(player.getUUID(), permission);
	}

	private void syncSelectorPayload() {
		if (heartbeatPublisher != null) {
			heartbeatPublisher.syncServerSelectorConfigNow();
		}
	}

	private ServerPlayer playerFrom(CommandSourceStack source) {
		return source == null || !(source.getEntity() instanceof ServerPlayer player) ? null : player;
	}

	@SuppressWarnings("unchecked")
	private PluginMessageBus<ServerPlayer, ServerPlayer> resolveMessagingBus() {
		return (PluginMessageBus<ServerPlayer, ServerPlayer>) dependencyManager.resolveOptional(PluginMessageBus.class).orElse(null);
	}

	private CompletableFuture<Suggestions> suggestServers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		List<String> suggestions = currentPayloadFor(playerFrom(context.getSource()) == null ? null : playerFrom(context.getSource()).getUUID()).servers().values().stream()
			.map(ServerPayload::backendName)
			.sorted(String.CASE_INSENSITIVE_ORDER)
			.toList();
		return SharedSuggestionProvider.suggest(suggestions, builder);
	}

	private ItemStack itemStack(String materialName, String title, List<String> loreLines, Boolean glintOverride) {
		Item item = resolveItem(materialName);
		ItemStack stack = new ItemStack(item == null ? Items.BARRIER : item);
		stack.set(DataComponents.CUSTOM_NAME, NeoForgeTextComponents.mini(server, safe(title)));
		if (loreLines != null && !loreLines.isEmpty()) {
			List<Component> lore = new ArrayList<>();
			for (String line : loreLines) {
				lore.add(NeoForgeTextComponents.mini(server, safe(line)));
			}
			stack.set(DataComponents.LORE, new ItemLore(lore));
		}
		if (glintOverride != null) {
			stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, glintOverride);
		}
		return stack;
	}

	private Item resolveItem(String materialName) {
		if (materialName == null || materialName.isBlank()) {
			return null;
		}
		String normalized = materialName.trim().toLowerCase(Locale.ROOT);
		String namespace = "minecraft";
		String path = normalized;
		int separator = normalized.indexOf(':');
		if (separator > 0 && separator < normalized.length() - 1) {
			namespace = normalized.substring(0, separator);
			path = normalized.substring(separator + 1);
		}
		ResourceLocation identifier = ResourceLocation.fromNamespaceAndPath(namespace, path);
		if (!BuiltInRegistries.ITEM.containsKey(identifier)) {
			return null;
		}
		Item item = BuiltInRegistries.ITEM.get(identifier);
		return item == Items.AIR ? null : item;
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

	private String safe(String value) {
		return value == null ? "" : value;
	}

	private SelectorPayload parsePayload(byte[] rawPayload) {
		if (rawPayload == null || rawPayload.length == 0) {
			return SelectorPayload.empty();
		}
		try {
			PluginMessageReader reader = PluginMessageReader.of(rawPayload);
			String mode = reader.readUtf();
			boolean v3 = "open-v3".equalsIgnoreCase(mode);
			boolean v4 = "open-v4".equalsIgnoreCase(mode);
			boolean v5 = "open-v5".equalsIgnoreCase(mode);
			boolean v6 = "open-v6".equalsIgnoreCase(mode);
			boolean v7 = "open-v7".equalsIgnoreCase(mode);
			boolean v8 = "open-v8".equalsIgnoreCase(mode);
			if (!v3 && !v4 && !v5 && !v6 && !v7 && !v8) {
				return SelectorPayload.empty();
			}

			String guiTitle = reader.readUtf();
			String name = reader.readUtf();
			List<String> header = List.copyOf(readLines(reader));
			String bodyLine = reader.readUtf();
			List<String> footer = List.copyOf(readLines(reader));
			String templateMaterial = (v7 || v8) ? reader.readUtf() : "";
			Map<String, TemplateOverridePayload> globalTemplateByStatus = readTemplateOverrides(reader);
			TemplatePayload baseTemplate = new TemplatePayload(name, header, bodyLine, footer, templateMaterial, globalTemplateByStatus);
			Map<String, String> statusColors = defaultStatusColors();
			Map<String, String> statusIcons = defaultStatusIcons();
			if (v4 || v5 || v6 || v7 || v8) {
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
				String hostName = v8 ? reader.readUtf() : backendName;
				int rawSlot = reader.readInt();
				int rawPage = reader.readInt();
				Integer slot = rawSlot < 0 ? null : rawSlot;
				Integer page = rawPage < 0 ? null : rawPage;
				String material = "";
				Map<String, String> materialByStatus = Map.of();
				Boolean glint = null;
				Map<String, Boolean> glintByStatus = Map.of();
				if (v5 || v6 || v7 || v8) {
					material = reader.readUtf();
					int materialByStatusCount = Math.max(0, reader.readInt());
					Map<String, String> materialByStatusPayload = new LinkedHashMap<>();
					for (int index = 0; index < materialByStatusCount; index++) {
						String statusKey = reader.readUtf().toUpperCase(Locale.ROOT);
						materialByStatusPayload.put(statusKey, reader.readUtf());
					}
					materialByStatus = Map.copyOf(materialByStatusPayload);
					if (reader.readBoolean()) {
						glint = reader.readBoolean();
					}
					int glintByStatusCount = Math.max(0, reader.readInt());
					Map<String, Boolean> glintByStatusPayload = new LinkedHashMap<>();
					for (int index = 0; index < glintByStatusCount; index++) {
						String statusKey = reader.readUtf().toUpperCase(Locale.ROOT);
						glintByStatusPayload.put(statusKey, reader.readBoolean());
					}
					glintByStatus = Map.copyOf(glintByStatusPayload);
				}

				List<ConditionalOverridePayload> conditional = List.of();
				if (v6 || v7 || v8) {
					int conditionalCount = Math.max(0, reader.readInt());
					List<ConditionalOverridePayload> conditionalPayload = new ArrayList<>();
					for (int index = 0; index < conditionalCount; index++) {
						String when = reader.readUtf();
						String conditionalMaterial = null;
						if (reader.readBoolean()) {
							conditionalMaterial = reader.readUtf();
						}
						Boolean conditionalGlint = null;
						if (reader.readBoolean()) {
							conditionalGlint = reader.readBoolean();
						}
						List<String> conditionalDescription = null;
						if (reader.readBoolean()) {
							conditionalDescription = List.copyOf(readLines(reader));
						}
						TemplateOverridePayload templateOverride = null;
						if (reader.readBoolean()) {
							templateOverride = readTemplateOverride(reader);
						}
						conditionalPayload.add(new ConditionalOverridePayload(
							when,
							conditionalMaterial,
							conditionalGlint,
							conditionalDescription,
							templateOverride
						));
					}
					conditional = List.copyOf(conditionalPayload);
				}

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
					String serverTemplateName = reader.readUtf();
					List<String> serverTemplateHeader = List.copyOf(readLines(reader));
					String serverTemplateBody = reader.readUtf();
					List<String> serverTemplateFooter = List.copyOf(readLines(reader));
					String serverTemplateMaterial = "";
					if (v7 || v8) {
						serverTemplateMaterial = reader.readUtf();
					}
					serverTemplate = new TemplatePayload(
						serverTemplateName,
						serverTemplateHeader,
						serverTemplateBody,
						serverTemplateFooter,
						serverTemplateMaterial,
						readTemplateOverrides(reader)
					);
				}

				servers.put(backendName.toLowerCase(Locale.ROOT), new ServerPayload(
					backendName,
					display,
					accent,
					permission,
					hostName,
					slot,
					page,
					material,
					materialByStatus,
					glint,
					glintByStatus,
					conditional,
					description,
					Map.copyOf(descriptionByStatus),
					serverTemplate
				));
			}
			return new SelectorPayload(guiTitle, baseTemplate, statusColors, statusIcons, Map.copyOf(servers));
		} catch (Exception exception) {
			logger.debug("Không thể parse selector payload trên NeoForge: " + exception.getMessage());
			return SelectorPayload.empty();
		}
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
			overrides.put(status, readTemplateOverride(reader));
		}
		return Map.copyOf(overrides);
	}

	private TemplateOverridePayload readTemplateOverride(PluginMessageReader reader) {
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
		return new TemplateOverridePayload(name, header, bodyLine, footer);
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

	private enum ViewKind {
		SELECTOR,
		DASHBOARD
	}

	private record OpenViewState(ViewKind kind, int page) {
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

		boolean isEmpty() {
			return servers.isEmpty();
		}

		Optional<SelectorServerEntry> server(String backendName) {
			if (backendName == null || backendName.isBlank()) {
				return Optional.empty();
			}
			ServerPayload payload = servers.get(backendName.trim().toLowerCase(Locale.ROOT));
			if (payload == null) {
				return Optional.empty();
			}
			return Optional.of(new SelectorServerEntry(payload.backendName(), payload.displayName(), payload.permission(), payload.hostName(), payload.description()));
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
		String material,
		Map<String, TemplateOverridePayload> byStatus
	) {
		static TemplatePayload defaultTemplate() {
			return new TemplatePayload("<b>%server_display%</b>", List.of(), "%line%", List.of(), "", Map.of());
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
				material,
				byStatus
			);
		}

		TemplatePayload merge(TemplatePayload serverTemplate) {
			return new TemplatePayload(
				serverTemplate.nameTemplate(),
				serverTemplate.headerLines(),
				serverTemplate.bodyLineTemplate(),
				serverTemplate.footerLines(),
				serverTemplate.material(),
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
		String hostName,
		Integer slot,
		Integer page,
		String material,
		Map<String, String> materialByStatus,
		Boolean glint,
		Map<String, Boolean> glintByStatus,
		List<ConditionalOverridePayload> conditional,
		List<String> description,
		Map<String, List<String>> descriptionByStatus,
		TemplatePayload template
	) {
		String material(String status) {
			if (status != null) {
				String statusOverride = materialByStatus.get(status.toUpperCase(Locale.ROOT));
				if (statusOverride != null && !statusOverride.isBlank()) {
					return statusOverride;
				}
			}
			return material;
		}

		Boolean glint(String status) {
			if (status != null && glintByStatus.containsKey(status.toUpperCase(Locale.ROOT))) {
				return glintByStatus.get(status.toUpperCase(Locale.ROOT));
			}
			return glint;
		}

		ConditionalOverridePayload resolveConditional(ConditionContext context, java.util.function.BiFunction<String, ConditionContext, Boolean> evaluator) {
			ConditionalOverridePayload merged = null;
			for (ConditionalOverridePayload override : conditional) {
				if (override == null || override.condition() == null || override.condition().isBlank()) {
					continue;
				}
				if (!Boolean.TRUE.equals(evaluator.apply(override.condition(), context))) {
					continue;
				}
				merged = merged == null ? override : merged.merge(override);
			}
			return merged;
		}

		List<String> description(String status) {
			if (status == null) {
				return description;
			}
			List<String> override = descriptionByStatus.get(status.toUpperCase(Locale.ROOT));
			return override == null ? description : override;
		}
	}

	private record SelectorServerEntry(String backendName, String displayName, String permission, String serverInfoName, List<String> description) {
	}

	private record ServerRenderEntry(BackendServerStatus status, ServerPayload payload) {
	}

	private record ConditionalOverridePayload(
		String condition,
		String material,
		Boolean glint,
		List<String> description,
		TemplateOverridePayload templateOverride
	) {
		ConditionalOverridePayload merge(ConditionalOverridePayload other) {
			if (other == null) {
				return this;
			}
			return new ConditionalOverridePayload(
				other.condition(),
				other.material() != null ? other.material() : material,
				other.glint() != null ? other.glint() : glint,
				other.description() != null ? other.description() : description,
				other.templateOverride() != null ? mergeTemplateOverride(templateOverride, other.templateOverride()) : templateOverride
			);
		}

		private TemplateOverridePayload mergeTemplateOverride(TemplateOverridePayload base, TemplateOverridePayload override) {
			if (base == null) {
				return override;
			}
			if (override == null) {
				return base;
			}
			return new TemplateOverridePayload(
				override.nameTemplate() != null ? override.nameTemplate() : base.nameTemplate(),
				override.headerLines() != null ? override.headerLines() : base.headerLines(),
				override.bodyLineTemplate() != null ? override.bodyLineTemplate() : base.bodyLineTemplate(),
				override.footerLines() != null ? override.footerLines() : base.footerLines()
			);
		}
	}

	private record ConditionContext(
		String status,
		String serverName,
		String hostName,
		String serverDisplay,
		int onlinePlayers,
		int maxPlayers,
		boolean whitelistEnabled,
		boolean noPermission,
		double tps,
		double cpuUsage,
		long latencyMs,
		double ramPercent
	) {
	}
}
