package dev.belikhun.luna.core.fabric.ui;

import dev.belikhun.luna.core.mc.ui.ChatPrompts;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import java.util.function.Supplier;

/**
 * The fabric half of {@link ChatPrompts}: which event a swallowed chat line
 * arrives on.
 *
 * Everything else - who is waiting, and getting the answer back onto the server
 * thread - is shared with the other loaders.
 */
public final class FabricChatPrompts {
	private FabricChatPrompts() {
	}

	/**
	 * Hook the chat and disconnect events.
	 *
	 * Called once, by the core's bootstrap. Fabric's events are static, so this
	 * must not run per module or per server start; the prompts themselves are
	 * looked up through the supplier so they can be swapped when a server does.
	 */
	public static void registerEvents(Supplier<ChatPrompts> holder) {
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
			ChatPrompts prompts = holder.get();

			if (prompts == null || sender == null) {
				return true;
			}

			return !prompts.consume(sender.getUUID(), message.signedContent());
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, ignored) -> {
			ChatPrompts prompts = holder.get();

			if (prompts != null && handler.getPlayer() != null) {
				prompts.cancel(handler.getPlayer().getUUID());
			}
		});
	}
}
