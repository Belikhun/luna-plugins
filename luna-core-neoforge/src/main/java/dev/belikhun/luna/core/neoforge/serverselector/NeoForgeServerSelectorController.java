package dev.belikhun.luna.core.neoforge.serverselector;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.CoreServerSelectorMessageChannels;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.neoforge.heartbeat.NeoForgeHeartbeatPublisher;
import dev.belikhun.luna.core.neoforge.text.NeoForgeTextComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class NeoForgeServerSelectorController {
	private static final String OPEN_COMMAND = "lunaservers";
	private static final String CONNECT_COMMAND = "lunacoreconnect";

	private final MinecraftServer server;
	private final DependencyManager dependencyManager;
	private final LunaLogger logger;
	private final PermissionService permissionService;

	private volatile SelectorPayload selectorPayload;
	private volatile boolean messagingAttached;
	private PluginMessageBus<ServerPlayer, ServerPlayer> messagingBus;
	private NeoForgeHeartbeatPublisher heartbeatPublisher;

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
		this.selectorPayload = SelectorPayload.empty();
		this.messagingAttached = false;
		this.messagingBus = null;
		this.heartbeatPublisher = null;
	}

	public void start(NeoForgeHeartbeatPublisher heartbeatPublisher) {
		this.heartbeatPublisher = heartbeatPublisher;
		if (heartbeatPublisher != null) {
			heartbeatPublisher.setSelectorPayloadConsumer(this::acceptSelectorPayload);
			heartbeatPublisher.syncServerSelectorConfigNow();
		}
		ensureMessagingAttached();
	}

	public void close() {
		if (heartbeatPublisher != null) {
			heartbeatPublisher.setSelectorPayloadConsumer(null);
			heartbeatPublisher = null;
		}

		if (messagingAttached && messagingBus != null) {
			messagingBus.unregisterIncoming(CoreServerSelectorMessageChannels.OPEN_MENU);
			messagingBus.unregisterOutgoing(CoreServerSelectorMessageChannels.CONNECT_REQUEST);
		}

		messagingAttached = false;
		messagingBus = null;
		selectorPayload = SelectorPayload.empty();
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
			if (context.source() == null) {
				return PluginMessageDispatchResult.HANDLED;
			}

			openSelector(context.source());
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
	}

	private int executeOpen(CommandContext<CommandSourceStack> context) {
		return openSelector(playerFrom(context.getSource())) ? 1 : 0;
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
			source.sendFailure(Component.literal("Hệ thống chuyển máy chủ chưa sẵn sàng.").withStyle(ChatFormatting.RED));
			return 0;
		}

		String normalizedBackend = backendName == null ? "" : backendName.trim();
		if (normalizedBackend.isBlank()) {
			source.sendFailure(Component.literal("Thiếu tên máy chủ cần kết nối.").withStyle(ChatFormatting.RED));
			return 0;
		}

		SelectorServerEntry entry = selectorPayload.server(normalizedBackend).orElse(null);
		if (entry != null && !canUse(player, entry)) {
			source.sendFailure(Component.literal("Bạn không có quyền truy cập máy chủ này.").withStyle(ChatFormatting.RED));
			return 0;
		}

		boolean sent = messagingBus.send(player, CoreServerSelectorMessageChannels.CONNECT_REQUEST, writer -> {
			writer.writeUtf(player.getUUID().toString());
			writer.writeUtf(normalizedBackend);
		});

		if (!sent) {
			source.sendFailure(Component.literal("Không thể gửi yêu cầu kết nối tới proxy.").withStyle(ChatFormatting.RED));
			return 0;
		}

		source.sendSuccess(
			() -> Component.literal("Đang chuyển tới máy chủ " + normalizedBackend + "...").withStyle(ChatFormatting.YELLOW),
			false
		);
		return 1;
	}

	private boolean openSelector(ServerPlayer player) {
		if (player == null) {
			return false;
		}

		ensureMessagingAttached();
		SelectorPayload payload = selectorPayload;
		if (payload.isEmpty()) {
			syncSelectorPayload();
			payload = selectorPayload;
		}

		if (payload.isEmpty()) {
			player.sendSystemMessage(Component.literal("Danh sách máy chủ đang được đồng bộ. Hãy thử lại sau ít giây.").withStyle(ChatFormatting.YELLOW));
			return false;
		}

		List<SelectorServerEntry> availableServers = payload.servers().stream()
			.filter(entry -> canUse(player, entry))
			.toList();
		if (availableServers.isEmpty()) {
			player.sendSystemMessage(Component.literal("Hiện chưa có máy chủ nào khả dụng cho bạn.").withStyle(ChatFormatting.YELLOW));
			return false;
		}

		player.sendSystemMessage(Component.literal(" "));
		player.sendSystemMessage(Component.literal(titleOf(payload)).withStyle(ChatFormatting.GOLD));
		player.sendSystemMessage(Component.literal("Nhấn vào một dòng để chuyển máy chủ.").withStyle(ChatFormatting.GRAY));
		for (SelectorServerEntry entry : availableServers) {
			player.sendSystemMessage(renderEntry(entry));
			for (String line : summarizedDescription(entry)) {
				player.sendSystemMessage(Component.literal("  " + line).withStyle(ChatFormatting.DARK_GRAY));
			}
		}
		return true;
	}

	private MutableComponent renderEntry(SelectorServerEntry entry) {
		String backendName = entry.backendName();
		String displayName = visibleName(entry);
		Component hover = Component.literal("Nhấn để chuyển tới " + displayName).withStyle(ChatFormatting.YELLOW);
		return Component.literal("▶ " + displayName)
			.setStyle(Style.EMPTY
				.withColor(ChatFormatting.AQUA)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + CONNECT_COMMAND + " " + backendName))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)));
	}

	private List<String> summarizedDescription(SelectorServerEntry entry) {
		List<String> lines = new ArrayList<>();
		for (String line : entry.description()) {
			String visible = Formatters.stripFormats(line);
			if (!visible.isBlank()) {
				lines.add(visible);
			}
			if (lines.size() >= 2) {
				break;
			}
		}
		return lines;
	}

	private String titleOf(SelectorPayload payload) {
		String title = Formatters.stripFormats(payload.guiTitle());
		return title.isBlank() ? "Danh Sách Máy Chủ" : title;
	}

	private String visibleName(SelectorServerEntry entry) {
		String display = Formatters.stripFormats(entry.displayName());
		return display.isBlank() ? entry.backendName() : display;
	}

	private boolean canUse(ServerPlayer player, SelectorServerEntry entry) {
		String permission = entry.permission();
		if (permission == null || permission.isBlank()) {
			return true;
		}

		if (permissionService == null || !permissionService.isAvailable()) {
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
		List<String> suggestions = selectorPayload.servers().stream()
			.map(SelectorServerEntry::backendName)
			.sorted(String.CASE_INSENSITIVE_ORDER)
			.toList();
		return SharedSuggestionProvider.suggest(suggestions, builder);
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
			if (!v3 && !v4 && !v5 && !v6 && !v7) {
				return SelectorPayload.empty();
			}

			String guiTitle = reader.readUtf();
			reader.readUtf();
			readLines(reader);
			reader.readUtf();
			readLines(reader);
			if (v7) {
				reader.readUtf();
			}
			skipTemplateOverrides(reader);

			if (v4 || v5 || v6 || v7) {
				int statusCount = Math.max(0, reader.readInt());
				for (int index = 0; index < statusCount; index++) {
					reader.readUtf();
					reader.readUtf();
					reader.readUtf();
				}
			}

			int serverCount = Math.max(0, reader.readInt());
			List<SelectorServerEntry> servers = new ArrayList<>(serverCount);
			for (int index = 0; index < serverCount; index++) {
				String backendName = reader.readUtf();
				String displayName = reader.readUtf();
				reader.readUtf();
				String permission = reader.readUtf();
				reader.readInt();
				reader.readInt();

				if (v5 || v6 || v7) {
					reader.readUtf();
					int materialCount = Math.max(0, reader.readInt());
					for (int materialIndex = 0; materialIndex < materialCount; materialIndex++) {
						reader.readUtf();
						reader.readUtf();
					}

					if (reader.readBoolean()) {
						reader.readBoolean();
					}

					int glintByStatusCount = Math.max(0, reader.readInt());
					for (int glintIndex = 0; glintIndex < glintByStatusCount; glintIndex++) {
						reader.readUtf();
						reader.readBoolean();
					}
				}

				if (v6 || v7) {
					int conditionalCount = Math.max(0, reader.readInt());
					for (int conditionalIndex = 0; conditionalIndex < conditionalCount; conditionalIndex++) {
						reader.readUtf();
						if (reader.readBoolean()) {
							reader.readUtf();
						}
						if (reader.readBoolean()) {
							reader.readBoolean();
						}
						if (reader.readBoolean()) {
							readLines(reader);
						}
						if (reader.readBoolean()) {
							skipTemplateOverride(reader);
						}
					}
				}

				List<String> description = readLines(reader);
				int statusDescriptionCount = Math.max(0, reader.readInt());
				for (int statusIndex = 0; statusIndex < statusDescriptionCount; statusIndex++) {
					reader.readUtf();
					readLines(reader);
				}

				boolean hasServerTemplate = reader.readBoolean();
				if (hasServerTemplate) {
					reader.readUtf();
					readLines(reader);
					reader.readUtf();
					readLines(reader);
					if (v7) {
						reader.readUtf();
					}
					skipTemplateOverrides(reader);
				}

				servers.add(new SelectorServerEntry(backendName, displayName, permission, List.copyOf(description)));
			}

			return new SelectorPayload(guiTitle, List.copyOf(servers));
		} catch (Exception exception) {
			logger.debug("Không thể parse selector payload trên NeoForge: " + exception.getMessage());
			return SelectorPayload.empty();
		}
	}

	private List<String> readLines(PluginMessageReader reader) {
		int lineCount = Math.max(0, reader.readInt());
		List<String> lines = new ArrayList<>(lineCount);
		for (int index = 0; index < lineCount; index++) {
			lines.add(reader.readUtf());
		}
		return lines;
	}

	private void skipTemplateOverrides(PluginMessageReader reader) {
		int overrideCount = Math.max(0, reader.readInt());
		for (int index = 0; index < overrideCount; index++) {
			reader.readUtf();
			skipTemplateOverride(reader);
		}
	}

	private void skipTemplateOverride(PluginMessageReader reader) {
		if (reader.readBoolean()) {
			reader.readUtf();
		}
		if (reader.readBoolean()) {
			readLines(reader);
		}
		if (reader.readBoolean()) {
			reader.readUtf();
		}
		if (reader.readBoolean()) {
			readLines(reader);
		}
	}

	private record SelectorPayload(String guiTitle, List<SelectorServerEntry> servers) {
		private static SelectorPayload empty() {
			return new SelectorPayload("", List.of());
		}

		private boolean isEmpty() {
			return servers.isEmpty();
		}

		private Optional<SelectorServerEntry> server(String backendName) {
			if (backendName == null || backendName.isBlank()) {
				return Optional.empty();
			}

			String normalized = backendName.trim().toLowerCase(Locale.ROOT);
			return servers.stream()
				.filter(entry -> entry.backendName().trim().toLowerCase(Locale.ROOT).equals(normalized))
				.findFirst();
		}
	}

	private record SelectorServerEntry(String backendName, String displayName, String permission, List<String> description) {
	}
}
