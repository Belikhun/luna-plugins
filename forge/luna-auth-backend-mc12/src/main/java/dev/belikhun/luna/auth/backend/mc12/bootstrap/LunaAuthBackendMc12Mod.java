package dev.belikhun.luna.auth.backend.mc12.bootstrap;

import dev.belikhun.luna.auth.backend.mc12.runtime.LegacyAuthRestrictionController;
import dev.belikhun.luna.auth.backend.mc12.service.LegacyAuthSpawnService;
import dev.belikhun.luna.core.mc12.LunaCore;
import dev.belikhun.luna.core.mc12.logging.LegacyLunaLogger;
import dev.belikhun.luna.legacy.auth.AuthBackendConfig;
import dev.belikhun.luna.legacy.auth.AuthBackendConfigLoader;
import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.messaging.PluginMessageBus;
import dev.belikhun.luna.legacy.messaging.bus.PlayerBridge;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.nio.file.Path;

/**
 * The 1.12.2 half of LunaAuth Backend: which event means what.
 *
 * The restriction itself is {@link LegacyAuthRestrictionController}; every method
 * here is one translation - ask the controller, cancel if it says no - and
 * nothing about the auth flow is decided in this file.
 *
 * **Legacy Forge is generous with events, which is why there are no mixins.** The
 * fabric build needs three of them for drops, item use and command dispatch;
 * 1.12.2 fires a cancellable event for each, so the cage is entirely event-driven
 * here. The one thing legacy FML does not give is a load-order guarantee against
 * the other luna mods, so the controller is built in
 * {@link #onServerStarting} - by which point LunaCore has published its services -
 * and every handler tolerates it still being null.
 */
@Mod(
	modid = LunaAuthBackendMc12Mod.MOD_ID,
	name = "LunaAuthBackend",
	version = "0.1.0-SNAPSHOT",
	acceptableRemoteVersions = "*",
	serverSideOnly = true,
	dependencies = "required-after:lunacore;after:lunacoremessaging"
)
public final class LunaAuthBackendMc12Mod {
	public static final String MOD_ID = "lunaauthbackend";

	private LunaLogger logger;
	private Path configDir;
	private LegacyAuthRestrictionController controller;

	@Mod.EventHandler
	public void onPreInit(FMLPreInitializationEvent event) {
		logger = LegacyLunaLogger.create(event.getModLog(), "LunaAuthBackend");

		File configRoot = event.getModConfigurationDirectory();

		configDir = configRoot.toPath();
		MinecraftForge.EVENT_BUS.register(this);
	}

	@Mod.EventHandler
	public void onServerStarting(FMLServerStartingEvent event) {
		if (!LunaCore.isReady()) {
			logger.error("LunaCore chưa sẵn sàng. LunaAuth Backend (Forge 1.12.2) sẽ không khởi động.");

			return;
		}

		AuthBackendConfigLoader.useConfigDirectory(configDir);

		AuthBackendConfig config = AuthBackendConfigLoader.load(getClass(), logger);

		if (config.authFlowLogsEnabled()) {
			logger = ((LegacyLunaLogger) logger).withDebug(true);
		}

		LegacyAuthSpawnService spawnService = new LegacyAuthSpawnService(AuthBackendConfigLoader.configPath(), logger);

		controller = new LegacyAuthRestrictionController(
			event.getServer(),
			logger,
			config,
			spawnService,
			playerBridge(),
			messagingBus()
		);

		controller.start();

		event.registerServerCommand(new AuthCommand(controller, "login", 1, 1));
		event.registerServerCommand(new AuthCommand(controller, "register", 2, 2));

		logger.success("Luna Auth Backend (Forge 1.12.2) đã sẵn sàng.");
	}

	@Mod.EventHandler
	public void onServerStopping(FMLServerStoppingEvent event) {
		if (controller != null) {
			controller.close();
			controller = null;
		}

		logger.audit("Luna Auth Backend (Forge 1.12.2) đã dừng.");
	}

	// ------------------------------------------------------------------- events

	@SubscribeEvent
	public void onPlayerLoggedIn(PlayerLoggedInEvent event) {
		if (controller != null && event.player instanceof EntityPlayerMP) {
			controller.onPlayerLoggedIn((EntityPlayerMP) event.player);
		}
	}

	@SubscribeEvent
	public void onPlayerLoggedOut(PlayerLoggedOutEvent event) {
		if (controller != null && event.player instanceof EntityPlayerMP) {
			controller.onPlayerLoggedOut((EntityPlayerMP) event.player);
		}
	}

	@SubscribeEvent
	public void onPlayerTick(TickEvent.PlayerTickEvent event) {
		if (controller == null || event.phase != TickEvent.Phase.END) {
			return;
		}

		if (event.player instanceof EntityPlayerMP) {
			controller.onPlayerTick((EntityPlayerMP) event.player);
		}
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onChat(ServerChatEvent event) {
		if (controller != null && !controller.allowChat(event.getPlayer())) {
			event.setCanceled(true);
		}
	}

	/**
	 * Commands, including the ones that authenticate.
	 *
	 * `CommandEvent` fires for every command from every sender, so the sender is
	 * narrowed to a player first: cancelling a console command because the console
	 * has no auth state would take the server down with it.
	 */
	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onCommand(CommandEvent event) {
		if (controller == null || !(event.getSender() instanceof EntityPlayerMP)) {
			return;
		}

		EntityPlayerMP player = (EntityPlayerMP) event.getSender();

		if (!controller.allowCommand(player, event.getCommand().getName())) {
			event.setCanceled(true);
		}
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onBlockBreak(BlockEvent.BreakEvent event) {
		if (controller != null && !controller.allowInteraction(event.getPlayer())) {
			event.setCanceled(true);
		}
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onBlockPlace(BlockEvent.PlaceEvent event) {
		if (controller != null && !controller.allowInteraction(event.getPlayer())) {
			event.setCanceled(true);
		}
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onInteract(PlayerInteractEvent event) {
		if (controller != null && !controller.allowInteraction(event.getEntityPlayer())) {
			event.setCanceled(true);
		}
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onAttack(LivingAttackEvent event) {
		if (controller == null) {
			return;
		}

		Entity attacker = event.getSource() == null ? null : event.getSource().getTrueSource();

		if (!controller.allowDamage(event.getEntity(), attacker)) {
			event.setCanceled(true);
		}
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onPickup(EntityItemPickupEvent event) {
		if (controller != null && !controller.allowInteraction(event.getEntityPlayer())) {
			event.setCanceled(true);
		}
	}

	/**
	 * A locked player may not drop anything.
	 *
	 * Cancelling `ItemTossEvent` leaves the stack with the *entity*, not the
	 * player, so the item has to be handed back explicitly - otherwise it vanishes
	 * from the inventory it never left.
	 */
	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onItemToss(ItemTossEvent event) {
		if (controller == null || !(event.getPlayer() instanceof EntityPlayerMP)) {
			return;
		}

		EntityPlayerMP player = (EntityPlayerMP) event.getPlayer();

		if (!controller.refuseDrop(player)) {
			return;
		}

		ItemStack tossed = event.getEntityItem() == null ? ItemStack.EMPTY : event.getEntityItem().getItem();

		event.setCanceled(true);
		controller.restoreTossedItem(player, tossed);
	}

	@SuppressWarnings("unchecked")
	private PlayerBridge<EntityPlayerMP> playerBridge() {
		return (PlayerBridge<EntityPlayerMP>) LunaCore.find(PlayerBridge.class);
	}

	@SuppressWarnings("unchecked")
	private PluginMessageBus<EntityPlayerMP, EntityPlayerMP> messagingBus() {
		return (PluginMessageBus<EntityPlayerMP, EntityPlayerMP>) LunaCore.find(PluginMessageBus.class);
	}
}
