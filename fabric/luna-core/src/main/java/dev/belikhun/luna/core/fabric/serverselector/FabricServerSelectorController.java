package dev.belikhun.luna.core.fabric.serverselector;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatPublisher;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.CoreServerSelectorMessageChannels;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.api.serverselector.ServerSelectorEngine;
import dev.belikhun.luna.core.api.serverselector.ServerSelectorEngine.RenderedServerItem;
import dev.belikhun.luna.core.api.serverselector.ServerSelectorEngine.ServerPayload;
import dev.belikhun.luna.core.api.serverselector.ServerSelectorEngine.ServerRenderEntry;
import dev.belikhun.luna.core.api.serverselector.ServerSelectorEngine.ServerSelectorPayload;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.fabric.compat.ChatEvents;
import dev.belikhun.luna.core.fabric.compat.Guarded;
import dev.belikhun.luna.core.fabric.text.FabricTextComponents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * The network's server list, rendered into chat.
 *
 * NeoForge and Paper open a real inventory for this, but an inventory is built
 * out of item display data, and that is precisely what the 1.20.5 component
 * rewrite replaced - a single jar cannot fill both shapes without carrying two
 * item layers. Chat costs no such split: the same {@link ServerSelectorEngine}
 * produces the same titles and the same lore, and a line the player can click is
 * what the menu was for. The clickable and hoverable parts are attached
 * defensively, because those two event types were themselves reshaped in 1.21.5;
 * where they do not take, the command is printed in full instead.
 */
public final class FabricServerSelectorController {
	private static final String OPEN_COMMAND = "lunaservers";
	private static final String CONNECT_COMMAND = "lunacoreconnect";

	private final MinecraftServer server;
	private final DependencyManager dependencyManager;
	private final LunaLogger logger;
	private final PermissionService permissionService;
	private final Map<UUID, ServerSelectorPayload> payloadByPlayer;

	private volatile ServerSelectorPayload selectorPayload;
	private volatile boolean messagingAttached;
	private volatile PluginMessageBus<ServerPlayer, ServerPlayer> messagingBus;
	private volatile BackendHeartbeatPublisher heartbeatPublisher;
	private volatile BackendStatusView statusView;

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
		this.selectorPayload = ServerSelectorPayload.empty();
		this.messagingAttached = false;
		this.messagingBus = null;
		this.heartbeatPublisher = null;
		this.statusView = null;
	}

	public void start(BackendHeartbeatPublisher publisher) {
		this.heartbeatPublisher = publisher;
		this.statusView = dependencyManager.resolveOptional(BackendStatusView.class).orElse(null);

		if (publisher != null) {
			publisher.setSelectorPayloadConsumer(this::acceptSelectorPayload);
			publisher.syncServerSelectorConfigNow();
		}

		ensureMessagingAttached();
	}

	public void close() {
		statusView = null;

		if (heartbeatPublisher != null) {
			heartbeatPublisher.setSelectorPayloadConsumer(null);
			heartbeatPublisher = null;
		}

		if (messagingAttached && messagingBus != null) {
			messagingBus.unregisterIncoming(CoreServerSelectorMessageChannels.OPEN_MENU);
			messagingBus.unregisterOutgoing(CoreServerSelectorMessageChannels.CONNECT_REQUEST);
		}

		payloadByPlayer.clear();
		messagingAttached = false;
		messagingBus = null;
		selectorPayload = ServerSelectorPayload.empty();
	}

	public void cleanupPlayer(UUID playerId) {
		if (playerId != null) {
			payloadByPlayer.remove(playerId);
		}
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
				payloadByPlayer.put(player.getUUID(), ServerSelectorEngine.parsePayload(context.payload()));
			}

			server.execute(() -> openSelector(player));
			return PluginMessageDispatchResult.HANDLED;
		});

		messagingBus = resolved;
		messagingAttached = true;
		logger.audit("Đã gắn Fabric server selector vào plugin messaging bus.");
	}

	public void acceptSelectorPayload(byte[] payload) {
		ServerSelectorPayload parsed = ServerSelectorEngine.parsePayload(payload);
		if (!parsed.isEmpty()) {
			selectorPayload = parsed;
		}
	}

	private int executeOpen(CommandContext<CommandSourceStack> context) {
		ServerPlayer player = playerFrom(context.getSource());
		return player != null && openSelector(player) ? 1 : 0;
	}

	private boolean openSelector(ServerPlayer player) {
		if (player == null) {
			return false;
		}

		ensureMessagingAttached();
		ServerSelectorPayload payload = currentPayloadFor(player.getUUID());
		if (payload.isEmpty()) {
			// the fetch is asynchronous, so it serves the player's next attempt
			syncSelectorPayload();
			player.sendSystemMessage(Component.literal("Danh sách máy chủ đang được đồng bộ. Hãy thử lại sau ít giây."));
			return false;
		}

		player.sendSystemMessage(FabricTextComponents.mini(
			ServerSelectorEngine.applyTemplate(payload.guiTitle(), Map.of("player_name", player.getName().getString()))
		));

		for (ServerRenderEntry entry : orderedEntries(payload)) {
			player.sendSystemMessage(line(player, payload, entry));
		}

		return true;
	}

	/**
	 * The engine lays servers out as pages of inventory slots; chat has neither,
	 * so the pages are flattened back into one ordered list while keeping the
	 * slot order the operator configured.
	 */
	private List<ServerRenderEntry> orderedEntries(ServerSelectorPayload payload) {
		Map<String, BackendServerStatus> snapshot = statusView == null ? Map.of() : statusView.snapshot();
		Map<Integer, Map<Integer, ServerRenderEntry>> byPage = ServerSelectorEngine.layoutByPage(payload, snapshot, null);
		List<ServerRenderEntry> ordered = new ArrayList<>();

		byPage.keySet().stream().sorted().forEach(page -> {
			Map<Integer, ServerRenderEntry> slots = byPage.get(page);
			slots.keySet().stream().sorted().forEach(slot -> ordered.add(slots.get(slot)));
		});

		return ordered;
	}

	private Component line(ServerPlayer player, ServerSelectorPayload payload, ServerRenderEntry entry) {
		ServerPayload serverPayload = entry.payload();
		String backendName = serverPayload == null ? "" : serverPayload.backendName();
		boolean noPermission = serverPayload != null && !canUse(player, serverPayload.permission());
		RenderedServerItem rendered = ServerSelectorEngine.renderServerItem(entry.status(), serverPayload, payload, noPermission);

		MutableComponent line = Component.empty()
			.append(Component.literal(" "))
			.append(FabricTextComponents.mini(rendered.title()));

		if (noPermission || backendName.isBlank()) {
			return line;
		}

		String command = "/" + CONNECT_COMMAND + " " + backendName;

		return clickable(line, command, rendered.lore());
	}

	/**
	 * Make a line run the connect command, with the item's lore as its tooltip.
	 * Both event types were reshaped in 1.21.5, so a version that refuses them
	 * gets the command spelled out rather than a line that does nothing.
	 *
	 * {@link ChatEvents} is the per-line half of that call; this build links the
	 * 1.20-1.21 copy of it.
	 */
	private Component clickable(MutableComponent line, String command, List<String> lore) {
		MutableComponent hover = Component.empty();
		boolean first = true;

		for (String loreLine : lore) {
			if (!first) {
				hover.append(Component.literal("\n"));
			}
			hover.append(FabricTextComponents.mini(loreLine));
			first = false;
		}

		boolean decorated = Guarded.booleanValue(() -> {
			ChatEvents.decorate(line, command, hover);
			return true;
		}, false);

		if (decorated) {
			return line;
		}

		return line.append(FabricTextComponents.mini(" <gray>(" + command + ")</gray>"));
	}

	private int sendConnectUsage(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		source.sendSuccess(
			() -> FabricTextComponents.mini(CommandStrings.usage(CONNECT_COMMAND, CommandStrings.required("server", "text"))),
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

	private boolean sendConnectRequest(ServerPlayer player, String backendName) {
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

	private ServerSelectorPayload currentPayloadFor(UUID playerId) {
		ServerSelectorPayload payload = playerId == null ? null : payloadByPlayer.get(playerId);
		if (payload != null && !payload.isEmpty()) {
			return payload;
		}
		return selectorPayload;
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
}
