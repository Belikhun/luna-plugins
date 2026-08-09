package dev.belikhun.luna.core.fabric.serverselector;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatPublisher;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusStore;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.CoreServerSelectorMessageChannels;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.api.serverselector.ServerSelectorEngine;
import dev.belikhun.luna.core.api.serverselector.ServerSelectorEngine.DashboardStats;
import dev.belikhun.luna.core.api.serverselector.ServerSelectorEngine.RenderedServerItem;
import dev.belikhun.luna.core.api.serverselector.ServerSelectorEngine.ServerPayload;
import dev.belikhun.luna.core.api.serverselector.ServerSelectorEngine.ServerRenderEntry;
import dev.belikhun.luna.core.api.serverselector.ServerSelectorEngine.ServerSelectorPayload;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.api.ui.LunaProgressBarPresets;
import dev.belikhun.luna.core.mc.text.LunaTextComponents;
import dev.belikhun.luna.core.mc.ui.LunaChestMenu;
import dev.belikhun.luna.core.mc.ui.LunaItems;
import dev.belikhun.luna.core.mc.ui.LunaChestMenuBase;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * The network's server list, drawn into a six-row chest.
 *
 * This is the same screen Paper and NeoForge open, down to the slot numbers: the
 * layout, the titles and the lore all come out of {@link ServerSelectorEngine},
 * which is platform-free, so a server reads identically whichever backend the
 * player happens to be standing on. What is left here is the platform's half -
 * opening a menu, building an item, and registering two commands.
 *
 * The two things that were renamed between the supported game lines are reached
 * through classes that exist once per line rather than linked directly: the click
 * handler ({@link LunaChestMenu}) and resolving an item by name
 * ({@link ItemLookup}). Everything else compiles against both.
 */
public final class FabricServerSelectorController {
	private static final String OPEN_COMMAND = "lunaservers";
	private static final String CONNECT_COMMAND = "lunacoreconnect";
	private static final int GUI_ROWS = 6;
	private static final int GUI_SIZE = GUI_ROWS * 9;
	private static final int SLOT_PREV_PAGE = 52;
	private static final int SLOT_LOBBY = 46;
	private static final int SLOT_PREVIOUS_SERVER = 47;
	private static final int SLOT_DASHBOARD = 48;
	private static final int SLOT_CLOSE = 49;
	private static final int SLOT_NEXT_PAGE = 53;

	private final MinecraftServer server;
	private final DependencyManager dependencyManager;
	private final LunaLogger logger;
	private final PermissionService permissionService;
	private final Map<UUID, ServerSelectorPayload> payloadByPlayer;
	private final Map<UUID, LunaChestMenuBase> openMenus;
	private final Map<UUID, OpenViewState> openViews;
	private final Set<UUID> suppressCloseCleanup;

	private volatile ServerSelectorPayload selectorPayload;
	private volatile boolean messagingAttached;
	private volatile PluginMessageBus<ServerPlayer, ServerPlayer> messagingBus;
	private volatile BackendHeartbeatPublisher heartbeatPublisher;
	private volatile BackendStatusView statusView;
	private volatile BackendStatusStore concreteStatusView;
	private volatile Runnable statusUpdateListener;

	public FabricServerSelectorController(
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
		this.selectorPayload = ServerSelectorPayload.empty();
		this.messagingAttached = false;
		this.messagingBus = null;
		this.heartbeatPublisher = null;
		this.statusView = null;
		this.concreteStatusView = null;
		this.statusUpdateListener = null;
	}

	public void start(BackendHeartbeatPublisher publisher) {
		this.heartbeatPublisher = publisher;
		this.statusView = dependencyManager.resolveOptional(BackendStatusView.class).orElse(null);

		BackendStatusStore resolvedStatusView = dependencyManager.resolveOptional(BackendStatusStore.class).orElse(null);
		this.concreteStatusView = resolvedStatusView;

		if (resolvedStatusView != null) {
			Runnable listener = this::scheduleRefreshOpenMenus;
			resolvedStatusView.addUpdateListener(listener);
			this.statusUpdateListener = listener;
		}

		if (publisher != null) {
			publisher.setSelectorPayloadConsumer(this::acceptSelectorPayload);
			publisher.syncServerSelectorConfigNow();
		}

		ensureMessagingAttached();
	}

	public void close() {
		BackendStatusStore resolvedStatusView = concreteStatusView;
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

		for (LunaChestMenuBase menu : openMenus.values()) {
			menu.suppressCloseCallbackOnce();
		}

		openMenus.clear();
		openViews.clear();
		payloadByPlayer.clear();
		suppressCloseCleanup.clear();
		messagingAttached = false;
		messagingBus = null;
		selectorPayload = ServerSelectorPayload.empty();
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

	/**
	 * Register the selector's commands.
	 *
	 * The dispatcher is built while the server is still loading, which is before
	 * there is a server to give the controller - so the tree is registered once
	 * against a lookup and every executor resolves the controller when the player
	 * actually runs it. Registering from the started hook instead would be too
	 * late: the command tree has already been sent to clients by then.
	 */
	public static void registerCommands(
		CommandDispatcher<CommandSourceStack> dispatcher,
		Supplier<FabricServerSelectorController> lookup
	) {
		dispatcher.register(Commands.literal(OPEN_COMMAND)
			.requires(source -> source.getEntity() instanceof ServerPlayer)
			.executes(context -> withController(context.getSource(), lookup, controller -> controller.executeOpen(context))));

		dispatcher.register(Commands.literal(CONNECT_COMMAND)
			.requires(source -> source.getEntity() instanceof ServerPlayer)
			.executes(context -> withController(context.getSource(), lookup, controller -> controller.sendConnectUsage(context)))
			.then(Commands.argument("server", StringArgumentType.word())
				.suggests((context, builder) -> {
					FabricServerSelectorController controller = lookup.get();
					return controller == null ? builder.buildFuture() : controller.suggestServers(context, builder);
				})
				.executes(context -> withController(
					context.getSource(),
					lookup,
					controller -> controller.executeConnect(context.getSource(), StringArgumentType.getString(context, "server"))
				))));
	}

	private static int withController(
		CommandSourceStack source,
		Supplier<FabricServerSelectorController> lookup,
		ToIntFunction<FabricServerSelectorController> action
	) {
		FabricServerSelectorController controller = lookup.get();

		if (controller == null) {
			source.sendFailure(Component.literal("Danh sách máy chủ chưa sẵn sàng."));
			return 0;
		}

		return action.applyAsInt(controller);
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
				payloadByPlayer.put(player.getUUID(), parsePayload(context.payload()));
			}

			server.execute(() -> openSelector(player, requestedPage(player.getUUID())));
			return PluginMessageDispatchResult.HANDLED;
		});

		messagingBus = resolved;
		messagingAttached = true;
		logger.audit("Đã gắn Fabric server selector vào plugin messaging bus.");
	}

	public void acceptSelectorPayload(byte[] payload) {
		ServerSelectorPayload parsed = parsePayload(payload);

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
			() -> LunaTextComponents.mini(CommandStrings.usage(CONNECT_COMMAND, CommandStrings.required("server", "text"))),
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

		ServerPayload entry = currentPayloadFor(player.getUUID()).server(normalizedBackend).orElse(null);
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
		ServerSelectorPayload payload = currentPayloadFor(player.getUUID());

		if (payload.isEmpty()) {
			// the fetch is asynchronous, so it serves the player's next attempt, not
			// this one; arrival re-renders anything already open via acceptSelectorPayload
			syncSelectorPayload();
			player.sendSystemMessage(Component.literal("Danh sách máy chủ đang được đồng bộ. Hãy thử lại sau ít giây."));
			return false;
		}

		Map<Integer, Map<Integer, ServerRenderEntry>> layoutByPage = layoutByPage(payload);
		int maxPage = layoutByPage.isEmpty() ? 0 : layoutByPage.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
		int currentPage = Math.max(0, Math.min(page, maxPage));

		openViews.put(player.getUUID(), new OpenViewState(ViewKind.SELECTOR, currentPage));
		openMenu(
			player,
			selectorTitle(payload, player),
			menu -> renderSelectorPage(player, menu, payload, layoutByPage, currentPage, maxPage)
		);

		return true;
	}

	private void openDashboard(ServerPlayer player, int returnPage) {
		if (player == null) {
			return;
		}

		openViews.put(player.getUUID(), new OpenViewState(ViewKind.DASHBOARD, Math.max(0, returnPage)));
		openMenu(
			player,
			LunaTextComponents.mini("<gradient:#6DFFD4:#4EA3FF>Thống Kê Toàn Mạng</gradient>"),
			menu -> renderDashboardPage(player, menu, returnPage)
		);
	}

	private void openMenu(ServerPlayer player, Component title, Consumer<LunaChestMenuBase> renderer) {
		UUID playerId = player.getUUID();
		LunaChestMenuBase previousMenu = openMenus.get(playerId);

		if (previousMenu != null) {
			previousMenu.suppressCloseCallbackOnce();
		}

		player.openMenu(new SimpleMenuProvider((containerId, inventory, ignoredPlayer) -> {
			LunaChestMenu menu = new LunaChestMenu(containerId, inventory, GUI_ROWS, () -> handleMenuClosed(playerId));
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

	/**
	 * Redraw every menu that is still open, in place.
	 *
	 * A heartbeat arriving is what makes the list live, and a player watching it
	 * should see a server go green without reopening anything. A view whose player
	 * has left, or who has since opened something else, is dropped instead.
	 */
	private void refreshOpenMenus() {
		for (Map.Entry<UUID, OpenViewState> entry : new LinkedHashMap<>(openViews).entrySet()) {
			UUID playerId = entry.getKey();
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			LunaChestMenuBase menu = openMenus.get(playerId);

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

			ServerSelectorPayload payload = currentPayloadFor(playerId);
			Map<Integer, Map<Integer, ServerRenderEntry>> layoutByPage = layoutByPage(payload);
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
		LunaChestMenuBase menu,
		ServerSelectorPayload payload,
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
			menu.setDecoration(SLOT_PREV_PAGE, itemStack("black_stained_glass_pane", "<dark_gray>Trang trước</dark_gray>", List.of(
				"<gray>Bạn đang ở trang đầu</gray>"
			), null));
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
			menu.setDecoration(SLOT_NEXT_PAGE, itemStack("black_stained_glass_pane", "<dark_gray>Trang sau</dark_gray>", List.of(
				"<gray>Bạn đang ở trang cuối</gray>"
			), null));
		}
	}

	private void renderDashboardPage(ServerPlayer player, LunaChestMenuBase menu, int returnPage) {
		menu.clearTopSlots();

		DashboardStats stats = ServerSelectorEngine.dashboardStats(statusView == null ? Map.of() : statusView.snapshot());

		fillDashboardBackground(menu);

		menu.setDecoration(10, itemStack("clock", "<yellow>TPS Tổng Thể</yellow>", List.of(
			"<gray>Giá trị trung bình toàn mạng</gray>",
			LunaProgressBarPresets.tps("TPS", stats.averageTps()).render()
		), null));

		menu.setDecoration(12, itemStack("redstone", "<color:#FF9A4D>CPU Trung Bình</color>", List.of(
			"<gray>Tải CPU theo heartbeat backend</gray>",
			LunaProgressBarPresets.cpu("CPU", stats.averageCpu()).render()
		), null));

		menu.setDecoration(14, itemStack("iron_block", "<color:#7FDBFF>RAM Tổng</color>", List.of(
			"<gray>Sử dụng bộ nhớ toàn mạng</gray>",
			LunaProgressBarPresets.ram("RAM", stats.totalRamUsedBytes(), stats.totalRamMaxBytes()).render(),
			"<gray>" + formatMb(stats.totalRamUsedBytes()) + "MB / " + formatMb(stats.totalRamMaxBytes()) + "MB</gray>"
		), null));

		menu.setDecoration(16, itemStack("repeater", "<aqua>Latency Heartbeat</aqua>", List.of(
			"<gray>Độ trễ backend → proxy</gray>",
			LunaProgressBarPresets.latency("Latency", stats.averageLatency()).render()
		), null));

		menu.setDecoration(31, itemStack("chest", "<gold>Uptime Cao Nhất</gold>", List.of(
			"<gray>Máy chủ chạy lâu nhất</gray>",
			"<white>" + Formatters.duration(Duration.ofMillis(Math.max(0L, stats.maxUptimeMillis()))) + "</white>"
		), null));

		menu.setDecoration(30, itemStack("emerald", "<green>Online Servers</green>", List.of(
			"<white>" + stats.onlineServerCount() + "</white><gray>/</gray><white>" + stats.allServers().size() + "</white>"
		), null));

		menu.setDecoration(32, itemStack("player_head", "<color:#9EE6A3>Người Chơi Toàn Mạng</color>", List.of(
			"<white>" + stats.totalOnlinePlayers() + "</white>"
		), null));

		menu.setTopSlot(49, itemStack("arrow", "<yellow>Quay Lại Danh Sách Server</yellow>", List.of(
			"<gray>Trở về trang trước đó</gray>"
		), null), () -> openSelector(player, returnPage));
	}

	private void decorateServerGrid(LunaChestMenuBase menu, Set<Integer> occupiedSlots) {
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

		int borderIndex = 0;

		for (int slot : borderSlots()) {
			if (occupiedSlots.contains(slot)) {
				continue;
			}

			String pane = gradientBorder[borderIndex % gradientBorder.length];
			menu.setDecoration(slot, itemStack(pane, "<gradient:#6DFFD4:#4EA3FF>◈</gradient>", List.of(), null));
			borderIndex++;
		}
	}

	private void decorateFooter(LunaChestMenuBase menu) {
		for (int slot = 45; slot <= 53; slot++) {
			menu.setDecoration(slot, itemStack("black_stained_glass_pane", "<dark_gray> </dark_gray>", List.of(), null));
		}
	}

	private void fillDashboardBackground(LunaChestMenuBase menu) {
		for (int slot = 0; slot < GUI_SIZE; slot++) {
			menu.setDecoration(slot, itemStack("gray_stained_glass_pane", "<gray> </gray>", List.of(), null));
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
		ServerSelectorPayload payload,
		boolean noPermission
	) {
		RenderedServerItem renderedItem = ServerSelectorEngine.renderServerItem(status, serverPayload, payload, noPermission);
		return itemStack(renderedItem.materialName(), renderedItem.title(), renderedItem.lore(), renderedItem.glint());
	}

	private String applyTemplate(String template, Map<String, String> values) {
		return ServerSelectorEngine.applyTemplate(template, values);
	}

	private Map<Integer, Map<Integer, ServerRenderEntry>> layoutByPage(ServerSelectorPayload payload) {
		return ServerSelectorEngine.layoutByPage(payload, statusView == null ? Map.of() : statusView.snapshot(), null);
	}

	private int requestedPage(UUID playerId) {
		OpenViewState state = playerId == null ? null : openViews.get(playerId);

		if (state == null || state.kind() != ViewKind.SELECTOR) {
			return 0;
		}

		return state.page();
	}

	private ServerSelectorPayload currentPayloadFor(UUID playerId) {
		ServerSelectorPayload payload = playerId == null ? null : payloadByPlayer.get(playerId);

		if (payload != null && !payload.isEmpty()) {
			return payload;
		}

		return selectorPayload;
	}

	private Component selectorTitle(ServerSelectorPayload payload, ServerPlayer player) {
		String title = payload == null ? "Danh Sách Máy Chủ" : payload.guiTitle();
		return LunaTextComponents.mini(applyTemplate(title, Map.of("player_name", player.getName().getString())));
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
		ServerPlayer player = playerFrom(context.getSource());
		List<String> suggestions = currentPayloadFor(player == null ? null : player.getUUID()).servers().values().stream()
			.map(ServerPayload::backendName)
			.sorted(String.CASE_INSENSITIVE_ORDER)
			.toList();

		return SharedSuggestionProvider.suggest(suggestions, builder);
	}

	private ItemStack itemStack(String materialName, String title, List<String> loreLines, Boolean glintOverride) {
		return LunaItems.of(materialName, title, loreLines, glintOverride);
	}

	private long formatMb(long bytes) {
		if (bytes <= 0L) {
			return 0L;
		}

		return Math.max(0L, bytes / 1024L / 1024L);
	}

	private String safe(String value) {
		return value == null ? "" : value;
	}

	private ServerSelectorPayload parsePayload(byte[] rawPayload) {
		return ServerSelectorEngine.parsePayload(rawPayload);
	}

	private enum ViewKind {
		SELECTOR,
		DASHBOARD
	}

	private record OpenViewState(ViewKind kind, int page) {
	}
}
