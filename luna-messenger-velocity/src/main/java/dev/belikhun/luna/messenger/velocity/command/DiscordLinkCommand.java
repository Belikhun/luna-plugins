package dev.belikhun.luna.messenger.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.api.ui.LunaPalette;
import dev.belikhun.luna.messenger.velocity.service.DiscordAccountLinkService;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

public final class DiscordLinkCommand implements SimpleCommand {
	private static final String DISCORD_CHANNEL_COLOR = LunaPalette.INFO_500;
	private static final String LINK_COMMAND_COLOR = LunaPalette.WARNING_300;

	private final ProxyServer proxyServer;
	private final Supplier<DiscordAccountLinkService> linkServiceSupplier;

	public DiscordLinkCommand(ProxyServer proxyServer, Supplier<DiscordAccountLinkService> linkServiceSupplier) {
		this.proxyServer = proxyServer;
		this.linkServiceSupplier = linkServiceSupplier;
	}

	@Override
	public void execute(Invocation invocation) {
		CommandSource source = invocation.source();
		String[] args = invocation.arguments();
		if (args.length == 0) {
			source.sendRichMessage(usage());
			return;
		}

		String action = args[0].trim().toLowerCase();
		switch (action) {
			case "link" -> executeLink(source);
			case "unlink" -> executeUnlink(source);
			case "info" -> executeInfo(source, args);
			default -> source.sendRichMessage(usage());
		}
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		String[] args = invocation.arguments();
		if (args.length == 0) {
			return List.of("link", "unlink", "info");
		}
		if (args.length == 1) {
			String input = args[0].toLowerCase();
			return List.of("link", "unlink", "info").stream()
				.filter(value -> value.startsWith(input))
				.toList();
		}
		if (args.length == 2 && "info".equalsIgnoreCase(args[0])) {
			String input = args[1].toLowerCase();
			return proxyServer.getAllPlayers().stream()
				.map(Player::getUsername)
				.filter(name -> name.toLowerCase().startsWith(input))
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.limit(20)
				.toList();
		}

		return List.of();
	}

	private void executeLink(CommandSource source) {
		DiscordAccountLinkService linkService = linkServiceSupplier.get();
		if (linkService == null) {
			source.sendRichMessage("<red>❌ Dịch vụ liên kết Discord chưa sẵn sàng.</red>");
			return;
		}

		if (!source.hasPermission("lunamessenger.discord.link")) {
			source.sendRichMessage("<red>❌ Bạn không có quyền dùng lệnh này.</red>");
			return;
		}
		if (!(source instanceof Player player)) {
			source.sendRichMessage("<red>❌ Lệnh này chỉ dùng cho người chơi.</red>");
			return;
		}

		DiscordAccountLinkService.BeginLinkResult result = linkService.beginLink(player.getUniqueId(), player.getUsername());
		if (result.databaseDisabled()) {
			source.sendRichMessage("<red>❌ Hệ thống liên kết Discord đang tắt vì LunaCore database chưa sẵn sàng.</red>");
			return;
		}

		source.sendRichMessage("Liên kết tài khoản discord của bạn với tài khoản minecraft này");
		source.sendRichMessage("bằng cách chạy lệnh sau trong kênh <color:" + DISCORD_CHANNEL_COLOR + ">#🧱-minecraft</color>:");
		source.sendRichMessage("");
		source.sendRichMessage(
			"<click:copy_to_clipboard:'/link " + result.code() + "'><hover:show_text:'<gray>Nhấn để sao chép lệnh</gray>'>"
				+ "<color:" + LINK_COMMAND_COLOR + ">/link " + result.code() + "</color>"
				+ "</hover></click>"
		);
		source.sendRichMessage("");
		source.sendRichMessage("<gray>ℹ Sử dụng câu lệnh của bot trong kênh này thay vì nhập trực tiếp tin nhắn vào chat</gray>");
		source.sendRichMessage("<gray>Mã hết hạn lúc: <white>" + Formatters.date(Instant.ofEpochMilli(result.expiresAtEpochMs())) + "</white></gray>");
	}

	private void executeUnlink(CommandSource source) {
		DiscordAccountLinkService linkService = linkServiceSupplier.get();
		if (linkService == null) {
			source.sendRichMessage("<red>❌ Dịch vụ liên kết Discord chưa sẵn sàng.</red>");
			return;
		}

		if (!source.hasPermission("lunamessenger.discord.unlink")) {
			source.sendRichMessage("<red>❌ Bạn không có quyền dùng lệnh này.</red>");
			return;
		}
		if (!(source instanceof Player player)) {
			source.sendRichMessage("<red>❌ Lệnh này chỉ dùng cho người chơi.</red>");
			return;
		}

		DiscordAccountLinkService.UnlinkResult result = linkService.unlinkByPlayer(player.getUniqueId());
		if (result.databaseDisabled()) {
			source.sendRichMessage("<red>❌ Hệ thống liên kết Discord đang tắt vì LunaCore database chưa sẵn sàng.</red>");
			return;
		}
		if (result.notLinked()) {
			source.sendRichMessage("<yellow>ℹ Bạn chưa liên kết tài khoản Discord.</yellow>");
			return;
		}
		if (!result.success() || result.account() == null) {
			source.sendRichMessage("<red>❌ Không thể hủy liên kết lúc này. Vui lòng thử lại sau.</red>");
			return;
		}

		source.sendRichMessage("<green>✔ Đã hủy liên kết Discord <white>" + escape(result.account().discordUsername()) + "</white>.</green>");
	}

	private void executeInfo(CommandSource source, String[] args) {
		DiscordAccountLinkService linkService = linkServiceSupplier.get();
		if (linkService == null) {
			source.sendRichMessage("<red>❌ Dịch vụ liên kết Discord chưa sẵn sàng.</red>");
			return;
		}

		if (!source.hasPermission("lunamessenger.admin.discord.info")) {
			source.sendRichMessage("<red>❌ Bạn không có quyền dùng lệnh này.</red>");
			return;
		}
		if (args.length < 2) {
			source.sendRichMessage(CommandStrings.usage("/discord", CommandStrings.literal("info"), CommandStrings.required("username", "text")));
			return;
		}

		String username = args[1];
		var linked = linkService.findByMinecraftName(username);
		if (linked.isEmpty()) {
			source.sendRichMessage("<yellow>ℹ Không tìm thấy liên kết Discord cho người chơi <white>" + escape(username) + "</white>.</yellow>");
			return;
		}

		DiscordAccountLinkService.LinkedAccount account = linked.get();
		source.sendRichMessage("<gold>Thông tin liên kết Discord</gold>");
		source.sendRichMessage("<gray>• Minecraft:</gray> <white>" + escape(account.playerName()) + "</white>");
		source.sendRichMessage("<gray>• UUID:</gray> <white>" + account.playerId() + "</white>");
		source.sendRichMessage("<gray>• Discord ID:</gray> <white>" + escape(account.discordUserId()) + "</white>");
		source.sendRichMessage("<gray>• Discord User:</gray> <white>" + escape(account.discordUsername()) + "</white>");
		source.sendRichMessage("<gray>• Linked At:</gray> <white>" + Formatters.date(Instant.ofEpochMilli(account.linkedAtEpochMs())) + "</white>");
		source.sendRichMessage("<gray>• Updated At:</gray> <white>" + Formatters.date(Instant.ofEpochMilli(account.updatedAtEpochMs())) + "</white>");
	}

	private String usage() {
		return CommandStrings.usage("/discord",
			CommandStrings.literal("link"),
			CommandStrings.literal("unlink"),
			CommandStrings.literal("info")
		);
	}

	private String escape(String value) {
		if (value == null) {
			return "";
		}
		return value.replace("<", "&lt;").replace(">", "&gt;");
	}
}
