package dev.belikhun.luna.deathswap;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.paper.lifecycle.PaperPluginBootstrap;
import dev.belikhun.luna.deathswap.gui.DeathSwapMenus;
import dev.belikhun.luna.deathswap.lobby.LobbyService;
import dev.belikhun.luna.deathswap.lobby.SpawnCommand;
import dev.belikhun.luna.deathswap.room.RoomManager;
import dev.belikhun.luna.deathswap.world.LobbyWorld;
import dev.belikhun.luna.deathswap.world.RoundWorldService;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;

/**
 * Wiring only. Worlds (pool, generator, cleanup), the lobby and the rooms
 * each live in their own service.
 */
public final class LunaDeathSwapPlugin extends JavaPlugin {
	private LunaLogger logger;
	private DeathSwapSettings settings;
	private DeathSwapStats stats;
	private LobbyService lobby;
	private World lobbyWorld;
	private RoundWorldService worlds;
	private RoomManager rooms;
	private DeathSwapMenus menus;

	@Override
	public void onEnable() {
		if (!PaperPluginBootstrap.ensurePluginEnabled(this, "LunaCore", "LunaCore chưa sẵn sàng hoặc bật lỗi. LunaDeathSwap sẽ tắt để tránh lỗi classpath.")) {
			return;
		}

		logger = PaperPluginBootstrap.initLogger(this, "DeathSwap");
		logger.audit("Bắt đầu khởi tạo LunaDeathSwap.");

		PaperPluginBootstrap.ensureDataFolder(this);
		saveDefaultConfig();
		reloadConfig();
		settings = DeathSwapSettings.load(getConfig());

		stats = new DeathSwapStats(this);
		stats.load();

		lobbyWorld = LobbyWorld.ensure(logger.scope("Lobby"));
		lobby = new LobbyService(this);
		lobby.load(getConfig(), lobbyWorld);

		worlds = new RoundWorldService(this, logger.scope("Worlds"), settings.worlds(), settings.generator());
		worlds.start();

		rooms = new RoomManager(this, logger.scope("Rooms"), stats, settings, worlds, lobby);
		menus = new DeathSwapMenus(this, rooms, stats);
		SpawnCommand spawn = new SpawnCommand(rooms, menus);
		menus.setSpawnCommand(spawn);
		menus.enable();
		getServer().getPluginManager().registerEvents(new DeathSwapListener(this, rooms, lobby, menus), this);

		DeathSwapCommand command = new DeathSwapCommand(this, rooms, lobby, stats, menus);
		getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
			commands.registrar().register("deathswap", "Lệnh DeathSwap", List.of("ds"), command);
			commands.registrar().register("spawn", "Về sảnh DeathSwap", List.of("lobby", "sanh"), spawn);
		});

		if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
			new DeathSwapPlaceholders(this, rooms).register();
			logger.info("Đã đăng ký placeholder %deathswap_*%.");
		}

		rooms.enable();
		logger.success("LunaDeathSwap đã khởi động thành công (" + rooms.rooms().size() + " phòng).");
	}

	@Override
	public void onDisable() {
		if (menus != null) {
			menus.disable();
		}
		if (rooms != null) {
			rooms.disable();
		}
		if (worlds != null) {
			worlds.shutdown();
		}
		if (logger != null) {
			logger.audit("LunaDeathSwap đã tắt.");
		}
	}

	public void reloadSettings() {
		reloadConfig();
		settings = DeathSwapSettings.load(getConfig());
		lobby.load(getConfig(), lobbyWorld);
		worlds.reload(settings.worlds(), settings.generator());
		rooms.reload(settings);
		logger.audit("Đã tải lại cấu hình.");
	}

	/** The player's name the way the other minigames show it: rank prefix from LuckPerms, name in white. */
	public String playerDisplay(Player player) {
		String format = settings == null ? "<white>%player_name%</white>" : settings.playerNameFormat();
		String filled = DeathSwapText.fill(format, Map.of("player_name", DeathSwapText.escape(player.getName())));
		return DeathSwapText.papi(player, filled);
	}
}
