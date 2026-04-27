package dev.belikhun.luna.auth.backend.neoforge.runtime;

import com.mojang.brigadier.context.CommandContext;
import dev.belikhun.luna.auth.backend.neoforge.config.AuthBackendNeoForgeConfig;
import dev.belikhun.luna.auth.backend.neoforge.service.BackendAuthSpawnService;
import dev.belikhun.luna.auth.backend.neoforge.service.BackendAuthSpawnService.StoredLocation;
import dev.belikhun.luna.auth.backend.neoforge.ui.AuthModeSelectorMenu;
import dev.belikhun.luna.core.api.auth.AuthChannels;
import dev.belikhun.luna.core.api.auth.BackendAuthStateRegistry;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.messaging.neoforge.NeoForgePluginMessagingBus;
import dev.belikhun.luna.core.neoforge.text.NeoForgeTextComponents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.bossevents.CustomBossEvents;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class AuthRestrictionController {
	private static final long RESTRICTION_LOG_THROTTLE_MS = 3000L;
	private static final long SYNC_REQUEST_THROTTLE_MS = 1500L;
	private static final long PROMPT_ACTIONBAR_THROTTLE_MS = 1500L;
	private static final long LOCK_EFFECT_REFRESH_THROTTLE_MS = 3000L;
	private static final long MODE_SELECTOR_DELAY_MS = 1500L;
	private static final int BLINDNESS_DURATION_TICKS = 600;
	private static final int LOCK_EFFECT_DURATION_TICKS = 220;
	private static final int SLOT_PREMIUM = 3;
	private static final int SLOT_OFFLINE = 5;
	private static final int SLOT_REMEMBER = 7;
	private static final String MODE_SELECTOR_TITLE = "Chọn kiểu tài khoản";

	private final MinecraftServer server;
	private final LunaLogger logger;
	private final AuthBackendNeoForgeConfig config;
	private final BackendAuthSpawnService spawnService;
	private final NeoForgePluginMessagingBus messagingBus;
	private final BackendAuthStateRegistry stateRegistry;
	private final PromptSet pendingPrompt;
	private final PromptSet loginPrompt;
	private final PromptSet registerPrompt;
	private final ConcurrentMap<UUID, ServerBossEvent> activeBossbars;
	private final ConcurrentMap<UUID, Long> lastMoveRestrictionLog;
	private final ConcurrentMap<UUID, Long> lastCommandRestrictionLog;
	private final ConcurrentMap<UUID, Long> lastChatRestrictionLog;
	private final ConcurrentMap<UUID, Long> lastSyncRequestLog;
	private final ConcurrentMap<UUID, Long> lastPromptActionbarLog;
	private final ConcurrentMap<UUID, Long> lastLockEffectRefreshLog;
	private final ConcurrentMap<UUID, Boolean> modeSelectorEligible;
	private final ConcurrentMap<UUID, Boolean> modePreferencePresent;
	private final ConcurrentMap<UUID, Boolean> modeRememberSelection;
	private final ConcurrentMap<UUID, Long> nextModeSelectorOpenAt;
	private final ConcurrentMap<UUID, AuthModeSelectorMenu> openModeMenus;
	private final ConcurrentMap<UUID, LockedPlayerState> lockedPlayers;
	private final Set<UUID> modeSelectedPlayers;
	private volatile boolean messagingAttached;

	public AuthRestrictionController(
		MinecraftServer server,
		LunaLogger logger,
		AuthBackendNeoForgeConfig config,
		BackendAuthSpawnService spawnService,
		NeoForgePluginMessagingBus messagingBus
	) {
		this.server = Objects.requireNonNull(server, "server");
		this.logger = logger;
		this.config = config;
		this.spawnService = spawnService;
		this.messagingBus = messagingBus;
		this.stateRegistry = new BackendAuthStateRegistry();
		this.pendingPrompt = promptSet(config.pendingPrompt());
		this.loginPrompt = promptSet(config.loginPrompt());
		this.registerPrompt = promptSet(config.registerPrompt());
		this.activeBossbars = new ConcurrentHashMap<>();
		this.lastMoveRestrictionLog = new ConcurrentHashMap<>();
		this.lastCommandRestrictionLog = new ConcurrentHashMap<>();
		this.lastChatRestrictionLog = new ConcurrentHashMap<>();
		this.lastSyncRequestLog = new ConcurrentHashMap<>();
		this.lastPromptActionbarLog = new ConcurrentHashMap<>();
		this.lastLockEffectRefreshLog = new ConcurrentHashMap<>();
		this.modeSelectorEligible = new ConcurrentHashMap<>();
		this.modePreferencePresent = new ConcurrentHashMap<>();
		this.modeRememberSelection = new ConcurrentHashMap<>();
		this.nextModeSelectorOpenAt = new ConcurrentHashMap<>();
		this.openModeMenus = new ConcurrentHashMap<>();
		this.lockedPlayers = new ConcurrentHashMap<>();
		this.modeSelectedPlayers = ConcurrentHashMap.newKeySet();
		this.messagingAttached = false;
	}

	public void start() {
		ensureMessagingAttached();
	}

	public void close() {
		if (messagingAttached && messagingBus != null) {
			messagingBus.unregisterOutgoing(AuthChannels.COMMAND_REQUEST);
			messagingBus.unregisterIncoming(AuthChannels.AUTH_STATE);
			messagingBus.unregisterIncoming(AuthChannels.COMMAND_RESPONSE);
			messagingBus.unregisterIncoming(AuthChannels.ADMIN_REQUEST);
		}
		messagingAttached = false;

		for (UUID playerId : List.copyOf(activeBossbars.keySet())) {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player != null) {
				hidePrompt(player);
			}
		}
		for (AuthModeSelectorMenu menu : openModeMenus.values()) {
			menu.suppressCloseCallbackOnce();
		}
		activeBossbars.clear();
		openModeMenus.clear();
		lastMoveRestrictionLog.clear();
		lastCommandRestrictionLog.clear();
		lastChatRestrictionLog.clear();
		lastSyncRequestLog.clear();
		lastPromptActionbarLog.clear();
		lastLockEffectRefreshLog.clear();
		modeSelectorEligible.clear();
		modePreferencePresent.clear();
		modeRememberSelection.clear();
		nextModeSelectorOpenAt.clear();
		lockedPlayers.clear();
		modeSelectedPlayers.clear();
	}

	public int executeLogin(CommandSourceStack source, String password) {
		ServerPlayer player = playerFrom(source);
		if (player == null) {
			source.sendSystemMessage(Component.literal("❌ Lệnh này chỉ dùng cho người chơi."));
			return 0;
		}
		if (password == null || password.isBlank()) {
			player.sendSystemMessage(Component.literal(CommandStrings.plainUsage("/login", CommandStrings.required("mat_khau", "text"))));
			return 0;
		}
		if (!sendCommandRequest(player, writer -> {
			writer.writeUtf("login");
			writer.writeUuid(player.getUUID());
			writer.writeUtf(player.getGameProfile().getName());
			writer.writeUtf(password);
		})) {
			player.sendSystemMessage(Component.literal("❌ Không thể gửi yêu cầu đăng nhập lúc này."));
			return 0;
		}
		return 1;
	}

	public int executeRegister(CommandSourceStack source, String password, String confirm) {
		ServerPlayer player = playerFrom(source);
		if (player == null) {
			source.sendSystemMessage(Component.literal("❌ Lệnh này chỉ dùng cho người chơi."));
			return 0;
		}
		if (password == null || password.isBlank() || confirm == null || confirm.isBlank()) {
			player.sendSystemMessage(Component.literal(CommandStrings.plainUsage(
				"/register",
				CommandStrings.required("mat_khau", "text"),
				CommandStrings.required("nhap_lai", "text")
			)));
			return 0;
		}
		if (!sendCommandRequest(player, writer -> {
			writer.writeUtf("register");
			writer.writeUuid(player.getUUID());
			writer.writeUtf(player.getGameProfile().getName());
			writer.writeUtf(password);
			writer.writeUtf(confirm);
		})) {
			player.sendSystemMessage(Component.literal("❌ Không thể gửi yêu cầu đăng ký lúc này."));
			return 0;
		}
		return 1;
	}

	public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
		if (!(event.getEntity() instanceof ServerPlayer player)) {
			return;
		}

		UUID playerId = player.getUUID();
		if (!stateRegistry.hasState(playerId)) {
			stateRegistry.markUnauthenticated(playerId);
			flow("Join player=" + player.getGameProfile().getName() + " uuid=" + playerId + " stateInit=PENDING");
		} else {
			flow("Join player=" + player.getGameProfile().getName() + " uuid=" + playerId + " statePreserved=" + stateRegistry.state(playerId));
		}

		requestStateSync(player);
		syncAuthLockState(player);
		showModeSelectorIfDue(player, System.currentTimeMillis(), true);
	}

	public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
		if (!(event.getEntity() instanceof ServerPlayer player)) {
			return;
		}

		UUID playerId = player.getUUID();
		hidePrompt(player);
		closeModeSelector(playerId);
		stateRegistry.clear(playerId);
		lockedPlayers.remove(playerId);
		modeSelectorEligible.remove(playerId);
		modePreferencePresent.remove(playerId);
		modeRememberSelection.remove(playerId);
		nextModeSelectorOpenAt.remove(playerId);
		modeSelectedPlayers.remove(playerId);
		lastMoveRestrictionLog.remove(playerId);
		lastCommandRestrictionLog.remove(playerId);
		lastChatRestrictionLog.remove(playerId);
		lastSyncRequestLog.remove(playerId);
		lastPromptActionbarLog.remove(playerId);
		lastLockEffectRefreshLog.remove(playerId);
		flow("Quit clear state player=" + player.getGameProfile().getName() + " uuid=" + playerId);
	}

	public void onPlayerTick(PlayerTickEvent.Post event) {
		if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()) {
			return;
		}

		UUID playerId = player.getUUID();
		if (stateRegistry.isAuthenticated(playerId)) {
			releaseAuthLockIfNeeded(player);
			hidePrompt(player);
			closeModeSelector(playerId);
			return;
		}

		if (!stateRegistry.hasState(playerId)) {
			stateRegistry.markUnauthenticated(playerId);
		}

		syncAuthLockState(player);
		enforceLockedPosition(player);
		keepProtectedState(player);

		long now = System.currentTimeMillis();
		if (shouldRunIfDue(lastLockEffectRefreshLog, playerId, now, LOCK_EFFECT_REFRESH_THROTTLE_MS)) {
			refreshLockEffects(player);
		}
		if (player.tickCount % 20 == 0) {
			showPrompt(player);
			showModeSelectorIfDue(player, now, false);
		}
	}

	public void onServerChat(ServerChatEvent event) {
		ServerPlayer player = event.getPlayer();
		if (player == null || stateRegistry.isAuthenticated(player.getUUID())) {
			return;
		}
		event.setCanceled(true);
		player.sendSystemMessage(promptFor(player.getUUID()).chat());
		throttledFlow(lastChatRestrictionLog, player.getUUID(), "BlockChat player=" + player.getGameProfile().getName() + " uuid=" + player.getUUID() + " state=" + stateRegistry.state(player.getUUID()));
	}

	public void onCommand(CommandEvent event) {
		if (!(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player)) {
			return;
		}

		UUID playerId = player.getUUID();
		if (stateRegistry.isAuthenticated(playerId)) {
			return;
		}

		String input = event.getParseResults().getReader().getString();
		String raw = input == null ? "" : input.trim();
		if (raw.startsWith("/")) {
			raw = raw.substring(1).trim();
		}
		if (raw.isEmpty()) {
			event.setCanceled(true);
			player.sendSystemMessage(promptFor(playerId).chat());
			throttledFlow(lastCommandRestrictionLog, playerId, "BlockCommand player=" + player.getGameProfile().getName() + " uuid=" + playerId + " command=<empty> state=" + stateRegistry.state(playerId));
			return;
		}

		String root = raw.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
		if (config.allowedCommands().contains(root)) {
			flow("AllowCommand player=" + player.getGameProfile().getName() + " uuid=" + playerId + " command=" + root);
			return;
		}

		event.setCanceled(true);
		player.sendSystemMessage(promptFor(playerId).chat());
		throttledFlow(lastCommandRestrictionLog, playerId, "BlockCommand player=" + player.getGameProfile().getName() + " uuid=" + playerId + " command=" + root + " state=" + stateRegistry.state(playerId));
	}

	public void onBlockBreak(BlockEvent.BreakEvent event) {
		if (!isLocked(event.getPlayer())) {
			return;
		}
		event.setCanceled(true);
	}

	public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
		if (!(event.getEntity() instanceof ServerPlayer player) || !isLocked(player)) {
			return;
		}
		event.setCanceled(true);
	}

	public void onIncomingDamage(LivingIncomingDamageEvent event) {
		if (event.getEntity() instanceof ServerPlayer player && isLocked(player)) {
			event.setCanceled(true);
			return;
		}

		Entity attacker = event.getSource().getEntity();
		if (attacker instanceof ServerPlayer player && isLocked(player)) {
			event.setCanceled(true);
		}
	}

	public void onItemPickup(ItemEntityPickupEvent.Pre event) {
		if (isLocked(event.getPlayer())) {
			event.setCanPickup(TriState.FALSE);
		}
	}

	public void onItemToss(ItemTossEvent event) {
		if (!isLocked(event.getPlayer())) {
			return;
		}
		event.setCanceled(true);
		ItemStack tossed = event.getEntity().getItem().copy();
		if (!tossed.isEmpty()) {
			event.getPlayer().getInventory().add(tossed);
		}
	}

	public void onUseItemOnBlock(UseItemOnBlockEvent event) {
		if (event.getPlayer() instanceof ServerPlayer player && isLocked(player)) {
			event.setCanceled(true);
		}
	}

	public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
		if (event.getEntity() instanceof ServerPlayer player && isLocked(player)) {
			event.setCanceled(true);
			event.setCancellationResult(InteractionResult.FAIL);
		}
	}

	public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		if (event.getEntity() instanceof ServerPlayer player && isLocked(player)) {
			event.setCanceled(true);
			event.setCancellationResult(InteractionResult.FAIL);
		}
	}

	public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
		if (event.getEntity() instanceof ServerPlayer player && isLocked(player)) {
			event.setCanceled(true);
			event.setCancellationResult(InteractionResult.FAIL);
		}
	}

	public void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
		if (event.getEntity() instanceof ServerPlayer player && isLocked(player)) {
			event.setCanceled(true);
			event.setCancellationResult(InteractionResult.FAIL);
		}
	}

	private void ensureMessagingAttached() {
		if (messagingAttached || messagingBus == null) {
			if (messagingBus == null) {
				logger.warn("Thiếu NeoForgePluginMessagingBus, LunaAuth Backend NeoForge sẽ không thể đồng bộ trạng thái xác thực.");
			}
			return;
		}

		messagingBus.registerOutgoing(AuthChannels.COMMAND_REQUEST);
		messagingBus.registerIncoming(AuthChannels.ADMIN_REQUEST, context -> {
			PluginMessageReader reader = PluginMessageReader.of(context.payload());
			String action = reader.readUtf();
			if (!"set_spawn".equals(action) || !(context.source() instanceof ServerPlayer source)) {
				return PluginMessageDispatchResult.HANDLED;
			}

			UUID targetUuid = reader.readUuid();
			String actorName = reader.readUtf();
			server.execute(() -> handleAdminSetSpawn(source, targetUuid, actorName));
			return PluginMessageDispatchResult.HANDLED;
		});
		messagingBus.registerIncoming(AuthChannels.AUTH_STATE, context -> {
			if (!(context.source() instanceof ServerPlayer source)) {
				return PluginMessageDispatchResult.HANDLED;
			}
			PluginMessageReader reader = PluginMessageReader.of(context.payload());
			String action = reader.readUtf();
			if (!"state".equals(action)) {
				return PluginMessageDispatchResult.HANDLED;
			}

			UUID playerUuid = reader.readUuid();
			boolean authenticated = reader.readBoolean();
			boolean needsRegister = reader.readBoolean();
			boolean premiumNameCandidate = reader.readBoolean();
			boolean hasModePreference = reader.readBoolean();
			String username = reader.readUtf();
			server.execute(() -> handleAuthState(source, playerUuid, authenticated, needsRegister, premiumNameCandidate, hasModePreference, username));
			return PluginMessageDispatchResult.HANDLED;
		});
		messagingBus.registerIncoming(AuthChannels.COMMAND_RESPONSE, context -> {
			if (!(context.source() instanceof ServerPlayer source)) {
				return PluginMessageDispatchResult.HANDLED;
			}
			PluginMessageReader reader = PluginMessageReader.of(context.payload());
			String action = reader.readUtf();
			if (!"auth_result".equals(action) && !"auth_result_v2".equals(action)) {
				return PluginMessageDispatchResult.HANDLED;
			}

			boolean v2Payload = "auth_result_v2".equals(action);
			UUID playerUuid = reader.readUuid();
			boolean success = reader.readBoolean();
			boolean authenticated = reader.readBoolean();
			boolean needsRegister = reader.readBoolean();
			boolean premiumNameCandidate = reader.readBoolean();
			boolean hasModePreference = reader.readBoolean();
			String authMethod = v2Payload ? reader.readUtf() : "default";
			String message = reader.readUtf();
			server.execute(() -> handleCommandResponse(source, playerUuid, success, authenticated, needsRegister, premiumNameCandidate, hasModePreference, authMethod, message));
			return PluginMessageDispatchResult.HANDLED;
		});

		messagingAttached = true;
		logger.audit("Đã gắn LunaAuth Backend NeoForge vào plugin messaging bus.");
	}

	private void handleAdminSetSpawn(ServerPlayer source, UUID targetUuid, String actorName) {
		flow("RX admin_request action=set_spawn source=" + source.getGameProfile().getName() + " sourceUuid=" + source.getUUID() + " targetUuid=" + targetUuid + " actor=" + actorName);
		if (!source.getUUID().equals(targetUuid)) {
			flow("Ignore admin_request set_spawn because sourceUuid!=targetUuid source=" + source.getUUID() + " target=" + targetUuid);
			return;
		}

		if (spawnService.setSpawn(source, actorName)) {
			source.sendSystemMessage(Component.literal("✔ Điểm auth-spawn đã được cập nhật bởi " + actorName + "."));
		} else {
			source.sendSystemMessage(Component.literal("❌ Không thể cập nhật auth-spawn tại vị trí hiện tại."));
		}
	}

	private void handleAuthState(
		ServerPlayer source,
		UUID playerUuid,
		boolean authenticated,
		boolean needsRegister,
		boolean premiumNameCandidate,
		boolean hasModePreference,
		String username
	) {
		flow("RX auth_state action=state source=" + source.getGameProfile().getName() + " sourceUuid=" + source.getUUID() + " payloadUuid=" + playerUuid + " authenticated=" + authenticated + " needsRegister=" + needsRegister + " premiumName=" + premiumNameCandidate + " hasModePreference=" + hasModePreference + " username=" + username);
		if (!source.getUUID().equals(playerUuid)) {
			flow("Ignore auth_state due to UUID mismatch source=" + source.getUUID() + " payload=" + playerUuid);
			return;
		}

		updateModeSelectorEligibility(source, premiumNameCandidate, hasModePreference);
		BackendAuthStateRegistry.AuthState previous = stateRegistry.state(playerUuid);
		if (authenticated) {
			stateRegistry.markAuthenticated(playerUuid);
			releaseAuthLockIfNeeded(source);
			hidePrompt(source);
			flow("StateTransition uuid=" + playerUuid + " from=" + previous + " to=" + stateRegistry.state(playerUuid) + " reason=AUTH_STATE");
			return;
		}

		stateRegistry.markUnauthenticated(playerUuid, needsRegister);
		syncAuthLockState(source);
		flow("StateTransition uuid=" + playerUuid + " from=" + previous + " to=" + stateRegistry.state(playerUuid) + " reason=AUTH_STATE");
	}

	private void handleCommandResponse(
		ServerPlayer source,
		UUID playerUuid,
		boolean success,
		boolean authenticated,
		boolean needsRegister,
		boolean premiumNameCandidate,
		boolean hasModePreference,
		String authMethod,
		String message
	) {
		flow("RX command_response source=" + source.getGameProfile().getName() + " sourceUuid=" + source.getUUID() + " payloadUuid=" + playerUuid + " success=" + success + " authenticated=" + authenticated + " needsRegister=" + needsRegister + " premiumName=" + premiumNameCandidate + " hasModePreference=" + hasModePreference + " authMethod=" + authMethod + " message=" + message);
		if (!source.getUUID().equals(playerUuid)) {
			flow("Ignore command_response due to UUID mismatch source=" + source.getUUID() + " payload=" + playerUuid);
			return;
		}

		updateModeSelectorEligibility(source, premiumNameCandidate, hasModePreference);
		BackendAuthStateRegistry.AuthState previous = stateRegistry.state(playerUuid);
		if (!success || !authenticated) {
			source.sendSystemMessage(Component.literal(message == null ? "" : message));
		}
		if (authenticated) {
			stateRegistry.markAuthenticated(playerUuid);
			releaseAuthLockIfNeeded(source);
			hidePrompt(source);
			flow("StateTransition uuid=" + playerUuid + " from=" + previous + " to=" + stateRegistry.state(playerUuid) + " reason=COMMAND_RESPONSE");
			if (success) {
				sendAuthenticatedFeedback(source, authMethod);
			}
			return;
		}

		stateRegistry.markUnauthenticated(playerUuid, needsRegister);
		syncAuthLockState(source);
		flow("StateTransition uuid=" + playerUuid + " from=" + previous + " to=" + stateRegistry.state(playerUuid) + " reason=COMMAND_RESPONSE");
	}

	private PromptSet promptSet(AuthBackendNeoForgeConfig.PromptTemplate template) {
		return new PromptSet(
			NeoForgeTextComponents.mini(server, template.bossbar()),
			NeoForgeTextComponents.mini(server, template.actionbar()),
			NeoForgeTextComponents.mini(server, template.chat())
		);
	}

	private PromptSet promptFor(UUID playerId) {
		BackendAuthStateRegistry.AuthState state = stateRegistry.state(playerId);
		if (state.authenticated()) {
			return pendingPrompt;
		}
		return switch (state.promptMode()) {
			case REGISTER -> registerPrompt;
			case LOGIN -> loginPrompt;
			case PENDING -> pendingPrompt;
		};
	}

	private void showPrompt(ServerPlayer player) {
		PromptSet prompt = promptFor(player.getUUID());
		if (prompt == pendingPrompt) {
			requestStateSyncIfDue(player, "PENDING_PROMPT_LOOP");
			hidePrompt(player);
			flow("SkipPrompt player=" + player.getGameProfile().getName() + " uuid=" + player.getUUID() + " reason=PENDING");
			return;
		}

		ServerBossEvent bossbar = activeBossbars.computeIfAbsent(player.getUUID(), ignored -> new ServerBossEvent(
			prompt.bossbar(),
			net.minecraft.world.BossEvent.BossBarColor.YELLOW,
			net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS
		));
		bossbar.setName(prompt.bossbar());
		bossbar.addPlayer(player);
		if (shouldRunIfDue(lastPromptActionbarLog, player.getUUID(), System.currentTimeMillis(), PROMPT_ACTIONBAR_THROTTLE_MS)) {
			player.displayClientMessage(prompt.actionbar(), true);
		}
	}

	private void hidePrompt(ServerPlayer player) {
		ServerBossEvent bossbar = activeBossbars.remove(player.getUUID());
		if (bossbar != null) {
			bossbar.removePlayer(player);
		}
	}

	private void syncAuthLockState(ServerPlayer player) {
		UUID playerId = player.getUUID();
		if (stateRegistry.isAuthenticated(playerId)) {
			releaseAuthLockIfNeeded(player);
			return;
		}

		lockedPlayers.computeIfAbsent(playerId, ignored -> {
			StoredLocation anchor = config.teleportToSpawnOnConnect() && spawnService.hasSpawn()
				? spawnService.spawnLocation()
				: StoredLocation.capture(player);
			LockedPlayerState state = new LockedPlayerState(anchor, player.isInvulnerable(), player.gameMode.getGameModeForPlayer());
			applyAuthLock(player, state);
			return state;
		});
	}

	private void applyAuthLock(ServerPlayer player, LockedPlayerState state) {
		player.setInvulnerable(true);
		player.setDeltaMovement(0D, 0D, 0D);
		refreshLockEffects(player);
		enforceLockedPosition(player);
	}

	private void releaseAuthLockIfNeeded(ServerPlayer player) {
		LockedPlayerState previous = lockedPlayers.remove(player.getUUID());
		if (previous == null) {
			return;
		}
		player.setInvulnerable(previous.wasInvulnerable());
		player.removeEffect(MobEffects.BLINDNESS);
		player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
		player.removeEffect(MobEffects.JUMP);
		player.setDeltaMovement(0D, 0D, 0D);
		flow("ReleaseAuthLock player=" + player.getGameProfile().getName() + " uuid=" + player.getUUID());
	}

	private void keepProtectedState(ServerPlayer player) {
		player.setInvulnerable(true);
		player.clearFire();
		player.setAirSupply(player.getMaxAirSupply());
		player.fallDistance = 0F;
		player.setRemainingFireTicks(0);
	}

	private void refreshLockEffects(ServerPlayer player) {
		player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, BLINDNESS_DURATION_TICKS, 0, false, false, false));
		player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, LOCK_EFFECT_DURATION_TICKS, 10, false, false, false));
		player.addEffect(new MobEffectInstance(MobEffects.JUMP, LOCK_EFFECT_DURATION_TICKS, 128, false, false, false));
	}

	private void enforceLockedPosition(ServerPlayer player) {
		LockedPlayerState state = lockedPlayers.get(player.getUUID());
		if (state == null || state.anchor() == null) {
			return;
		}

		StoredLocation anchor = state.anchor();
		boolean moved = !player.serverLevel().dimension().equals(anchor.dimension())
			|| distanceSquared(player, anchor) > 0.04D;
		if (moved) {
			spawnService.teleport(player, anchor);
			throttledFlow(lastMoveRestrictionLog, player.getUUID(), "BlockMove player=" + player.getGameProfile().getName() + " uuid=" + player.getUUID() + " state=" + stateRegistry.state(player.getUUID()));
		}
		player.setDeltaMovement(0D, 0D, 0D);
	}

	private double distanceSquared(ServerPlayer player, StoredLocation anchor) {
		double dx = player.getX() - anchor.x();
		double dy = player.getY() - anchor.y();
		double dz = player.getZ() - anchor.z();
		return (dx * dx) + (dy * dy) + (dz * dz);
	}

	private boolean isLocked(Player player) {
		return player instanceof ServerPlayer serverPlayer && !stateRegistry.isAuthenticated(serverPlayer.getUUID());
	}

	private void requestStateSyncIfDue(ServerPlayer player, String reason) {
		if (shouldRunIfDue(lastSyncRequestLog, player.getUUID(), System.currentTimeMillis(), SYNC_REQUEST_THROTTLE_MS)) {
			requestStateSync(player);
			flow("RequestStateSync player=" + player.getGameProfile().getName() + " uuid=" + player.getUUID() + " reason=" + reason);
		}
	}

	private void requestStateSync(ServerPlayer player) {
		sendCommandRequest(player, writer -> {
			writer.writeUtf("sync_state");
			writer.writeUuid(player.getUUID());
			writer.writeUtf(player.getGameProfile().getName());
		});
	}

	private boolean sendCommandRequest(ServerPlayer player, java.util.function.Consumer<dev.belikhun.luna.core.api.messaging.PluginMessageWriter> payloadWriter) {
		ensureMessagingAttached();
		if (messagingBus == null) {
			return false;
		}
		boolean sent = messagingBus.send(player, AuthChannels.COMMAND_REQUEST, writer -> payloadWriter.accept(writer));
		flow("TX command_request player=" + player.getGameProfile().getName() + " uuid=" + player.getUUID() + " sent=" + sent + " at=" + Instant.now());
		return sent;
	}

	private boolean sendProbePreference(ServerPlayer player, String mode) {
		boolean sent = sendCommandRequest(player, writer -> {
			writer.writeUtf("set_probe_preference");
			writer.writeUuid(player.getUUID());
			writer.writeUtf(player.getGameProfile().getName());
			writer.writeUtf(mode);
		});
		if (!sent) {
			player.sendSystemMessage(Component.literal("❌ Không thể gửi lựa chọn xác thực lên proxy. Vui lòng thử lại sau vài giây."));
		}
		return sent;
	}

	private void updateModeSelectorEligibility(ServerPlayer player, boolean premiumNameCandidate, boolean hasModePreference) {
		UUID playerId = player.getUUID();
		modeSelectorEligible.put(playerId, premiumNameCandidate);
		modePreferencePresent.put(playerId, hasModePreference);
		if (!premiumNameCandidate) {
			modeSelectedPlayers.add(playerId);
			closeModeSelector(playerId);
			flow("ModeSelectorEligibility player=" + player.getGameProfile().getName() + " uuid=" + playerId + " premiumName=false modePreference=" + hasModePreference);
			return;
		}

		if (hasModePreference) {
			modeSelectedPlayers.add(playerId);
			closeModeSelector(playerId);
			flow("ModeSelectorEligibility player=" + player.getGameProfile().getName() + " uuid=" + playerId + " premiumName=true modePreference=true -> skip selector");
			return;
		}

		if (stateRegistry.isAuthenticated(playerId)) {
			return;
		}

		modeSelectedPlayers.remove(playerId);
		nextModeSelectorOpenAt.putIfAbsent(playerId, System.currentTimeMillis() + MODE_SELECTOR_DELAY_MS);
		showModeSelectorIfDue(player, System.currentTimeMillis(), false);
		flow("ModeSelectorEligibility player=" + player.getGameProfile().getName() + " uuid=" + playerId + " premiumName=true modePreference=false");
	}

	private void showModeSelectorIfDue(ServerPlayer player, long now, boolean force) {
		UUID playerId = player.getUUID();
		if (!shouldShowModeSelector(playerId)) {
			return;
		}
		long openAt = nextModeSelectorOpenAt.getOrDefault(playerId, now + MODE_SELECTOR_DELAY_MS);
		if (!force && now < openAt) {
			return;
		}
		if (openModeMenus.containsKey(playerId) && player.containerMenu == openModeMenus.get(playerId)) {
			return;
		}

		openModeSelector(player);
	}

	private boolean shouldShowModeSelector(UUID playerId) {
		if (!config.modeSelectorGuiEnabled()) {
			return false;
		}
		if (stateRegistry.isAuthenticated(playerId)) {
			return false;
		}
		if (modeSelectedPlayers.contains(playerId)) {
			return false;
		}
		if (Boolean.TRUE.equals(modePreferencePresent.get(playerId))) {
			return false;
		}
		return Boolean.TRUE.equals(modeSelectorEligible.get(playerId));
	}

	private void openModeSelector(ServerPlayer player) {
		UUID playerId = player.getUUID();
		closeModeSelector(playerId);
		player.openMenu(new SimpleMenuProvider((containerId, inventory, ignoredPlayer) -> {
			AuthModeSelectorMenu menu = new AuthModeSelectorMenu(containerId, inventory, () -> handleModeSelectorClosed(playerId));
			openModeMenus.put(playerId, menu);
			renderModeSelector(player, menu);
			menu.broadcastChanges();
			return menu;
		}, Component.literal(MODE_SELECTOR_TITLE)));
		flow("ShowModeSelector player=" + player.getGameProfile().getName() + " uuid=" + playerId);
	}

	private void renderModeSelector(ServerPlayer player, AuthModeSelectorMenu menu) {
		menu.clearTopSlots();
		boolean remember = modeRememberSelection.getOrDefault(player.getUUID(), false);
		for (int slot : List.of(0, 1, 2, 6, 8)) {
			menu.setTopSlot(slot, itemStack("gray_stained_glass_pane", "<dark_gray>•</dark_gray>", List.of("<gray> </gray>")), null);
		}
		menu.setTopSlot(4, itemStack("book", "<yellow><b>ℹ Chọn Chế Độ Đăng Nhập</b></yellow>", List.of(
			"<gray>Premium hoặc Offline.</gray>",
			"<gray>Nút bên phải bật/tắt ghi nhớ.</gray>",
			"",
			"<gold>⚠ Hãy chọn đúng để tránh lỗi phiên.</gold>"
		)), null);
		menu.setTopSlot(SLOT_PREMIUM, itemStack("nether_star", "<green><b>★ Tài Khoản Premium</b></green>", List.of(
			"<gray>Dùng launcher Microsoft.</gray>",
			"<gray>Sẽ probe xác thực online.</gray>",
			"",
			"<yellow>▶ Ấn để chọn.</yellow>"
		)), () -> chooseMode(player, remember ? "online_forever" : "online", remember, true));
		menu.setTopSlot(SLOT_OFFLINE, itemStack("iron_bars", "<aqua><b>⬤ Tài Khoản Offline</b></aqua>", List.of(
			"<gray>Dùng launcher cracked.</gray>",
			"<gray>Không ép xác thực online.</gray>",
			"",
			"<yellow>▶ Ấn để chọn.</yellow>"
		)), () -> chooseMode(player, remember ? "offline_forever" : "offline", remember, false));
		menu.setTopSlot(SLOT_REMEMBER, rememberToggleItem(remember), () -> toggleRemember(player));
	}

	private void chooseMode(ServerPlayer player, String mode, boolean remember, boolean premium) {
		if (!sendProbePreference(player, mode)) {
			modeSelectedPlayers.remove(player.getUUID());
			player.displayClientMessage(Component.literal("Không gửi được lựa chọn. Vui lòng thử lại."), true);
			flow("ModeSelectorChoiceSendFailed player=" + player.getGameProfile().getName() + " uuid=" + player.getUUID() + " mode=" + mode);
			return;
		}

		modeSelectedPlayers.add(player.getUUID());
		nextModeSelectorOpenAt.remove(player.getUUID());
		if (premium) {
			player.sendSystemMessage(Component.literal(remember
				? "Đã chọn Premium (ghi nhớ vĩnh viễn). Bạn sẽ được kết nối lại để xác thực online."
				: "Đã chọn Premium (24h). Bạn sẽ được kết nối lại để xác thực online."));
		} else {
			player.sendSystemMessage(Component.literal(remember
				? "Đã chọn Offline (ghi nhớ vĩnh viễn). Tiếp tục đăng nhập bằng mật khẩu server."
				: "Đã chọn Offline (24h). Tiếp tục đăng nhập bằng mật khẩu server."));
		}
		closeModeSelector(player.getUUID());
		player.closeContainer();
		flow("ModeSelectorChoice player=" + player.getGameProfile().getName() + " uuid=" + player.getUUID() + " mode=" + mode);
	}

	private void toggleRemember(ServerPlayer player) {
		UUID playerId = player.getUUID();
		boolean next = !modeRememberSelection.getOrDefault(playerId, false);
		modeRememberSelection.put(playerId, next);
		AuthModeSelectorMenu menu = openModeMenus.get(playerId);
		if (menu != null) {
			menu.setTopSlot(SLOT_REMEMBER, rememberToggleItem(next), () -> toggleRemember(player));
			menu.broadcastChanges();
		}
		player.displayClientMessage(Component.literal(next ? "Đã bật ghi nhớ lựa chọn vĩnh viễn." : "Đã tắt ghi nhớ vĩnh viễn (chỉ 24h)."), true);
	}

	private void handleModeSelectorClosed(UUID playerId) {
		openModeMenus.remove(playerId);
	}

	private void closeModeSelector(UUID playerId) {
		AuthModeSelectorMenu menu = openModeMenus.remove(playerId);
		if (menu != null) {
			menu.suppressCloseCallbackOnce();
		}
		ServerPlayer player = server.getPlayerList().getPlayer(playerId);
		if (player != null && player.containerMenu == menu) {
			player.closeContainer();
		}
	}

	private ItemStack rememberToggleItem(boolean remember) {
		return itemStack(
			remember ? "lime_dye" : "gray_dye",
			remember ? "<gold><b>🔔 Ghi Nhớ: BẬT</b></gold>" : "<gray><b>🔔 Ghi Nhớ: TẮT</b></gray>",
			List.of(
				remember ? "<gray>Lựa chọn sẽ được giữ vĩnh viễn.</gray>" : "<gray>Lựa chọn chỉ có hiệu lực 24 giờ.</gray>",
				"<yellow>▶ Ấn để chuyển trạng thái.</yellow>"
			)
		);
	}

	private ItemStack itemStack(String materialName, String title, List<String> loreLines) {
		Item item = resolveItem(materialName);
		ItemStack stack = new ItemStack(item == null ? Items.BARRIER : item);
		stack.set(DataComponents.CUSTOM_NAME, NeoForgeTextComponents.mini(server, safe(title)));
		if (loreLines != null && !loreLines.isEmpty()) {
			List<Component> lore = new ArrayList<>();
			for (String line : loreLines) {
				lore.add(NeoForgeTextComponents.mini(server, safe(line)));
			}
			stack.set(DataComponents.LORE, new ItemLore(lore));
		}
		return stack;
	}

	private Item resolveItem(String materialName) {
		if (materialName == null || materialName.isBlank()) {
			return null;
		}
		String normalized = materialName.trim().toLowerCase(Locale.ROOT);
		String namespace = "minecraft";
		String path = normalized;
		int separator = normalized.indexOf(':');
		if (separator > 0 && separator < normalized.length() - 1) {
			namespace = normalized.substring(0, separator);
			path = normalized.substring(separator + 1);
		}
		ResourceLocation identifier = ResourceLocation.fromNamespaceAndPath(namespace, path);
		return net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(identifier)
			? net.minecraft.core.registries.BuiltInRegistries.ITEM.get(identifier)
			: null;
	}

	private void sendAuthenticatedFeedback(ServerPlayer player, String authMethod) {
		String normalizedMethod = normalizeAuthMethod(authMethod);
		AuthBackendNeoForgeConfig.MethodFeedback feedback = config.authenticatedPrompt().byMethod().get(normalizedMethod);
		String chat = feedback == null || feedback.chat() == null || feedback.chat().isBlank()
			? config.authenticatedPrompt().chat()
			: feedback.chat();
		String actionbar = feedback == null || feedback.actionbar() == null || feedback.actionbar().isBlank()
			? config.authenticatedPrompt().actionbar()
			: feedback.actionbar();
		player.sendSystemMessage(NeoForgeTextComponents.mini(server, chat));
		player.displayClientMessage(NeoForgeTextComponents.mini(server, actionbar), true);
		flow("SendAuthenticatedFeedback player=" + player.getGameProfile().getName() + " uuid=" + player.getUUID() + " authMethod=" + normalizedMethod);
	}

	private String normalizeAuthMethod(String authMethod) {
		if (authMethod == null || authMethod.isBlank()) {
			return "default";
		}
		String normalized = authMethod.trim().toLowerCase(Locale.ROOT);
		if ("quick-login".equals(normalized) || "quickauth".equals(normalized)) {
			return "quick_login";
		}
		if ("session-resume".equals(normalized) || "session_resume".equals(normalized)) {
			return "session_resume";
		}
		if ("login".equals(normalized) || "password-login".equals(normalized)) {
			return "password_login";
		}
		if ("register".equals(normalized) || "register-password".equals(normalized)) {
			return "register_password";
		}
		return normalized;
	}

	private boolean shouldRunIfDue(Map<UUID, Long> throttleMap, UUID playerId, long now, long throttleMillis) {
		Long previous = throttleMap.get(playerId);
		if (previous != null && now - previous < throttleMillis) {
			return false;
		}
		throttleMap.put(playerId, now);
		return true;
	}

	private void throttledFlow(Map<UUID, Long> throttleMap, UUID playerId, String message) {
		if (!config.authFlowLogsEnabled()) {
			return;
		}
		long now = System.currentTimeMillis();
		Long previous = throttleMap.get(playerId);
		if (previous != null && now - previous < RESTRICTION_LOG_THROTTLE_MS) {
			return;
		}
		throttleMap.put(playerId, now);
		logger.audit(message);
	}

	private void flow(String message) {
		if (!config.authFlowLogsEnabled()) {
			return;
		}
		logger.audit(message);
	}

	private ServerPlayer playerFrom(CommandSourceStack source) {
		return source == null || !(source.getEntity() instanceof ServerPlayer player) ? null : player;
	}

	private String safe(String value) {
		return value == null ? "" : value;
	}

	private record PromptSet(Component bossbar, Component actionbar, Component chat) {
	}

	private record LockedPlayerState(StoredLocation anchor, boolean wasInvulnerable, GameType gameType) {
	}
}
