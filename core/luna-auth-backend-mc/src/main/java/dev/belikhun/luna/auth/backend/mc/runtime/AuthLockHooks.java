package dev.belikhun.luna.auth.backend.mc.runtime;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * How a mixin reaches the running controller.
 *
 * Three of the things the forge loaders give an event for - running a command,
 * picking an item up, throwing one away - have no fabric-api equivalent, so those are
 * intercepted with mixins instead. A mixin is loaded by the game before any mod
 * is constructed and holds no reference to one, so it asks here rather than being
 * handed a controller.
 *
 * Every method answers "allowed" when there is no controller yet: the mod not
 * being started is not a reason to break the server it was added to.
 */
public final class AuthLockHooks {
	private static volatile AuthRestrictionController controller;

	private AuthLockHooks() {
	}

	public static void install(AuthRestrictionController running) {
		controller = running;
	}

	public static void clear(AuthRestrictionController running) {
		if (controller == running) {
			controller = null;
		}
	}

	/** Whether this player is still waiting to authenticate. */
	public static boolean isLocked(Player player) {
		AuthRestrictionController current = controller;

		return current != null && current.isLocked(player);
	}

	/** False when the command must not run; the player has been told why. */
	public static boolean allowCommand(ServerPlayer player, String rawCommand) {
		AuthRestrictionController current = controller;

		return current == null || current.allowCommand(player, rawCommand);
	}

	/** Whether this drop must be refused; the stack is put back by the caller. */
	public static boolean refuseDrop(ServerPlayer player, ItemStack stack) {
		AuthRestrictionController current = controller;

		return current != null && current.refuseDrop(player, stack);
	}

	/** Put back an item a locked player managed to throw. */
	public static void restoreTossedItem(ServerPlayer player, ItemStack tossed) {
		AuthRestrictionController current = controller;

		if (current != null) {
			current.restoreTossedItem(player, tossed);
		}
	}
}
