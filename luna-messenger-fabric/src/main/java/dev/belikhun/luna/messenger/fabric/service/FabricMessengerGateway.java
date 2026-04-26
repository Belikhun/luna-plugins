package dev.belikhun.luna.messenger.fabric.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messenger.BackendPlaceholderResolver;
import dev.belikhun.luna.core.api.messenger.MessengerChannels;
import dev.belikhun.luna.core.api.messenger.MessengerCommandRequest;
import dev.belikhun.luna.core.api.messenger.MessengerCommandType;
import dev.belikhun.luna.core.api.messenger.MessengerPresenceMessage;
import dev.belikhun.luna.core.api.messenger.MessengerPresenceType;
import dev.belikhun.luna.core.api.messenger.MessengerResultMessage;
import dev.belikhun.luna.core.api.messenger.MessengerResultType;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionRequest;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.fabric.toast.FabricAdvancementToastService;
import dev.belikhun.luna.core.fabric.messaging.FabricMessageSource;
import dev.belikhun.luna.core.fabric.messaging.FabricMessageTarget;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class FabricMessengerGateway {
	private static final long MIN_TIMEOUT_MILLIS = 1000L;

	private final LunaLogger logger;
	private final PluginMessageBus<FabricMessageSource, FabricMessageTarget> bus;
	private final Supplier<MinecraftServer> serverSupplier;
	private final FabricAdvancementToastService toastService;
	private final BackendPlaceholderResolver placeholderResolver;
	private final boolean timeoutEnabled;
	private final long requestTimeoutMillis;
	private final Map<UUID, String> networkPlayerNames = new ConcurrentHashMap<>();
	private final Map<UUID, MessengerResultMessage> latestResultByReceiver = new ConcurrentHashMap<>();
	private final Map<UUID, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

	public FabricMessengerGateway(
		LunaLogger logger,
		PluginMessageBus<FabricMessageSource, FabricMessageTarget> bus
	) {
		this(logger, bus, () -> null, null, new FabricBackendPlaceholderResolver(), 6000L, true);
	}

	public FabricMessengerGateway(
		LunaLogger logger,
		PluginMessageBus<FabricMessageSource, FabricMessageTarget> bus,
		BackendPlaceholderResolver placeholderResolver,
		long requestTimeoutMillis,
		boolean timeoutEnabled
	) {
		this(logger, bus, () -> null, null, placeholderResolver, requestTimeoutMillis, timeoutEnabled);
	}

	public FabricMessengerGateway(
		LunaLogger logger,
		PluginMessageBus<FabricMessageSource, FabricMessageTarget> bus,
		Supplier<MinecraftServer> serverSupplier,
		FabricAdvancementToastService toastService,
		BackendPlaceholderResolver placeholderResolver,
		long requestTimeoutMillis,
		boolean timeoutEnabled
	) {
		this.logger = logger.scope("Gateway");
		this.bus = bus;
		this.serverSupplier = serverSupplier == null ? () -> null : serverSupplier;
		this.toastService = toastService;
		this.placeholderResolver = placeholderResolver;
		this.requestTimeoutMillis = Math.max(MIN_TIMEOUT_MILLIS, requestTimeoutMillis);
		this.timeoutEnabled = timeoutEnabled;
	}

	public void registerChannels() {
		bus.registerOutgoing(MessengerChannels.COMMAND);
		bus.registerIncoming(MessengerChannels.RESULT, context -> {
			MessengerResultMessage result = MessengerResultMessage.readFrom(context.reader());
			latestResultByReceiver.put(result.receiverId(), result);
			if (result.correlationId() != null) {
				pendingRequests.remove(result.correlationId());
			}
			deliverResult(result);
			logger.audit("Đã nhận MessengerResult type=" + result.resultType().name() + " receiver=" + result.receiverId());
			return PluginMessageDispatchResult.HANDLED;
		});
		bus.registerIncoming(MessengerChannels.PRESENCE, context -> {
			MessengerPresenceMessage presence = MessengerPresenceMessage.readFrom(context.reader());
			handlePresence(presence);
			return PluginMessageDispatchResult.HANDLED;
		});
	}

	public void close() {
		bus.unregisterOutgoing(MessengerChannels.COMMAND);
		bus.unregisterIncoming(MessengerChannels.RESULT);
		bus.unregisterIncoming(MessengerChannels.PRESENCE);
		networkPlayerNames.clear();
		latestResultByReceiver.clear();
		pendingRequests.clear();
	}

	public Collection<String> suggestDirectTargets(String partial, String senderName) {
		String token = partial == null ? "" : partial;
		String sender = senderName == null ? "" : senderName;
		ArrayList<String> names = new ArrayList<>();
		for (String value : networkPlayerNames.values()) {
			if (value == null || value.isBlank()) {
				continue;
			}
			if (value.equalsIgnoreCase(sender)) {
				continue;
			}
			if (!token.isEmpty() && !value.regionMatches(true, 0, token, 0, token.length())) {
				continue;
			}
			names.add(value);
		}
		names.sort(String.CASE_INSENSITIVE_ORDER);
		if (names.size() > 20) {
			return List.copyOf(names.subList(0, 20));
		}
		return names;
	}

	public boolean switchNetwork(UUID senderId, String senderName, String senderServer) {
		return send(senderId, senderName, senderServer, MessengerCommandType.SWITCH_NETWORK, "", Map.of());
	}

	public boolean switchServer(UUID senderId, String senderName, String senderServer) {
		return send(senderId, senderName, senderServer, MessengerCommandType.SWITCH_SERVER, "", Map.of());
	}

	public boolean switchDirect(UUID senderId, String senderName, String senderServer, String targetName) {
		return send(senderId, senderName, senderServer, MessengerCommandType.SWITCH_DIRECT, targetName, Map.of());
	}

	public boolean sendDirect(UUID senderId, String senderName, String senderServer, String content, String targetName) {
		return send(senderId, senderName, senderServer, MessengerCommandType.SEND_DIRECT, content, Map.of("target", normalize(targetName)));
	}

	public boolean sendPoke(UUID senderId, String senderName, String senderServer, String targetName) {
		return send(senderId, senderName, senderServer, MessengerCommandType.SEND_POKE, targetName, Map.of());
	}

	public boolean sendReply(UUID senderId, String senderName, String senderServer, String content) {
		return send(senderId, senderName, senderServer, MessengerCommandType.SEND_REPLY, content, Map.of());
	}

	public boolean sendChat(UUID senderId, String senderName, String senderServer, String content) {
		return send(senderId, senderName, senderServer, MessengerCommandType.SEND_CHAT, content, Map.of());
	}

	public Map<UUID, MessengerResultMessage> latestResults() {
		return Collections.unmodifiableMap(latestResultByReceiver);
	}

	public Map<UUID, PendingRequest> pendingRequests() {
		return Collections.unmodifiableMap(pendingRequests);
	}

	public Map<UUID, String> onlineNetworkPlayers() {
		return Collections.unmodifiableMap(networkPlayerNames);
	}

	public void handleLocalPlayerJoin(UUID playerId, String playerName) {
		if (playerId == null || playerName == null || playerName.isBlank()) {
			return;
		}

		networkPlayerNames.put(playerId, playerName);
	}

	public void handleLocalPlayerQuit(UUID playerId) {
		if (playerId == null) {
			return;
		}

		networkPlayerNames.remove(playerId);
		pendingRequests.entrySet().removeIf(entry -> entry.getValue().playerId().equals(playerId));
	}

	public Collection<PendingRequest> collectTimedOutRequests(long currentEpochMillis) {
		if (!timeoutEnabled) {
			return List.of();
		}

		ArrayList<PendingRequest> timedOut = new ArrayList<>();
		for (Map.Entry<UUID, PendingRequest> entry : pendingRequests.entrySet()) {
			PendingRequest pending = entry.getValue();
			if (currentEpochMillis - pending.createdAtEpochMillis() < requestTimeoutMillis) {
				continue;
			}

			if (pendingRequests.remove(entry.getKey(), pending)) {
				timedOut.add(pending);
			}
		}

		return timedOut;
	}

	public void handleTimedOutRequests(Collection<PendingRequest> timedOut) {
		if (timedOut == null || timedOut.isEmpty()) {
			return;
		}

		executeOnServer(server -> {
			Component timeoutMessage = Component.literal("❌ Hệ thống chat liên server đang chậm. Vui lòng thử lại.");
			for (PendingRequest pending : timedOut) {
				ServerPlayer player = server.getPlayerList().getPlayer(pending.playerId());
				if (player != null) {
					player.sendSystemMessage(timeoutMessage);
				}
				logger.warn("Timeout command=" + pending.commandType().name() + " reqId=" + pending.requestId());
			}
		});
	}

	private boolean send(
		UUID senderId,
		String senderName,
		String senderServer,
		MessengerCommandType commandType,
		String argument,
		Map<String, String> resolvedValues
	) {
		if (senderId == null) {
			return false;
		}
		UUID requestId = UUID.randomUUID();
		Map<String, String> internalValues = new LinkedHashMap<>();
		internalValues.put("sender_name", normalize(senderName));
		internalValues.put("player_name", normalize(senderName));
		internalValues.put("sender_server", normalize(senderServer));
		internalValues.put("server_name", normalize(senderServer));
		if (commandType == MessengerCommandType.SWITCH_DIRECT
			|| commandType == MessengerCommandType.SEND_DIRECT
			|| commandType == MessengerCommandType.SEND_POKE) {
			String targetName = resolvedValues.getOrDefault("target", argument);
			internalValues.put("target_name", normalize(targetName));
		}

		var resolution = placeholderResolver.resolve(new PlaceholderResolutionRequest(
			senderId,
			normalize(senderName),
			normalize(senderServer),
			normalize(argument),
			internalValues
		));

		MessengerCommandRequest request = new MessengerCommandRequest(
			MessengerCommandRequest.CURRENT_PROTOCOL,
			requestId,
			commandType,
			senderId,
			normalize(senderName),
			normalize(senderServer),
			resolution.resolvedContent(),
			null,
			resolution.exportedValues()
		);

		boolean sent = bus.send(FabricMessageTarget.player(senderId), MessengerChannels.COMMAND, request::writeTo);
		if (sent) {
			pendingRequests.put(requestId, new PendingRequest(requestId, senderId, commandType, System.currentTimeMillis()));
		}
		return sent;
	}

	private void handlePresence(MessengerPresenceMessage presence) {
		if (presence.presenceType() == MessengerPresenceType.LEAVE) {
			networkPlayerNames.remove(presence.playerId());
			return;
		}

		networkPlayerNames.put(presence.playerId(), presence.playerName());
	}

	private void deliverResult(MessengerResultMessage result) {
		executeOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayer(result.receiverId());
			if (player == null) {
				return;
			}

			sendPlainMessage(player, result.miniMessage());
			if (result.resultType() == MessengerResultType.MENTION_ALERT) {
				playResultSound(player, result.metadata().get("mention.sound"), "1.0", "1.0", "mention");
				sendMentionToast(player, result.metadata());
			} else if (result.resultType() == MessengerResultType.POKE_ALERT) {
				playResultSound(player, result.metadata().get("poke.sound"), result.metadata().get("poke.volume"), result.metadata().get("poke.pitch"), "poke");
			}
		});
	}

	private void sendPlainMessage(ServerPlayer player, String miniMessage) {
		String plain = stripMiniMessage(miniMessage);
		if (!plain.isBlank()) {
			player.sendSystemMessage(Component.literal(plain));
		}
	}

	private void sendMentionToast(ServerPlayer player, Map<String, String> metadata) {
		Map<String, String> safeMetadata = metadata == null ? Map.of() : metadata;
		boolean toastEnabled = !"false".equalsIgnoreCase(safeMetadata.getOrDefault("toast.enabled", "true"));
		if (!toastEnabled) {
			return;
		}

		String title = stripMiniMessage(safeMetadata.getOrDefault("toast.title", "Bạn được nhắc đến"));
		String subtitle = stripMiniMessage(safeMetadata.getOrDefault("toast.subtitle", "Kiểm tra khung chat"));
		if (toastService != null) {
			FabricAdvancementToastService.ToastResult result = toastService.sendOneShot(player, "mention_toast", title, subtitle);
			if (result.success()) {
				return;
			}
			logger.audit("MENTION_TOAST_FAIL player=" + player.getGameProfile().getName() + " reason=" + result.failureReason());
		}

		if (!title.isBlank()) {
			player.sendSystemMessage(Component.literal("ℹ " + title));
		}
		if (!subtitle.isBlank()) {
			player.sendSystemMessage(Component.literal(subtitle));
		}
	}

	private void playResultSound(ServerPlayer player, String configuredSound, String volumeText, String pitchText, String label) {
		if (configuredSound == null || configuredSound.isBlank() || configuredSound.equalsIgnoreCase("none")) {
			return;
		}

		String normalized = configuredSound.trim().toLowerCase();
		if (!normalized.contains(":")) {
			normalized = "minecraft:" + normalized.replace('_', '.');
		}

		ResourceLocation location = ResourceLocation.tryParse(normalized);
		if (location == null) {
			logger.debug("Sound " + label + " không hợp lệ: " + configuredSound);
			return;
		}

		SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getOptional(location).orElse(null);
		if (sound == null) {
			logger.debug("Không tìm thấy sound " + label + ": " + normalized);
			return;
		}

		float volume = parseFloat(volumeText, 1f);
		float pitch = parseFloat(pitchText, 1f);
		try {
			player.playNotifySound(sound, SoundSource.MASTER, volume, pitch);
		} catch (Throwable throwable) {
			logger.debug("Không thể phát sound " + label + " cho " + player.getGameProfile().getName() + ": " + configuredSound);
		}
	}

	private float parseFloat(String text, float fallback) {
		if (text == null || text.isBlank()) {
			return fallback;
		}

		try {
			return Float.parseFloat(text.trim());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private void executeOnServer(Consumer<MinecraftServer> action) {
		MinecraftServer server = serverSupplier.get();
		if (server == null || action == null) {
			return;
		}

		server.execute(() -> action.accept(server));
	}

	private String normalize(String value) {
		return value == null ? "" : value;
	}

	private String stripMiniMessage(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}

		return text
			.replace("<br>", "\n")
			.replace("<newline>", "\n")
			.replaceAll("<[^>]+>", "")
			.replaceAll("\\s+", " ")
			.trim();
	}

	public record PendingRequest(
		UUID requestId,
		UUID playerId,
		MessengerCommandType commandType,
		long createdAtEpochMillis
	) {
	}
}
