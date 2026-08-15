package dev.belikhun.luna.legacy.tabbridge;

import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.messaging.bus.PluginMessagingBus;
import dev.belikhun.luna.legacy.permission.PermissionService;

/** Builds the TAB bridge runtime, or the no-op one when it cannot be carried. */
public final class TabBridgeRuntimeFactory {
	private TabBridgeRuntimeFactory() {
	}

	/**
	 * @param bus               the messaging bus; without one there is no channel to speak on
	 * @param players           the platform's player seam
	 * @param permissions       answers TAB's permission and group questions; may be null
	 * @param playerStateSource vanish and disguise, when this backend has a plugin
	 *                          that knows; null means nobody is either
	 */
	public static <P> TabBridgeRuntime<P> create(
		LunaLogger logger,
		PluginMessagingBus<P> bus,
		TabPlayerBridge<P> players,
		PermissionService permissions,
		TabBridgePlayerStateSource<P> playerStateSource
	) {
		LunaLogger runtimeLogger = logger.scope("Runtime");

		if (bus == null) {
			runtimeLogger.warn("Thiếu PluginMessagingBus, fallback sang TAB bridge runtime no-op.");

			return new NoopTabBridgeRuntime<P>();
		}

		return new RawChannelTabBridgeRuntime<P>(runtimeLogger, bus, players, permissions, playerStateSource);
	}

	/** The relational source a backend gets when nothing else registers one. */
	public static <P> TabBridgeRelationalPlaceholderSource<P> defaultRelationalSource(
		TabPlayerBridge<P> players,
		PermissionService permissions
	) {
		if (players == null) {
			return new NoopTabBridgeRelationalPlaceholderSource<P>();
		}

		return new BuiltInTabBridgeRelationalPlaceholderSource<P>(players, permissions);
	}
}
