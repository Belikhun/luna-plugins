package dev.belikhun.luna.core.messaging.neoforge;

import dev.belikhun.luna.core.api.config.BackendCoreRuntimeConfig;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatPublisher;
import dev.belikhun.luna.core.api.heartbeat.BackendIdentity;
import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfigCodec;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.neoforge.LunaCoreNeoForge;
import dev.belikhun.luna.core.neoforge.logging.NeoForgeLunaLoggers;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(LunaCoreMessagingMod.MOD_ID)
public final class LunaCoreMessagingMod {
	public static final String MOD_ID = "lunacoremessaging";

	private final LunaLogger logger;
	private DependencyManager dependencyManager;
	private NeoForgePluginMessagingBus pluginMessagingBus;

	public LunaCoreMessagingMod(IEventBus modEventBus) {
		this.logger = NeoForgeLunaLoggers.create("LunaCoreMessagingNeoForge", true).scope("CoreMessagingNeoForge");
		NeoForge.EVENT_BUS.register(this);
		modEventBus.addListener(this::onRegisterPayloadHandlers);
	}

	private void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
		NeoForgePayloadFallbackTransport.registerPayloadHandlers(event);
	}

	@SubscribeEvent
	public void onServerStarted(ServerStartedEvent event) {
		dependencyManager = LunaCoreNeoForge.services().dependencyManager();
		pluginMessagingBus = new NeoForgePluginMessagingBus(logger, resolveBackendIdentity(), pluginMessagingLoggingEnabled());
		NeoForgePayloadFallbackTransport.activate(pluginMessagingBus);

		attachMessagingConfig();

		dependencyManager.registerSingleton(NeoForgePluginMessagingBus.class, pluginMessagingBus);
		dependencyManager.registerSingleton(PluginMessageBus.class, pluginMessagingBus);
		logger.success("Luna Core Messaging NeoForge bus đã sẵn sàng.");
	}

	/**
	 * Take the AMQP settings from the proxy over the heartbeat, the way Paper
	 * does. This mod loads after the core, so the first fetch may already have
	 * happened; the registry client clears its checksum when a consumer arrives
	 * late, and the sync below is what delivers it.
	 */
	private void attachMessagingConfig() {
		BackendHeartbeatPublisher heartbeatPublisher = dependencyManager.resolveOptional(BackendHeartbeatPublisher.class)
			.orElse(null);

		if (heartbeatPublisher == null) {
			logger.warn("Thiếu BackendHeartbeatPublisher, AMQP transport sẽ không nhận được cấu hình từ proxy.");
			return;
		}

		NeoForgePluginMessagingBus bus = pluginMessagingBus;
		heartbeatPublisher.setMessagingConfigConsumer(payload -> bus.updateAmqpConfig(AmqpMessagingConfigCodec.decode(payload)));
		heartbeatPublisher.syncMessagingConfigNow();
	}

	/** The same audit switch Paper reads: logging.pluginMessaging.enabled. */
	private boolean pluginMessagingLoggingEnabled() {
		return dependencyManager.resolveOptional(BackendCoreRuntimeConfig.class)
			.map(BackendCoreRuntimeConfig::pluginMessagingLoggingEnabled)
			.orElse(false);
	}

	private BackendIdentity resolveBackendIdentity() {
		return dependencyManager.resolveOptional(BackendIdentity.class)
			.orElseGet(() -> {
				logger.warn("Thiếu BackendIdentity, dùng tên backend mặc định cho AMQP queue.");
				return () -> new BackendMetadata("backend", "", "").sanitize();
			});
	}

	@SubscribeEvent
	public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
		if (pluginMessagingBus == null || !(event.getEntity() instanceof ServerPlayer player)) {
			return;
		}

		pluginMessagingBus.bindSender(player);
	}

	@SubscribeEvent
	public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
		if (pluginMessagingBus == null || !(event.getEntity() instanceof ServerPlayer player)) {
			return;
		}

		pluginMessagingBus.unbindSender(player);
	}

	@SubscribeEvent
	public void onServerStopping(ServerStoppingEvent event) {
		if (dependencyManager != null) {
			dependencyManager.unregister(NeoForgePluginMessagingBus.class);
			dependencyManager.unregister(PluginMessageBus.class);
		}

		if (pluginMessagingBus != null) {
			pluginMessagingBus.close();
			pluginMessagingBus = null;
		}

		dependencyManager = null;
	}
}
