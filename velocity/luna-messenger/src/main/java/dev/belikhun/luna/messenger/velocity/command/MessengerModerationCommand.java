package dev.belikhun.luna.messenger.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.messenger.velocity.service.VelocityMessengerRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MessengerModerationCommand implements SimpleCommand {
	public enum Action {
		MUTE,
		UNMUTE,
		MUTECHECK,
		WARN
	}

	private final ProxyServer proxyServer;
	private final VelocityMessengerRouter router;
	private final Action action;

	public MessengerModerationCommand(ProxyServer proxyServer, VelocityMessengerRouter router, Action action) {
		this.proxyServer = proxyServer;
		this.router = router;
		this.action = action;
	}

	@Override
	public void execute(Invocation invocation) {
		CommandSource source = invocation.source();
		String[] args = invocation.arguments();
		String permission = switch (action) {
			case MUTE -> "lunamessenger.admin.mute";
			case UNMUTE -> "lunamessenger.admin.unmute";
			case MUTECHECK -> "lunamessenger.admin.mutecheck";
			case WARN -> "lunamessenger.admin.warn";
		};
		if (!source.hasPermission(permission)) {
			source.sendRichMessage("<red>❌ Bạn không có quyền dùng lệnh này.</red>");
			return;
		}

		if (args.length < 1) {
			source.sendRichMessage(usage());
			return;
		}

		String actor = source.toString();
		String target = args[0];
 		VelocityMessengerRouter.ModerationResult result;
		if (action == Action.MUTE) {
			DurationParseResult durationParse = parseOptionalDuration(args);
			List<String> reasonTokens = new ArrayList<>();
			for (int index = durationParse.nextArgIndex(); index < args.length; index++) {
				reasonTokens.add(args[index]);
			}
			String reason = reasonTokens.isEmpty() ? "Không có lý do" : String.join(" ", reasonTokens);
			result = router.muteByName(actor, target, reason, durationParse.durationMillis());
		} else if (action == Action.UNMUTE) {
			result = router.unmuteByName(actor, target);
		} else if (action == Action.MUTECHECK) {
			result = router.muteStatusByName(target);
		} else {
			if (args.length < 2) {
				source.sendRichMessage(usage());
				return;
			}
			String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
			result = router.warnByName(actor, target, reason);
		}

		source.sendRichMessage(result.message());
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		if (invocation.arguments().length == 1) {
			String input = invocation.arguments()[0].toLowerCase();
			return proxyServer.getAllPlayers().stream()
				.map(player -> player.getUsername())
				.filter(name -> name.toLowerCase().startsWith(input))
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.limit(20)
				.toList();
		}
		return List.of();
	}

	private String usage() {
		if (action == Action.MUTE) {
			return CommandStrings.usage("/mute",
				CommandStrings.required("người_chơi", "text"),
				CommandStrings.optional("thời_gian", "duration"),
				CommandStrings.optional("lý_do", "text")
			);
		}
		if (action == Action.UNMUTE) {
			return CommandStrings.usage("/unmute", CommandStrings.required("người_chơi", "text"));
		}
		if (action == Action.MUTECHECK) {
			return CommandStrings.usage("/mutecheck", CommandStrings.required("người_chơi", "text"));
		}
		return CommandStrings.usage("/warn", CommandStrings.required("người_chơi", "text"), CommandStrings.required("lý_do", "text"));
	}

	private DurationParseResult parseOptionalDuration(String[] args) {
		if (args.length < 2) {
			return new DurationParseResult(null, 1);
		}

		Long duration = parseDurationMillis(args[1]);
		if (duration == null) {
			return new DurationParseResult(null, 1);
		}

		return new DurationParseResult(duration, 2);
	}

	private Long parseDurationMillis(String input) {
		if (input == null || input.isBlank()) {
			return null;
		}

		String value = input.trim().toLowerCase(Locale.ROOT);
		long total = 0L;
		boolean matched = false;
		int index = 0;
		while (index < value.length()) {
			int start = index;
			while (index < value.length() && Character.isDigit(value.charAt(index))) {
				index++;
			}

			if (start == index || index >= value.length()) {
				return null;
			}

			long amount;
			try {
				amount = Long.parseLong(value.substring(start, index));
			} catch (NumberFormatException exception) {
				return null;
			}

			char unit = value.charAt(index);
			index++;
			switch (unit) {
				case 'd' -> total += amount * 24L * 60L * 60L * 1000L;
				case 'h' -> total += amount * 60L * 60L * 1000L;
				case 'm' -> total += amount * 60L * 1000L;
				case 's' -> total += amount * 1000L;
				default -> {
					return null;
				}
			}
			matched = true;
		}

		if (!matched || total <= 0L) {
			return null;
		}

		return total;
	}

	private record DurationParseResult(Long durationMillis, int nextArgIndex) {
	}
}
