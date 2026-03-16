package dev.belikhun.luna.auth.backend.listener;

import dev.belikhun.luna.auth.backend.api.AuthLobbyItemRegistry;
import dev.belikhun.luna.auth.backend.service.BackendAuthStateRegistry;
import dev.belikhun.luna.auth.backend.service.BackendAuthSpawnService;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.List;
import java.util.stream.Collectors;

public final class AuthRestrictionListener implements Listener, AuthLobbyItemRegistry {
	private static final long RESTRICTION_LOG_THROTTLE_MS = 3000L;
	private static final long SYNC_REQUEST_THROTTLE_MS = 1500L;
	private static final float DEFAULT_WALK_SPEED = 0.2F;
	private static final float DEFAULT_FLY_SPEED = 0.1F;
	private static final Component MODE_SELECTOR_TITLE = Component.text("Chọn kiểu tài khoản");
	private static final int SLOT_PREMIUM = 3;
	private static final int SLOT_OFFLINE = 5;
	private static final int SLOT_REMEMBER = 7;

	private final JavaPlugin plugin;
	private final BackendAuthStateRegistry stateRegistry;
	private final MiniMessage miniMessage;
	private final PromptSet loginPrompt;
	private final PromptSet registerPrompt;
	private final PromptSet pendingPrompt;
	private final Map<UUID, BossBar> activeBossbars;
	private final Set<String> allowedCommands;
	private final BackendAuthSpawnService spawnService;
	private final Consumer<Player> syncStateRequestSender;
	private final BiFunction<Player, String, Boolean> probePreferenceSender;
	private final LunaLogger logger;
	private final boolean authFlowLogsEnabled;
	private final boolean modeSelectorGuiEnabled;
	private final Set<UUID> shownModeSelectorPlayers;
	private final Set<UUID> modeSelectedPlayers;
	private final ConcurrentMap<UUID, Boolean> modeSelectorEligible;
	private final ConcurrentMap<UUID, Boolean> modePreferencePresent;
	private final ConcurrentMap<UUID, Boolean> modeRememberSelection;
	private final ConcurrentMap<UUID, Long> lastMoveRestrictionLog;
	private final ConcurrentMap<UUID, Long> lastCommandRestrictionLog;
	private final ConcurrentMap<UUID, Long> lastChatRestrictionLog;
	private final ConcurrentMap<UUID, Long> lastSyncRequestLog;
	private final ConcurrentMap<UUID, MovementProfile> movementProfiles;
	private final Set<UUID> authLockedPlayers;
	private final Set<UUID> lobbyItemsAppliedPlayers;
	private final ConcurrentMap<String, LobbyItem> registeredLobbyItems;
	private final NamespacedKey lobbyItemKey;

	public AuthRestrictionListener(
		JavaPlugin plugin,
		BackendAuthStateRegistry stateRegistry,
		BackendAuthSpawnService spawnService,
		PromptTemplate loginPrompt,
		PromptTemplate registerPrompt,
		PromptTemplate pendingPrompt,
		Set<String> allowedCommands,
		Consumer<Player> syncStateRequestSender,
		BiFunction<Player, String, Boolean> probePreferenceSender,
		boolean modeSelectorGuiEnabled,
		LunaLogger logger,
		boolean authFlowLogsEnabled
	) {
		this.plugin = plugin;
		this.stateRegistry = stateRegistry;
		this.spawnService = spawnService;
		this.syncStateRequestSender = syncStateRequestSender;
		this.logger = logger;
		this.miniMessage = MiniMessage.miniMessage();
		this.loginPrompt = toComponents(loginPrompt);
		this.registerPrompt = toComponents(registerPrompt);
		this.pendingPrompt = toComponents(pendingPrompt);
		this.activeBossbars = new ConcurrentHashMap<>();
		this.allowedCommands = allowedCommands;
		this.probePreferenceSender = probePreferenceSender;
		this.authFlowLogsEnabled = authFlowLogsEnabled;
		this.modeSelectorGuiEnabled = modeSelectorGuiEnabled;
		this.shownModeSelectorPlayers = ConcurrentHashMap.newKeySet();
		this.modeSelectedPlayers = ConcurrentHashMap.newKeySet();
		this.modeSelectorEligible = new ConcurrentHashMap<>();
		this.modePreferencePresent = new ConcurrentHashMap<>();
		this.modeRememberSelection = new ConcurrentHashMap<>();
		this.lastMoveRestrictionLog = new ConcurrentHashMap<>();
		this.lastCommandRestrictionLog = new ConcurrentHashMap<>();
		this.lastChatRestrictionLog = new ConcurrentHashMap<>();
		this.lastSyncRequestLog = new ConcurrentHashMap<>();
		this.movementProfiles = new ConcurrentHashMap<>();
		this.authLockedPlayers = ConcurrentHashMap.newKeySet();
		this.lobbyItemsAppliedPlayers = ConcurrentHashMap.newKeySet();
		this.registeredLobbyItems = new ConcurrentHashMap<>();
		this.lobbyItemKey = new NamespacedKey(plugin, "auth_lobby_item");
	}

	public void startPromptTask() {
		Bukkit.getScheduler().runTaskTimer(plugin, () -> {
			for (Player player : Bukkit.getOnlinePlayers()) {
				syncAuthLockState(player);
				if (stateRegistry.isAuthenticated(player.getUniqueId())) {
					hidePrompt(player);
					continue;
				}
				showPrompt(player);
			}
		}, 20L, 20L);
	}

	public void hidePrompt(Player player) {
		if (player == null) {
			return;
		}

		runOnMainThread(() -> {
			BossBar bar = activeBossbars.remove(player.getUniqueId());
			if (bar != null) {
				player.hideBossBar(bar);
				flow("Ẩn prompt player=" + player.getName() + " uuid=" + player.getUniqueId());
			}
		});
	}

	public void refreshPlayerState(Player player) {
		if (player == null || !player.isOnline()) {
			return;
		}

		runOnMainThread(() -> {
			if (!player.isOnline()) {
				return;
			}
			syncAuthLockState(player);
		});
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		UUID playerUuid = event.getPlayer().getUniqueId();
		if (!stateRegistry.hasState(playerUuid)) {
			stateRegistry.markUnauthenticated(playerUuid);
			flow("Join player=" + event.getPlayer().getName() + " uuid=" + playerUuid + " stateInit=PENDING");
		} else {
			flow("Join player=" + event.getPlayer().getName() + " uuid=" + playerUuid + " statePreserved=" + stateRegistry.state(playerUuid));
		}

		PromptSet prompt = promptFor(playerUuid);
		requestStateSyncIfDue(event.getPlayer(), "JOIN");
		if (prompt != pendingPrompt) {
			event.getPlayer().sendMessage(prompt.chat());
		}
		if (spawnService.hasSpawn()) {
			Bukkit.getScheduler().runTask(plugin, () -> spawnService.teleportToSpawn(event.getPlayer()));
		}
		syncAuthLockState(event.getPlayer());
		showModeSelectorIfNeeded(event.getPlayer());
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		hidePrompt(event.getPlayer());
		stateRegistry.clear(event.getPlayer().getUniqueId());
		lastMoveRestrictionLog.remove(event.getPlayer().getUniqueId());
		lastCommandRestrictionLog.remove(event.getPlayer().getUniqueId());
		lastChatRestrictionLog.remove(event.getPlayer().getUniqueId());
		lastSyncRequestLog.remove(event.getPlayer().getUniqueId());
		shownModeSelectorPlayers.remove(event.getPlayer().getUniqueId());
		modeSelectedPlayers.remove(event.getPlayer().getUniqueId());
		modeSelectorEligible.remove(event.getPlayer().getUniqueId());
		modePreferencePresent.remove(event.getPlayer().getUniqueId());
		modeRememberSelection.remove(event.getPlayer().getUniqueId());
		authLockedPlayers.remove(event.getPlayer().getUniqueId());
		lobbyItemsAppliedPlayers.remove(event.getPlayer().getUniqueId());
		movementProfiles.remove(event.getPlayer().getUniqueId());
		flow("Quit clear state player=" + event.getPlayer().getName() + " uuid=" + event.getPlayer().getUniqueId());
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onModeSelectorClick(InventoryClickEvent event) {
		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}
		if (!(event.getView().getTopInventory().getHolder() instanceof ModeSelectorHolder)) {
			return;
		}

		event.setCancelled(true);
		if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
			return;
		}

		if (stateRegistry.isAuthenticated(player.getUniqueId())) {
			player.closeInventory();
			return;
		}

		if (event.getSlot() == SLOT_REMEMBER) {
			boolean next = !modeRememberSelection.getOrDefault(player.getUniqueId(), false);
			modeRememberSelection.put(player.getUniqueId(), next);
			event.getView().getTopInventory().setItem(SLOT_REMEMBER, rememberToggleItem(next));
			player.sendActionBar(miniMessage.deserialize(next
				? "<gold>Đã bật ghi nhớ lựa chọn vĩnh viễn.</gold>"
				: "<yellow>Đã tắt ghi nhớ vĩnh viễn (chỉ 24h).</yellow>"));
			return;
		}

		if (event.getSlot() == SLOT_PREMIUM) {
			boolean remember = modeRememberSelection.getOrDefault(player.getUniqueId(), false);
			String selectedMode = remember ? "online_forever" : "online";
			boolean sent = probePreferenceSender.apply(player, selectedMode);
			if (!sent) {
				modeSelectedPlayers.remove(player.getUniqueId());
				player.sendActionBar(miniMessage.deserialize("<red>Không gửi được lựa chọn. Vui lòng thử lại.</red>"));
				flow("ModeSelectorChoiceSendFailed player=" + player.getName() + " uuid=" + player.getUniqueId() + " mode=" + selectedMode);
				return;
			}
			modeSelectedPlayers.add(player.getUniqueId());
			player.sendRichMessage(remember
				? "<yellow>Đã chọn Premium (ghi nhớ vĩnh viễn). Bạn sẽ được kết nối lại để xác thực online.</yellow>"
				: "<yellow>Đã chọn Premium (24h). Bạn sẽ được kết nối lại để xác thực online.</yellow>");
			player.closeInventory();
			flow("ModeSelectorChoice player=" + player.getName() + " uuid=" + player.getUniqueId() + " mode=" + (remember ? "online_forever" : "online"));
			return;
		}

		if (event.getSlot() == SLOT_OFFLINE) {
			boolean remember = modeRememberSelection.getOrDefault(player.getUniqueId(), false);
			String selectedMode = remember ? "offline_forever" : "offline";
			boolean sent = probePreferenceSender.apply(player, selectedMode);
			if (!sent) {
				modeSelectedPlayers.remove(player.getUniqueId());
				player.sendActionBar(miniMessage.deserialize("<red>Không gửi được lựa chọn. Vui lòng thử lại.</red>"));
				flow("ModeSelectorChoiceSendFailed player=" + player.getName() + " uuid=" + player.getUniqueId() + " mode=" + selectedMode);
				return;
			}
			modeSelectedPlayers.add(player.getUniqueId());
			player.sendRichMessage(remember
				? "<green>Đã chọn Offline (ghi nhớ vĩnh viễn). Tiếp tục đăng nhập bằng mật khẩu server.</green>"
				: "<green>Đã chọn Offline (24h). Tiếp tục đăng nhập bằng mật khẩu server.</green>");
			player.closeInventory();
			flow("ModeSelectorChoice player=" + player.getName() + " uuid=" + player.getUniqueId() + " mode=" + (remember ? "offline_forever" : "offline"));
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onModeSelectorDrag(InventoryDragEvent event) {
		if (!(event.getView().getTopInventory().getHolder() instanceof ModeSelectorHolder)) {
			if (event.getWhoClicked() instanceof Player player && stateRegistry.isAuthenticated(player.getUniqueId()) && dragTouchesProtectedLobbyItem(event)) {
				event.setCancelled(true);
			}
			return;
		}
		event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onInventoryClick(InventoryClickEvent event) {
		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}
		if (!stateRegistry.isAuthenticated(player.getUniqueId())) {
			return;
		}
		if (event.getView().getTopInventory().getHolder() instanceof ModeSelectorHolder) {
			return;
		}

		if (clickTouchesProtectedLobbyItem(player, event)) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onModeSelectorClose(InventoryCloseEvent event) {
		if (!(event.getPlayer() instanceof Player player)) {
			return;
		}
		if (!(event.getInventory().getHolder() instanceof ModeSelectorHolder)) {
			return;
		}
		if (stateRegistry.isAuthenticated(player.getUniqueId())) {
			return;
		}
		if (!shouldShowModeSelector(player.getUniqueId())) {
			return;
		}

		Bukkit.getScheduler().runTask(plugin, () -> {
			if (!player.isOnline() || stateRegistry.isAuthenticated(player.getUniqueId()) || modeSelectedPlayers.contains(player.getUniqueId())) {
				return;
			}
			player.openInventory(createModeSelectorInventory(player.getUniqueId()));
			flow("ReopenModeSelector player=" + player.getName() + " uuid=" + player.getUniqueId());
		});
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onMove(PlayerMoveEvent event) {
		Player player = event.getPlayer();
		UUID playerUuid = player.getUniqueId();
		if (stateRegistry.isAuthenticated(playerUuid)) {
			syncAuthLockState(player);
			return;
		}
		syncAuthLockState(player);
		Location from = event.getFrom();
		Location to = event.getTo();
		if (to == null) {
			return;
		}
		if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
			return;
		}

		throttledFlow(lastMoveRestrictionLog, playerUuid,
			"BlockMove player=" + player.getName()
				+ " uuid=" + playerUuid
				+ " from=" + formatLocation(from)
				+ " to=" + formatLocation(to)
				+ " state=" + stateRegistry.state(playerUuid));
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onCommand(PlayerCommandPreprocessEvent event) {
		UUID playerUuid = event.getPlayer().getUniqueId();
		if (stateRegistry.isAuthenticated(playerUuid)) {
			return;
		}
		String message = event.getMessage();
		PromptSet prompt = promptFor(playerUuid);
		if (message == null || message.length() < 2) {
			event.setCancelled(true);
			event.getPlayer().sendMessage(prompt.chat());
			throttledFlow(lastCommandRestrictionLog, playerUuid,
				"BlockCommand player=" + event.getPlayer().getName() + " uuid=" + playerUuid + " command=<empty> state=" + stateRegistry.state(playerUuid));
			return;
		}
		String command = message.substring(1).trim();
		int split = command.indexOf(' ');
		String root = split > -1 ? command.substring(0, split) : command;
		if (allowedCommands.contains(root.toLowerCase())) {
			flow("AllowCommand player=" + event.getPlayer().getName() + " uuid=" + playerUuid + " command=" + root.toLowerCase());
			return;
		}
		event.setCancelled(true);
		event.getPlayer().sendMessage(prompt.chat());
		throttledFlow(lastCommandRestrictionLog, playerUuid,
			"BlockCommand player=" + event.getPlayer().getName() + " uuid=" + playerUuid + " command=" + root.toLowerCase() + " state=" + stateRegistry.state(playerUuid));
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onChat(AsyncChatEvent event) {
		UUID playerUuid = event.getPlayer().getUniqueId();
		if (stateRegistry.isAuthenticated(playerUuid)) {
			return;
		}
		event.setCancelled(true);
		event.getPlayer().sendMessage(promptFor(playerUuid).chat());
		throttledFlow(lastChatRestrictionLog, playerUuid,
			"BlockChat player=" + event.getPlayer().getName() + " uuid=" + playerUuid + " state=" + stateRegistry.state(playerUuid));
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
	public void onInteract(PlayerInteractEvent event) {
		Player player = event.getPlayer();
		if (!stateRegistry.isAuthenticated(player.getUniqueId())) {
			event.setCancelled(true);
			return;
		}

		if (event.getHand() != EquipmentSlot.HAND) {
			return;
		}

		ItemStack used = event.getItem();
		if (!isProtectedLobbyItem(used)) {
			return;
		}

		AuthLobbyItemRegistry.ClickType clickType = switch (event.getAction()) {
			case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK -> AuthLobbyItemRegistry.ClickType.LEFT;
			case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> AuthLobbyItemRegistry.ClickType.RIGHT;
			default -> null;
		};
		if (clickType == null) {
			return;
		}

		event.setCancelled(true);
		String key = lobbyItemId(used);
		LobbyItem lobbyItem = key == null ? null : registeredLobbyItems.get(key);
		if (lobbyItem != null && lobbyItem.action() != null) {
			flow("LobbyItemUse player=" + player.getName() + " uuid=" + player.getUniqueId() + " key=" + key + " click=" + clickType);
			lobbyItem.action().onUse(player, clickType);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onDrop(PlayerDropItemEvent event) {
		if (!stateRegistry.isAuthenticated(event.getPlayer().getUniqueId())) {
			event.setCancelled(true);
			return;
		}

		if (isProtectedLobbyItem(event.getItemDrop().getItemStack())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onSwapHands(PlayerSwapHandItemsEvent event) {
		if (!stateRegistry.isAuthenticated(event.getPlayer().getUniqueId())) {
			return;
		}

		if (isProtectedLobbyItem(event.getMainHandItem()) || isProtectedLobbyItem(event.getOffHandItem())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onItemDamage(PlayerItemDamageEvent event) {
		if (!stateRegistry.isAuthenticated(event.getPlayer().getUniqueId())) {
			return;
		}

		if (isProtectedLobbyItem(event.getItem())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPickup(EntityPickupItemEvent event) {
		if (event.getEntity() instanceof Player player && !stateRegistry.isAuthenticated(player.getUniqueId())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onOpenInventory(InventoryOpenEvent event) {
		if (event.getInventory().getHolder() instanceof ModeSelectorHolder) {
			return;
		}
		if (event.getPlayer() instanceof Player player && !stateRegistry.isAuthenticated(player.getUniqueId())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onBlockBreak(BlockBreakEvent event) {
		if (!stateRegistry.isAuthenticated(event.getPlayer().getUniqueId())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onBlockPlace(BlockPlaceEvent event) {
		if (!stateRegistry.isAuthenticated(event.getPlayer().getUniqueId())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onDamage(EntityDamageEvent event) {
		if (event.getEntity() instanceof Player player && !stateRegistry.isAuthenticated(player.getUniqueId())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onDamageOthers(EntityDamageByEntityEvent event) {
		if (event.getDamager() instanceof Player player && !stateRegistry.isAuthenticated(player.getUniqueId())) {
			event.setCancelled(true);
		}
	}

	private void showPrompt(Player player) {
		PromptSet prompt = promptFor(player.getUniqueId());
		if (prompt == pendingPrompt) {
			requestStateSyncIfDue(player, "PENDING_PROMPT_LOOP");
			hidePrompt(player);
			flow("SkipPrompt player=" + player.getName() + " uuid=" + player.getUniqueId() + " reason=PENDING");
			return;
		}

		BossBar bar = activeBossbars.computeIfAbsent(player.getUniqueId(), ignored -> BossBar.bossBar(
			prompt.bossbar(),
			1f,
			BossBar.Color.YELLOW,
			BossBar.Overlay.PROGRESS
		));
		bar.name(prompt.bossbar());
		player.showBossBar(bar);
		player.sendActionBar(prompt.actionbar());
		flow("ShowPrompt player=" + player.getName() + " uuid=" + player.getUniqueId() + " mode=" + stateRegistry.state(player.getUniqueId()).promptMode());
	}

	private void applyAuthLock(Player player) {
		UUID playerUuid = player.getUniqueId();
		movementProfiles.computeIfAbsent(playerUuid, ignored -> new MovementProfile(
			normalizeSpeed(player.getWalkSpeed(), DEFAULT_WALK_SPEED),
			normalizeSpeed(player.getFlySpeed(), DEFAULT_FLY_SPEED),
			player.getAllowFlight()
		));

		player.setWalkSpeed(0F);
		player.setFlySpeed(0F);
		player.setVelocity(new Vector(0D, 0D, 0D));
		player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 220, 0, false, false, false));
		player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 220, 10, false, false, false));
		player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 220, 128, false, false, false));
	}

	private void releaseAuthLock(Player player) {
		UUID playerUuid = player.getUniqueId();
		MovementProfile profile = movementProfiles.remove(playerUuid);
		float walkSpeed = profile != null ? normalizeSpeed(profile.walkSpeed(), DEFAULT_WALK_SPEED) : DEFAULT_WALK_SPEED;
		float flySpeed = profile != null ? normalizeSpeed(profile.flySpeed(), DEFAULT_FLY_SPEED) : DEFAULT_FLY_SPEED;
		boolean allowFlight = profile != null && profile.allowFlight();

		player.setWalkSpeed(walkSpeed);
		player.setFlySpeed(flySpeed);
		player.setAllowFlight(allowFlight);

		player.removePotionEffect(PotionEffectType.BLINDNESS);
		player.removePotionEffect(PotionEffectType.SLOWNESS);
		player.removePotionEffect(PotionEffectType.JUMP_BOOST);
		flow("ReleaseAuthLock player=" + player.getName() + " uuid=" + playerUuid + " walkSpeed=" + walkSpeed + " flySpeed=" + flySpeed + " allowFlight=" + allowFlight);
	}

	private float normalizeSpeed(float value, float fallback) {
		if (value > 0F) {
			return value;
		}

		return fallback;
	}

	private void syncAuthLockState(Player player) {
		UUID playerUuid = player.getUniqueId();
		boolean authenticated = stateRegistry.isAuthenticated(playerUuid);
		if (authenticated) {
			if (authLockedPlayers.remove(playerUuid)) {
				releaseAuthLock(player);
			}
			if (lobbyItemsAppliedPlayers.add(playerUuid)) {
				applyLobbyItems(player);
			}
			return;
		}

		lobbyItemsAppliedPlayers.remove(playerUuid);

		if (authLockedPlayers.add(playerUuid)) {
			clearUnauthorizedInventory(player);
			applyAuthLock(player);
		}
	}

	@Override
	public void registerLobbyItem(LobbyItem item) {
		if (item == null || item.key() == null || item.key().isBlank() || item.item() == null) {
			return;
		}

		int slot = Math.max(0, Math.min(8, item.hotbarSlot()));
		LobbyItem normalized = new LobbyItem(item.key().trim().toLowerCase(java.util.Locale.ROOT), slot, item.item().clone(), item.action());
		registeredLobbyItems.put(normalized.key(), normalized);

		for (Player online : Bukkit.getOnlinePlayers()) {
			if (stateRegistry.isAuthenticated(online.getUniqueId())) {
				applyLobbyItems(online);
			}
		}
	}

	@Override
	public void unregisterLobbyItem(String key) {
		if (key == null || key.isBlank()) {
			return;
		}

		String normalized = key.trim().toLowerCase(java.util.Locale.ROOT);
		registeredLobbyItems.remove(normalized);
		for (Player online : Bukkit.getOnlinePlayers()) {
			removeLobbyItemByKey(online, normalized);
		}
	}

	@Override
	public Map<String, LobbyItem> lobbyItems() {
		return Collections.unmodifiableMap(new LinkedHashMap<>(registeredLobbyItems));
	}

	@Override
	public void applyLobbyItems(Player player) {
		if (player == null || !stateRegistry.isAuthenticated(player.getUniqueId())) {
			return;
		}

		PlayerInventory inventory = player.getInventory();
		inventory.clear();
		inventory.setArmorContents(new ItemStack[4]);
		inventory.setItemInOffHand(null);
		for (LobbyItem lobbyItem : registeredLobbyItems.values().stream().sorted(java.util.Comparator.comparingInt(LobbyItem::hotbarSlot)).collect(Collectors.toList())) {
			ItemStack stack = lobbyItem.item().clone();
			tagLobbyItem(stack, lobbyItem.key());
			inventory.setItem(lobbyItem.hotbarSlot(), stack);
		}
	}

	private void clearUnauthorizedInventory(Player player) {
		PlayerInventory inventory = player.getInventory();
		inventory.clear();
		inventory.setArmorContents(new ItemStack[4]);
		inventory.setItemInOffHand(null);
	}

	private void tagLobbyItem(ItemStack stack, String key) {
		if (stack == null || key == null || key.isBlank()) {
			return;
		}
		ItemMeta meta = stack.getItemMeta();
		meta.getPersistentDataContainer().set(lobbyItemKey, PersistentDataType.STRING, key);
		meta.setUnbreakable(true);
		stack.setItemMeta(meta);
	}

	private String lobbyItemId(ItemStack stack) {
		if (stack == null || !stack.hasItemMeta()) {
			return null;
		}
		return stack.getItemMeta().getPersistentDataContainer().get(lobbyItemKey, PersistentDataType.STRING);
	}

	private boolean isProtectedLobbyItem(ItemStack stack) {
		String key = lobbyItemId(stack);
		return key != null && registeredLobbyItems.containsKey(key);
	}

	private void removeLobbyItemByKey(Player player, String key) {
		PlayerInventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			String itemKey = lobbyItemId(stack);
			if (key.equals(itemKey)) {
				inventory.setItem(slot, null);
			}
		}
		if (key.equals(lobbyItemId(inventory.getItemInOffHand()))) {
			inventory.setItemInOffHand(null);
		}
	}

	private boolean clickTouchesProtectedLobbyItem(Player player, InventoryClickEvent event) {
		if (isProtectedLobbyItem(event.getCurrentItem()) || isProtectedLobbyItem(event.getCursor())) {
			return true;
		}

		if (event.getHotbarButton() >= 0 && event.getHotbarButton() <= 8) {
			ItemStack hotbarItem = player.getInventory().getItem(event.getHotbarButton());
			if (isProtectedLobbyItem(hotbarItem)) {
				return true;
			}
		}

		if (event.getClick().name().contains("SWAP_OFFHAND") && isProtectedLobbyItem(player.getInventory().getItemInOffHand())) {
			return true;
		}

		return false;
	}

	private boolean dragTouchesProtectedLobbyItem(InventoryDragEvent event) {
		if (isProtectedLobbyItem(event.getOldCursor())) {
			return true;
		}

		for (ItemStack stack : event.getNewItems().values()) {
			if (isProtectedLobbyItem(stack)) {
				return true;
			}
		}

		return false;
	}

	private PromptSet promptFor(UUID playerUuid) {
		BackendAuthStateRegistry.AuthState state = stateRegistry.state(playerUuid);
		if (state.authenticated()) {
			return pendingPrompt;
		}
		return switch (state.promptMode()) {
			case REGISTER -> registerPrompt;
			case LOGIN -> loginPrompt;
			case PENDING -> pendingPrompt;
		};
	}

	private PromptSet toComponents(PromptTemplate promptSet) {
		return new PromptSet(
			miniMessage.deserialize(promptSet.bossbar()),
			miniMessage.deserialize(promptSet.actionbar()),
			miniMessage.deserialize(promptSet.chat())
		);
	}

	private void throttledFlow(Map<UUID, Long> throttleMap, UUID playerUuid, String message) {
		if (!authFlowLogsEnabled) {
			return;
		}

		long now = System.currentTimeMillis();
		Long last = throttleMap.get(playerUuid);
		if (last != null && now - last < RESTRICTION_LOG_THROTTLE_MS) {
			return;
		}

		throttleMap.put(playerUuid, now);
		logger.audit(message);
	}

	private void requestStateSyncIfDue(Player player, String reason) {
		UUID playerUuid = player.getUniqueId();
		long now = System.currentTimeMillis();
		Long last = lastSyncRequestLog.get(playerUuid);
		if (last != null && now - last < SYNC_REQUEST_THROTTLE_MS) {
			return;
		}

		lastSyncRequestLog.put(playerUuid, now);
		syncStateRequestSender.accept(player);
		flow("RequestStateSync player=" + player.getName() + " uuid=" + playerUuid + " reason=" + reason);
	}

	private void showModeSelectorIfNeeded(Player player) {
		if (!shouldShowModeSelector(player.getUniqueId())) {
			return;
		}
		if (!shownModeSelectorPlayers.add(player.getUniqueId())) {
			return;
		}

		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if (!player.isOnline() || stateRegistry.isAuthenticated(player.getUniqueId()) || modeSelectedPlayers.contains(player.getUniqueId())) {
				return;
			}
			player.openInventory(createModeSelectorInventory(player.getUniqueId()));
			flow("ShowModeSelector player=" + player.getName() + " uuid=" + player.getUniqueId());
		}, 30L);
	}

	public void updateModeSelectorEligibility(Player player, boolean premiumNameCandidate, boolean hasModePreference) {
		if (player == null) {
			return;
		}

		runOnMainThread(() -> {
			if (!player.isOnline()) {
				return;
			}

			UUID playerUuid = player.getUniqueId();
			modeSelectorEligible.put(playerUuid, premiumNameCandidate);
			modePreferencePresent.put(playerUuid, hasModePreference);
			if (!premiumNameCandidate) {
				modeSelectedPlayers.add(playerUuid);
				if (player.getOpenInventory().getTopInventory().getHolder() instanceof ModeSelectorHolder) {
					player.closeInventory();
				}
				flow("ModeSelectorEligibility player=" + player.getName() + " uuid=" + playerUuid + " premiumName=false modePreference=" + hasModePreference);
				return;
			}

			if (hasModePreference) {
				modeSelectedPlayers.add(playerUuid);
				if (player.getOpenInventory().getTopInventory().getHolder() instanceof ModeSelectorHolder) {
					player.closeInventory();
				}
				flow("ModeSelectorEligibility player=" + player.getName() + " uuid=" + playerUuid + " premiumName=true modePreference=true -> skip selector");
				return;
			}

			if (stateRegistry.isAuthenticated(playerUuid)) {
				return;
			}

			modeSelectedPlayers.remove(playerUuid);
			showModeSelectorIfNeeded(player);
			flow("ModeSelectorEligibility player=" + player.getName() + " uuid=" + playerUuid + " premiumName=true modePreference=false");
		});
	}

	private void runOnMainThread(Runnable task) {
		if (Bukkit.isPrimaryThread()) {
			task.run();
			return;
		}

		Bukkit.getScheduler().runTask(plugin, task);
	}

	private boolean shouldShowModeSelector(UUID playerUuid) {
		if (!modeSelectorGuiEnabled) {
			return false;
		}
		if (stateRegistry.isAuthenticated(playerUuid)) {
			return false;
		}
		if (modeSelectedPlayers.contains(playerUuid)) {
			return false;
		}
		if (Boolean.TRUE.equals(modePreferencePresent.get(playerUuid))) {
			return false;
		}
		return Boolean.TRUE.equals(modeSelectorEligible.get(playerUuid));
	}

	private Inventory createModeSelectorInventory(UUID playerUuid) {
		ModeSelectorHolder holder = new ModeSelectorHolder();
		Inventory inventory = Bukkit.createInventory(holder, 9, MODE_SELECTOR_TITLE);
		holder.inventory = inventory;
		boolean remember = modeRememberSelection.getOrDefault(playerUuid, false);

		ItemStack frame = selectorItem(
			Material.GRAY_STAINED_GLASS_PANE,
			"<dark_gray>•</dark_gray>",
			List.of("<gray> </gray>")
		);
		for (int slot : List.of(0, 1, 2, 6, 8)) {
			inventory.setItem(slot, frame);
		}

		inventory.setItem(4, selectorItem(
			Material.BOOK,
			"<yellow><b>ℹ Chọn Chế Độ Đăng Nhập</b></yellow>",
			List.of(
				"<gray>Premium hoặc Offline.</gray>",
				"<gray>Nút bên phải bật/tắt ghi nhớ.</gray>",
				"",
				"<gold>⚠ Hãy chọn đúng để tránh lỗi phiên.</gold>"
			)
		));

		inventory.setItem(SLOT_PREMIUM, selectorItem(
			Material.NETHER_STAR,
			"<green><b>★ Tài Khoản Premium</b></green>",
			List.of(
				"<gray>Dùng launcher Microsoft.</gray>",
				"<gray>Sẽ probe xác thực online.</gray>",
				"",
				"<yellow>▶ Ấn để chọn.</yellow>"
			)
		));
		inventory.setItem(SLOT_OFFLINE, selectorItem(
			Material.IRON_BARS,
			"<aqua><b>⬤ Tài Khoản Offline</b></aqua>",
			List.of(
				"<gray>Dùng launcher cracked.</gray>",
				"<gray>Không ép xác thực online.</gray>",
				"",
				"<yellow>▶ Ấn để chọn.</yellow>"
			)
		));

		inventory.setItem(SLOT_REMEMBER, rememberToggleItem(remember));
		return inventory;
	}

	private ItemStack rememberToggleItem(boolean remember) {
		return selectorItem(
			remember ? Material.LIME_DYE : Material.GRAY_DYE,
			remember
				? "<gold><b>🔔 Ghi Nhớ: BẬT</b></gold>"
				: "<gray><b>🔔 Ghi Nhớ: TẮT</b></gray>",
			List.of(
				remember
					? "<gray>Lựa chọn sẽ được giữ vĩnh viễn.</gray>"
					: "<gray>Lựa chọn chỉ có hiệu lực 24 giờ.</gray>",
				"<yellow>▶ Ấn để chuyển trạng thái.</yellow>"
			)
		);
	}

	private ItemStack selectorItem(Material material, String name, List<String> loreLines) {
		ItemStack stack = new ItemStack(material);
		ItemMeta meta = stack.getItemMeta();
		meta.displayName(miniMessage.deserialize("<!i>" + name));
		List<Component> lore = new java.util.ArrayList<>();
		for (String line : loreLines) {
			lore.add(miniMessage.deserialize("<!i>" + line));
		}
		meta.lore(lore);
		stack.setItemMeta(meta);
		return stack;
	}

	private static final class ModeSelectorHolder implements InventoryHolder {
		private Inventory inventory;

		@Override
		public Inventory getInventory() {
			return inventory;
		}
	}

	private void flow(String message) {
		if (!authFlowLogsEnabled) {
			return;
		}
		logger.audit(message);
	}

	private String formatLocation(Location location) {
		return location.getWorld().getName() + "@"
			+ String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", location.getX(), location.getY(), location.getZ());
	}

	public record PromptTemplate(String bossbar, String actionbar, String chat) {
	}

	public record PromptSet(Component bossbar, Component actionbar, Component chat) {
	}

	private record MovementProfile(float walkSpeed, float flySpeed, boolean allowFlight) {
	}
}
