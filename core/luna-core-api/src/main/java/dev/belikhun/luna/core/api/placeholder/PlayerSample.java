package dev.belikhun.luna.core.api.placeholder;

import dev.belikhun.luna.core.api.placeholder.LunaImportedPlaceholderSupport.VoiceChatStatus;
import dev.belikhun.luna.core.api.placeholder.LunaImportedPlaceholderSupport.WorldKind;

import java.util.UUID;

/**
 * The facts about one player that placeholders read, taken on the server
 * thread and then immutable.
 *
 * Built with {@link Builder} because a platform fills what it can and leaves
 * the rest at the defaults; a Paper host has no game mode name for an offline
 * player, a proxy host has no position at all.
 */
public record PlayerSample(
	UUID id,
	String name,
	String displayName,
	int pingMillis,
	String worldName,
	WorldKind worldKind,
	String dimensionId,
	int blockX,
	int blockY,
	int blockZ,
	double health,
	double maxHealth,
	int level,
	int foodLevel,
	String gameMode,
	String biome,
	String prefix,
	String suffix,
	String groupName,
	String groupDisplayName,
	VoiceChatStatus voiceChatStatus,
	String voiceChatGroup,
	long sampledAtEpochMillis
) {
	public static Builder builder(UUID id, String name) {
		return new Builder(id, name);
	}

	/** Age of this sample in milliseconds. */
	public long ageMillis() {
		return sampledAtEpochMillis <= 0L ? -1L : Math.max(0L, System.currentTimeMillis() - sampledAtEpochMillis);
	}

	public static final class Builder {
		private final UUID id;
		private final String name;
		private String displayName;
		private int pingMillis;
		private String worldName = "";
		private WorldKind worldKind = WorldKind.CUSTOM;
		private String dimensionId = "";
		private int blockX;
		private int blockY;
		private int blockZ;
		private double health;
		private double maxHealth;
		private int level;
		private int foodLevel;
		private String gameMode = "";
		private String biome = "";
		private String prefix = "";
		private String suffix = "";
		private String groupName = "";
		private String groupDisplayName = "";
		private VoiceChatStatus voiceChatStatus = VoiceChatStatus.UNKNOWN;
		private String voiceChatGroup = "null";

		private Builder(UUID id, String name) {
			this.id = id;
			this.name = name == null ? "" : name;
			this.displayName = this.name;
		}

		public Builder displayName(String value) {
			displayName = value == null ? name : value;
			return this;
		}

		public Builder ping(int value) {
			pingMillis = Math.max(0, value);
			return this;
		}

		public Builder world(String name, WorldKind kind, String dimension) {
			worldName = name == null ? "" : name;
			worldKind = kind == null ? WorldKind.CUSTOM : kind;
			dimensionId = dimension == null ? "" : dimension;
			return this;
		}

		public Builder position(int x, int y, int z) {
			blockX = x;
			blockY = y;
			blockZ = z;
			return this;
		}

		public Builder health(double current, double max) {
			health = Math.max(0D, current);
			maxHealth = Math.max(0D, max);
			return this;
		}

		public Builder level(int value) {
			level = Math.max(0, value);
			return this;
		}

		public Builder food(int value) {
			foodLevel = Math.max(0, value);
			return this;
		}

		public Builder gameMode(String value) {
			gameMode = value == null ? "" : value;
			return this;
		}

		public Builder biome(String value) {
			biome = value == null ? "" : value;
			return this;
		}

		public Builder permissions(String prefixValue, String suffixValue, String group, String groupDisplay) {
			prefix = prefixValue == null ? "" : prefixValue;
			suffix = suffixValue == null ? "" : suffixValue;
			groupName = group == null ? "" : group;
			groupDisplayName = groupDisplay == null ? "" : groupDisplay;
			return this;
		}

		public Builder voiceChat(VoiceChatStatus status, String group) {
			voiceChatStatus = status == null ? VoiceChatStatus.UNKNOWN : status;
			voiceChatGroup = group == null ? "null" : group;
			return this;
		}

		public PlayerSample build() {
			return new PlayerSample(
				id, name, displayName, pingMillis, worldName, worldKind, dimensionId,
				blockX, blockY, blockZ, health, maxHealth, level, foodLevel, gameMode, biome,
				prefix, suffix, groupName, groupDisplayName, voiceChatStatus, voiceChatGroup,
				System.currentTimeMillis()
			);
		}
	}
}
