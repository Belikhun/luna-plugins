package dev.belikhun.luna.vault.backend.forge.bootstrap;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import dev.belikhun.luna.core.api.config.YamlConfigFile;
import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.NoopDatabase;
import dev.belikhun.luna.core.api.database.migration.DatabaseMigrator;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.mc.text.LunaTextComponents;
import dev.belikhun.luna.core.mc.LunaCore;
import dev.belikhun.luna.core.mc.logging.LunaLoggers;
import dev.belikhun.luna.core.mc.placeholder.PlaceholderService;
import dev.belikhun.luna.vault.api.LunaVaultApi;
import dev.belikhun.luna.vault.api.model.VaultDatabaseMigrations;
import dev.belikhun.luna.vault.backend.mc.gui.TransactionHistoryScreen;
import dev.belikhun.luna.vault.backend.mc.placeholder.VaultPlaceholders;
import dev.belikhun.luna.vault.backend.mc.service.VaultGateway;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

import java.nio.file.Path;
import java.util.UUID;

/**
 * The backend half of the network economy, as a Forge mod.
 *
 * Everything it does - the gateway, the history screen, the placeholders - is the
 * shared trunk, the same classes the fabric build runs. This is the entry point
 * and the event wiring, and nothing else.
 */
@Mod(LunaVaultBackendForgeMod.MOD_ID)
public final class LunaVaultBackendForgeMod {
	/** This mod's own default config inside its jar; see the note on the name. */
	private static final String CONFIG_RESOURCE = "lunavaultbackend/config.yml";

	public static final String MOD_ID = "lunavaultbackend";
	private static final String PLAYERS_ONLY = "<red>❌ Chỉ người chơi mới dùng lệnh này.</red>";
	private static final String NOT_READY = "<red>❌ LunaVaultBackend chưa sẵn sàng.</red>";
	private static final String PERMISSION_HISTORY = "lunavault.transactions";

	private LunaLogger logger;
	private VaultGateway gateway;
	private TransactionHistoryScreen historyScreen;
	private VaultPlaceholders placeholders;
	private PermissionService permissionService;

	public LunaVaultBackendForgeMod() {
		this.logger = LunaLoggers.create("VaultBackend", true);
		MinecraftForge.EVENT_BUS.register(this);
	}

	@SuppressWarnings("unchecked")
	/**
	 * Runs after LunaCore's own handler.
	 *
	 * A dependency's `ordering="AFTER"` in mods.toml orders mod *loading*, not
	 * the event bus: both handlers sit on the same bus at NORMAL and fire in
	 * registration order, which put this one ~90ms ahead of the core and left it
	 * without a permission service. LOWEST is what actually orders them, after the core and the messaging bus.
	 */
	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onServerStarted(ServerStartedEvent event) {
		MinecraftServer server = event.getServer();
		DependencyManager dependencyManager = LunaCore.services().dependencyManager();
		YamlConfigFile coreConfig = LunaCore.services().config();
		Database database = LunaCore.services().database();

		permissionService = dependencyManager.resolveOptional(PermissionService.class).orElse(null);

		Path configPath = FMLPaths.CONFIGDIR.get().resolve(MOD_ID).resolve("config.yml");
		YamlConfigFile config = YamlConfigFile.load(configPath, getClass(), CONFIG_RESOURCE);

		// only the direct-database mode owns these tables; in rpc mode the proxy is
		// the one that has already migrated them
		if (!(database instanceof NoopDatabase)) {
			try {
				DatabaseMigrator migrator = new DatabaseMigrator(database, logger.scope("Migration"));
				VaultDatabaseMigrations.register(migrator);
				migrator.migrateNamespace("lunavault");
			} catch (Exception exception) {
				logger.error("Không thể chuẩn bị schema cho LunaVaultBackend.", exception);
			}
		}

		long timeoutMillis = config.getLong("transport.timeout-millis", 3000L);
		int pageSize = config.getInt("history.page-size", 45);

		PluginMessageBus<ServerPlayer, ServerPlayer> bus = (PluginMessageBus<ServerPlayer, ServerPlayer>) dependencyManager
			.resolveOptional(PluginMessageBus.class)
			.orElse(null);

		if (bus == null) {
			logger.error("Không tìm thấy PluginMessageBus. LunaVaultBackend sẽ không khởi động.");
			return;
		}

		gateway = new VaultGateway(server, MOD_ID, logger, bus, database, timeoutMillis);
		gateway.registerChannels();

		historyScreen = new TransactionHistoryScreen(server, gateway, coreConfig, pageSize);

		dependencyManager.registerSingleton(LunaVaultApi.class, gateway);
		dependencyManager.registerSingleton(VaultGateway.class, gateway);

		registerPlaceholders(dependencyManager, coreConfig, timeoutMillis);

		logger.success("LunaVaultBackend Forge đã sẵn sàng và nối tới Velocity.");
	}

	private void registerPlaceholders(DependencyManager dependencyManager, YamlConfigFile coreConfig, long timeoutMillis) {
		PlaceholderService placeholderService = dependencyManager
			.resolveOptional(PlaceholderService.class)
			.orElse(null);

		if (placeholderService == null) {
			logger.warn("Không tìm thấy placeholder service. Bỏ qua namespace lunavault.");
			return;
		}

		placeholders = new VaultPlaceholders(gateway, coreConfig, timeoutMillis);
		placeholderService.registerExtension(placeholders);
		logger.success("Đã đăng ký placeholder %lunavault_...%.");
	}

	@SubscribeEvent
	public void onServerStopping(ServerStoppingEvent event) {
		if (historyScreen != null) {
			historyScreen.close();
			historyScreen = null;
		}

		if (gateway != null) {
			gateway.close();
			gateway = null;
		}

		placeholders = null;
	}

	@SubscribeEvent
	public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
		if (gateway != null && event.getEntity() instanceof ServerPlayer player) {
			gateway.onPlayerJoin(player);
		}
	}

	@SubscribeEvent
	public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
		if (!(event.getEntity() instanceof ServerPlayer player)) {
			return;
		}

		UUID playerId = player.getUUID();

		if (gateway != null) {
			gateway.onPlayerQuit(player);
		}

		if (historyScreen != null) {
			historyScreen.forget(playerId);
		}

		if (placeholders != null) {
			placeholders.forget(playerId);
		}
	}

	@SubscribeEvent
	public void onRegisterCommands(RegisterCommandsEvent event) {
		registerTransactionsCommand(event.getDispatcher(), "transactions");
		registerTransactionsCommand(event.getDispatcher(), "txns");
		registerTransactionsCommand(event.getDispatcher(), "lichsu");
	}

	private void registerTransactionsCommand(CommandDispatcher<CommandSourceStack> dispatcher, String root) {
		dispatcher.register(Commands.literal(root)
			.requires(this::mayViewHistory)
			.executes(context -> openHistory(context.getSource(), 1))
			.then(Commands.argument("trang", IntegerArgumentType.integer(1))
				.executes(context -> openHistory(context.getSource(), IntegerArgumentType.getInteger(context, "trang")))));
	}

	/**
	 * The same permission the Paper plugin puts on the command, asked of LuckPerms
	 * and nothing else. See the fabric bootstrap for why the game's own op check is
	 * not a fallback.
	 */
	private boolean mayViewHistory(CommandSourceStack source) {
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			return true;
		}

		return permissionService != null
			&& permissionService.isAvailable()
			&& permissionService.hasPermission(player.getUUID(), PERMISSION_HISTORY);
	}

	private int openHistory(CommandSourceStack source, int page) {
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			source.sendSystemMessage(LunaTextComponents.mini(PLAYERS_ONLY));
			return 0;
		}

		if (historyScreen == null) {
			source.sendSystemMessage(LunaTextComponents.mini(NOT_READY));
			return 0;
		}

		historyScreen.open(player, Math.max(0, page - 1));
		return 1;
	}
}
