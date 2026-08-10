package dev.belikhun.luna.auth.backend.fabric.bootstrap;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.belikhun.luna.auth.backend.fabric.compat.UseItemGate;
import dev.belikhun.luna.auth.backend.mc.config.AuthBackendConfig;
import dev.belikhun.luna.auth.backend.mc.config.AuthBackendConfigLoader;
import dev.belikhun.luna.auth.backend.mc.runtime.AuthLockHooks;
import dev.belikhun.luna.auth.backend.mc.runtime.AuthRestrictionController;
import dev.belikhun.luna.auth.backend.mc.service.BackendAuthSpawnService;
import dev.belikhun.luna.core.api.auth.AuthMessages;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.mc.text.LunaTextComponents;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.fabric.LunaCoreFabric;
import dev.belikhun.luna.core.fabric.logging.FabricLunaLoggers;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

/**
 * The Fabric entrypoint: builds the controller once the server exists, then
 * hands every callback that can be refused to it.
 *
 * NeoForge subscribes one bus and gets a cancellable event per hook. Fabric's
 * callbacks are each their own interface with their own return type, so the
 * adapting happens here and the controller keeps a single vocabulary of
 * decisions. The three hooks Fabric has no callback for - commands, item pickup,
 * item toss - are mixins that reach the controller through {@link AuthLockHooks}.
 */
public final class LunaAuthBackendFabricMod implements DedicatedServerModInitializer {
	private LunaLogger logger;
	private DependencyManager dependencyManager;
	private AuthRestrictionController controller;
	private MinecraftServer pendingServer;

	public LunaAuthBackendFabricMod() {
		this.logger = FabricLunaLoggers.create("LunaAuthBackend", true);
		this.dependencyManager = null;
		this.controller = null;
		this.pendingServer = null;
	}

	@Override
	public void onInitializeServer() {
		registerCommands();
		registerRestrictions();

		ServerLifecycleEvents.SERVER_STARTED.register(server -> pendingServer = server);

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (controller != null) {
				AuthLockHooks.clear(controller);
				controller.close();
				controller = null;
			}

			pendingServer = null;
			dependencyManager = null;
		});
	}

	/**
	 * The two commands an unauthenticated player is allowed to run, plus their
	 * short forms; the allow-list in the config is what actually lets them past
	 * the command mixin.
	 */
	private void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
			registerLoginCommand(dispatcher, "login");
			registerLoginCommand(dispatcher, "l");
			registerRegisterCommand(dispatcher, "register");
			registerRegisterCommand(dispatcher, "reg");
		});
	}

	private void registerRestrictions() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (controller != null) {
				controller.onPlayerLoggedIn(handler.getPlayer());
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (controller != null) {
				controller.onPlayerLoggedOut(handler.getPlayer());
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			startWhenCoreIsReady();

			if (controller != null) {
				controller.onServerTick();
			}
		});

		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) ->
			controller == null || controller.allowChat(sender));

		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
			controller == null || controller.allowInteraction(player));

		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
			controller == null || controller.allowDamage(entity, source.getEntity()));

		// placing a block, using an item on one and right-clicking one all arrive as
		// this single callback, so it covers three of NeoForge's separate events
		UseBlockCallback.EVENT.register((player, level, hand, hitResult) ->
			controller == null || controller.allowInteraction(player)
				? InteractionResult.PASS
				: InteractionResult.FAIL);

		UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) ->
			controller == null || controller.allowInteraction(player)
				? InteractionResult.PASS
				: InteractionResult.FAIL);

		// the only callback whose shape differs between the two game lines
		UseItemGate.register(player -> controller == null || controller.allowInteraction(player));
	}

	/**
	 * Build the controller the first tick LunaCore can answer for its services.
	 *
	 * Fabric hands every mod the same server-started event and orders the
	 * listeners by mod id, so a dependant whose id sorts first - which this one
	 * does, "lunaauthbackend" before "lunacore" - would otherwise ask the core for
	 * services it has not published yet. Waiting for a tick costs nothing: no
	 * player can be connected before the first one.
	 */
	private void startWhenCoreIsReady() {
		if (controller != null || pendingServer == null || !LunaCoreFabric.isReady()) {
			return;
		}

		MinecraftServer server = pendingServer;
		pendingServer = null;
		dependencyManager = LunaCoreFabric.services().dependencyManager();

		// fabric names its own config directory; FML names it for the forge family,
		// so the shared loader is told where to write rather than resolving it
		AuthBackendConfigLoader.useConfigDirectory(FabricLoader.getInstance().getConfigDir());

		AuthBackendConfig config = AuthBackendConfigLoader.load(getClass(), logger);
		this.logger = FabricLunaLoggers.create("LunaAuthBackend", true, config.authFlowLogsEnabled());

		PluginMessageBus<ServerPlayer, ServerPlayer> messagingBus = resolveMessagingBus();
		BackendAuthSpawnService spawnService = new BackendAuthSpawnService(
			AuthBackendConfigLoader.configPath(),
			logger.scope("Spawn")
		);

		controller = new AuthRestrictionController(server, logger.scope("Restriction"), config, spawnService, messagingBus);
		controller.start();
		AuthLockHooks.install(controller);
		logger.success("Luna Auth Backend Fabric đã sẵn sàng.");
	}

	@SuppressWarnings("unchecked")
	private PluginMessageBus<ServerPlayer, ServerPlayer> resolveMessagingBus() {
		if (dependencyManager == null) {
			return null;
		}

		return (PluginMessageBus<ServerPlayer, ServerPlayer>) dependencyManager
			.resolveOptional(PluginMessageBus.class)
			.orElse(null);
	}

	private void registerLoginCommand(CommandDispatcher<CommandSourceStack> dispatcher, String root) {
		dispatcher.register(Commands.literal(root)
			.requires(source -> source.getEntity() instanceof ServerPlayer)
			.executes(context -> sendLoginUsage(context.getSource()))
			.then(Commands.argument("password", StringArgumentType.word())
				.executes(context -> controller == null
					? notReady(context.getSource())
					: controller.executeLogin(
						context.getSource(),
						StringArgumentType.getString(context, "password")
					))));
	}

	private void registerRegisterCommand(CommandDispatcher<CommandSourceStack> dispatcher, String root) {
		dispatcher.register(Commands.literal(root)
			.requires(source -> source.getEntity() instanceof ServerPlayer)
			.executes(context -> sendRegisterUsage(context.getSource()))
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

	private int sendLoginUsage(CommandSourceStack source) {
		source.sendSystemMessage(LunaTextComponents.mini(AuthMessages.loginUsage()));

		return 0;
	}

	private int sendRegisterUsage(CommandSourceStack source) {
		source.sendSystemMessage(LunaTextComponents.mini(AuthMessages.registerUsage()));

		return 0;
	}

	private int notReady(CommandSourceStack source) {
		source.sendSystemMessage(LunaTextComponents.mini(AuthMessages.commandSendFailed()));

		return 0;
	}
}
