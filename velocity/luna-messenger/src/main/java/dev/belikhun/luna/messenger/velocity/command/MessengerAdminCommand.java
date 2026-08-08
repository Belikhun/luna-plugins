package dev.belikhun.luna.messenger.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import dev.belikhun.luna.core.api.string.CommandStrings;

import java.util.List;

public final class MessengerAdminCommand implements SimpleCommand {
	@FunctionalInterface
	public interface ReloadHandler {
		void reload() throws Exception;
	}

	private final ReloadHandler reloadHandler;

	public MessengerAdminCommand(ReloadHandler reloadHandler) {
		this.reloadHandler = reloadHandler;
	}

	@Override
	public void execute(Invocation invocation) {
		CommandSource source = invocation.source();
		String[] args = invocation.arguments();
		if (!source.hasPermission("lunamessenger.admin.reload")) {
			source.sendRichMessage("<red>❌ Bạn không có quyền dùng lệnh này.</red>");
			return;
		}

		if (args.length == 0) {
			source.sendRichMessage(CommandStrings.usage("/lunamessenger", CommandStrings.literal("reload")));
			return;
		}

		if (!args[0].equalsIgnoreCase("reload")) {
			source.sendRichMessage(CommandStrings.usage("/lunamessenger", CommandStrings.literal("reload")));
			return;
		}

		try {
			reloadHandler.reload();
			source.sendRichMessage("<green>✔ Đã tải lại cấu hình LunaMessenger (Velocity).</green>");
		} catch (Exception exception) {
			source.sendRichMessage("<red>❌ Tải lại thất bại: " + exception.getMessage() + "</red>");
		}
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		if (invocation.arguments().length <= 1) {
			String input = invocation.arguments().length == 0 ? "" : invocation.arguments()[0];
			if ("reload".startsWith(input.toLowerCase())) {
				return List.of("reload");
			}
		}
		return List.of();
	}
}
