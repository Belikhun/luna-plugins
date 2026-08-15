package dev.belikhun.luna.legacy.tabbridge;

import dev.belikhun.luna.legacy.messaging.PluginMessageChannel;

/**
 * TAB's own channel, not one of luna's.
 *
 * The `-6` is TAB's bridge protocol version, and it is part of the name: TAB 5.x
 * speaks `tab:bridge-6` and a backend answering on `tab:bridge-5` is simply not
 * heard. Bumping it is a protocol change, never a tidy-up.
 *
 * It also happens to be what makes this feature possible on 1.12.2 at all. That
 * protocol caps a channel name at 20 characters, which is why every `luna:`
 * channel goes through the broker instead; `tab:bridge-6` is twelve, so it can
 * ride the player's connection - and it has to, because TAB's proxy half listens
 * for plugin messages and knows nothing about luna's queues.
 */
public final class TabBridgeChannels {
	public static final PluginMessageChannel BRIDGE = PluginMessageChannel.of("tab:bridge-6");

	private TabBridgeChannels() {
	}
}
