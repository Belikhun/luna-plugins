package dev.belikhun.luna.vault.backend.mc12;

import dev.belikhun.luna.core.mc12.LunaCore;
import dev.belikhun.luna.core.mc12.logging.LegacyLunaLogger;
import dev.belikhun.luna.core.mc12.text.LunaTextComponents;
import dev.belikhun.luna.legacy.config.YamlConfigFile;
import dev.belikhun.luna.legacy.database.Database;
import dev.belikhun.luna.legacy.database.NoopDatabase;
import dev.belikhun.luna.legacy.database.migration.DatabaseMigrator;
import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.messaging.PluginMessageBus;
import dev.belikhun.luna.legacy.messaging.bus.PlayerBridge;
import dev.belikhun.luna.legacy.permission.PermissionService;
import dev.belikhun.luna.legacy.placeholder.PlaceholderService;
import dev.belikhun.luna.legacy.vault.LunaVaultApi;
import dev.belikhun.luna.legacy.vault.model.VaultDatabaseMigrations;
import dev.belikhun.luna.legacy.vault.runtime.VaultGateway;
import dev.belikhun.luna.legacy.vault.runtime.VaultPlaceholders;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The backend half of the network economy on 1.12.2.
 *
 * Everything it does - the gateway, the snapshot cache, the placeholders - is the
 * shared trunk in `luna-legacy-api`, the same classes every other backend runs.
 * This is the entry point and the event wiring.
 *
 * It provides what other backend mods depend on - `LunaVaultApi`, so balances,
 * deposits, withdrawals and transfers - plus the per-player snapshot cache, the
 * plugin-message channels the proxy pushes over, and `/transactions`.
 *
 * **RPC mode is the norm.** The proxy owns the tables; a backend only migrates
 * anything when it has been given a real database of its own, which on this line
 * it usually has not.
 */
@Mod(
	modid = LunaVaultBackendMc12Mod.MOD_ID,
	name = "LunaVaultBackend",
	version = "0.1.0-SNAPSHOT",
	dependencies = "required-after:lunacore;required-after:lunacoremessaging",
	acceptableRemoteVersions = "*",
	serverSideOnly = true
)
public final class LunaVaultBackendMc12Mod {
	public static final String MOD_ID = "lunavaultbackend";

	private static final String PERMISSION_HISTORY = "lunavault.transactions";
	private static final String PLAYERS_ONLY = "<red>❌ Chỉ người chơi mới dùng lệnh này.</red>";
	private static final String NOT_READY = "<red>❌ LunaVaultBackend chưa sẵn sàng.</red>";

	/**
	 * Ticks to wait before the join rpc.
	 *
	 * Two things have to have happened, and the later one sets this number. The
	 * player has to be in `PlayerList` - one tick, since this version adds them
	 * after firing the login event - and their connection has to be past the
	 * messaging bus's 1500ms sender warmup, below which `send` refuses and the rpc
	 * fails outright. Forty ticks is two seconds, comfortably the far side of it.
	 */
	private static final int JOIN_DELAY_TICKS = 40;

	/** This mod's own default config inside its jar, scoped by mod id. */
	private static final String CONFIG_RESOURCE = MOD_ID + "/config.yml";

	private LunaLogger logger;
	private Path configDir;
	private VaultGateway<EntityPlayerMP> gateway;
	private VaultPlaceholders<EntityPlayerMP> placeholders;
	private TransactionHistoryScreen historyScreen;
	private final Map<UUID, Integer> pendingJoins = new ConcurrentHashMap<UUID, Integer>();
	private PermissionService permissions;

	@Mod.EventHandler
	public void onPreInit(FMLPreInitializationEvent event) {
		logger = LegacyLunaLogger.create(event.getModLog(), "VaultBackend");
		configDir = event.getModConfigurationDirectory().toPath();
	}

	@Mod.EventHandler
	public void onServerStarting(FMLServerStartingEvent event) {
		if (!LunaCore.isReady()) {
			logger.error("LunaCore chưa sẵn sàng. LunaVaultBackend sẽ không khởi động.");
			return;
		}

		PlayerBridge<EntityPlayerMP> players = playerBridge();
		PluginMessageBus<EntityPlayerMP, EntityPlayerMP> bus = messageBus();

		if (players == null || bus == null) {
			logger.error("Thiếu PlayerBridge hoặc PluginMessageBus. LunaVaultBackend sẽ không khởi động.");
			return;
		}

		YamlConfigFile config = YamlConfigFile.load(
			configDir.resolve(MOD_ID).resolve("config.yml"),
			getClass(),
			CONFIG_RESOURCE
		);

		Database database = database();

		migrateIfOwned(database);

		long timeoutMillis = config.getLong("transport.timeout-millis", 3000L);
		int pageSize = config.getInt("history.page-size", 45);

		gateway = new VaultGateway<EntityPlayerMP>(players, MOD_ID, logger, bus, database, timeoutMillis);
		gateway.registerChannels();

		permissions = LunaCore.find(PermissionService.class);
		historyScreen = new TransactionHistoryScreen(players, gateway, LunaCore.find(YamlConfigFile.class), pageSize);

		LunaCore.services().register(LunaVaultApi.class, gateway);
		LunaCore.services().register(VaultGateway.class, gateway);

		registerPlaceholders(players, timeoutMillis);
		MinecraftForge.EVENT_BUS.register(this);

		for (String alias : new String[] { "transactions", "txns", "lichsu" }) {
			event.registerServerCommand(new TransactionsCommand(alias));
		}

		logger.success("LunaVaultBackend (Forge 1.12.2) đã sẵn sàng và nối tới Velocity.");
	}

	/**
	 * Only a backend with a database of its own owns these tables.
	 *
	 * In RPC mode the proxy has already migrated them, and a second migrator racing
	 * it is how two servers end up half-applying the same schema change.
	 */
	private void migrateIfOwned(Database database) {
		if (database instanceof NoopDatabase) {
			return;
		}

		try {
			DatabaseMigrator migrator = new DatabaseMigrator(database, logger.scope("Migration"));

			VaultDatabaseMigrations.register(migrator);
			migrator.migrateNamespace("lunavault");
		} catch (Exception exception) {
			logger.error("Không thể chuẩn bị schema cho LunaVaultBackend.", exception);
		}
	}

	private void registerPlaceholders(PlayerBridge<EntityPlayerMP> players, long timeoutMillis) {
		@SuppressWarnings("unchecked")
		PlaceholderService<EntityPlayerMP> placeholderService =
			(PlaceholderService<EntityPlayerMP>) LunaCore.find(PlaceholderService.class);

		if (placeholderService == null) {
			// the core publishes one, so this now means the core failed to start
			logger.warn("Không tìm thấy placeholder service. Bỏ qua namespace lunavault.");
			return;
		}

		placeholders = new VaultPlaceholders<EntityPlayerMP>(
			gateway,
			players,
			LunaCore.find(YamlConfigFile.class),
			timeoutMillis
		);

		placeholderService.registerExtension(placeholders);
		logger.success("Đã đăng ký placeholder %lunavault_...%.");
	}

	@SuppressWarnings("unchecked")
	private PlayerBridge<EntityPlayerMP> playerBridge() {
		return (PlayerBridge<EntityPlayerMP>) LunaCore.find(PlayerBridge.class);
	}

	@SuppressWarnings("unchecked")
	private PluginMessageBus<EntityPlayerMP, EntityPlayerMP> messageBus() {
		return (PluginMessageBus<EntityPlayerMP, EntityPlayerMP>) LunaCore.find(PluginMessageBus.class);
	}

	/** The core's database when it has one; RPC mode otherwise. */
	private Database database() {
		Database registered = LunaCore.find(Database.class);

		return registered == null ? new NoopDatabase() : registered;
	}

	/**
	 * The join snapshot waits one tick, and has to.
	 *
	 * On this version `PlayerList` fires the login event *before* it adds the player
	 * to its own list, so anything asking "who is online" during this event does not
	 * see the player who just joined - and the vault's rpc rides a player connection,
	 * so it would find no carrier and fail every single join. The modern builds add
	 * first and fire second, which is why they can call this inline.
	 */
	@SubscribeEvent
	public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
		if (gateway != null && event.player instanceof EntityPlayerMP) {
			pendingJoins.put(((EntityPlayerMP) event.player).getUniqueID(), Integer.valueOf(JOIN_DELAY_TICKS));
		}
	}

	@SubscribeEvent
	public void onServerTick(TickEvent.ServerTickEvent event) {
		if (event.phase != TickEvent.Phase.END || pendingJoins.isEmpty() || gateway == null) {
			return;
		}

		PlayerBridge<EntityPlayerMP> players = playerBridge();

		if (players == null) {
			pendingJoins.clear();
			return;
		}

		for (Map.Entry<UUID, Integer> entry : pendingJoins.entrySet()) {
			int remaining = entry.getValue().intValue() - 1;
			UUID playerId = entry.getKey();

			if (remaining > 0) {
				entry.setValue(Integer.valueOf(remaining));
				continue;
			}

			EntityPlayerMP player = players.byId(playerId);

			// gone before the delay elapsed, or somehow still unregistered; either way
			// there is nothing to announce
			if (player == null) {
				pendingJoins.remove(playerId);
				continue;
			}

			// claim it first, so a later tick cannot announce the same join twice
			if (pendingJoins.remove(playerId) != null) {
				gateway.onPlayerJoin(player);
			}
		}
	}

	@SubscribeEvent
	public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
		if (!(event.player instanceof EntityPlayerMP)) {
			return;
		}

		EntityPlayerMP player = (EntityPlayerMP) event.player;
		UUID playerId = player.getUniqueID();

		if (gateway != null) {
			gateway.onPlayerQuit(player);
		}

		if (placeholders != null) {
			placeholders.forget(playerId);
		}

		if (historyScreen != null) {
			historyScreen.forget(playerId);
		}

		// a player who left before their deferred join ran has nothing to fetch
		pendingJoins.remove(playerId);
	}

	/**
	 * `/transactions`, `/txns`, `/lichsu`.
	 *
	 * Gated on the same LuckPerms node the other backends use and on nothing else:
	 * the game's own op level is not a fallback, because the network's answer to
	 * "may this player see their history" has to be the same everywhere.
	 */
	private final class TransactionsCommand extends CommandBase {
		private final String name;

		TransactionsCommand(String name) {
			this.name = name;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public String getUsage(ICommandSender sender) {
			return "/" + name + " [trang]";
		}

		@Override
		public int getRequiredPermissionLevel() {
			return 0;
		}

		@Override
		public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
			if (!(sender instanceof EntityPlayerMP)) {
				return true;
			}

			return permissions != null
				&& permissions.isAvailable()
				&& permissions.hasPermission(((EntityPlayerMP) sender).getUniqueID(), PERMISSION_HISTORY);
		}

		@Override
		public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
			if (!(sender instanceof EntityPlayerMP)) {
				sender.sendMessage(LunaTextComponents.mini(PLAYERS_ONLY));
				return;
			}

			if (historyScreen == null) {
				sender.sendMessage(LunaTextComponents.mini(NOT_READY));
				return;
			}

			int page = 1;

			if (args.length > 0) {
				try {
					page = Math.max(1, Integer.parseInt(args[0]));
				} catch (NumberFormatException ignored) {
					// a non-numeric page is the first page, not an error worth a message
				}
			}

			historyScreen.open((EntityPlayerMP) sender, page - 1);
		}
	}

	@Mod.EventHandler
	public void onServerStopping(FMLServerStoppingEvent event) {
		if (LunaCore.isReady()) {
			LunaCore.services().unregister(LunaVaultApi.class);
			LunaCore.services().unregister(VaultGateway.class);
		}

		if (historyScreen != null) {
			historyScreen.close();
			historyScreen = null;
		}

		if (gateway != null) {
			gateway.close();
			gateway = null;
		}

		placeholders = null;
		logger.audit("LunaVaultBackend (Forge 1.12.2) đã dừng.");
	}
}
