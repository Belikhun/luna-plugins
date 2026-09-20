package dev.belikhun.luna.vault.rpc;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageContext;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.vault.api.VaultChannels;
import dev.belikhun.luna.vault.api.VaultFailureReason;
import dev.belikhun.luna.vault.api.VaultOperationResult;
import dev.belikhun.luna.vault.api.VaultPlayerSnapshot;
import dev.belikhun.luna.vault.api.ledger.LedgerResult;
import dev.belikhun.luna.vault.api.rpc.VaultRpcProtocol;
import dev.belikhun.luna.vault.api.rpc.VaultRpcRequest;
import dev.belikhun.luna.vault.api.rpc.VaultRpcResponse;
import dev.belikhun.luna.vault.service.VelocityVaultService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The proxy's end of the backend RPC channel.
 *
 * A frame is decoded, checked for protocol version, handed to the service and
 * answered when the service is done; nothing here waits on the database, so
 * the thread the bus delivered on (an event thread, or a scheduler thread for
 * AMQP) is free again at once.
 *
 * The reply goes to the *server* the frame came from, never to the player who
 * carried it. Answering the player is how a reply used to end up inside a
 * Minecraft client when the carrier had switched servers in the meantime,
 * leaving the backend to time out on a request the ledger had already applied.
 */
public final class VaultRpcHandler {
	private static final long PROTOCOL_WARN_INTERVAL_MILLIS = 60_000L;
	private static final String MESSAGE_INTERNAL = "Yêu cầu kinh tế thất bại ở proxy.";
	private static final String MESSAGE_NOT_RECORDED = "Chưa có giao dịch nào với mã này.";
	private static final String MESSAGE_INVALID_PLAYER = "Người chơi không hợp lệ.";

	private final ProxyServer proxyServer;
	private final LunaLogger logger;
	private final VelocityVaultService service;
	private final PluginMessageBus<Object, Object> bus;
	private final Map<String, Long> protocolWarnings;
	private final AtomicLong unroutableReplies;

	public VaultRpcHandler(ProxyServer proxyServer, LunaLogger logger, VelocityVaultService service, PluginMessageBus<Object, Object> bus) {
		this.proxyServer = proxyServer;
		this.logger = logger.scope("Rpc");
		this.service = service;
		this.bus = bus;
		this.protocolWarnings = new ConcurrentHashMap<>();
		this.unroutableReplies = new AtomicLong();
	}

	public void register() {
		bus.registerOutgoing(VaultChannels.CACHE_SYNC);
		bus.registerOutgoing(VaultChannels.RPC);
		bus.registerIncoming(VaultChannels.RPC, this::onFrame);
	}

	public void unregister() {
		bus.unregisterIncoming(VaultChannels.RPC);
		bus.unregisterOutgoing(VaultChannels.CACHE_SYNC);
		bus.unregisterOutgoing(VaultChannels.RPC);
	}

	private PluginMessageDispatchResult onFrame(PluginMessageContext<Object> context) {
		Object replyTarget = replyTargetFor(context.source());
		PluginMessageReader reader = PluginMessageReader.of(context.payload());
		int version;

		try {
			version = VaultRpcProtocol.readVersion(reader);
		} catch (RuntimeException malformed) {
			warnProtocol(replyTarget, -1);
			return PluginMessageDispatchResult.HANDLED;
		}

		if (!VaultRpcProtocol.compatible(version)) {
			warnProtocol(replyTarget, version);
			return PluginMessageDispatchResult.HANDLED;
		}

		VaultRpcRequest request;

		try {
			request = VaultRpcRequest.readBody(reader);
		} catch (RuntimeException malformed) {
			logger.warn("Bỏ qua RPC LunaVault không đọc được từ " + describe(replyTarget) + ": " + malformed.getMessage());
			return PluginMessageDispatchResult.HANDLED;
		}

		handle(request).whenComplete((response, throwable) -> {
			VaultRpcResponse reply = response;

			if (throwable != null || reply == null) {
				if (throwable != null) {
					logger.error("Xử lý RPC của LunaVault thất bại.", throwable);
				}

				reply = VaultRpcResponse.failed(request.correlationId(), VaultFailureReason.INTERNAL_ERROR, MESSAGE_INTERNAL);
			}

			send(replyTarget, reply, request);
		});

		return PluginMessageDispatchResult.HANDLED;
	}

	/**
	 * Answer one request.
	 *
	 * Public so the same mapping can be exercised without a bus: every action
	 * becomes a service call, every service outcome a response carrying the
	 * snapshots it produced.
	 */
	public CompletableFuture<VaultRpcResponse> handle(VaultRpcRequest request) {
		UUID correlationId = request.correlationId();

		switch (request.action()) {
			case SNAPSHOT:
			case BALANCE:
				if (request.playerId() == null) {
					return CompletableFuture.completedFuture(VaultRpcResponse.failed(correlationId, VaultFailureReason.PLAYER_NOT_FOUND, MESSAGE_INVALID_PLAYER));
				}

				return service.snapshot(request.playerId(), request.playerName()).thenApply(snapshot ->
					VaultRpcResponse.of(correlationId, VaultOperationResult.success(null, snapshot.balanceMinor(), null), List.of(snapshot))
				);

			case DEPOSIT:
				return service.deposit(operationIdOf(request), request.actorId(), request.actorName(), request.playerId(), request.playerName(), request.amountMinor(), request.source(), request.details())
					.thenApply(outcome -> toResponse(correlationId, outcome));

			case WITHDRAW:
				return service.withdraw(operationIdOf(request), request.actorId(), request.actorName(), request.playerId(), request.playerName(), request.amountMinor(), request.source(), request.details())
					.thenApply(outcome -> toResponse(correlationId, outcome));

			case TRANSFER:
				return service.transfer(operationIdOf(request), request.playerId(), request.playerName(), request.targetId(), request.targetName(), request.amountMinor(), request.source(), request.details())
					.thenApply(outcome -> toResponse(correlationId, outcome));

			case SET_BALANCE:
				return service.setBalance(operationIdOf(request), request.actorId(), request.actorName(), request.playerId(), request.playerName(), request.amountMinor(), request.source(), request.details())
					.thenApply(outcome -> toResponse(correlationId, outcome));

			case HISTORY:
				if (request.playerId() == null) {
					return CompletableFuture.completedFuture(VaultRpcResponse.failed(correlationId, VaultFailureReason.PLAYER_NOT_FOUND, MESSAGE_INVALID_PLAYER));
				}

				return service.history(request.playerId(), request.page(), request.pageSize()).thenApply(page ->
					new VaultRpcResponse(correlationId, VaultOperationResult.success(null, 0L, null), List.of(), page, null)
				);

			case LEADERBOARD:
				return service.leaderboard(request.page(), request.pageSize()).thenApply(page ->
					new VaultRpcResponse(correlationId, VaultOperationResult.success(null, 0L, null), List.of(), null, page)
				);

			case LOOKUP:
				return service.lookup(request.operationId(), request.playerId()).thenApply(found -> found
					.map(outcome -> toResponse(correlationId, outcome))
					.orElse(VaultRpcResponse.failed(correlationId, VaultFailureReason.NOT_RECORDED, MESSAGE_NOT_RECORDED))
				);

			default:
				return CompletableFuture.completedFuture(VaultRpcResponse.failed(correlationId, VaultFailureReason.INTERNAL_ERROR, MESSAGE_INTERNAL));
		}
	}

	private VaultRpcResponse toResponse(UUID correlationId, LedgerResult outcome) {
		return VaultRpcResponse.of(correlationId, outcome.result(), outcome.snapshots());
	}

	/** A write without an id still runs; it just cannot be recognised if resent. */
	private UUID operationIdOf(VaultRpcRequest request) {
		return request.operationId() == null ? UUID.randomUUID() : request.operationId();
	}

	// ---------------------------------------------------------------- routing

	private Object replyTargetFor(Object source) {
		if (source instanceof ServerConnection connection) {
			return connection.getServer();
		}

		if (source instanceof RegisteredServer server) {
			return server;
		}

		if (source instanceof Player player) {
			Optional<ServerConnection> current = player.getCurrentServer();

			if (current.isPresent()) {
				return current.get().getServer();
			}
		}

		return null;
	}

	private void send(Object replyTarget, VaultRpcResponse reply, VaultRpcRequest request) {
		if (replyTarget == null) {
			long count = unroutableReplies.incrementAndGet();

			if (count == 1L || count % 20L == 0L) {
				logger.warn("Không xác định được backend để trả lời RPC " + request.action() + " (backend=" + request.backendName() + ", tổng " + count + " lần).");
			}

			return;
		}

		boolean sent;

		try {
			sent = bus.send(replyTarget, VaultChannels.RPC, writer -> reply.writeTo(writer));
		} catch (RuntimeException exception) {
			logger.warn("Không gửi được phản hồi RPC tới " + describe(replyTarget) + ": " + exception.getMessage());
			return;
		}

		if (!sent) {
			logger.warn("Phản hồi RPC " + request.action() + " tới " + describe(replyTarget) + " không gửi được (không còn kết nối).");
		}
	}

	private void warnProtocol(Object replyTarget, int version) {
		String key = describe(replyTarget);
		long now = System.currentTimeMillis();
		Long last = protocolWarnings.get(key);

		if (last != null && now - last < PROTOCOL_WARN_INTERVAL_MILLIS) {
			return;
		}

		protocolWarnings.put(key, now);
		logger.warn("Backend " + key + " dùng giao thức LunaVault phiên bản " + version + ", proxy dùng " + VaultRpcProtocol.VERSION + "; cần cập nhật jar luna-vault-backend ở đó.");
	}

	private String describe(Object target) {
		if (target instanceof RegisteredServer server) {
			return server.getServerInfo().getName();
		}

		if (target == null) {
			return "unknown";
		}

		return target.getClass().getSimpleName();
	}
}
