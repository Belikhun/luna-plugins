package dev.belikhun.luna.messenger.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.messenger.velocity.service.VelocityMessengerRouter;

import java.util.List;

public final class MessengerBroadcastCommand implements SimpleCommand {
	private final VelocityMessengerRouter router;

	public MessengerBroadcastCommand(VelocityMessengerRouter router) {
		this.router = router;
	}

	@Override
	public void execute(Invocation invocation) {
		CommandSource source = invocation.source();
		if (!source.hasPermission("lunamessenger.admin.broadcast")) {
			source.sendRichMessage("<red>❌ Bạn không có quyền dùng lệnh này.</red>");
			return;
		}

		String[] args = invocation.arguments();
		if (args.length < 1) {
			source.sendRichMessage(CommandStrings.usage("/broadcast", CommandStrings.required("nội_dung", "text")));
			return;
		}

		String actor = source.toString();
		String message = String.join(" ", args);
		VelocityMessengerRouter.ModerationResult result = router.broadcast(actor, message);
		source.sendRichMessage(result.message());
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		return List.of();
	}
}
