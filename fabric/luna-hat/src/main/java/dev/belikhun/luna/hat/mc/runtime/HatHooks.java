package dev.belikhun.luna.hat.mc.runtime;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * How the mixin reaches the mod.
 *
 * A mixin is constructed by the game, not by us, so it cannot be handed anything;
 * this static bridge is the only way it can ask a question. It answers "not mine"
 * whenever the mod is not running, so the game's own rule stands whenever this
 * mod is absent, disabled or still starting.
 */
public final class HatHooks {
	private static volatile HatService service;

	private HatHooks() {
	}

	public static void install(HatService hatService) {
		service = hatService;
	}

	public static void clear() {
		service = null;
	}

	/**
	 * @return whether the helmet slot should accept this item after all
	 */
	public static boolean allowInHelmetSlot(ServerPlayer player, ItemStack stack) {
		HatService current = service;

		if (current == null || player == null || stack == null || stack.isEmpty()) {
			return false;
		}

		return current.mayWear(player, stack);
	}
}
