package dev.belikhun.luna.messenger.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.messenger.velocity.service.VelocityMessengerRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class MessengerSpyCommand implements SimpleCommand {
	private final ProxyServer proxyServer;
	private final VelocityMessengerRouter router;

	public MessengerSpyCommand(ProxyServer proxyServer, VelocityMessengerRouter router) {
		this.proxyServer = proxyServer;
		this.router = router;
	}

	@Override
	public void execute(Invocation invocation) {
		CommandSource source = invocation.source();
		if (!source.hasPermission("lunamessenger.admin.spy")) {
			source.sendRichMessage("<red>❌ Bạn không có quyền dùng lệnh này.</red>");
			return;
		}

		if (!(source instanceof Player player)) {
			source.sendRichMessage("<red>❌ Lệnh này chỉ dùng cho người chơi.</red>");
			return;
		}

		String[] args = invocation.arguments();
		UUID watcherId = player.getUniqueId();
		if (args.length == 0) {
			source.sendRichMessage(router.spyStatus(watcherId).message());
			source.sendRichMessage(CommandStrings.usage("/spy", CommandStrings.optional("all|off|người_chơi", "text")));
			return;
		}

		String mode = args[0].toLowerCase(Locale.ROOT);
		VelocityMessengerRouter.ModerationResult result;
		if (mode.equals("off") || mode.equals("disable") || mode.equals("none")) {
			result = router.disableSpy(watcherId);
		} else if (mode.equals("all") || mode.equals("*")) {
			result = router.enableSpyAll(watcherId);
		} else {
			result = router.enableSpyTarget(watcherId, args[0]);
		}

		source.sendRichMessage(result.message());
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		if (invocation.arguments().length != 1) {
			return List.of();
		}

		String input = invocation.arguments()[0].toLowerCase(Locale.ROOT);
		List<String> suggestions = new ArrayList<>();
		if ("all".startsWith(input)) {
			suggestions.add("all");
		}
		if ("off".startsWith(input)) {
			suggestions.add("off");
		}

		proxyServer.getAllPlayers().stream()
			.map(Player::getUsername)
			.filter(name -> name.toLowerCase(Locale.ROOT).startsWith(input))
			.sorted(String.CASE_INSENSITIVE_ORDER)
			.limit(20)
			.forEach(suggestions::add);
		return suggestions.stream().distinct().limit(20).toList();
	}
}
