package dev.belikhun.luna.auth.backend.neoforge.service;

import dev.belikhun.luna.core.api.config.ConfigValues;
import dev.belikhun.luna.core.api.config.LunaYamlConfig;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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
		if (player == null || player.server == null || player.serverLevel() == null) {
			return false;
		}

		StoredLocation updated = StoredLocation.capture(player);
		try {
			Map<String, Object> root = new LinkedHashMap<>(LunaYamlConfig.loadMap(configPath));
			Map<String, Object> authSpawn = new LinkedHashMap<>(ConfigValues.map(root, "auth-spawn"));
			authSpawn.put("world", updated.dimension().location().toString());
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
			logger.warn("Không thể lưu auth-spawn NeoForge: " + exception.getMessage());
			return false;
		}
	}

	public boolean teleportToSpawn(ServerPlayer player) {
		StoredLocation target = spawnLocation;
		return target != null && teleport(player, target);
	}

	public boolean teleport(ServerPlayer player, StoredLocation target) {
		if (player == null || target == null || player.server == null) {
			return false;
		}

		ServerLevel targetLevel = target.resolve(player.server);
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

	private StoredLocation loadSpawnLocation() {
		try {
			Map<String, Object> root = LunaYamlConfig.loadMap(configPath);
			Map<String, Object> authSpawn = ConfigValues.map(root, "auth-spawn");
			String worldId = ConfigValues.string(authSpawn, "world", "");
			if (worldId.isBlank()) {
				return null;
			}
			ResourceLocation location = ResourceLocation.parse(worldId);
			return new StoredLocation(
				ResourceKey.create(Registries.DIMENSION, location),
				asDouble(authSpawn.get("x"), 0D),
				asDouble(authSpawn.get("y"), 0D),
				asDouble(authSpawn.get("z"), 0D),
				(float) asDouble(authSpawn.get("yaw"), 0D),
				(float) asDouble(authSpawn.get("pitch"), 0D)
			);
		} catch (RuntimeException exception) {
			logger.warn("Không thể đọc auth-spawn NeoForge: " + exception.getMessage());
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
				player.serverLevel().dimension(),
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
