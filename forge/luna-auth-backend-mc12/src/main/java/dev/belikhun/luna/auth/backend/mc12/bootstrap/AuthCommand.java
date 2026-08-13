package dev.belikhun.luna.auth.backend.mc12.bootstrap;

import dev.belikhun.luna.auth.backend.mc12.runtime.LegacyAuthRestrictionController;
import dev.belikhun.luna.core.mc12.text.LunaTextComponents;
import dev.belikhun.luna.legacy.auth.AuthMessages;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/**
 * `/login <password>` and `/register <password> <confirm>`.
 *
 * Neither decides anything: the arguments go straight to the proxy over the
 * command-request channel and the answer comes back as a `command_response`,
 * which is what actually unlocks the player. So this class only validates the
 * shape of the input and reports a bus that could not carry it.
 *
 * Password arguments are never logged, not even at debug: the auth-flow log
 * records that a request was sent and nothing about what was in it.
 */
public final class AuthCommand extends CommandBase {
	private final LegacyAuthRestrictionController controller;
	private final String name;
	private final int minimumArguments;
	private final int maximumArguments;

	public AuthCommand(LegacyAuthRestrictionController controller, String name, int minimumArguments, int maximumArguments) {
		this.controller = controller;
		this.name = name;
		this.minimumArguments = minimumArguments;
		this.maximumArguments = maximumArguments;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public String getUsage(ICommandSender sender) {
		return "register".equals(name) ? "/register <mật_khẩu> <nhập_lại>" : "/login <mật_khẩu>";
	}

	/**
	 * Zero, not the usual 4.
	 *
	 * `CommandBase` defaults to requiring op and 1.12.2 checks that *before* the
	 * command runs, so leaving it would make `/login` unusable by exactly the
	 * players who need it.
	 */
	@Override
	public int getRequiredPermissionLevel() {
		return 0;
	}

	@Override
	public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
		return true;
	}

	@Override
	public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, net.minecraft.util.math.BlockPos pos) {
		// never complete a password
		return new ArrayList<String>();
	}

	@Override
	public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
		if (!(sender instanceof EntityPlayerMP)) {
			sender.sendMessage(LunaTextComponents.mini(AuthMessages.notAPlayer()));

			return;
		}

		EntityPlayerMP player = (EntityPlayerMP) sender;

		if (args.length < minimumArguments || args.length > maximumArguments) {
			player.sendMessage(LunaTextComponents.mini("register".equals(name)
				? AuthMessages.registerUsage()
				: AuthMessages.loginUsage()));

			return;
		}

		if (!controller.sendAuthCommand(player, name, args)) {
			player.sendMessage(LunaTextComponents.mini(AuthMessages.commandSendFailed()));
		}
	}
}
