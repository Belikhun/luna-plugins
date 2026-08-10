package dev.belikhun.luna.auth.backend.neoforge.bootstrap;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.belikhun.luna.auth.backend.mc.config.AuthBackendConfig;
import dev.belikhun.luna.auth.backend.mc.config.AuthBackendConfigLoader;
import dev.belikhun.luna.auth.backend.mc.runtime.AuthLockHooks;
import dev.belikhun.luna.auth.backend.mc.runtime.AuthRestrictionController;
import dev.belikhun.luna.auth.backend.mc.service.BackendAuthSpawnService;
import dev.belikhun.luna.core.api.auth.AuthMessages;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.messaging.mc.PluginMessagingBus;
import dev.belikhun.luna.core.mc.LunaCore;
import dev.belikhun.luna.core.mc.logging.LunaLoggers;
import dev.belikhun.luna.core.mc.text.ServerTextComponents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;

import java.util.Locale;

/**
 * The neoforge half of LunaAuth Backend: which event means what.
 *
 * The restriction itself is {@link AuthRestrictionController}, shared with the
 * fabric and forge builds, so every method here is one translation - ask the
 * controller, cancel if it says no.
 *
 * Handlers sit at {@link EventPriority#LOWEST}: LunaCore publishes its services
 * at NORMAL and luna-core-messaging attaches the bus at LOW, and this needs both
 * before it can resolve anything. The `ordering="AFTER"` in mods.toml sequences
 * mod *loading*, not the event bus, which is a trap this cluster has already
 * fallen into once.
 */
@Mod(LunaAuthBackendNeoForgeMod.MOD_ID)
public final class LunaAuthBackendNeoForgeMod {
	public static final String MOD_ID = "lunaauthbackend";

	private LunaLogger logger;
	private AuthRestrictionController controller;

	public LunaAuthBackendNeoForgeMod() {
		this.logger = LunaLoggers.create("LunaAuthBackend", true);
		this.controller = null;
		NeoForge.EVENT_BUS.register(this);
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onServerStarted(ServerStartedEvent event) {
		if (!LunaCore.isReady()) {
			logger.error("LunaCore chưa sẵn sàng. LunaAuth Backend NeoForge sẽ không khởi động.");
			return;
		}

		AuthBackendConfigLoader.useConfigDirectory(FMLPaths.CONFIGDIR.get());

		AuthBackendConfig config = AuthBackendConfigLoader.load(getClass(), logger);
		this.logger = LunaLoggers.create("LunaAuthBackend", true, config.authFlowLogsEnabled());

		PluginMessageBus<ServerPlayer, ServerPlayer> messagingBus = resolveMessagingBus();
		BackendAuthSpawnService spawnService = new BackendAuthSpawnService(
			AuthBackendConfigLoader.configPath(),
			logger.scope("Spawn")
		);

		controller = new AuthRestrictionController(
			event.getServer(),
			logger.scope("Restriction"),
			config,
			spawnService,
			messagingBus
		);
		controller.start();
		AuthLockHooks.install(controller);
		logger.success("Luna Auth Backend NeoForge đã sẵn sàng.");
	}

	@SubscribeEvent
	public void onServerStopping(ServerStoppingEvent event) {
		if (controller == null) {
			return;
		}

		AuthLockHooks.clear(controller);
		controller.close();
		controller = null;
	}

	@SubscribeEvent
	public void onRegisterCommands(RegisterCommandsEvent event) {
		registerLoginCommand(event, "login");
		registerLoginCommand(event, "l");
		registerRegisterCommand(event, "register");
		registerRegisterCommand(event, "reg");
	}

	@SubscribeEvent
	public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
		if (controller != null && event.getEntity() instanceof ServerPlayer player) {
			controller.onPlayerLoggedIn(player);
		}
	}

	@SubscribeEvent
	public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
		if (controller != null && event.getEntity() instanceof ServerPlayer player) {
			controller.onPlayerLoggedOut(player);
		}
	}

	/**
	 * The cage's periodic work runs off the player tick rather than a server
	 * tick, because that is the event this loader gives; the controller throttles
	 * per player, so the extra calls cost nothing.
	 */
	@SubscribeEvent
	public void onPlayerTick(PlayerTickEvent.Post event) {
		if (controller != null && event.getEntity() instanceof ServerPlayer) {
			controller.onServerTick();
		}
	}

	@SubscribeEvent
	public void onServerChat(ServerChatEvent event) {
		if (controller != null && !controller.allowChat(event.getPlayer())) {
			event.setCanceled(true);
		}
	}

	@SubscribeEvent
	public void onCommand(CommandEvent event) {
		if (controller == null) {
			return;
		}

		if (!(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player)) {
			return;
		}

		if (!controller.allowCommand(player, event.getParseResults().getReader().getString())) {
			event.setCanceled(true);
		}
	}

	@SubscribeEvent
	public void onBlockBreak(BlockEvent.BreakEvent event) {
		if (controller != null && !controller.allowInteraction(event.getPlayer())) {
			event.setCanceled(true);
		}
	}

	@SubscribeEvent
	public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
		if (controller != null && event.getEntity() instanceof Player player && !controller.allowInteraction(player)) {
			event.setCanceled(true);
		}
	}

	@SubscribeEvent
	public void onIncomingDamage(LivingIncomingDamageEvent event) {
		if (controller != null && !controller.allowDamage(event.getEntity(), event.getSource().getEntity())) {
			event.setCanceled(true);
		}
	}

	@SubscribeEvent
	public void onItemPickup(ItemEntityPickupEvent.Pre event) {
		if (controller != null && controller.isLocked(event.getPlayer())) {
			event.setCanPickup(TriState.FALSE);
		}
	}

	@SubscribeEvent
	public void onUseItemOnBlock(UseItemOnBlockEvent event) {
		if (controller != null && !controller.allowInteraction(event.getPlayer())) {
			event.setCanceled(true);
		}
	}

	@SubscribeEvent
	public void onItemToss(ItemTossEvent event) {
		if (controller == null || !(event.getPlayer() instanceof ServerPlayer player)) {
			return;
		}

		ItemStack tossed = event.getEntity().getItem().copy();

		if (!controller.refuseDrop(player, tossed)) {
			return;
		}

		event.setCanceled(true);
		controller.restoreTossedItem(player, tossed);
	}

	@SubscribeEvent
	public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
		refuseInteraction(event, event);
	}

	@SubscribeEvent
	public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		refuseInteraction(event, event);
	}

	@SubscribeEvent
	public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
		refuseInteraction(event, event);
	}

	@SubscribeEvent
	public void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
		refuseInteraction(event, event);
	}

	/**
	 * The interaction events are cancellable one by one on this loader; the base
	 * class is not, so the event arrives already narrowed to something that can
	 * be refused.
	 */
	private void refuseInteraction(PlayerInteractEvent event, ICancellableEvent cancellable) {
		if (controller != null && !controller.allowInteraction(event.getEntity())) {
			cancellable.setCanceled(true);
		}
	}

	@SuppressWarnings("unchecked")
	private PluginMessageBus<ServerPlayer, ServerPlayer> resolveMessagingBus() {
		PluginMessagingBus bus = LunaCore.services()
			.dependencyManager()
			.resolveOptional(PluginMessagingBus.class)
			.orElse(null);

		if (bus == null) {
			logger.warn("Thiếu PluginMessagingBus, LunaAuth Backend NeoForge sẽ không thể đồng bộ trạng thái xác thực.");
		}

		return (PluginMessageBus<ServerPlayer, ServerPlayer>) bus;
	}

	private void registerLoginCommand(RegisterCommandsEvent event, String root) {
		event.getDispatcher().register(Commands.literal(root)
			.requires(source -> source.getEntity() instanceof ServerPlayer)
			.executes(context -> usage(context.getSource(), AuthMessages.loginUsage()))
			.then(Commands.argument("password", StringArgumentType.word())
				.executes(context -> controller == null
					? notReady(context.getSource())
					: controller.executeLogin(
						context.getSource(),
						StringArgumentType.getString(context, "password")
					))));
	}

	private void registerRegisterCommand(RegisterCommandsEvent event, String root) {
		event.getDispatcher().register(Commands.literal(root)
			.requires(source -> source.getEntity() instanceof ServerPlayer)
			.executes(context -> usage(context.getSource(), AuthMessages.registerUsage()))
			.then(Commands.argument("password", StringArgumentType.word())
				.then(Commands.argument("confirm", StringArgumentType.word())
					.executes(context -> controller == null
						? notReady(context.getSource())
						: controller.executeRegister(
							context.getSource(),
							StringArgumentType.getString(context, "password"),
							StringArgumentType.getString(context, "confirm")
						)))));
	}

	private int usage(CommandSourceStack source, String miniMessage) {
		source.sendSystemMessage(mini(source, miniMessage));

		return 0;
	}

	private int notReady(CommandSourceStack source) {
		source.sendSystemMessage(mini(source, AuthMessages.commandSendFailed()));

		return 0;
	}

	private Component mini(CommandSourceStack source, String miniMessage) {
		return ServerTextComponents.mini(source.getServer(), miniMessage);
	}
}
