package dev.belikhun.luna.core.messaging.mc12;

import dev.belikhun.luna.core.mc12.LunaCore;
import dev.belikhun.luna.core.mc12.logging.LegacyLunaLogger;
import dev.belikhun.luna.legacy.config.BackendCoreRuntimeConfig;
import dev.belikhun.luna.legacy.heartbeat.BackendHeartbeatPublisher;
import dev.belikhun.luna.legacy.heartbeat.BackendIdentity;
import dev.belikhun.luna.legacy.heartbeat.BackendMetadata;
import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.legacy.messaging.AmqpMessagingConfigCodec;
import dev.belikhun.luna.legacy.messaging.PluginMessageBus;
import dev.belikhun.luna.legacy.messaging.bus.NoopAmqpTransport;
import dev.belikhun.luna.legacy.messaging.bus.PlayerBridge;
import dev.belikhun.luna.legacy.messaging.bus.PluginMessagingBus;
import dev.belikhun.luna.legacy.messaging.bus.RabbitMqAmqpTransport;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * AMQP messaging for the 1.12.2 backend.
 *
 * Almost nothing happens here. The bus, both transports and all the channel
 * bookkeeping live in `luna-legacy-api` written generic over the player type, so
 * this mod supplies three things: the FML lifecycle, a {@link LegacyPlayerBridge}
 * and the wiring that lets the proxy hand down the AMQP settings.
 *
 * **No payload fallback, by protocol.** 1.12.2 caps a plugin channel name at 20
 * characters and every luna channel is `luna:`-namespaced past that, so a custom
 * payload cannot carry ours at all. Every message on this backend goes through
 * the broker, which is why the AMQP settings arriving is the difference between
 * this mod working and doing nothing.
 */
@Mod(
	modid = LunaCoreMessagingMc12Mod.MOD_ID,
	name = "LunaCoreMessaging",
	version = "0.1.0-SNAPSHOT",
	dependencies = "required-after:lunacore",
	acceptableRemoteVersions = "*",
	serverSideOnly = true
)
public final class LunaCoreMessagingMc12Mod {
	public static final String MOD_ID = "lunacoremessaging";

	private LunaLogger logger;
	private PluginMessagingBus<EntityPlayerMP> bus;

	@Mod.EventHandler
	public void onPreInit(FMLPreInitializationEvent event) {
		logger = LegacyLunaLogger.create(event.getModLog(), "LunaCoreMessaging");
	}

	@Mod.EventHandler
	public void onServerStarting(FMLServerStartingEvent event) {
		PlayerBridge<EntityPlayerMP> players = resolvePlayerBridge();

		if (players == null) {
			logger.error("Thiếu PlayerBridge từ LunaCore; messaging sẽ không khởi động.");
			return;
		}

		BackendIdentity identity = resolveBackendIdentity();
		boolean loggingEnabled = pluginMessagingLoggingEnabled();

		bus = buildBus(players, identity, loggingEnabled);

		attachMessagingConfig();

		LunaCore.services().register(PluginMessagingBus.class, bus);
		LunaCore.services().register(PluginMessageBus.class, bus);

		MinecraftForge.EVENT_BUS.register(this);
		logger.success("Luna Core Messaging (Forge 1.12.2) đã sẵn sàng.");
	}

	/**
	 * Constructing the RabbitMQ client can fail - a missing class, a broken shade -
	 * and a backend that cannot reach the broker still has to boot. The no-op
	 * transport answers false to everything, so messaging goes quiet rather than
	 * taking the server with it.
	 */
	private PluginMessagingBus<EntityPlayerMP> buildBus(
		final PlayerBridge<EntityPlayerMP> players,
		final BackendIdentity identity,
		final boolean loggingEnabled
	) {
		try {
			return new PluginMessagingBus<EntityPlayerMP>(logger, players, owner ->
				new RabbitMqAmqpTransport<EntityPlayerMP>(
					owner,
					players,
					identity,
					logger.scope("Amqp/rabbitmq"),
					loggingEnabled,
					"forge-1.12.2"
				));
		} catch (RuntimeException exception) {
			logger.error("Không thể khởi tạo AMQP transport RabbitMQ. Dùng no-op transport.", exception);

			return new PluginMessagingBus<EntityPlayerMP>(
				logger,
				players,
				owner -> new NoopAmqpTransport<EntityPlayerMP>()
			);
		}
	}

	/**
	 * Take the AMQP settings from the proxy over the heartbeat, the way every other
	 * backend does. This mod loads after the core, so the first fetch may already
	 * have happened; the registry client clears its checksum when a consumer
	 * arrives late, and the sync below is what delivers it.
	 */
	private void attachMessagingConfig() {
		BackendHeartbeatPublisher publisher = LunaCore.find(BackendHeartbeatPublisher.class);

		if (publisher == null) {
			logger.warn("Thiếu BackendHeartbeatPublisher, AMQP transport sẽ không nhận được cấu hình từ proxy.");
			return;
		}

		final PluginMessagingBus<EntityPlayerMP> target = bus;

		publisher.setMessagingConfigConsumer(payload -> applyMessagingConfig(target, payload));
		publisher.syncMessagingConfigNow();
	}

	/**
	 * Apply the settings the proxy sent, and never let a failure vanish.
	 *
	 * This runs on the heartbeat's executor, inside a fetch that catches
	 * `Exception`. Anything that is not one - a `NoClassDefFoundError` from a
	 * mis-shaded AMQP client, most plausibly - would otherwise propagate out of the
	 * task and be swallowed whole: no connection, no message, nothing in the log to
	 * say why. Catching `Throwable` here is the difference between a diagnosable
	 * backend and a silent one.
	 */
	private void applyMessagingConfig(PluginMessagingBus<EntityPlayerMP> target, byte[] payload) {
		try {
			AmqpMessagingConfig config = AmqpMessagingConfigCodec.decode(payload);

			logger.debug("Nhận cấu hình AMQP từ proxy: enabled=" + config.enabled()
				+ " configured=" + config.isConfigured());

			target.updateAmqpConfig(config);
		} catch (Throwable failure) {
			logger.error("Không áp dụng được cấu hình AMQP từ proxy: " + failure, failure);
		}
	}

	/** The same audit switch Paper reads: logging.pluginMessaging.enabled. */
	private boolean pluginMessagingLoggingEnabled() {
		BackendCoreRuntimeConfig config = LunaCore.find(BackendCoreRuntimeConfig.class);

		return config != null && config.pluginMessagingLoggingEnabled();
	}

	/** The core's own bridge; there is exactly one per server and the core owns it. */
	@SuppressWarnings("unchecked")
	private PlayerBridge<EntityPlayerMP> resolvePlayerBridge() {
		return (PlayerBridge<EntityPlayerMP>) LunaCore.find(PlayerBridge.class);
	}

	private BackendIdentity resolveBackendIdentity() {
		BackendIdentity identity = LunaCore.find(BackendIdentity.class);

		if (identity != null) {
			return identity;
		}

		logger.warn("Thiếu BackendIdentity, dùng tên backend mặc định cho AMQP queue.");

		return () -> new BackendMetadata("backend", "", "").sanitize();
	}

	@SubscribeEvent
	public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
		if (bus != null && event.player instanceof EntityPlayerMP) {
			bus.bindSender((EntityPlayerMP) event.player);
		}
	}

	@SubscribeEvent
	public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
		if (bus != null && event.player instanceof EntityPlayerMP) {
			bus.unbindSender((EntityPlayerMP) event.player);
		}
	}

	@Mod.EventHandler
	public void onServerStopping(FMLServerStoppingEvent event) {
		if (LunaCore.isReady()) {
			LunaCore.services().unregister(PluginMessagingBus.class);
			LunaCore.services().unregister(PluginMessageBus.class);
		}

		if (bus != null) {
			bus.close();
			bus = null;
		}

		logger.audit("Luna Core Messaging (Forge 1.12.2) đã dừng.");
	}
}
