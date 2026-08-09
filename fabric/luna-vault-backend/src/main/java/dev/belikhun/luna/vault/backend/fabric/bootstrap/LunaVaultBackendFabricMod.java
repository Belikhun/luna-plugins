package dev.belikhun.luna.vault.backend.fabric.bootstrap;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.belikhun.luna.core.api.config.YamlConfigFile;
import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.NoopDatabase;
import dev.belikhun.luna.core.api.database.migration.DatabaseMigrator;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.fabric.LunaCoreFabric;
import dev.belikhun.luna.core.fabric.logging.FabricLunaLoggers;
import dev.belikhun.luna.core.fabric.placeholder.FabricPlaceholderService;
import dev.belikhun.luna.core.mc.text.LunaTextComponents;
import dev.belikhun.luna.vault.api.LunaVaultApi;
import dev.belikhun.luna.vault.api.model.VaultDatabaseMigrations;
import dev.belikhun.luna.vault.backend.mc.gui.TransactionHistoryScreen;
import dev.belikhun.luna.vault.backend.mc.placeholder.VaultPlaceholders;
import dev.belikhun.luna.vault.backend.mc.service.VaultGateway;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.UUID;

/**
 * The backend half of the network economy, as a fabric mod.
 *
 * There is no Vault here, and there is nothing to register with: Vault is a
 * Bukkit service and the mod loaders have no equivalent registry. What the Paper
 * plugin publishes as a {@code Economy} provider, this publishes as the
 * {@link LunaVaultApi} singleton in the core's dependency manager, which is what
 * LunaShop and anything else asks for. The wire protocol, the database schema and
 * every screen are unchanged.
 */
public final class LunaVaultBackendFabricMod implements DedicatedServerModInitializer {
	public static final String MOD_ID = "lunavaultbackend";
	private static final String PLAYERS_ONLY = "<red>❌ Chỉ người chơi mới dùng lệnh này.</red>";
	private static final String NOT_READY = "<red>❌ LunaVaultBackend chưa sẵn sàng.</red>";
	private static final String PERMISSION_HISTORY = "lunavault.transactions";

	private LunaLogger logger;
	private VaultGateway gateway;
	private TransactionHistoryScreen historyScreen;
	private VaultPlaceholders placeholders;
	private PermissionService permissionService;

	public LunaVaultBackendFabricMod() {
		this.logger = FabricLunaLoggers.create("VaultBackend", true);
		this.gateway = null;
		this.historyScreen = null;
		this.placeholders = null;
		this.permissionService = null;
	}

	@Override
	public void onInitializeServer() {
		ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> registerCommands(dispatcher));

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (gateway != null) {
				gateway.onPlayerJoin(handler.getPlayer());
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayer player = handler.getPlayer();

			if (player == null) {
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
		});
	}

	@SuppressWarnings("unchecked")
	private void onServerStarted(MinecraftServer server) {
		DependencyManager dependencyManager = LunaCoreFabric.services().dependencyManager();
		YamlConfigFile coreConfig = LunaCoreFabric.services().config();
		Database database = LunaCoreFabric.services().database();

		permissionService = dependencyManager.resolveOptional(PermissionService.class).orElse(null);

		Path configPath = FabricLoader.getInstance().getConfigDir().toAbsolutePath().normalize()
			.resolve(MOD_ID).resolve("config.yml");
		YamlConfigFile config = YamlConfigFile.load(configPath, getClass(), "config.yml");

		logger = FabricLunaLoggers.create("VaultBackend", true);

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

		logger.success("LunaVaultBackend Fabric đã sẵn sàng và nối tới Velocity.");
	}

	private void registerPlaceholders(DependencyManager dependencyManager, YamlConfigFile coreConfig, long timeoutMillis) {
		FabricPlaceholderService placeholderService = dependencyManager
			.resolveOptional(FabricPlaceholderService.class)
			.orElse(null);

		if (placeholderService == null) {
			logger.warn("Không tìm thấy placeholder service. Bỏ qua namespace lunavault.");
			return;
		}

		placeholders = new VaultPlaceholders(gateway, coreConfig, timeoutMillis);
		placeholderService.registerExtension(placeholders);
		logger.success("Đã đăng ký placeholder %lunavault_...%.");
	}

	private void onServerStopping(MinecraftServer server) {
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

	/**
	 * {@code /transactions [trang]}, plus the two aliases the Paper plugin
	 * registers. The page a player types is one-based; everything below it counts
	 * from zero.
	 */
	private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		registerTransactionsCommand(dispatcher, "transactions");
		registerTransactionsCommand(dispatcher, "txns");
		registerTransactionsCommand(dispatcher, "lichsu");
	}

	private void registerTransactionsCommand(CommandDispatcher<CommandSourceStack> dispatcher, String root) {
		dispatcher.register(Commands.literal(root)
			.requires(this::mayViewHistory)
			.executes(context -> openHistory(context.getSource(), 1))
			.then(Commands.argument("trang", IntegerArgumentType.integer(1))
				.executes(context -> openHistory(context.getSource(), IntegerArgumentType.getInteger(context, "trang")))));
	}

	/**
	 * The same permission the Paper plugin puts on the command.
	 *
	 * LuckPerms is the only thing asked. The game's own op check is not a fallback
	 * here because its two game lines spell it differently - {@code isOp} takes a
	 * GameProfile through 1.21 and a NameAndId from 26.x - and a backend in this
	 * fleet without LuckPerms has bigger problems: the core refuses to build the
	 * server selector at all in that case, and says so at boot.
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
