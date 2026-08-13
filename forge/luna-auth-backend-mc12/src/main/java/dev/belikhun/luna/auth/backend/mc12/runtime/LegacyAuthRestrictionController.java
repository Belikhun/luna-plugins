package dev.belikhun.luna.auth.backend.mc12.runtime;

import dev.belikhun.luna.auth.backend.mc12.service.LegacyAuthSpawnService;
import dev.belikhun.luna.auth.backend.mc12.service.LegacyAuthSpawnService.StoredLocation;
import dev.belikhun.luna.core.mc12.text.LunaTextComponents;
import dev.belikhun.luna.legacy.auth.AuthBackendConfig;
import dev.belikhun.luna.legacy.auth.AuthChannels;
import dev.belikhun.luna.legacy.auth.AuthMessages;
import dev.belikhun.luna.legacy.auth.BackendAuthStateRegistry;
import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.messaging.PluginMessageBus;
import dev.belikhun.luna.legacy.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.legacy.messaging.PluginMessageReader;
import dev.belikhun.luna.legacy.messaging.PluginMessageWriter;
import dev.belikhun.luna.legacy.messaging.bus.PlayerBridge;
import dev.belikhun.luna.legacy.string.Strings;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.BossInfo;
import net.minecraft.world.BossInfoServer;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Everything a player may not do until the proxy says they are authenticated.
 *
 * The 1.12.2 half of the same cage paper and the modern loaders apply: hold the
 * player at the auth spawn, blind and immobilise them, refuse chat, commands,
 * interaction and damage, show the bossbar and actionbar prompts, and - for a
 * name that could be a premium account - offer the mode picker. The state itself
 * is not decided here; it arrives from the proxy over {@link AuthChannels} and
 * this class only enforces it.
 *
 * **Written against this line rather than translated from the modern trunk.**
 * The container hierarchy, the potion constructors, the boss bar and the action
 * bar are each a different call here, not a rename, and the decision points are
 * legacy Forge events rather than mixins. What *is* shared is everything a player
 * reads: {@link AuthMessages} and the config both live in `luna-legacy-api`, so a
 * prompt on 1.12.2 is the same string as on 1.21.
 */
public final class LegacyAuthRestrictionController {
	private static final long RESTRICTION_LOG_THROTTLE_MS = 3000L;
	private static final long SYNC_REQUEST_THROTTLE_MS = 1500L;
	private static final long PROMPT_ACTIONBAR_THROTTLE_MS = 1500L;
	private static final long LOCK_EFFECT_REFRESH_THROTTLE_MS = 3000L;
	private static final long MODE_SELECTOR_DELAY_MS = 1500L;
	private static final int BLINDNESS_DURATION_TICKS = 600;
	private static final int LOCK_EFFECT_DURATION_TICKS = 220;

	/** How far a locked player may drift before being put back, squared. */
	private static final double DRIFT_TOLERANCE_SQUARED = 0.04D;

	private final MinecraftServer server;
	private final LunaLogger logger;
	private final AuthBackendConfig config;
	private final LegacyAuthSpawnService spawnService;
	private final PlayerBridge<EntityPlayerMP> players;
	private final PluginMessageBus<EntityPlayerMP, EntityPlayerMP> messagingBus;
	private final BackendAuthStateRegistry stateRegistry;
	private final AuthModeSelector modeSelector;
	private final PromptSet pendingPrompt;
	private final PromptSet loginPrompt;
	private final PromptSet registerPrompt;
	private final ConcurrentMap<UUID, BossInfoServer> activeBossbars;
	private final ConcurrentMap<UUID, StoredLocation> lockAnchors;
	private final ConcurrentMap<UUID, Boolean> lockWasInvulnerable;
	private final ConcurrentMap<UUID, BackendAuthStateRegistry.PromptMode> announcedPromptMode;
	private final ConcurrentMap<UUID, Long> lastMoveRestrictionLog;
	private final ConcurrentMap<UUID, Long> lastCommandRestrictionLog;
	private final ConcurrentMap<UUID, Long> lastChatRestrictionLog;
	private final ConcurrentMap<UUID, Long> lastSyncRequestLog;
	private final ConcurrentMap<UUID, Long> lastPromptActionbarLog;
	private final ConcurrentMap<UUID, Long> lastLockEffectRefreshLog;

	private volatile boolean messagingAttached;

	public LegacyAuthRestrictionController(
		MinecraftServer server,
		LunaLogger logger,
		AuthBackendConfig config,
		LegacyAuthSpawnService spawnService,
		PlayerBridge<EntityPlayerMP> players,
		PluginMessageBus<EntityPlayerMP, EntityPlayerMP> messagingBus
	) {
		this.server = server;
		this.logger = logger;
		this.config = config;
		this.spawnService = spawnService;
		this.players = players;
		this.messagingBus = messagingBus;
		this.stateRegistry = new BackendAuthStateRegistry();
		this.pendingPrompt = promptSet(config.pendingPrompt());
		this.loginPrompt = promptSet(config.loginPrompt());
		this.registerPrompt = promptSet(config.registerPrompt());
		this.activeBossbars = new ConcurrentHashMap<UUID, BossInfoServer>();
		this.lockAnchors = new ConcurrentHashMap<UUID, StoredLocation>();
		this.lockWasInvulnerable = new ConcurrentHashMap<UUID, Boolean>();
		this.announcedPromptMode = new ConcurrentHashMap<UUID, BackendAuthStateRegistry.PromptMode>();
		this.lastMoveRestrictionLog = new ConcurrentHashMap<UUID, Long>();
		this.lastCommandRestrictionLog = new ConcurrentHashMap<UUID, Long>();
		this.lastChatRestrictionLog = new ConcurrentHashMap<UUID, Long>();
		this.lastSyncRequestLog = new ConcurrentHashMap<UUID, Long>();
		this.lastPromptActionbarLog = new ConcurrentHashMap<UUID, Long>();
		this.lastLockEffectRefreshLog = new ConcurrentHashMap<UUID, Long>();
		this.modeSelector = new AuthModeSelector(this, logger);
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

		for (UUID playerId : new java.util.ArrayList<UUID>(activeBossbars.keySet())) {
			EntityPlayerMP player = players.byId(playerId);

			if (player != null) {
				hidePrompt(player);
			}
		}

		modeSelector.close();
		activeBossbars.clear();
		lockAnchors.clear();
		lockWasInvulnerable.clear();
		announcedPromptMode.clear();
		messagingAttached = false;
	}

	// ---------------------------------------------------------------- lifecycle

	public void onPlayerLoggedIn(EntityPlayerMP player) {
		if (player == null) {
			return;
		}

		UUID playerId = players.idOf(player);

		if (!stateRegistry.hasState(playerId)) {
			stateRegistry.markUnauthenticated(playerId);
			flow("Join player=" + player.getName() + " uuid=" + playerId + " stateInit=PENDING");
		} else {
			flow("Join player=" + player.getName() + " uuid=" + playerId + " statePreserved=" + stateRegistry.state(playerId));
		}

		requestStateSync(player);

		// the pending prompt says "hold on, we are asking the proxy", which is not
		// worth a chat line; the real one is sent by announcePrompt once the proxy
		// has answered, which on a fresh join is always after this point
		announcePrompt(player);

		syncAuthLockState(player);

		if (config.teleportToSpawnOnConnect() && spawnService.hasSpawn()) {
			spawnService.teleportToSpawn(player);
		}
	}

	public void onPlayerLoggedOut(EntityPlayerMP player) {
		if (player == null) {
			return;
		}

		UUID playerId = players.idOf(player);

		hidePrompt(player);
		modeSelector.forget(playerId);
		stateRegistry.clear(playerId);
		lockAnchors.remove(playerId);
		lockWasInvulnerable.remove(playerId);
		announcedPromptMode.remove(playerId);
		lastMoveRestrictionLog.remove(playerId);
		lastCommandRestrictionLog.remove(playerId);
		lastChatRestrictionLog.remove(playerId);
		lastSyncRequestLog.remove(playerId);
		lastPromptActionbarLog.remove(playerId);
		lastLockEffectRefreshLog.remove(playerId);
		flow("Quit clear state player=" + player.getName() + " uuid=" + playerId);
	}

	/**
	 * The per-player tick.
	 *
	 * Legacy Forge fires one `PlayerTickEvent` per player, so unlike the fabric
	 * build this does not have to walk the player list itself.
	 */
	public void onPlayerTick(EntityPlayerMP player) {
		if (player == null) {
			return;
		}

		UUID playerId = players.idOf(player);

		if (stateRegistry.isAuthenticated(playerId)) {
			releaseAuthLockIfNeeded(player);
			hidePrompt(player);
			modeSelector.closeFor(player);

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

		if (player.ticksExisted % 20 == 0) {
			showPrompt(player);
			modeSelector.showIfDue(player, now);
		}
	}

	// ------------------------------------------------------------ the decisions

	/** False when the message must not be sent, having told the player why. */
	public boolean allowChat(EntityPlayerMP player) {
		if (player == null || stateRegistry.isAuthenticated(players.idOf(player))) {
			return true;
		}

		UUID playerId = players.idOf(player);

		player.sendMessage(promptFor(playerId).chat());
		throttledFlow(lastChatRestrictionLog, playerId, "BlockChat player=" + player.getName() + " uuid=" + playerId + " state=" + stateRegistry.state(playerId));

		return false;
	}

	/**
	 * False when the command must not run, having told the player why.
	 *
	 * The allow-list is what lets an unauthenticated player type the very commands
	 * that authenticate them; everything else is refused with the same prompt chat
	 * gets.
	 */
	public boolean allowCommand(EntityPlayerMP player, String rawCommand) {
		if (player == null) {
			return true;
		}

		UUID playerId = players.idOf(player);

		if (stateRegistry.isAuthenticated(playerId)) {
			return true;
		}

		String raw = rawCommand == null ? "" : rawCommand.trim();

		if (raw.startsWith("/")) {
			raw = raw.substring(1).trim();
		}

		if (raw.isEmpty()) {
			player.sendMessage(promptFor(playerId).chat());
			throttledFlow(lastCommandRestrictionLog, playerId, "BlockCommand player=" + player.getName() + " uuid=" + playerId + " command=<empty> state=" + stateRegistry.state(playerId));

			return false;
		}

		String root = raw.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);

		if (config.allowedCommands().contains(root)) {
			flow("AllowCommand player=" + player.getName() + " uuid=" + playerId + " command=" + root);

			return true;
		}

		player.sendMessage(promptFor(playerId).chat());
		throttledFlow(lastCommandRestrictionLog, playerId, "BlockCommand player=" + player.getName() + " uuid=" + playerId + " command=" + root + " state=" + stateRegistry.state(playerId));

		return false;
	}

	/** False when this player may not break, place, use or interact right now. */
	public boolean allowInteraction(Entity entity) {
		return !isLocked(entity);
	}

	/** Whether a drop must be refused, because the player is locked. */
	public boolean refuseDrop(EntityPlayerMP player) {
		return isLocked(player);
	}

	/**
	 * False when the damage must not land.
	 *
	 * Both directions are covered, as on paper: a locked player takes none, and
	 * deals none either.
	 */
	public boolean allowDamage(Entity target, Entity attacker) {
		return !isLocked(target) && !isLocked(attacker);
	}

	/** Put back an item a locked player managed to throw. */
	public void restoreTossedItem(EntityPlayerMP player, ItemStack tossed) {
		if (player == null || tossed == null || tossed.isEmpty()) {
			return;
		}

		player.inventory.addItemStackToInventory(tossed.copy());
	}

	public boolean isLocked(Entity entity) {
		if (!(entity instanceof EntityPlayerMP)) {
			return false;
		}

		return !stateRegistry.isAuthenticated(players.idOf((EntityPlayerMP) entity));
	}

	// --------------------------------------------------------------- the prompt

	/**
	 * Say in chat what the player has to do, once per state.
	 *
	 * The bossbar and the actionbar are repainted on a loop, so they can afford to
	 * wait for the proxy's answer. A chat line cannot: it is sent once, and on a
	 * fresh join the state is still PENDING at that moment, so without this the
	 * only prompt a player ever saw was the one above their hotbar.
	 */
	private void announcePrompt(EntityPlayerMP player) {
		UUID playerId = players.idOf(player);
		BackendAuthStateRegistry.AuthState state = stateRegistry.state(playerId);

		if (state.authenticated() || state.promptMode() == BackendAuthStateRegistry.PromptMode.PENDING) {
			return;
		}

		if (announcedPromptMode.put(playerId, state.promptMode()) == state.promptMode()) {
			return;
		}

		player.sendMessage(promptFor(playerId).chat());
		flow("AnnouncePrompt player=" + player.getName() + " uuid=" + playerId + " mode=" + state.promptMode());
	}

	private void showPrompt(EntityPlayerMP player) {
		PromptSet prompt = promptFor(players.idOf(player));

		if (prompt == pendingPrompt) {
			requestStateSyncIfDue(player, "PENDING_PROMPT_LOOP");
			hidePrompt(player);
			flow("SkipPrompt player=" + player.getName() + " uuid=" + players.idOf(player) + " reason=PENDING");

			return;
		}

		UUID playerId = players.idOf(player);
		BossInfoServer bossbar = activeBossbars.get(playerId);

		if (bossbar == null) {
			bossbar = new BossInfoServer(prompt.bossbar(), BossInfo.Color.YELLOW, BossInfo.Overlay.PROGRESS);
			activeBossbars.put(playerId, bossbar);
		}

		bossbar.setName(prompt.bossbar());
		bossbar.addPlayer(player);

		if (shouldRunIfDue(lastPromptActionbarLog, playerId, System.currentTimeMillis(), PROMPT_ACTIONBAR_THROTTLE_MS)) {
			LegacyAuthPlayerCompat.actionBar(player, prompt.actionbar());
		}
	}

	private void hidePrompt(EntityPlayerMP player) {
		BossInfoServer bossbar = activeBossbars.remove(players.idOf(player));

		if (bossbar != null) {
			bossbar.removePlayer(player);
		}
	}

	// ----------------------------------------------------------------- the lock

	private void syncAuthLockState(EntityPlayerMP player) {
		UUID playerId = players.idOf(player);

		if (stateRegistry.isAuthenticated(playerId)) {
			releaseAuthLockIfNeeded(player);

			return;
		}

		if (lockAnchors.containsKey(playerId)) {
			return;
		}

		StoredLocation anchor = config.teleportToSpawnOnConnect() && spawnService.hasSpawn()
			? spawnService.spawnLocation()
			: StoredLocation.capture(player);

		lockWasInvulnerable.put(playerId, Boolean.valueOf(player.isEntityInvulnerable(net.minecraft.util.DamageSource.GENERIC)));
		lockAnchors.put(playerId, anchor);

		player.setEntityInvulnerable(true);
		LegacyAuthPlayerCompat.halt(player);
		refreshLockEffects(player);
		enforceLockedPosition(player);
		flow("ApplyAuthLock player=" + player.getName() + " uuid=" + playerId);
	}

	private void releaseAuthLockIfNeeded(EntityPlayerMP player) {
		UUID playerId = players.idOf(player);
		StoredLocation previous = lockAnchors.remove(playerId);

		if (previous == null) {
			return;
		}

		Boolean wasInvulnerable = lockWasInvulnerable.remove(playerId);

		player.setEntityInvulnerable(wasInvulnerable != null && wasInvulnerable.booleanValue());
		LegacyAuthPlayerCompat.clearLockEffects(player);
		LegacyAuthPlayerCompat.halt(player);
		flow("ReleaseAuthLock player=" + player.getName() + " uuid=" + playerId);
	}

	private void keepProtectedState(EntityPlayerMP player) {
		player.setEntityInvulnerable(true);
		player.extinguish();
		player.setAir(300);
		player.fallDistance = 0F;
	}

	private void refreshLockEffects(EntityPlayerMP player) {
		LegacyAuthPlayerCompat.applyLockEffects(player, BLINDNESS_DURATION_TICKS, LOCK_EFFECT_DURATION_TICKS);
	}

	private void enforceLockedPosition(EntityPlayerMP player) {
		StoredLocation anchor = lockAnchors.get(players.idOf(player));

		if (anchor == null) {
			return;
		}

		boolean moved = player.dimension != anchor.dimension() || distanceSquared(player, anchor) > DRIFT_TOLERANCE_SQUARED;

		if (moved) {
			spawnService.teleport(player, anchor);
			throttledFlow(lastMoveRestrictionLog, players.idOf(player), "BlockMove player=" + player.getName() + " uuid=" + players.idOf(player) + " state=" + stateRegistry.state(players.idOf(player)));
		}

		LegacyAuthPlayerCompat.halt(player);
	}

	private double distanceSquared(EntityPlayerMP player, StoredLocation anchor) {
		double dx = player.posX - anchor.x();
		double dy = player.posY - anchor.y();
		double dz = player.posZ - anchor.z();

		return (dx * dx) + (dy * dy) + (dz * dz);
	}

	// -------------------------------------------------------------- the channel

	private void ensureMessagingAttached() {
		if (messagingAttached) {
			return;
		}

		if (messagingBus == null) {
			logger.warn("Thiếu PluginMessageBus, LunaAuth Backend sẽ không thể đồng bộ trạng thái xác thực.");

			return;
		}

		messagingBus.registerOutgoing(AuthChannels.COMMAND_REQUEST);

		messagingBus.registerIncoming(AuthChannels.ADMIN_REQUEST, context -> {
			PluginMessageReader reader = PluginMessageReader.of(context.payload());
			String action = reader.readUtf();

			if (!"set_spawn".equals(action) || context.source() == null) {
				return PluginMessageDispatchResult.HANDLED;
			}

			final EntityPlayerMP source = context.source();
			final UUID targetUuid = reader.readUuid();
			final String actorName = reader.readUtf();

			players.onServerThread(() -> handleAdminSetSpawn(source, targetUuid, actorName));

			return PluginMessageDispatchResult.HANDLED;
		});

		messagingBus.registerIncoming(AuthChannels.AUTH_STATE, context -> {
			if (context.source() == null) {
				return PluginMessageDispatchResult.HANDLED;
			}

			final EntityPlayerMP source = context.source();
			PluginMessageReader reader = PluginMessageReader.of(context.payload());
			String action = reader.readUtf();

			if (!"state".equals(action)) {
				return PluginMessageDispatchResult.HANDLED;
			}

			final UUID playerUuid = reader.readUuid();
			final boolean authenticated = reader.readBoolean();
			final boolean needsRegister = reader.readBoolean();
			final boolean premiumNameCandidate = reader.readBoolean();
			final boolean hasModePreference = reader.readBoolean();

			players.onServerThread(() ->
				handleAuthState(source, playerUuid, authenticated, needsRegister, premiumNameCandidate, hasModePreference));

			return PluginMessageDispatchResult.HANDLED;
		});

		messagingBus.registerIncoming(AuthChannels.COMMAND_RESPONSE, context -> {
			if (context.source() == null) {
				return PluginMessageDispatchResult.HANDLED;
			}

			final EntityPlayerMP source = context.source();
			PluginMessageReader reader = PluginMessageReader.of(context.payload());
			String action = reader.readUtf();

			if (!"auth_result".equals(action) && !"auth_result_v2".equals(action)) {
				return PluginMessageDispatchResult.HANDLED;
			}

			boolean v2Payload = "auth_result_v2".equals(action);
			final UUID playerUuid = reader.readUuid();
			final boolean success = reader.readBoolean();
			final boolean authenticated = reader.readBoolean();
			final boolean needsRegister = reader.readBoolean();
			final boolean premiumNameCandidate = reader.readBoolean();
			final boolean hasModePreference = reader.readBoolean();
			final String authMethod = v2Payload ? reader.readUtf() : "default";
			final String message = reader.readUtf();

			players.onServerThread(() ->
				handleCommandResponse(source, playerUuid, success, authenticated, needsRegister, premiumNameCandidate, hasModePreference, authMethod, message));

			return PluginMessageDispatchResult.HANDLED;
		});

		messagingAttached = true;
		logger.audit("Đã gắn LunaAuth Backend vào plugin messaging bus.");
	}

	private void handleAuthState(
		EntityPlayerMP source,
		UUID playerUuid,
		boolean authenticated,
		boolean needsRegister,
		boolean premiumNameCandidate,
		boolean hasModePreference
	) {
		if (!players.idOf(source).equals(playerUuid)) {
			flow("Ignore auth_state due to UUID mismatch source=" + players.idOf(source) + " payload=" + playerUuid);

			return;
		}

		modeSelector.updateEligibility(playerUuid, premiumNameCandidate, hasModePreference);
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
		EntityPlayerMP source,
		UUID playerUuid,
		boolean success,
		boolean authenticated,
		boolean needsRegister,
		boolean premiumNameCandidate,
		boolean hasModePreference,
		String authMethod,
		String message
	) {
		if (!players.idOf(source).equals(playerUuid)) {
			flow("Ignore command_response due to UUID mismatch source=" + players.idOf(source) + " payload=" + playerUuid);

			return;
		}

		modeSelector.updateEligibility(playerUuid, premiumNameCandidate, hasModePreference);
		BackendAuthStateRegistry.AuthState previous = stateRegistry.state(playerUuid);

		if (!success || !authenticated) {
			source.sendMessage(mini(AuthMessages.commandResult(success, message)));
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

	private void handleAdminSetSpawn(EntityPlayerMP source, UUID targetUuid, String actorName) {
		flow("RX admin_request action=set_spawn source=" + source.getName() + " targetUuid=" + targetUuid + " actor=" + actorName);

		if (!players.idOf(source).equals(targetUuid)) {
			flow("Ignore admin_request set_spawn because sourceUuid!=targetUuid");

			return;
		}

		if (spawnService.setSpawn(source, actorName)) {
			source.sendMessage(mini(AuthMessages.spawnUpdated(actorName)));
		} else {
			source.sendMessage(mini(AuthMessages.spawnUpdateFailed()));
		}
	}

	private void sendAuthenticatedFeedback(EntityPlayerMP player, String authMethod) {
		String normalized = authMethod == null || authMethod.trim().isEmpty()
			? "default"
			: authMethod.trim().toLowerCase(Locale.ROOT);

		AuthBackendConfig.MethodFeedback feedback = config.authenticatedPrompt().byMethod().get(normalized);

		String chat = feedback == null || Strings.isBlank(feedback.chat())
			? config.authenticatedPrompt().chat()
			: feedback.chat();
		String actionbar = feedback == null || Strings.isBlank(feedback.actionbar())
			? config.authenticatedPrompt().actionbar()
			: feedback.actionbar();

		player.sendMessage(mini(chat));
		LegacyAuthPlayerCompat.actionBar(player, mini(actionbar));
	}

	// --------------------------------------------------------------- the client

	/** `/login <password>` and `/register <password> <confirm>` reach the proxy here. */
	public boolean sendAuthCommand(EntityPlayerMP player, String action, String... arguments) {
		final String[] args = arguments;

		return sendCommandRequest(player, writer -> {
			writer.writeUtf(action);
			writer.writeUuid(players.idOf(player));
			writer.writeUtf(player.getName());

			for (String argument : args) {
				writer.writeUtf(argument == null ? "" : argument);
			}
		});
	}

	boolean sendProbePreference(EntityPlayerMP player, final String mode) {
		boolean sent = sendCommandRequest(player, writer -> {
			writer.writeUtf("set_probe_preference");
			writer.writeUuid(players.idOf(player));
			writer.writeUtf(player.getName());
			writer.writeUtf(mode);
		});

		if (!sent) {
			player.sendMessage(mini(AuthMessages.probePreferenceFailed()));
		}

		return sent;
	}

	private void requestStateSyncIfDue(EntityPlayerMP player, String reason) {
		if (shouldRunIfDue(lastSyncRequestLog, players.idOf(player), System.currentTimeMillis(), SYNC_REQUEST_THROTTLE_MS)) {
			requestStateSync(player);
			flow("RequestStateSync player=" + player.getName() + " uuid=" + players.idOf(player) + " reason=" + reason);
		}
	}

	private void requestStateSync(EntityPlayerMP player) {
		sendCommandRequest(player, writer -> {
			writer.writeUtf("sync_state");
			writer.writeUuid(players.idOf(player));
			writer.writeUtf(player.getName());
		});
	}

	private boolean sendCommandRequest(EntityPlayerMP player, java.util.function.Consumer<PluginMessageWriter> payloadWriter) {
		ensureMessagingAttached();

		if (messagingBus == null) {
			return false;
		}

		boolean sent = messagingBus.send(player, AuthChannels.COMMAND_REQUEST, payloadWriter::accept);

		flow("TX command_request player=" + player.getName() + " uuid=" + players.idOf(player) + " sent=" + sent);

		return sent;
	}

	// ----------------------------------------------------------------- plumbing

	PlayerBridge<EntityPlayerMP> players() {
		return players;
	}

	AuthBackendConfig config() {
		return config;
	}

	boolean isAuthenticated(UUID playerId) {
		return stateRegistry.isAuthenticated(playerId);
	}

	long modeSelectorDelayMillis() {
		return MODE_SELECTOR_DELAY_MS;
	}

	private PromptSet promptFor(UUID playerId) {
		BackendAuthStateRegistry.AuthState state = stateRegistry.state(playerId);

		if (state.authenticated()) {
			return pendingPrompt;
		}

		switch (state.promptMode()) {
			case REGISTER:
				return registerPrompt;
			case LOGIN:
				return loginPrompt;
			default:
				return pendingPrompt;
		}
	}

	private PromptSet promptSet(AuthBackendConfig.PromptTemplate template) {
		return new PromptSet(
			mini(template.bossbar()),
			mini(template.actionbar()),
			mini(template.chat())
		);
	}

	static ITextComponent mini(String miniMessage) {
		return LunaTextComponents.mini(miniMessage == null ? "" : miniMessage);
	}

	private boolean shouldRunIfDue(ConcurrentMap<UUID, Long> throttleMap, UUID playerId, long now, long intervalMillis) {
		Long last = throttleMap.get(playerId);

		if (last != null && now - last.longValue() < intervalMillis) {
			return false;
		}

		throttleMap.put(playerId, Long.valueOf(now));

		return true;
	}

	private void throttledFlow(ConcurrentMap<UUID, Long> throttleMap, UUID playerId, String message) {
		if (shouldRunIfDue(throttleMap, playerId, System.currentTimeMillis(), RESTRICTION_LOG_THROTTLE_MS)) {
			flow(message);
		}
	}

	void flow(String message) {
		if (config.authFlowLogsEnabled()) {
			logger.debug(message);
		}
	}

	/** The same prompt on all three surfaces, pre-rendered once. */
	private static final class PromptSet {
		private final ITextComponent bossbar;
		private final ITextComponent actionbar;
		private final ITextComponent chat;

		private PromptSet(ITextComponent bossbar, ITextComponent actionbar, ITextComponent chat) {
			this.bossbar = bossbar;
			this.actionbar = actionbar;
			this.chat = chat;
		}

		private ITextComponent bossbar() {
			return bossbar;
		}

		private ITextComponent actionbar() {
			return actionbar;
		}

		private ITextComponent chat() {
			return chat;
		}
	}
}
