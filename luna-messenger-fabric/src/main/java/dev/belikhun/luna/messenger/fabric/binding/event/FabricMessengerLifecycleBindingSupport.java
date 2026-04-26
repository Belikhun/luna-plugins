package dev.belikhun.luna.messenger.fabric.binding.event;

import dev.belikhun.luna.core.fabric.util.FabricPlayerNames;
import dev.belikhun.luna.messenger.fabric.service.FabricMessengerGateway;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FabricMessengerLifecycleBindingSupport {
	private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);
	private static final Set<String> SUPPRESSED_GAME_MESSAGE_KEYS = Set.of(
		"multiplayer.player.joined",
		"multiplayer.player.left",
		"multiplayer.player.joined.renamed"
	);

	private FabricMessengerLifecycleBindingSupport() {
	}

	public static boolean register(FabricMessengerGateway gateway) {
		if (gateway == null) {
			return false;
		}
		if (!REGISTERED.compareAndSet(false, true)) {
			return true;
		}

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			PlayerIdentity identity = resolveIdentity(handler.getPlayer());
			if (identity != null) {
				gateway.handleLocalPlayerJoin(identity.id(), identity.name());
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			PlayerIdentity identity = resolveIdentity(handler.getPlayer());
			if (identity != null) {
				gateway.handleLocalPlayerQuit(identity.id());
			}
		});
		ServerMessageEvents.ALLOW_GAME_MESSAGE.register((server, message, overlay) -> shouldAllowGameMessage(message, overlay));
		return true;
	}

	private static boolean shouldAllowGameMessage(Component message, boolean overlay) {
		if (overlay || message == null) {
			return true;
		}

		return !(message.getContents() instanceof TranslatableContents translatable
			&& SUPPRESSED_GAME_MESSAGE_KEYS.contains(translatable.getKey()));
	}

	private static PlayerIdentity resolveIdentity(ServerPlayer player) {
		if (player == null) {
			return null;
		}

		String name = FabricPlayerNames.resolve(player);
		return new PlayerIdentity(player.getUUID(), name);
	}

	private record PlayerIdentity(UUID id, String name) {
	}
}
