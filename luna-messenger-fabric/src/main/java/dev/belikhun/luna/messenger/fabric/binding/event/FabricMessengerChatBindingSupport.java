package dev.belikhun.luna.messenger.fabric.binding.event;

import dev.belikhun.luna.messenger.fabric.service.FabricMessengerCommandService;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FabricMessengerChatBindingSupport {

	private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

	private FabricMessengerChatBindingSupport() {
	}

	public static boolean register(FabricMessengerCommandService commandService) {
		if (commandService == null) {
			return false;
		}
		if (!REGISTERED.compareAndSet(false, true)) {
			return true;
		}

		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> handleChat(commandService, sender, message.signedContent(), true));
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> handleChat(commandService, sender, message.signedContent(), false));
		return true;
	}

	private static boolean handleChat(
		FabricMessengerCommandService commandService,
		ServerPlayer sender,
		String content,
		boolean cancellable
	) {
		PlayerIdentity identity = resolveIdentity(sender);
		if (identity == null || content == null || content.isBlank()) {
			return true;
		}

		var result = commandService.sendChat(identity.id(), identity.name(), identity.server(), content);
		if (!result.success()) {
			return true;
		}

		return !cancellable;
	}

	private static PlayerIdentity resolveIdentity(ServerPlayer player) {
		if (player == null) {
			return null;
		}

		String name = player.getGameProfile().getName();
		if (name == null || name.isBlank()) {
			name = player.getName().getString();
		}
		return new PlayerIdentity(player.getUUID(), name, "fabric");
	}

	private record PlayerIdentity(UUID id, String name, String server) {
	}
}
