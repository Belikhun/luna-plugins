package dev.belikhun.luna.core.messaging.neoforge;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
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
		pluginMessagingBus = new NeoForgePluginMessagingBus(logger);
		NeoForgePayloadFallbackTransport.activate(pluginMessagingBus);
		AmqpMessagingConfig amqpMessagingConfig = dependencyManager.resolveOptional(AmqpMessagingConfig.class)
			.orElse(AmqpMessagingConfig.disabled());
		pluginMessagingBus.updateAmqpConfig(amqpMessagingConfig);
		dependencyManager.registerSingleton(NeoForgePluginMessagingBus.class, pluginMessagingBus);
		dependencyManager.registerSingleton(PluginMessageBus.class, pluginMessagingBus);
		logger.success("Luna Core Messaging NeoForge bus đã sẵn sàng.");
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
