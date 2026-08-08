package dev.belikhun.luna.glyph.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import dev.belikhun.luna.core.api.string.CommandStrings;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class LunaGlyphAdminCommand implements SimpleCommand {
	private static final MiniMessage MM = MiniMessage.miniMessage();

	@FunctionalInterface
	public interface ReloadAction {
		void reload(Consumer<String> reporter) throws Exception;
	}

	private final ReloadAction reloadAction;

	public LunaGlyphAdminCommand(ReloadAction reloadAction) {
		this.reloadAction = reloadAction;
	}

	@Override
	public void execute(Invocation invocation) {
		CommandSource source = invocation.source();
		if (!source.hasPermission("lunaglyph.admin")) {
			source.sendRichMessage("<red>❌ Bạn không có quyền dùng lệnh này.</red>");
			return;
		}

		String[] args = invocation.arguments();
		if (args.length == 0) {
			source.sendRichMessage(CommandStrings.usage("/lunaglyph", CommandStrings.literal("reload")));
			return;
		}

		String sub = args[0].trim().toLowerCase(Locale.ROOT);
		if (!sub.equals("reload")) {
			source.sendRichMessage(CommandStrings.usage("/lunaglyph", CommandStrings.literal("reload")));
			return;
		}

		source.sendRichMessage("<yellow>⌛ Đang reload LunaGlyph...</yellow>");
		Consumer<String> reporter = line -> source.sendRichMessage("<gray>[LunaGlyph] " + MM.escapeTags(line) + "</gray>");
		try {
			reloadAction.reload(reporter);
			source.sendRichMessage("<green>✔ Reload LunaGlyph thành công.</green>");
		} catch (Exception exception) {
			source.sendRichMessage("<red>❌ Reload LunaGlyph thất bại: <white>" + String.valueOf(exception.getMessage()) + "</white></red>");
		}
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		String[] args = invocation.arguments();
		if (args.length == 0) {
			return List.of("reload");
		}

		if (args.length == 1 && "reload".startsWith(args[0].toLowerCase(Locale.ROOT))) {
			return List.of("reload");
		}

		return List.of();
	}
}
