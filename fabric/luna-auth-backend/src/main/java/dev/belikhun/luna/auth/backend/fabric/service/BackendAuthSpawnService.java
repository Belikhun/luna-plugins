package dev.belikhun.luna.auth.backend.fabric.service;

import dev.belikhun.luna.core.api.config.ConfigValues;
import dev.belikhun.luna.core.api.config.LunaYamlConfig;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.fabric.compat.DimensionKeys;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Where a player is held while they authenticate, and how they get put back
 * there when they drift.
 *
 * The point is written into the mod's own config by an operator running
 * {@code /auth setspawn} on the proxy, which arrives here as an admin plugin
 * message; there is no separate store.
 */
public final class BackendAuthSpawnService {
	private final Path configPath;
	private final LunaLogger logger;
	private volatile StoredLocation spawnLocation;

	public BackendAuthSpawnService(Path configPath, LunaLogger logger) {
		this.configPath = configPath;
		this.logger = logger;
		this.spawnLocation = loadSpawnLocation();
	}

	public boolean hasSpawn() {
		return spawnLocation != null;
	}

	public StoredLocation spawnLocation() {
		return spawnLocation;
	}

	public boolean setSpawn(ServerPlayer player, String actor) {
		if (player == null || serverOf(player) == null) {
			return false;
		}

		StoredLocation updated = StoredLocation.capture(player);

		try {
			Map<String, Object> root = new LinkedHashMap<>(LunaYamlConfig.loadMap(configPath));
			Map<String, Object> authSpawn = new LinkedHashMap<>(ConfigValues.map(root, "auth-spawn"));

			authSpawn.put("world", DimensionKeys.name(updated.dimension()));
			authSpawn.put("x", updated.x());
			authSpawn.put("y", updated.y());
			authSpawn.put("z", updated.z());
			authSpawn.put("yaw", (double) updated.yaw());
			authSpawn.put("pitch", (double) updated.pitch());
			authSpawn.put("set-by", actor == null ? "unknown" : actor);
			authSpawn.put("updated-at", System.currentTimeMillis());
			root.put("auth-spawn", authSpawn);
			LunaYamlConfig.dumpMap(configPath, root);
			spawnLocation = updated;

			return true;
		} catch (RuntimeException exception) {
			logger.warn("Không thể lưu auth-spawn Fabric: " + exception.getMessage());
			return false;
		}
	}

	public boolean teleportToSpawn(ServerPlayer player) {
		StoredLocation target = spawnLocation;
		return target != null && teleport(player, target);
	}

	/**
	 * Put the player exactly where the anchor says.
	 *
	 * {@code teleportTo} has been re-signatured repeatedly across the versions one
	 * jar has to serve - it gained a relative-movement set, then a dismount flag -
	 * so the overload is picked reflectively rather than linked. Falling back to
	 * setting the position outright keeps a future rename from stranding a locked
	 * player mid-world.
	 */
	public boolean teleport(ServerPlayer player, StoredLocation target) {
		if (player == null || target == null || serverOf(player) == null) {
			return false;
		}

		ServerLevel targetLevel = target.resolve(serverOf(player));

		if (targetLevel == null) {
			return false;
		}

		try {
			for (Method method : ServerPlayer.class.getMethods()) {
				if (!"teleportTo".equals(method.getName())) {
					continue;
				}

				Class<?>[] parameterTypes = method.getParameterTypes();

				if (parameterTypes.length == 6
					&& ServerLevel.class.isAssignableFrom(parameterTypes[0])
					&& parameterTypes[1] == double.class
					&& parameterTypes[2] == double.class
					&& parameterTypes[3] == double.class
					&& parameterTypes[4] == float.class
					&& parameterTypes[5] == float.class) {
					method.invoke(player, targetLevel, target.x(), target.y(), target.z(), target.yaw(), target.pitch());
					return true;
				}

				if (parameterTypes.length == 7
					&& ServerLevel.class.isAssignableFrom(parameterTypes[0])
					&& parameterTypes[1] == double.class
					&& parameterTypes[2] == double.class
					&& parameterTypes[3] == double.class
					&& Set.class.isAssignableFrom(parameterTypes[4])
					&& parameterTypes[5] == float.class
					&& parameterTypes[6] == float.class) {
					method.invoke(player, targetLevel, target.x(), target.y(), target.z(), Set.of(), target.yaw(), target.pitch());
					return true;
				}

				if (parameterTypes.length == 8
					&& ServerLevel.class.isAssignableFrom(parameterTypes[0])
					&& parameterTypes[1] == double.class
					&& parameterTypes[2] == double.class
					&& parameterTypes[3] == double.class
					&& Set.class.isAssignableFrom(parameterTypes[4])
					&& parameterTypes[5] == float.class
					&& parameterTypes[6] == float.class
					&& parameterTypes[7] == boolean.class) {
					method.invoke(player, targetLevel, target.x(), target.y(), target.z(), Set.of(), target.yaw(), target.pitch(), false);
					return true;
				}
			}

			player.setPos(target.x(), target.y(), target.z());
			player.setYRot(target.yaw());
			player.setXRot(target.pitch());
			player.setYHeadRot(target.yaw());

			return true;
		} catch (ReflectiveOperationException exception) {
			logger.warn("Không thể dịch chuyển người chơi đến auth-spawn: " + exception.getMessage());
			return false;
		}
	}

	/**
	 * The server this player belongs to.
	 *
	 * The {@code server} field went private on 26.x and {@code Entity.getServer()}
	 * is gone there too, so it is reached through the level - which every version
	 * in range agrees on.
	 */
	private static MinecraftServer serverOf(ServerPlayer player) {
		return player.level() instanceof ServerLevel level ? level.getServer() : null;
	}

	private StoredLocation loadSpawnLocation() {
		try {
			Map<String, Object> root = LunaYamlConfig.loadMap(configPath);
			Map<String, Object> authSpawn = ConfigValues.map(root, "auth-spawn");
			String worldId = ConfigValues.string(authSpawn, "world", "");

			if (worldId.isBlank()) {
				return null;
			}

			ResourceKey<Level> dimension = DimensionKeys.parse(worldId);

			if (dimension == null) {
				return null;
			}

			return new StoredLocation(
				dimension,
				asDouble(authSpawn.get("x"), 0D),
				asDouble(authSpawn.get("y"), 0D),
				asDouble(authSpawn.get("z"), 0D),
				(float) asDouble(authSpawn.get("yaw"), 0D),
				(float) asDouble(authSpawn.get("pitch"), 0D)
			);
		} catch (RuntimeException exception) {
			logger.warn("Không thể đọc auth-spawn Fabric: " + exception.getMessage());
			return null;
		}
	}

	private double asDouble(Object value, double fallback) {
		if (value instanceof Number number) {
			return number.doubleValue();
		}

		if (value == null) {
			return fallback;
		}

		try {
			return Double.parseDouble(String.valueOf(value).trim());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	public record StoredLocation(ResourceKey<Level> dimension, double x, double y, double z, float yaw, float pitch) {
		public static StoredLocation capture(ServerPlayer player) {
			return new StoredLocation(
				((ServerLevel) player.level()).dimension(),
				player.getX(),
				player.getY(),
				player.getZ(),
				player.getYRot(),
				player.getXRot()
			);
		}

		public ServerLevel resolve(MinecraftServer server) {
			return server == null ? null : server.getLevel(dimension);
		}
	}
}
