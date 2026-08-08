package dev.belikhun.luna.auth.backend.neoforge.bootstrap;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.belikhun.luna.auth.backend.neoforge.config.AuthBackendNeoForgeConfig;
import dev.belikhun.luna.auth.backend.neoforge.config.AuthBackendNeoForgeConfigLoader;
import dev.belikhun.luna.auth.backend.neoforge.runtime.AuthRestrictionController;
import dev.belikhun.luna.auth.backend.neoforge.service.BackendAuthSpawnService;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.messaging.neoforge.NeoForgePluginMessagingBus;
import dev.belikhun.luna.core.neoforge.LunaCoreNeoForge;
import dev.belikhun.luna.core.neoforge.logging.NeoForgeLunaLoggers;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
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

@Mod(LunaAuthBackendNeoForgeMod.MOD_ID)
public final class LunaAuthBackendNeoForgeMod {
	public static final String MOD_ID = "lunaauthbackend";

	private LunaLogger logger;
	private DependencyManager dependencyManager;
	private AuthRestrictionController controller;

	public LunaAuthBackendNeoForgeMod() {
		this.logger = NeoForgeLunaLoggers.create("LunaAuthBackend", true);
		this.dependencyManager = null;
		this.controller = null;
		NeoForge.EVENT_BUS.register(this);
	}

	@SubscribeEvent
	public void onServerStarted(ServerStartedEvent event) {
		dependencyManager = LunaCoreNeoForge.services().dependencyManager();
		AuthBackendNeoForgeConfig config = AuthBackendNeoForgeConfigLoader.load(getClass(), logger);
		this.logger = NeoForgeLunaLoggers.create(
			"LunaAuthBackend",
			true,
			config.authFlowLogsEnabled()
		);
		NeoForgePluginMessagingBus messagingBus = dependencyManager.resolveOptional(NeoForgePluginMessagingBus.class).orElse(null);
		BackendAuthSpawnService spawnService = new BackendAuthSpawnService(AuthBackendNeoForgeConfigLoader.configPath(), logger.scope("Spawn"));
		controller = new AuthRestrictionController(event.getServer(), logger.scope("Restriction"), config, spawnService, messagingBus);
		controller.start();
		logger.success("Luna Auth Backend NeoForge đã sẵn sàng.");
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
		if (controller != null) {
			controller.onPlayerLoggedIn(event);
		}
	}

	@SubscribeEvent
	public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
		if (controller != null) {
			controller.onPlayerLoggedOut(event);
		}
	}

	@SubscribeEvent
	public void onPlayerTick(PlayerTickEvent.Post event) {
		if (controller != null) {
			controller.onPlayerTick(event);
		}
	}

	@SubscribeEvent
	public void onServerChat(ServerChatEvent event) {
		if (controller != null) {
			controller.onServerChat(event);
		}
	}

	@SubscribeEvent
	public void onCommand(CommandEvent event) {
		if (controller != null) {
			controller.onCommand(event);
		}
	}

	@SubscribeEvent
	public void onBlockBreak(BlockEvent.BreakEvent event) {
		if (controller != null) {
			controller.onBlockBreak(event);
		}
	}

	@SubscribeEvent
	public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
		if (controller != null) {
			controller.onBlockPlace(event);
		}
	}

	@SubscribeEvent
	public void onIncomingDamage(LivingIncomingDamageEvent event) {
		if (controller != null) {
			controller.onIncomingDamage(event);
		}
	}

	@SubscribeEvent
	public void onItemPickup(ItemEntityPickupEvent.Pre event) {
		if (controller != null) {
			controller.onItemPickup(event);
		}
	}

	@SubscribeEvent
	public void onItemToss(ItemTossEvent event) {
		if (controller != null) {
			controller.onItemToss(event);
		}
	}

	@SubscribeEvent
	public void onUseItemOnBlock(UseItemOnBlockEvent event) {
		if (controller != null) {
			controller.onUseItemOnBlock(event);
		}
	}

	@SubscribeEvent
	public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
		if (controller != null) {
			controller.onRightClickItem(event);
		}
	}

	@SubscribeEvent
	public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		if (controller != null) {
			controller.onRightClickBlock(event);
		}
	}

	@SubscribeEvent
	public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
		if (controller != null) {
			controller.onEntityInteract(event);
		}
	}

	@SubscribeEvent
	public void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
		if (controller != null) {
			controller.onEntityInteractSpecific(event);
		}
	}

	@SubscribeEvent
	public void onServerStopping(ServerStoppingEvent event) {
		if (controller != null) {
			controller.close();
			controller = null;
		}
		dependencyManager = null;
	}

	private void registerLoginCommand(RegisterCommandsEvent event, String root) {
		event.getDispatcher().register(Commands.literal(root)
			.requires(source -> source.getEntity() instanceof net.minecraft.server.level.ServerPlayer)
			.executes(context -> sendLoginUsage(context.getSource()))
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
			.requires(source -> source.getEntity() instanceof net.minecraft.server.level.ServerPlayer)
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

	private int sendLoginUsage(net.minecraft.commands.CommandSourceStack source) {
		source.sendSystemMessage(Component.literal(dev.belikhun.luna.core.api.string.CommandStrings.plainUsage(
			"/login",
			dev.belikhun.luna.core.api.string.CommandStrings.required("mat_khau", "text")
		)));
		return 0;
	}

	private int sendRegisterUsage(net.minecraft.commands.CommandSourceStack source) {
		source.sendSystemMessage(Component.literal(dev.belikhun.luna.core.api.string.CommandStrings.plainUsage(
			"/register",
			dev.belikhun.luna.core.api.string.CommandStrings.required("mat_khau", "text"),
			dev.belikhun.luna.core.api.string.CommandStrings.required("nhap_lai", "text")
		)));
		return 0;
	}

	private int notReady(net.minecraft.commands.CommandSourceStack source) {
		source.sendSystemMessage(Component.literal("❌ LunaAuth Backend NeoForge chưa sẵn sàng."));
		return 0;
	}
}
