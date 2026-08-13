package dev.belikhun.luna.auth.backend.mc.runtime;

import dev.belikhun.luna.auth.backend.mc.compat.AuthPlayerCompat;
import dev.belikhun.luna.auth.backend.mc.config.AuthBackendConfig;
import dev.belikhun.luna.auth.backend.mc.service.BackendAuthSpawnService;
import dev.belikhun.luna.auth.backend.mc.service.BackendAuthSpawnService.StoredLocation;
import dev.belikhun.luna.core.api.auth.AuthChannels;
import dev.belikhun.luna.core.api.auth.AuthMessages;
import dev.belikhun.luna.core.api.auth.BackendAuthStateRegistry;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;
import dev.belikhun.luna.core.mc.compat.ItemDecor;
import dev.belikhun.luna.core.mc.compat.ItemLookup;
import dev.belikhun.luna.core.mc.auth.AuthLobbyItems;
import dev.belikhun.luna.core.mc.text.LunaTextComponents;
import dev.belikhun.luna.core.mc.ui.LunaChestMenu;
import dev.belikhun.luna.core.mc.ui.LunaChestMenuBase;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

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
import java.util.function.Consumer;

/**
 * Everything a player may not do until the proxy says they are authenticated.
 *
 * This is the mod-loader half of the same restriction paper applies: hold
 * the player at the auth spawn, blind and immobilise them, refuse chat, commands,
 * interaction and damage, show the bossbar and actionbar prompts, and - for a
 * name that could be a premium account - offer the mode picker. The state itself
 * is not decided here; it arrives from the proxy over
 * {@link AuthChannels} and this class only enforces it.
 *
 * Fabric, forge and neoforge each hand out their hooks in a different shape, so
 * the entry points below take a player and answer a decision rather than a
 * loader's event. Each platform's bootstrap adapts its own events onto them,
 * which is what lets one cage serve all three; the three hooks fabric has no event
 * for reach the same decisions through
 * {@link dev.belikhun.luna.auth.backend.mc.runtime.AuthLockHooks}.
 */
public final class AuthRestrictionController {
	private static final long RESTRICTION_LOG_THROTTLE_MS = 3000L;
	private static final long SYNC_REQUEST_THROTTLE_MS = 1500L;
	private static final long PROMPT_ACTIONBAR_THROTTLE_MS = 1500L;
	private static final long LOCK_EFFECT_REFRESH_THROTTLE_MS = 3000L;
	private static final long MODE_SELECTOR_DELAY_MS = 1500L;
	private static final int BLINDNESS_DURATION_TICKS = 600;
	private static final int LOCK_EFFECT_DURATION_TICKS = 220;
	private static final int MODE_SELECTOR_ROWS = 1;
	private static final int SLOT_PREMIUM = AuthMessages.MODE_SELECTOR_SLOT_PREMIUM;
	private static final int SLOT_OFFLINE = AuthMessages.MODE_SELECTOR_SLOT_OFFLINE;
	private static final int SLOT_REMEMBER = AuthMessages.MODE_SELECTOR_SLOT_REMEMBER;

	private final MinecraftServer server;
	private final LunaLogger logger;
	private final AuthBackendConfig config;
	private final BackendAuthSpawnService spawnService;
	private final PluginMessageBus<ServerPlayer, ServerPlayer> messagingBus;
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
	private final ConcurrentMap<UUID, BackendAuthStateRegistry.PromptMode> announcedPromptMode;
	private final ConcurrentMap<UUID, Long> lastLockEffectRefreshLog;
	private final ConcurrentMap<UUID, Boolean> modeSelectorEligible;
	private final ConcurrentMap<UUID, Boolean> modePreferencePresent;
	private final ConcurrentMap<UUID, Boolean> modeRememberSelection;
	private final ConcurrentMap<UUID, Long> nextModeSelectorOpenAt;
	private final ConcurrentMap<UUID, LunaChestMenuBase> openModeMenus;
	private final ConcurrentMap<UUID, LockedPlayerState> lockedPlayers;
	private final Set<UUID> modeSelectedPlayers;
	private final Set<UUID> lobbyItemsApplied;
	private volatile boolean messagingAttached;

	public AuthRestrictionController(
		MinecraftServer server,
		LunaLogger logger,
		AuthBackendConfig config,
		BackendAuthSpawnService spawnService,
		PluginMessageBus<ServerPlayer, ServerPlayer> messagingBus
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
		this.announcedPromptMode = new ConcurrentHashMap<>();
		this.lastLockEffectRefreshLog = new ConcurrentHashMap<>();
		this.modeSelectorEligible = new ConcurrentHashMap<>();
		this.modePreferencePresent = new ConcurrentHashMap<>();
		this.modeRememberSelection = new ConcurrentHashMap<>();
		this.nextModeSelectorOpenAt = new ConcurrentHashMap<>();
		this.openModeMenus = new ConcurrentHashMap<>();
		this.lockedPlayers = new ConcurrentHashMap<>();
		this.modeSelectedPlayers = ConcurrentHashMap.newKeySet();
		this.lobbyItemsApplied = ConcurrentHashMap.newKeySet();
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

		for (LunaChestMenuBase menu : openModeMenus.values()) {
			menu.suppressCloseCallbackOnce();
		}

		activeBossbars.clear();
		openModeMenus.clear();
		lastMoveRestrictionLog.clear();
		lastCommandRestrictionLog.clear();
		lastChatRestrictionLog.clear();
		lastSyncRequestLog.clear();
		lastPromptActionbarLog.clear();
		announcedPromptMode.clear();
		lastLockEffectRefreshLog.clear();
		modeSelectorEligible.clear();
		modePreferencePresent.clear();
		modeRememberSelection.clear();
		nextModeSelectorOpenAt.clear();
		lockedPlayers.clear();
		modeSelectedPlayers.clear();
		lobbyItemsApplied.clear();
	}

	public int executeLogin(CommandSourceStack source, String password) {
		ServerPlayer player = playerFrom(source);

		if (player == null) {
			source.sendSystemMessage(mini(AuthMessages.notAPlayer()));
			return 0;
		}

		if (password == null || password.isBlank()) {
			player.sendSystemMessage(mini(AuthMessages.loginUsage()));
			return 0;
		}

		if (!sendCommandRequest(player, writer -> {
			writer.writeUtf("login");
			writer.writeUuid(player.getUUID());
			writer.writeUtf(player.getName().getString());
			writer.writeUtf(password);
		})) {
			player.sendSystemMessage(mini(AuthMessages.commandSendFailed()));
			return 0;
		}

		return 1;
	}

	public int executeRegister(CommandSourceStack source, String password, String confirm) {
		ServerPlayer player = playerFrom(source);

		if (player == null) {
			source.sendSystemMessage(mini(AuthMessages.notAPlayer()));
			return 0;
		}

		if (password == null || password.isBlank() || confirm == null || confirm.isBlank()) {
			player.sendSystemMessage(mini(AuthMessages.registerUsage()));
			return 0;
		}

		if (!sendCommandRequest(player, writer -> {
			writer.writeUtf("register");
			writer.writeUuid(player.getUUID());
			writer.writeUtf(player.getName().getString());
			writer.writeUtf(password);
			writer.writeUtf(confirm);
		})) {
			player.sendSystemMessage(mini(AuthMessages.commandSendFailed()));
			return 0;
		}

		return 1;
	}

	public void onPlayerLoggedIn(ServerPlayer player) {
		if (player == null) {
			return;
		}

		UUID playerId = player.getUUID();

		if (!stateRegistry.hasState(playerId)) {
			stateRegistry.markUnauthenticated(playerId);
			flow("Join player=" + player.getName().getString() + " uuid=" + playerId + " stateInit=PENDING");
		} else {
			flow("Join player=" + player.getName().getString() + " uuid=" + playerId + " statePreserved=" + stateRegistry.state(playerId));
		}

		requestStateSync(player);

		// the pending prompt says "hold on, we are asking the proxy", which is not
		// worth a chat line; the real one is sent by announcePrompt once the proxy
		// has answered, which on a fresh join is always after this point
		announcePrompt(player);

		syncAuthLockState(player);
		showModeSelectorIfDue(player, System.currentTimeMillis(), true);
	}

	public void onPlayerLoggedOut(ServerPlayer player) {
		if (player == null) {
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
		lobbyItemsApplied.remove(playerId);
		lastMoveRestrictionLog.remove(playerId);
		lastCommandRestrictionLog.remove(playerId);
		lastChatRestrictionLog.remove(playerId);
		lastSyncRequestLog.remove(playerId);
		lastPromptActionbarLog.remove(playerId);
		announcedPromptMode.remove(playerId);
		lastLockEffectRefreshLog.remove(playerId);
		flow("Quit clear state player=" + player.getName().getString() + " uuid=" + playerId);
	}

	/**
	 * The per-player tick, driven off the server tick.
	 *
	 * The forge family fires one event per player; fabric's lifecycle events stop at the
	 * server, so the player list is walked here instead. The body, including the
	 * every-20-ticks cadence read off the player's own tick count, is the same.
	 */
	public void onServerTick() {
		for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
			onPlayerTick(player);
		}
	}

	private void onPlayerTick(ServerPlayer player) {
		if (player == null || player.level().isClientSide()) {
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

	/** False when the message must not be sent, having told the player why. */
	public boolean allowChat(ServerPlayer player) {
		if (player == null || stateRegistry.isAuthenticated(player.getUUID())) {
			return true;
		}

		player.sendSystemMessage(promptFor(player.getUUID()).chat());
		throttledFlow(lastChatRestrictionLog, player.getUUID(), "BlockChat player=" + player.getName().getString() + " uuid=" + player.getUUID() + " state=" + stateRegistry.state(player.getUUID()));

		return false;
	}

	/**
	 * False when the command must not run, having told the player why.
	 *
	 * The allow-list is what lets an unauthenticated player type the very commands
	 * that authenticate them; everything else is refused with the same prompt chat
	 * gets.
	 */
	public boolean allowCommand(ServerPlayer player, String rawCommand) {
		if (player == null) {
			return true;
		}

		UUID playerId = player.getUUID();

		if (stateRegistry.isAuthenticated(playerId)) {
			return true;
		}

		String raw = rawCommand == null ? "" : rawCommand.trim();

		if (raw.startsWith("/")) {
			raw = raw.substring(1).trim();
		}

		if (raw.isEmpty()) {
			player.sendSystemMessage(promptFor(playerId).chat());
			throttledFlow(lastCommandRestrictionLog, playerId, "BlockCommand player=" + player.getName().getString() + " uuid=" + playerId + " command=<empty> state=" + stateRegistry.state(playerId));
			return false;
		}

		String root = raw.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);

		if (config.allowedCommands().contains(root)) {
			flow("AllowCommand player=" + player.getName().getString() + " uuid=" + playerId + " command=" + root);
			return true;
		}

		player.sendSystemMessage(promptFor(playerId).chat());
		throttledFlow(lastCommandRestrictionLog, playerId, "BlockCommand player=" + player.getName().getString() + " uuid=" + playerId + " command=" + root + " state=" + stateRegistry.state(playerId));

		return false;
	}

	/** False when this player may not break, place, use or interact right now. */
	public boolean allowInteraction(Player player) {
		if (isLocked(player)) {
			return false;
		}

		if (!(player instanceof ServerPlayer serverPlayer) || !isProtectedLobbyItem(serverPlayer.getMainHandItem())) {
			return true;
		}

		AuthLobbyItems.openServerSelector(server, serverPlayer);
		flow("LobbyItemUse player=" + serverPlayer.getName().getString() + " uuid=" + serverPlayer.getUUID() + " key=" + AuthMessages.LOBBY_SELECTOR_KEY);

		return false;
	}

	/** Whether a drop must be refused: the player is locked, or it is a lobby item. */
	public boolean refuseDrop(ServerPlayer player, ItemStack stack) {
		return isLocked(player) || isProtectedLobbyItem(stack);
	}

	/** A lobby item may not be dropped, and is what opens the selector. */
	private boolean isProtectedLobbyItem(ItemStack stack) {
		return config.lobbyItemsEnabled() && AuthLobbyItems.isServerSelector(stack);
	}

	/**
	 * False when the damage must not land.
	 *
	 * Both directions are covered, as on paper: a locked player takes none, and
	 * deals none either.
	 */
	public boolean allowDamage(Entity target, Entity attacker) {
		if (target instanceof ServerPlayer player && isLocked(player)) {
			return false;
		}

		return !(attacker instanceof ServerPlayer player && isLocked(player));
	}

	/** Put back an item a locked player managed to throw. */
	public void restoreTossedItem(ServerPlayer player, ItemStack tossed) {
		if (player == null || tossed == null || tossed.isEmpty()) {
			return;
		}

		player.getInventory().add(tossed.copy());
	}

	public boolean isLocked(Player player) {
		return player instanceof ServerPlayer serverPlayer && !stateRegistry.isAuthenticated(serverPlayer.getUUID());
	}

	private void ensureMessagingAttached() {
		if (messagingAttached || messagingBus == null) {
			if (messagingBus == null) {
				logger.warn("Thiếu PluginMessageBus, LunaAuth Backend sẽ không thể đồng bộ trạng thái xác thực.");
			}

			return;
		}

		messagingBus.registerOutgoing(AuthChannels.COMMAND_REQUEST);
		messagingBus.registerIncoming(AuthChannels.ADMIN_REQUEST, context -> {
			PluginMessageReader reader = PluginMessageReader.of(context.payload());
			String action = reader.readUtf();

			if (!"set_spawn".equals(action) || context.source() == null) {
				return PluginMessageDispatchResult.HANDLED;
			}

			ServerPlayer source = context.source();
			UUID targetUuid = reader.readUuid();
			String actorName = reader.readUtf();
			server.execute(() -> handleAdminSetSpawn(source, targetUuid, actorName));

			return PluginMessageDispatchResult.HANDLED;
		});
		messagingBus.registerIncoming(AuthChannels.AUTH_STATE, context -> {
			if (context.source() == null) {
				return PluginMessageDispatchResult.HANDLED;
			}

			ServerPlayer source = context.source();
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
			if (context.source() == null) {
				return PluginMessageDispatchResult.HANDLED;
			}

			ServerPlayer source = context.source();
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
		logger.audit("Đã gắn LunaAuth Backend vào plugin messaging bus.");
	}

	private void handleAdminSetSpawn(ServerPlayer source, UUID targetUuid, String actorName) {
		flow("RX admin_request action=set_spawn source=" + source.getName().getString() + " sourceUuid=" + source.getUUID() + " targetUuid=" + targetUuid + " actor=" + actorName);

		if (!source.getUUID().equals(targetUuid)) {
			flow("Ignore admin_request set_spawn because sourceUuid!=targetUuid source=" + source.getUUID() + " target=" + targetUuid);
			return;
		}

		if (spawnService.setSpawn(source, actorName)) {
			source.sendSystemMessage(mini(AuthMessages.spawnUpdated(actorName)));
		} else {
			source.sendSystemMessage(mini(AuthMessages.spawnUpdateFailed()));
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
		flow("RX auth_state action=state source=" + source.getName().getString() + " sourceUuid=" + source.getUUID() + " payloadUuid=" + playerUuid + " authenticated=" + authenticated + " needsRegister=" + needsRegister + " premiumName=" + premiumNameCandidate + " hasModePreference=" + hasModePreference + " username=" + username);

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
			announcedPromptMode.remove(playerUuid);
			flow("StateTransition uuid=" + playerUuid + " from=" + previous + " to=" + stateRegistry.state(playerUuid) + " reason=AUTH_STATE");
			return;
		}

		stateRegistry.markUnauthenticated(playerUuid, needsRegister);
		syncAuthLockState(source);
		announcePrompt(source);
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
		flow("RX command_response source=" + source.getName().getString() + " sourceUuid=" + source.getUUID() + " payloadUuid=" + playerUuid + " success=" + success + " authenticated=" + authenticated + " needsRegister=" + needsRegister + " premiumName=" + premiumNameCandidate + " hasModePreference=" + hasModePreference + " authMethod=" + authMethod + " message=" + message);

		if (!source.getUUID().equals(playerUuid)) {
			flow("Ignore command_response due to UUID mismatch source=" + source.getUUID() + " payload=" + playerUuid);
			return;
		}

		updateModeSelectorEligibility(source, premiumNameCandidate, hasModePreference);
		BackendAuthStateRegistry.AuthState previous = stateRegistry.state(playerUuid);

		if (!success || !authenticated) {
			source.sendSystemMessage(mini(AuthMessages.commandResult(success, message)));
		}

		if (authenticated) {
			stateRegistry.markAuthenticated(playerUuid);
			releaseAuthLockIfNeeded(source);
			hidePrompt(source);
			announcedPromptMode.remove(playerUuid);
			flow("StateTransition uuid=" + playerUuid + " from=" + previous + " to=" + stateRegistry.state(playerUuid) + " reason=COMMAND_RESPONSE");

			if (success) {
				sendAuthenticatedFeedback(source, authMethod);
			}

			return;
		}

		stateRegistry.markUnauthenticated(playerUuid, needsRegister);
		syncAuthLockState(source);

		// the command already answered them; this only speaks when the answer moved
		// them to a different prompt, e.g. a wrong /login on an unregistered name
		announcePrompt(source);
		flow("StateTransition uuid=" + playerUuid + " from=" + previous + " to=" + stateRegistry.state(playerUuid) + " reason=COMMAND_RESPONSE");
	}

	private PromptSet promptSet(AuthBackendConfig.PromptTemplate template) {
		return new PromptSet(
			LunaTextComponents.mini(template.bossbar()),
			LunaTextComponents.mini(template.actionbar()),
			LunaTextComponents.mini(template.chat())
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

	/**
	 * Say in chat what the player has to do, once per state.
	 *
	 * The bossbar and the actionbar are repainted on a loop, so they can afford to
	 * wait for the proxy's answer. A chat line cannot: it is sent once, and on a
	 * fresh join the state is still PENDING at that moment, so the only prompt the
	 * player ever saw was the one above their hotbar. This runs again when the
	 * answer lands, and the recorded mode is what keeps a repeated `auth_state`
	 * from repeating the line.
	 */
	private void announcePrompt(ServerPlayer player) {
		UUID playerId = player.getUUID();
		BackendAuthStateRegistry.AuthState state = stateRegistry.state(playerId);

		if (state.authenticated() || state.promptMode() == BackendAuthStateRegistry.PromptMode.PENDING) {
			return;
		}

		if (announcedPromptMode.put(playerId, state.promptMode()) == state.promptMode()) {
			return;
		}

		player.sendSystemMessage(promptFor(playerId).chat());
		flow("AnnouncePrompt player=" + player.getName().getString() + " uuid=" + playerId + " mode=" + state.promptMode());
	}

	private void showPrompt(ServerPlayer player) {
		PromptSet prompt = promptFor(player.getUUID());

		if (prompt == pendingPrompt) {
			requestStateSyncIfDue(player, "PENDING_PROMPT_LOOP");
			hidePrompt(player);
			flow("SkipPrompt player=" + player.getName().getString() + " uuid=" + player.getUUID() + " reason=PENDING");
			return;
		}

		ServerBossEvent bossbar = activeBossbars.computeIfAbsent(
			player.getUUID(),
			ignored -> AuthPlayerCompat.bossEvent(prompt.bossbar())
		);

		bossbar.setName(prompt.bossbar());
		bossbar.addPlayer(player);

		if (shouldRunIfDue(lastPromptActionbarLog, player.getUUID(), System.currentTimeMillis(), PROMPT_ACTIONBAR_THROTTLE_MS)) {
			AuthPlayerCompat.actionBar(player, prompt.actionbar());
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

			if (config.lobbyItemsEnabled() && lobbyItemsApplied.add(playerId)) {
				AuthLobbyItems.applyLobbyItems(player);
			}

			return;
		}

		lobbyItemsApplied.remove(playerId);

		lockedPlayers.computeIfAbsent(playerId, ignored -> {
			StoredLocation anchor = config.teleportToSpawnOnConnect() && spawnService.hasSpawn()
				? spawnService.spawnLocation()
				: StoredLocation.capture(player);
			LockedPlayerState state = new LockedPlayerState(anchor, player.isInvulnerable(), player.gameMode.getGameModeForPlayer());

			if (config.lobbyItemsEnabled()) {
				AuthLobbyItems.clearInventory(player);
			}

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
		AuthPlayerCompat.clearLockEffects(player);
		player.setDeltaMovement(0D, 0D, 0D);
		flow("ReleaseAuthLock player=" + player.getName().getString() + " uuid=" + player.getUUID());
	}

	private void keepProtectedState(ServerPlayer player) {
		player.setInvulnerable(true);
		player.clearFire();
		player.setAirSupply(player.getMaxAirSupply());
		player.fallDistance = 0F;
		player.setRemainingFireTicks(0);
	}

	private void refreshLockEffects(ServerPlayer player) {
		AuthPlayerCompat.applyLockEffects(player, BLINDNESS_DURATION_TICKS, LOCK_EFFECT_DURATION_TICKS);
	}

	private void enforceLockedPosition(ServerPlayer player) {
		LockedPlayerState state = lockedPlayers.get(player.getUUID());

		if (state == null || state.anchor() == null) {
			return;
		}

		StoredLocation anchor = state.anchor();
		boolean moved = !serverLevelOf(player).dimension().equals(anchor.dimension())
			|| distanceSquared(player, anchor) > 0.04D;

		if (moved) {
			spawnService.teleport(player, anchor);
			throttledFlow(lastMoveRestrictionLog, player.getUUID(), "BlockMove player=" + player.getName().getString() + " uuid=" + player.getUUID() + " state=" + stateRegistry.state(player.getUUID()));
		}

		player.setDeltaMovement(0D, 0D, 0D);
	}

	private double distanceSquared(ServerPlayer player, StoredLocation anchor) {
		double dx = player.getX() - anchor.x();
		double dy = player.getY() - anchor.y();
		double dz = player.getZ() - anchor.z();

		return (dx * dx) + (dy * dy) + (dz * dz);
	}

	private void requestStateSyncIfDue(ServerPlayer player, String reason) {
		if (shouldRunIfDue(lastSyncRequestLog, player.getUUID(), System.currentTimeMillis(), SYNC_REQUEST_THROTTLE_MS)) {
			requestStateSync(player);
			flow("RequestStateSync player=" + player.getName().getString() + " uuid=" + player.getUUID() + " reason=" + reason);
		}
	}

	private void requestStateSync(ServerPlayer player) {
		sendCommandRequest(player, writer -> {
			writer.writeUtf("sync_state");
			writer.writeUuid(player.getUUID());
			writer.writeUtf(player.getName().getString());
		});
	}

	private boolean sendCommandRequest(ServerPlayer player, Consumer<PluginMessageWriter> payloadWriter) {
		ensureMessagingAttached();

		if (messagingBus == null) {
			return false;
		}

		boolean sent = messagingBus.send(player, AuthChannels.COMMAND_REQUEST, payloadWriter::accept);
		flow("TX command_request player=" + player.getName().getString() + " uuid=" + player.getUUID() + " sent=" + sent + " at=" + Instant.now());

		return sent;
	}

	private boolean sendProbePreference(ServerPlayer player, String mode) {
		boolean sent = sendCommandRequest(player, writer -> {
			writer.writeUtf("set_probe_preference");
			writer.writeUuid(player.getUUID());
			writer.writeUtf(player.getName().getString());
			writer.writeUtf(mode);
		});

		if (!sent) {
			player.sendSystemMessage(mini(AuthMessages.probePreferenceFailed()));
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
			flow("ModeSelectorEligibility player=" + player.getName().getString() + " uuid=" + playerId + " premiumName=false modePreference=" + hasModePreference);
			return;
		}

		if (hasModePreference) {
			modeSelectedPlayers.add(playerId);
			closeModeSelector(playerId);
			flow("ModeSelectorEligibility player=" + player.getName().getString() + " uuid=" + playerId + " premiumName=true modePreference=true -> skip selector");
			return;
		}

		if (stateRegistry.isAuthenticated(playerId)) {
			return;
		}

		modeSelectedPlayers.remove(playerId);
		nextModeSelectorOpenAt.putIfAbsent(playerId, System.currentTimeMillis() + MODE_SELECTOR_DELAY_MS);
		showModeSelectorIfDue(player, System.currentTimeMillis(), false);
		flow("ModeSelectorEligibility player=" + player.getName().getString() + " uuid=" + playerId + " premiumName=true modePreference=false");
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
			LunaChestMenu menu = new LunaChestMenu(containerId, inventory, MODE_SELECTOR_ROWS, () -> handleModeSelectorClosed(playerId));
			openModeMenus.put(playerId, menu);
			renderModeSelector(player, menu);
			menu.broadcastChanges();

			return menu;
		}, mini(AuthMessages.modeSelectorTitle())));

		flow("ShowModeSelector player=" + player.getName().getString() + " uuid=" + playerId);
	}

	private void renderModeSelector(ServerPlayer player, LunaChestMenuBase menu) {
		menu.clearTopSlots();
		boolean remember = modeRememberSelection.getOrDefault(player.getUUID(), false);

		for (int slot : AuthMessages.MODE_SELECTOR_FRAME_SLOTS) {
			menu.setDecoration(slot, itemStack(AuthMessages.ITEM_FRAME, AuthMessages.frameItemName(), AuthMessages.frameItemLore()));
		}

		menu.setDecoration(AuthMessages.MODE_SELECTOR_SLOT_INFO, itemStack(
			AuthMessages.ITEM_INFO,
			AuthMessages.infoItemName(),
			AuthMessages.infoItemLore()
		));

		menu.setTopSlot(SLOT_PREMIUM, itemStack(
			AuthMessages.ITEM_PREMIUM,
			AuthMessages.premiumItemName(),
			AuthMessages.premiumItemLore()
		), () -> chooseMode(player, remember, true));

		menu.setTopSlot(SLOT_OFFLINE, itemStack(
			AuthMessages.ITEM_OFFLINE,
			AuthMessages.offlineItemName(),
			AuthMessages.offlineItemLore()
		), () -> chooseMode(player, remember, false));

		menu.setTopSlot(SLOT_REMEMBER, rememberToggleItem(remember), () -> toggleRemember(player));
	}

	private void chooseMode(ServerPlayer player, boolean remember, boolean premium) {
		String mode = (premium ? "online" : "offline") + (remember ? "_forever" : "");

		if (!sendProbePreference(player, mode)) {
			modeSelectedPlayers.remove(player.getUUID());
			AuthPlayerCompat.actionBar(player, mini(AuthMessages.modeChoiceSendFailed()));
			flow("ModeSelectorChoiceSendFailed player=" + player.getName().getString() + " uuid=" + player.getUUID() + " mode=" + mode);
			return;
		}

		modeSelectedPlayers.add(player.getUUID());
		nextModeSelectorOpenAt.remove(player.getUUID());

		player.sendSystemMessage(mini(premium
			? AuthMessages.modePremiumChosen(remember)
			: AuthMessages.modeOfflineChosen(remember)));

		closeModeSelector(player.getUUID());
		player.closeContainer();
		flow("ModeSelectorChoice player=" + player.getName().getString() + " uuid=" + player.getUUID() + " mode=" + mode);
	}

	private void toggleRemember(ServerPlayer player) {
		UUID playerId = player.getUUID();
		boolean next = !modeRememberSelection.getOrDefault(playerId, false);
		modeRememberSelection.put(playerId, next);
		LunaChestMenuBase menu = openModeMenus.get(playerId);

		if (menu != null) {
			menu.setTopSlot(SLOT_REMEMBER, rememberToggleItem(next), () -> toggleRemember(player));
			menu.broadcastChanges();
		}

		AuthPlayerCompat.actionBar(player, mini(AuthMessages.rememberToggled(next)));
	}

	private void handleModeSelectorClosed(UUID playerId) {
		openModeMenus.remove(playerId);
	}

	private void closeModeSelector(UUID playerId) {
		LunaChestMenuBase menu = openModeMenus.remove(playerId);

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
			remember ? AuthMessages.ITEM_REMEMBER_ON : AuthMessages.ITEM_REMEMBER_OFF,
			AuthMessages.rememberItem(remember),
			AuthMessages.rememberItemLore(remember)
		);
	}

	private ItemStack itemStack(String materialName, String title, List<String> loreLines) {
		Item item = resolveItem(materialName);
		ItemStack stack = new ItemStack(item == null ? Items.BARRIER : item);
		ItemDecor.name(stack, LunaTextComponents.mini(safe(title)));

		if (loreLines != null && !loreLines.isEmpty()) {
			List<Component> lore = new ArrayList<>();

			for (String line : loreLines) {
				lore.add(LunaTextComponents.mini(safe(line)));
			}

			ItemDecor.lore(stack, lore);
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

		return ItemLookup.byName(namespace, path);
	}

	private void sendAuthenticatedFeedback(ServerPlayer player, String authMethod) {
		String normalizedMethod = normalizeAuthMethod(authMethod);
		AuthBackendConfig.MethodFeedback feedback = config.authenticatedPrompt().byMethod().get(normalizedMethod);
		String chat = feedback == null || feedback.chat() == null || feedback.chat().isBlank()
			? config.authenticatedPrompt().chat()
			: feedback.chat();
		String actionbar = feedback == null || feedback.actionbar() == null || feedback.actionbar().isBlank()
			? config.authenticatedPrompt().actionbar()
			: feedback.actionbar();

		player.sendSystemMessage(LunaTextComponents.mini(chat));
		AuthPlayerCompat.actionBar(player, LunaTextComponents.mini(actionbar));
		flow("SendAuthenticatedFeedback player=" + player.getName().getString() + " uuid=" + player.getUUID() + " authMethod=" + normalizedMethod);
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

	/**
	 * The level the player is in, as a server level.
	 *
	 * {@code serverLevel()} was removed on 26.x, where {@code level()} already
	 * answers with the narrower type; the cast is what compiles on both.
	 */
	private ServerLevel serverLevelOf(ServerPlayer player) {
		return (ServerLevel) player.level();
	}

	private ServerPlayer playerFrom(CommandSourceStack source) {
		return source == null || !(source.getEntity() instanceof ServerPlayer player) ? null : player;
	}

	/** Render one of the shared auth strings; they are all MiniMessage. */
	private Component mini(String miniMessage) {
		return LunaTextComponents.mini(safe(miniMessage));
	}

	private String safe(String value) {
		return value == null ? "" : value;
	}

	private record PromptSet(Component bossbar, Component actionbar, Component chat) {
	}

	private record LockedPlayerState(StoredLocation anchor, boolean wasInvulnerable, GameType gameType) {
	}
}
