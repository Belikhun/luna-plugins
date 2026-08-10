package dev.belikhun.luna.auth.backend.forge.bootstrap;

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
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.util.Locale;

/**
 * The forge half of LunaAuth Backend: which event means what.
 *
 * The restriction itself is {@link AuthRestrictionController}, shared with the
 * fabric and neoforge builds. Forge hands out a cancellable event per hook, so
 * every method here is one translation - ask the controller, cancel if it says
 * no - and nothing about the auth flow is decided in this file.
 *
 * Handlers sit at {@link EventPriority#LOWEST}: LunaCore publishes its services
 * at NORMAL and luna-core-messaging attaches the bus at LOW, and this needs both
 * before it can resolve anything. The `ordering="AFTER"` in mods.toml sequences
 * mod *loading*, not the event bus, which is a trap this cluster has already
 * fallen into once.
 */
@Mod(LunaAuthBackendForgeMod.MOD_ID)
public final class LunaAuthBackendForgeMod {
	public static final String MOD_ID = "lunaauthbackend";

	private LunaLogger logger;
	private AuthRestrictionController controller;

	public LunaAuthBackendForgeMod() {
		this.logger = LunaLoggers.create("LunaAuthBackend", true);
		this.controller = null;
		MinecraftForge.EVENT_BUS.register(this);
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onServerStarted(ServerStartedEvent event) {
		if (!LunaCore.isReady()) {
			logger.error("LunaCore chưa sẵn sàng. LunaAuth Backend Forge sẽ không khởi động.");
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
		logger.success("Luna Auth Backend Forge đã sẵn sàng.");
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

	@SubscribeEvent
	public void onServerTick(TickEvent.ServerTickEvent event) {
		// this line's tick event fires twice per tick; the cage runs on the end phase
		if (controller != null && event.phase == TickEvent.Phase.END) {
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

	/**
	 * Damage is refused at {@code LivingAttackEvent} rather than
	 * {@code LivingDamageEvent}, because this line runs the attack event before
	 * any of the knockback and armour handling; cancelling later would leave a
	 * locked player shoved off the auth spawn.
	 */
	@SubscribeEvent
	public void onLivingAttack(LivingAttackEvent event) {
		if (controller != null && !controller.allowDamage(event.getEntity(), event.getSource().getEntity())) {
			event.setCanceled(true);
		}
	}

	@SubscribeEvent
	public void onItemPickup(EntityItemPickupEvent event) {
		if (controller != null && controller.isLocked(event.getEntity())) {
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
		refuseInteraction(event);
	}

	@SubscribeEvent
	public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		refuseInteraction(event);
	}

	@SubscribeEvent
	public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
		refuseInteraction(event);
	}

	@SubscribeEvent
	public void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
		refuseInteraction(event);
	}

	private void refuseInteraction(PlayerInteractEvent event) {
		if (controller != null && !controller.allowInteraction(event.getEntity())) {
			event.setCanceled(true);
		}
	}

	@SuppressWarnings("unchecked")
	private PluginMessageBus<ServerPlayer, ServerPlayer> resolveMessagingBus() {
		PluginMessagingBus bus = LunaCore.services()
			.dependencyManager()
			.resolveOptional(PluginMessagingBus.class)
			.orElse(null);

		if (bus == null) {
			logger.warn("Thiếu PluginMessagingBus, LunaAuth Backend Forge sẽ không thể đồng bộ trạng thái xác thực.");
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
