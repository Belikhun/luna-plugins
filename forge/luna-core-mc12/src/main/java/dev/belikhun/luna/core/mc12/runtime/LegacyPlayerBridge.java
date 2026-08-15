package dev.belikhun.luna.core.mc12.runtime;

import dev.belikhun.luna.legacy.messaging.bus.PlayerBridge;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;

/**
 * The five Minecraft calls the shared trunks need, on 1.12.2.
 *
 * It lives in the core and is published through the service registry, because it
 * is not one feature's glue: the messaging bus routes bytes with it and the
 * messenger looks players up with it, and a second copy per mod would be five
 * more chances to disagree about what a player is.
 *
 * MCP names throughout (`getUniqueID`, `getPlayerByUUID`, `addScheduledTask`),
 * which RFG reobfuscates to SRG at packaging.
 */
public final class LegacyPlayerBridge implements PlayerBridge<EntityPlayerMP> {
	private final MinecraftServer server;

	public LegacyPlayerBridge(MinecraftServer server) {
		if (server == null) {
			throw new IllegalArgumentException("server");
		}

		this.server = server;
	}

	@Override
	public UUID idOf(EntityPlayerMP player) {
		return player.getUniqueID();
	}

	@Override
	public String nameOf(EntityPlayerMP player) {
		return player.getGameProfile().getName();
	}

	@Override
	public EntityPlayerMP byId(UUID id) {
		return server.getPlayerList().getPlayerByUUID(id);
	}

	@Override
	public EntityPlayerMP byName(String name) {
		return server.getPlayerList().getPlayerByUsername(name);
	}

	@Override
	public Collection<EntityPlayerMP> online() {
		// a copy: the caller may pick from this while a join or quit mutates the
		// server's own list, and 1.12.2 hands back the live one
		return new ArrayList<EntityPlayerMP>(server.getPlayerList().getPlayers());
	}

	/**
	 * 1.12.2's answer to `MinecraftServer.execute`.
	 *
	 * Deliberately **not** `addScheduledTask`: that takes a monitor the server
	 * thread holds for the whole of every packet handler, so handing work over from
	 * a network thread can block for as long as a handler runs - and a handler
	 * waiting on a reply that arrives through here waits for itself. See
	 * {@link ServerThreadTasks}. The contract is otherwise identical: next tick, or
	 * inline when the caller is already the server thread.
	 */
	@Override
	public void onServerThread(Runnable task) {
		ServerThreadTasks.run(server, task);
	}
}
