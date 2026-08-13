package dev.belikhun.luna.shop.mc12.runtime;

import dev.belikhun.luna.legacy.shop.ShopGameClock;

import net.minecraft.server.MinecraftServer;

/**
 * The overworld's game time, which is what a daily trade limit resets on.
 *
 * Dimension 0 rather than a named key: worlds are ints on this line, and the
 * overworld is the one every server has.
 */
public final class LegacyShopGameClock implements ShopGameClock {
	private static final int OVERWORLD = 0;

	private final MinecraftServer server;

	public LegacyShopGameClock(MinecraftServer server) {
		this.server = server;
	}

	@Override
	public long gameTime() {
		if (server == null || server.getWorld(OVERWORLD) == null) {
			return -1L;
		}

		return server.getWorld(OVERWORLD).getTotalWorldTime();
	}
}
