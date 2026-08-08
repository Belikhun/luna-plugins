package dev.belikhun.luna.core.messaging.fabric.bootstrap;

import dev.belikhun.luna.core.api.config.BackendCoreRuntimeConfig;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatPublisher;
import dev.belikhun.luna.core.api.heartbeat.BackendIdentity;
import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfigCodec;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.fabric.LunaCoreFabric;
import dev.belikhun.luna.core.fabric.logging.FabricLunaLoggers;
import dev.belikhun.luna.core.messaging.fabric.FabricPluginMessagingBus;
import dev.belikhun.luna.core.messaging.fabric.payload.PayloadFallbackTransport;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;

import java.util.Set;

/**
 * The plugin-message bus every other luna fabric module talks through.
 *
 * The payload types are declared here in {@code onInitializeServer}, not when the
 * server starts: the game builds its packet codecs before that, and a channel
 * registered late is a channel whose packets are discarded. The bus itself is
 * built at server start, because that is when the core's config exists, and the
 * two are joined by {@link PayloadFallbackTransport#activate}.
 */
public final class LunaCoreMessagingFabricMod implements DedicatedServerModInitializer {
	public static final String MOD_ID = "lunacoremessaging";

	private final LunaLogger logger;
	private DependencyManager dependencyManager;
	private FabricPluginMessagingBus pluginMessagingBus;

	public LunaCoreMessagingFabricMod() {
		this.logger = FabricLunaLoggers.create("LunaCoreMessagingFabric", true).scope("CoreMessagingFabric");
	}

	@Override
	public void onInitializeServer() {
		Set<PluginMessageChannel> channels = PayloadFallbackTransport.registerPayloadTypes();

		if (channels.isEmpty()) {
			logger.warn("Không có channel nào dùng custom payload. Plugin message sẽ chỉ đi qua AMQP.");
		} else {
			logger.audit("Đã đăng ký " + channels.size() + " custom payload channel: " + channels);
		}

		ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (pluginMessagingBus != null) {
				pluginMessagingBus.bindSender(handler.getPlayer());
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (pluginMessagingBus != null) {
				pluginMessagingBus.unbindSender(handler.getPlayer());
			}
		});
	}

	private void onServerStarted(MinecraftServer server) {
		dependencyManager = LunaCoreFabric.services().dependencyManager();
		pluginMessagingBus = new FabricPluginMessagingBus(logger, resolveBackendIdentity(), pluginMessagingLoggingEnabled());

		PayloadFallbackTransport.activate(pluginMessagingBus, (player, payload) ->
			pluginMessagingBus.dispatchIncomingMessage(player, payload.channel(), payload.data()));

		attachMessagingConfig();

		dependencyManager.registerSingleton(FabricPluginMessagingBus.class, pluginMessagingBus);
		dependencyManager.registerSingleton(PluginMessageBus.class, pluginMessagingBus);

		logger.success("Luna Core Messaging Fabric bus đã sẵn sàng.");
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

		FabricPluginMessagingBus bus = pluginMessagingBus;
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

	private void onServerStopping(MinecraftServer server) {
		if (dependencyManager != null) {
			dependencyManager.unregister(FabricPluginMessagingBus.class);
			dependencyManager.unregister(PluginMessageBus.class);
			dependencyManager = null;
		}

		if (pluginMessagingBus != null) {
			pluginMessagingBus.close();
			pluginMessagingBus = null;
		}
	}
}
