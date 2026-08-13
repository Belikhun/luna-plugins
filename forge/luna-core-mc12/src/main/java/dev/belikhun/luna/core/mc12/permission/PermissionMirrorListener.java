package dev.belikhun.luna.core.mc12.permission;

import dev.belikhun.luna.legacy.permission.MirroredPermissionService;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

/**
 * Keeps the permission mirror in step with who is actually on the server.
 *
 * Warming on join is what makes the mirror usable: a snapshot fetched the moment a
 * player connects is almost always there by the time they type anything, so the
 * UNDEFINED window the mirror documents stays a startup detail rather than something
 * players meet. Forgetting on quit is what makes a rejoin re-read the proxy, so a
 * permission changed in the console takes effect on the next login instead of at the
 * end of the cache TTL.
 */
public final class PermissionMirrorListener {
	private final MirroredPermissionService permissions;

	public PermissionMirrorListener(MirroredPermissionService permissions) {
		this.permissions = permissions;
	}

	@SubscribeEvent
	public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
		EntityPlayer player = event.player;

		if (player == null) {
			return;
		}

		permissions.warm(player.getUniqueID(), player.getName());
	}

	@SubscribeEvent
	public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
		EntityPlayer player = event.player;

		if (player == null) {
			return;
		}

		permissions.forget(player.getUniqueID());
	}
}
