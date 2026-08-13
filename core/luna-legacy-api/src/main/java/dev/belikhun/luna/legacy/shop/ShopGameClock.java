package dev.belikhun.luna.legacy.shop;

/**
 * The one thing a daily trade limit needs from the world: what day it is.
 *
 * Limits reset on the Minecraft day rather than the wall clock, so the service
 * has to read the overworld's game time - and that is its entire coupling to the
 * game, which is why it arrives as this instead of a server object.
 */
public interface ShopGameClock {
	/** Ticks since the world began, or -1 when there is no world to ask. */
	long gameTime();

	/** A clock for a server that has no world yet; falls back to the wall clock. */
	ShopGameClock NONE = new ShopGameClock() {
		@Override
		public long gameTime() {
			return -1L;
		}
	};
}
