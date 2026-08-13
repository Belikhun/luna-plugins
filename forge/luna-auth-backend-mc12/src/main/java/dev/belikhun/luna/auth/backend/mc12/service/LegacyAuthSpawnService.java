package dev.belikhun.luna.auth.backend.mc12.service;

import dev.belikhun.luna.legacy.config.ConfigValues;
import dev.belikhun.luna.legacy.config.LunaYamlConfig;
import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.string.Strings;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where a player is held while they authenticate, on 1.12.2.
 *
 * The point is written into the mod's own config by an operator running
 * `/auth setspawn` on the proxy, which arrives here as an admin plugin message;
 * there is no separate store, and the file is the same `auth-spawn` block every
 * other platform writes.
 *
 * **A dimension is an int here, not a resource key.** The modern service stores
 * `minecraft:overworld`; this one stores `0`, and reads either - a config copied
 * from a modern backend still resolves, because a lobby is the overworld on every
 * server anyone has actually set this on.
 */
public final class LegacyAuthSpawnService {
	private static final int OVERWORLD = 0;

	private final Path configPath;
	private final LunaLogger logger;

	private volatile StoredLocation spawnLocation;

	public LegacyAuthSpawnService(Path configPath, LunaLogger logger) {
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

	/** Capture the actor's position as the new auth spawn and persist it. */
	public boolean setSpawn(EntityPlayerMP player, String actor) {
		if (player == null) {
			return false;
		}

		StoredLocation updated = StoredLocation.capture(player);

		try {
			Map<String, Object> root = new LinkedHashMap<String, Object>(LunaYamlConfig.loadMap(configPath));
			Map<String, Object> authSpawn = new LinkedHashMap<String, Object>(ConfigValues.map(root, "auth-spawn"));

			authSpawn.put("world", String.valueOf(updated.dimension()));
			authSpawn.put("x", Double.valueOf(updated.x()));
			authSpawn.put("y", Double.valueOf(updated.y()));
			authSpawn.put("z", Double.valueOf(updated.z()));
			authSpawn.put("yaw", Double.valueOf(updated.yaw()));
			authSpawn.put("pitch", Double.valueOf(updated.pitch()));
			authSpawn.put("set-by", actor == null ? "unknown" : actor);
			authSpawn.put("updated-at", Long.valueOf(System.currentTimeMillis()));
			root.put("auth-spawn", authSpawn);

			LunaYamlConfig.dumpMap(configPath, root);
			spawnLocation = updated;

			return true;
		} catch (RuntimeException exception) {
			logger.warn("Không thể lưu auth-spawn: " + exception.getMessage());

			return false;
		}
	}

	public boolean teleportToSpawn(EntityPlayerMP player) {
		StoredLocation target = spawnLocation;

		return target != null && teleport(player, target);
	}

	/**
	 * Put the player exactly where the anchor says.
	 *
	 * `setPlayerLocation` goes through the connection rather than the entity, so
	 * the client is told at the same moment the server moves them; setting the
	 * position on the entity alone leaves the client believing it is elsewhere and
	 * rubber-banding back, which is the whole failure this cage exists to avoid.
	 */
	public boolean teleport(EntityPlayerMP player, StoredLocation target) {
		if (player == null || target == null || player.connection == null) {
			return false;
		}

		MinecraftServer server = player.getServer();

		if (server == null) {
			return false;
		}

		if (player.dimension != target.dimension()) {
			WorldServer targetWorld = server.getWorld(target.dimension());

			if (targetWorld == null) {
				return false;
			}

			server.getPlayerList().transferPlayerToDimension(player, target.dimension(), targetWorld.getDefaultTeleporter());
		}

		player.connection.setPlayerLocation(target.x(), target.y(), target.z(), target.yaw(), target.pitch());

		return true;
	}

	private StoredLocation loadSpawnLocation() {
		try {
			Map<String, Object> root = LunaYamlConfig.loadMap(configPath);
			Map<String, Object> authSpawn = ConfigValues.map(root, "auth-spawn");
			String worldId = ConfigValues.string(authSpawn, "world", "");

			if (Strings.isBlank(worldId)) {
				return null;
			}

			return new StoredLocation(
				parseDimension(worldId),
				asDouble(authSpawn.get("x"), 0D),
				asDouble(authSpawn.get("y"), 0D),
				asDouble(authSpawn.get("z"), 0D),
				(float) asDouble(authSpawn.get("yaw"), 0D),
				(float) asDouble(authSpawn.get("pitch"), 0D)
			);
		} catch (RuntimeException exception) {
			logger.warn("Không thể đọc auth-spawn: " + exception.getMessage());

			return null;
		}
	}

	/**
	 * A dimension id from whatever the file holds.
	 *
	 * An int is this line's own form; the named keys are what a config written by a
	 * modern backend contains, and only the three vanilla ones can be mapped, since
	 * a modded dimension's id is assigned per server.
	 */
	private int parseDimension(String worldId) {
		String value = worldId.trim().toLowerCase(java.util.Locale.ROOT);

		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException ignored) {
			// fall through to the named forms
		}

		if (value.endsWith("the_nether") || value.equals("nether")) {
			return -1;
		}

		if (value.endsWith("the_end") || value.equals("end")) {
			return 1;
		}

		return OVERWORLD;
	}

	private double asDouble(Object value, double fallback) {
		if (value instanceof Number) {
			return ((Number) value).doubleValue();
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

	/** A position a locked player is pinned to. */
	public static final class StoredLocation {
		private final int dimension;
		private final double x;
		private final double y;
		private final double z;
		private final float yaw;
		private final float pitch;

		public StoredLocation(int dimension, double x, double y, double z, float yaw, float pitch) {
			this.dimension = dimension;
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
			this.pitch = pitch;
		}

		public static StoredLocation capture(EntityPlayerMP player) {
			return new StoredLocation(
				player.dimension,
				player.posX,
				player.posY,
				player.posZ,
				player.rotationYaw,
				player.rotationPitch
			);
		}

		public int dimension() {
			return dimension;
		}

		public double x() {
			return x;
		}

		public double y() {
			return y;
		}

		public double z() {
			return z;
		}

		public float yaw() {
			return yaw;
		}

		public float pitch() {
			return pitch;
		}
	}
}
