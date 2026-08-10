package dev.belikhun.luna.auth.backend.fabric.mixin;

import com.mojang.brigadier.ParseResults;
import dev.belikhun.luna.auth.backend.mc.runtime.AuthLockHooks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Refuse a command from a player who has not authenticated yet.
 *
 * This is the one restriction that is a security boundary rather than a comfort:
 * without it an unauthenticated session could run anything the account is opped
 * for. NeoForge has {@code CommandEvent} for this; Fabric API has no
 * command-execution event, so the dispatcher is intercepted directly.
 *
 * The allow-list lives in the controller, because {@code /login} and
 * {@code /register} have to get through - refusing every command would lock the
 * player out of the only thing that unlocks them.
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {
	@Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
	private void luna$refuseUntilAuthenticated(ParseResults<CommandSourceStack> parseResults, String command, CallbackInfo callback) {
		if (!(parseResults.getContext().getSource().getEntity() instanceof ServerPlayer player)) {
			return;
		}

		if (!AuthLockHooks.allowCommand(player, command)) {
			callback.cancel();
		}
	}
}
