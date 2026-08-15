package dev.belikhun.luna.core.mc.heartbeat;

import dev.belikhun.luna.core.api.heartbeat.ServerWorldStats;
import dev.belikhun.luna.core.mc.compat.DimensionKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/**
 * Counting what each dimension is holding, for the loaders that share Mojang's
 * server classes.
 *
 * Shared by Fabric, NeoForge and Forge because it is the same game code on all
 * three; every call here is compiled rather than reflected, so the toolchain
 * remaps it and Fabric's intermediary names are a non-issue. That is also why
 * this lives in the trunk's {@code main} set rather than {@code services}, which
 * only two of the three include.
 *
 * Entities are classified by whether the chunk they are standing in is being
 * ticked. {@code ServerLevel} exposes that as a position query rather than as a
 * flag on the entity, so it is asked per entity - the cheap direction, since a
 * world holds far more chunks than entities once the entities are counted at all.
 */
public final class LevelStats {
	private LevelStats() {
	}

	/** One row per loaded dimension; empty when the server has none yet. */
	public static List<ServerWorldStats> scan(MinecraftServer server) {
		if (server == null) {
			return List.of();
		}

		List<ServerWorldStats> worlds = new ArrayList<>();

		for (ServerLevel level : server.getAllLevels()) {
			if (level == null) {
				continue;
			}

			try {
				worlds.add(scanLevel(level));
			} catch (Throwable ignored) {
				// one dimension that will not answer must not cost the others their
				// row; a version that moved a method takes itself out of the report
			}
		}

		return List.copyOf(worlds);
	}

	private static ServerWorldStats scanLevel(ServerLevel level) {
		int ticking = 0;
		int nonTicking = 0;

		// `getAllEntities()` rather than the entity getter behind it: that one is
		// protected under Fabric's mappings and public under NeoForge's, so reaching
		// for it compiles on one loader and not the other
		for (Entity entity : level.getAllEntities()) {
			if (entity == null) {
				continue;
			}

			if (level.isPositionEntityTicking(entity.blockPosition())) {
				ticking++;
			} else {
				nonTicking++;
			}
		}

		return new ServerWorldStats(
			// through the compat helper: 26.x renamed the identifier class and the
			// accessor that reads it back, which is exactly what that set is for
			DimensionKeys.name(level.dimension()),
			level.getChunkSource().getLoadedChunksCount(),
			ticking,
			nonTicking
		);
	}
}
