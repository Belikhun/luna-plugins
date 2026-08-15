package dev.belikhun.luna.tabbridge.mc12.bootstrap;

import dev.belikhun.luna.core.messaging.mc12.LegacyPayloadFallback;
import dev.belikhun.luna.core.mc12.LunaCore;
import dev.belikhun.luna.core.mc12.logging.LegacyLunaLogger;
import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.messaging.bus.PlayerBridge;
import dev.belikhun.luna.legacy.messaging.bus.PluginMessagingBus;
import dev.belikhun.luna.legacy.permission.PermissionService;
import dev.belikhun.luna.legacy.placeholder.PlaceholderService;
import dev.belikhun.luna.legacy.tabbridge.TabBridgeChannels;
import dev.belikhun.luna.legacy.tabbridge.TabBridgePlaceholderUpdater;
import dev.belikhun.luna.legacy.tabbridge.TabBridgeRelationalPlaceholderSource;
import dev.belikhun.luna.legacy.tabbridge.TabBridgeRuntime;
import dev.belikhun.luna.legacy.tabbridge.TabBridgeRuntimeFactory;
import dev.belikhun.luna.tabbridge.mc12.LegacyTabPlayerBridge;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

/**
 * Feeds TAB the per-player state its tab list is rendered from, on 1.12.2.
 *
 * The protocol, the queueing and the placeholder bookkeeping are all shared -
 * they live in `luna-legacy-api` written generic over the player type, so this
 * mod is the FML lifecycle, a three-method player seam and the transport.
 *
 * **The transport is this line's own problem.** Every other luna channel goes
 * through the broker here, because 1.12.2 caps a channel name at 20 characters
 * and `luna:`-namespaced names do not fit. TAB's channel does fit, and has to
 * ride the connection regardless: TAB's proxy half listens for plugin messages
 * and would never see a queue. So this mod installs a
 * {@link LegacyPayloadFallback} on the bus for that one channel.
 */
@Mod(
	modid = LunaTabBridgeMc12Mod.MOD_ID,
	name = "LunaTabBridge",
	version = "0.1.0-SNAPSHOT",
	dependencies = "required-after:lunacore;required-after:lunacoremessaging",
	acceptableRemoteVersions = "*",
	serverSideOnly = true
)
public final class LunaTabBridgeMc12Mod {
	public static final String MOD_ID = "lunatabbridge";

	private LunaLogger logger;
	private LegacyTabPlayerBridge players;
	private TabBridgeRuntime<EntityPlayerMP> runtime;
	private TabBridgePlaceholderUpdater<EntityPlayerMP> placeholderUpdater;
	private LegacyPayloadFallback fallback;

	@Mod.EventHandler
	public void onPreInit(FMLPreInitializationEvent event) {
		logger = LegacyLunaLogger.create(event.getModLog(), "LunaTabBridge");
	}

	@Mod.EventHandler
	public void onServerStarting(FMLServerStartingEvent event) {
		PlayerBridge<EntityPlayerMP> corePlayers = resolvePlayerBridge();

		if (corePlayers == null) {
			logger.error("Thiếu PlayerBridge từ LunaCore; TAB bridge sẽ không khởi động.");
			return;
		}

		PluginMessagingBus<EntityPlayerMP> bus = resolveBus();

		if (bus == null) {
			logger.error("Thiếu PluginMessagingBus; TAB bridge sẽ không khởi động.");
			return;
		}

		PlaceholderService<EntityPlayerMP> placeholders = resolvePlaceholderService();

		if (placeholders == null) {
			logger.error("Thiếu PlaceholderService từ LunaCore; TAB bridge sẽ không khởi động.");
			return;
		}

		players = new LegacyTabPlayerBridge(corePlayers);

		// before the runtime registers the channel: the runtime's first act is to
		// register its listener, and a listener with no transport under it would take
		// the AMQP path and publish TAB's frames into a queue nobody reads
		installFallback(bus, corePlayers);

		PermissionService permissions = LunaCore.find(PermissionService.class);

		runtime = TabBridgeRuntimeFactory.create(logger, bus, players, permissions, null);

		TabBridgeRelationalPlaceholderSource<EntityPlayerMP> relational =
			TabBridgeRuntimeFactory.defaultRelationalSource(players, permissions);

		placeholderUpdater = new TabBridgePlaceholderUpdater<EntityPlayerMP>(
			players,
			runtime,
			relational,
			placeholders
		);

		runtime.bindPlaceholderResolver((player, identifier) ->
			placeholderUpdater.resolvePlaceholder(player, identifier));

		LunaCore.services().register(TabBridgeRuntime.class, runtime);

		MinecraftForge.EVENT_BUS.register(this);
		placeholderUpdater.refreshOnlinePlayers();

		logger.success("Luna TAB Bridge (Forge 1.12.2) đã sẵn sàng.");
	}

	/**
	 * Give the bus a way to carry TAB's channel over the player's connection.
	 *
	 * FML wants a mod container active when a channel is created, which is true
	 * inside this handler and not on an arbitrary thread later, so this happens
	 * here rather than lazily on the first send.
	 */
	private void installFallback(PluginMessagingBus<EntityPlayerMP> bus, PlayerBridge<EntityPlayerMP> corePlayers) {
		try {
			fallback = new LegacyPayloadFallback(logger, corePlayers, TabBridgeChannels.BRIDGE);

			bus.useFallback(fallback);
			logger.info("Đăng ký custom payload channel " + TabBridgeChannels.BRIDGE.value() + " cho TAB.");
		} catch (RuntimeException failure) {
			// FML throws when the channel name is already taken, which means another
			// mod on this server speaks TAB's protocol too; running both would give
			// TAB two different answers per player
			fallback = null;
			logger.error("Không đăng ký được channel " + TabBridgeChannels.BRIDGE.value()
				+ "; TAB sẽ không nhận được dữ liệu từ backend này.", failure);
		}
	}

	@SubscribeEvent
	public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
		if (placeholderUpdater != null && event.player instanceof EntityPlayerMP) {
			placeholderUpdater.refreshPlayer((EntityPlayerMP) event.player);
		}
	}

	@SubscribeEvent
	public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
		if (runtime != null && event.player instanceof EntityPlayerMP) {
			runtime.removePlayer(players.idOf((EntityPlayerMP) event.player));
		}
	}

	@Mod.EventHandler
	public void onServerStopping(FMLServerStoppingEvent event) {
		if (placeholderUpdater != null) {
			placeholderUpdater.close();
			placeholderUpdater = null;
		}

		if (LunaCore.isReady()) {
			LunaCore.services().unregister(TabBridgeRuntime.class);
		}

		if (runtime != null) {
			runtime.close();
			runtime = null;
		}

		if (fallback != null) {
			fallback.detach();
			fallback = null;
		}

		players = null;
		logger.audit("Luna TAB Bridge (Forge 1.12.2) đã dừng.");
	}

	@SuppressWarnings("unchecked")
	private PlayerBridge<EntityPlayerMP> resolvePlayerBridge() {
		return (PlayerBridge<EntityPlayerMP>) LunaCore.find(PlayerBridge.class);
	}

	@SuppressWarnings("unchecked")
	private PluginMessagingBus<EntityPlayerMP> resolveBus() {
		return (PluginMessagingBus<EntityPlayerMP>) LunaCore.find(PluginMessagingBus.class);
	}

	@SuppressWarnings("unchecked")
	private PlaceholderService<EntityPlayerMP> resolvePlaceholderService() {
		return (PlaceholderService<EntityPlayerMP>) LunaCore.find(PlaceholderService.class);
	}
}
