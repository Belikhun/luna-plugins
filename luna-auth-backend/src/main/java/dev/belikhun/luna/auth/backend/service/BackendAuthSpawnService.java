package dev.belikhun.luna.auth.backend.service;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class BackendAuthSpawnService {
	private final JavaPlugin plugin;

	public BackendAuthSpawnService(JavaPlugin plugin) {
		this.plugin = plugin;
	}

	public boolean hasSpawn() {
		return plugin.getConfig().contains("auth-spawn.world");
	}

	public boolean setSpawn(Location location, String actor) {
		if (location == null || location.getWorld() == null) {
			return false;
		}
		plugin.getConfig().set("auth-spawn.world", location.getWorld().getName());
		plugin.getConfig().set("auth-spawn.x", location.getX());
		plugin.getConfig().set("auth-spawn.y", location.getY());
		plugin.getConfig().set("auth-spawn.z", location.getZ());
		plugin.getConfig().set("auth-spawn.yaw", (double) location.getYaw());
		plugin.getConfig().set("auth-spawn.pitch", (double) location.getPitch());
		plugin.getConfig().set("auth-spawn.set-by", actor == null ? "unknown" : actor);
		plugin.getConfig().set("auth-spawn.updated-at", System.currentTimeMillis());
		plugin.saveConfig();
		return true;
	}

	public boolean teleportToSpawn(Player player) {
		Location location = spawnLocation();
		if (location == null) {
			return false;
		}
		return player.teleport(location);
	}

	private Location spawnLocation() {
		String worldName = plugin.getConfig().getString("auth-spawn.world", "");
		if (worldName == null || worldName.isBlank()) {
			return null;
		}
		World world = Bukkit.getWorld(worldName);
		if (world == null) {
			return null;
		}
		double x = plugin.getConfig().getDouble("auth-spawn.x");
		double y = plugin.getConfig().getDouble("auth-spawn.y");
		double z = plugin.getConfig().getDouble("auth-spawn.z");
		float yaw = (float) plugin.getConfig().getDouble("auth-spawn.yaw");
		float pitch = (float) plugin.getConfig().getDouble("auth-spawn.pitch");
		return new Location(world, x, y, z, yaw, pitch);
	}
}
