package dev.belikhun.luna.core.mc.placeholder;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The game readings whose accessor moved between Minecraft versions.
 *
 * The host is version-blind and reads the world through this; each loader
 * module supplies the implementation its compat sets allow. Every method must
 * be safe to call on the server thread and must never throw for a missing
 * value, returning the documented fallback instead.
 */
public interface GameReads {
	/** Ticks per second over the recent window; 20 when unknown. */
	double tps(MinecraftServer server);

	/** Mean tick duration in milliseconds; 0 when the server has not ticked yet. */
	double tickDurationMillis(MinecraftServer server);

	/** A player's round trip in milliseconds; 0 when unknown. */
	int pingMillis(ServerPlayer player);

	/** The level a player stands in, never null while the player is online. */
	ServerLevel levelOf(ServerPlayer player);

	/** Ticks into the current day, 0 to 23999. */
	long dayTimeTicks(ServerLevel level);

	/** How long the current weather still runs, in ticks; 0 when the level cannot say. */
	long weatherDurationTicks(ServerLevel level, boolean raining, boolean thundering);

	/** The path of the biome the player stands in; "unknown" when it cannot be read. */
	String biomePath(ServerPlayer player);

	/** A level's full dimension id, {@code minecraft:the_nether}; blank when unknown. */
	String dimensionId(ServerLevel level);

	/** A level's dimension path alone, {@code the_nether}; blank when unknown. */
	String dimensionPath(ServerLevel level);
}
