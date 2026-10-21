package dev.belikhun.luna.deathswap.lobby;

import dev.belikhun.luna.deathswap.DeathSwapText;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * The lobby: where it is, putting players into it, and the hotbar they hold
 * there. Lobby items carry an action in their persistent data, so they are
 * recognised by that and not by name or material.
 */
public final class LobbyService {
	public enum Action {
		ROOMS,
		TOGGLE,
		STATUS,
		LEAVE_ROOM,
		PROFILE
	}

	public static final int SLOT_ROOMS = 0;
	public static final int SLOT_TOGGLE = 2;
	public static final int SLOT_STATUS = 4;
	public static final int SLOT_LEAVE = 6;
	public static final int SLOT_PROFILE = 8;

	private final JavaPlugin plugin;
	private final NamespacedKey actionKey;
	private Location spawn;

	public LobbyService(JavaPlugin plugin) {
		this.plugin = plugin;
		this.actionKey = new NamespacedKey(plugin, "lobby_action");
	}

	/**
	 * Read lobby.spawn. Its world defaults to the void lobby world; an unset
	 * position falls back to that world's spawn.
	 */
	public void load(FileConfiguration config, World lobbyWorld) {
		String worldName = config.getString("lobby.spawn.world", "");
		World world = worldName == null || worldName.isBlank() ? null : Bukkit.getWorld(worldName);
		if (world == null) {
			world = lobbyWorld != null ? lobbyWorld : Bukkit.getWorlds().get(0);
		}
		if (config.isSet("lobby.spawn.x") && world.getName().equals(config.getString("lobby.spawn.world", world.getName()))) {
			spawn = new Location(world,
				config.getDouble("lobby.spawn.x"),
				config.getDouble("lobby.spawn.y"),
				config.getDouble("lobby.spawn.z"),
				(float) config.getDouble("lobby.spawn.yaw"),
				(float) config.getDouble("lobby.spawn.pitch"));
		} else {
			spawn = world.getSpawnLocation().add(0.5, 0, 0.5);
		}
	}

	/** True while the player stands in the lobby world. */
	public boolean isIn(Player player) {
		return player.getWorld().equals(spawn.getWorld());
	}

	public void setSpawn(Location location) {
		Location clean = location.clone();
		spawn = clean;
		FileConfiguration config = plugin.getConfig();
		config.set("lobby.spawn.world", clean.getWorld().getName());
		config.set("lobby.spawn.x", round(clean.getX()));
		config.set("lobby.spawn.y", round(clean.getY()));
		config.set("lobby.spawn.z", round(clean.getZ()));
		config.set("lobby.spawn.yaw", round(clean.getYaw()));
		config.set("lobby.spawn.pitch", round(clean.getPitch()));
		plugin.saveConfig();
	}

	/** Safe to read from any thread: a copy of the last loaded spawn. */
	public Location spawn() {
		return spawn.clone();
	}

	public World world() {
		return spawn.getWorld();
	}

	/** Reset the player, put them at the lobby and hand them the lobby hotbar. */
	public void enter(Player player, boolean inRoom, boolean sittingOut) {
		resetPlayer(player, GameMode.ADVENTURE);
		player.setInvulnerable(false);
		if (!player.isDead() && (!isIn(player) || player.getLocation().getY() < spawn.getWorld().getMinHeight() + 1)) {
			player.teleport(spawn(), PlayerTeleportEvent.TeleportCause.PLUGIN);
		}
		player.setRespawnLocation(spawn(), true);
		giveItems(player, inRoom, sittingOut);
	}

	public void giveItems(Player player, boolean inRoom, boolean sittingOut) {
		PlayerInventory inventory = player.getInventory();
		inventory.setItem(SLOT_ROOMS, item(Material.NETHER_STAR, "<gold>⌂ Danh sách phòng</gold>", List.of(
			"<gray>Xem và chọn phòng để chơi.</gray>"), Action.ROOMS));
		if (!inRoom) {
			inventory.setItem(SLOT_TOGGLE, null);
			inventory.setItem(SLOT_LEAVE, null);
		} else {
			inventory.setItem(SLOT_TOGGLE, sittingOut
				? item(Material.GRAY_DYE, "<gray>👻 Đang ngồi ngoài</gray>", List.of(
					"<gray>Bạn sẽ không vào ván sau.</gray>",
					"",
					"<yellow>▶</yellow> <white>Bấm để tham gia lại</white>"), Action.TOGGLE)
				: item(Material.LIME_DYE, "<green>👤 Đang tham gia</green>", List.of(
					"<gray>Bạn sẽ vào ván tiếp theo.</gray>",
					"",
					"<yellow>▶</yellow> <white>Bấm để ngồi ngoài</white>"), Action.TOGGLE));
			inventory.setItem(SLOT_LEAVE, item(Material.BARRIER, "<red>❌ Rời phòng</red>", List.of(
				"<gray>Ra khỏi phòng hiện tại.</gray>"), Action.LEAVE_ROOM));
		}
		inventory.setItem(SLOT_STATUS, item(Material.COMPASS, "<yellow>ℹ Thông tin phòng</yellow>", List.of(
			"<gray>Xem trạng thái và người chơi.</gray>"), Action.STATUS));

		ItemStack head = item(Material.PLAYER_HEAD, "<yellow>Hồ sơ của</yellow> <white>" + DeathSwapText.escape(player.getName()) + "</white>", List.of(
			"<gray>Xem thành tích DeathSwap của bạn.</gray>"), Action.PROFILE);
		if (head.getItemMeta() instanceof SkullMeta skull) {
			skull.setOwningPlayer(player);
			head.setItemMeta(skull);
		}
		inventory.setItem(SLOT_PROFILE, head);
		inventory.setHeldItemSlot(SLOT_STATUS);
	}

	public Action actionOf(ItemStack item) {
		if (item == null || !item.hasItemMeta()) {
			return null;
		}
		String value = item.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
		if (value == null) {
			return null;
		}
		try {
			return Action.valueOf(value);
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	public boolean isLobbyItem(ItemStack item) {
		return actionOf(item) != null;
	}

	private ItemStack item(Material material, String name, List<String> lore, Action action) {
		ItemStack item = new ItemStack(material);
		ItemMeta meta = item.getItemMeta();
		meta.displayName(DeathSwapText.mm("<!italic>" + name));
		List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
		for (String line : lore) {
			lines.add(DeathSwapText.mm("<!italic>" + line));
		}
		meta.lore(lines);
		meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
		meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action.name());
		item.setItemMeta(meta);
		return item;
	}

	/** Back to a clean state: empty inventory, full health and food, no effects. */
	public static void resetPlayer(Player player, GameMode mode) {
		player.setGameMode(mode);
		player.getInventory().clear();
		player.getEnderChest().clear();
		player.setItemOnCursor(null);
		player.clearActivePotionEffects();
		AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
		if (maxHealth != null) {
			// rounds can change it (max-hearts, LifeSwap); everywhere else it is vanilla 10 hearts
			maxHealth.setBaseValue(20D);
		}
		if (!player.isDead()) {
			player.setHealth(maxHealth != null ? maxHealth.getValue() : 20D);
		}
		player.setFoodLevel(20);
		player.setSaturation(5f);
		player.setExhaustion(0f);
		player.setLevel(0);
		player.setExp(0f);
		player.setTotalExperience(0);
		player.setFireTicks(0);
		player.setFreezeTicks(0);
		player.setFallDistance(0f);
		player.setAllowFlight(mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR);
	}

	private static double round(double value) {
		return Math.round(value * 100D) / 100D;
	}
}
