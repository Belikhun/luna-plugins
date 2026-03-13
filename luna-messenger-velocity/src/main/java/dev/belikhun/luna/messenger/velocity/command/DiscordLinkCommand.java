package dev.belikhun.luna.messenger.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.messenger.velocity.service.DiscordAccountLinkService;
import dev.belikhun.luna.messenger.velocity.service.DiscordLinkInstructionMessages;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

public final class DiscordLinkCommand implements SimpleCommand {
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
			case "bypass" -> executeBypass(source, args);
			case "unbypass" -> executeUnbypass(source, args);
			case "info" -> executeInfo(source, args);
			default -> source.sendRichMessage(usage());
		}
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		String[] args = invocation.arguments();
		if (args.length == 0) {
			return List.of("link", "unlink", "bypass", "unbypass", "info");
		}
		if (args.length == 1) {
			String input = args[0].toLowerCase();
			return List.of("link", "unlink", "bypass", "unbypass", "info").stream()
				.filter(value -> value.startsWith(input))
				.toList();
		}
		if (args.length == 2 && "bypass".equalsIgnoreCase(args[0])) {
			String input = args[1].toLowerCase();
			List<String> values = new java.util.ArrayList<>();
			values.add("list");
			values.addAll(proxyServer.getAllPlayers().stream()
				.map(Player::getUsername)
				.filter(name -> name.toLowerCase().startsWith(input))
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.limit(20)
				.toList());
			return values.stream().filter(value -> value.toLowerCase().startsWith(input)).toList();
		}
		if (args.length == 2 && ("info".equalsIgnoreCase(args[0]) || "unbypass".equalsIgnoreCase(args[0]))) {
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

		if (!(source instanceof Player player)) {
			source.sendRichMessage("<red>❌ Lệnh này chỉ dùng cho người chơi.</red>");
			return;
		}

		var existing = linkService.findByPlayerId(player.getUniqueId());
		if (existing.isPresent()) {
			source.sendRichMessage(
				"<yellow>ℹ Bạn đã liên kết với Discord <white>" + escape(existing.get().discordUsername())
					+ "</white>. Hãy dùng <gold>/discord unlink</gold> trước khi liên kết lại.</yellow>"
			);
			return;
		}

		DiscordAccountLinkService.BeginLinkResult result = linkService.beginLink(player.getUniqueId(), player.getUsername());
		if (result.databaseDisabled()) {
			source.sendRichMessage("<red>❌ Hệ thống liên kết Discord đang tắt vì LunaCore database chưa sẵn sàng.</red>");
			return;
		}

		DiscordLinkInstructionMessages.sendInstruction(source::sendRichMessage, result.code(), result.expiresAtEpochMs());
	}

	private void executeUnlink(CommandSource source) {
		DiscordAccountLinkService linkService = linkServiceSupplier.get();
		if (linkService == null) {
			source.sendRichMessage("<red>❌ Dịch vụ liên kết Discord chưa sẵn sàng.</red>");
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

	private void executeBypass(CommandSource source, String[] args) {
		DiscordAccountLinkService linkService = linkServiceSupplier.get();
		if (linkService == null) {
			source.sendRichMessage("<red>❌ Dịch vụ liên kết Discord chưa sẵn sàng.</red>");
			return;
		}

		if (!source.hasPermission("lunamessenger.admin.discord.bypass")) {
			source.sendRichMessage("<red>❌ Bạn không có quyền dùng lệnh này.</red>");
			return;
		}
		if (args.length < 2) {
			source.sendRichMessage(CommandStrings.usage("/discord", CommandStrings.literal("bypass"), CommandStrings.required("player|list", "text")));
			return;
		}
		if ("list".equalsIgnoreCase(args[1])) {
			executeBypassList(source, linkService);
			return;
		}

		String username = args[1];
		var target = linkService.resolvePlayerIdentity(username);
		if (target.isEmpty()) {
			source.sendRichMessage("<yellow>ℹ Không tìm thấy người chơi <white>" + escape(username) + "</white>.</yellow>");
			return;
		}

		boolean added = linkService.grantServerProtectionBypass(target.get());
		if (!added) {
			source.sendRichMessage("<yellow>ℹ Người chơi <white>" + escape(target.get().playerName()) + "</white> đã có bypass Discord-link trước đó.</yellow>");
			return;
		}

		source.sendRichMessage("<green>✔ Đã cấp bypass Discord-link cho <white>" + escape(target.get().playerName()) + "</white>.</green>");
	}

	private void executeUnbypass(CommandSource source, String[] args) {
		DiscordAccountLinkService linkService = linkServiceSupplier.get();
		if (linkService == null) {
			source.sendRichMessage("<red>❌ Dịch vụ liên kết Discord chưa sẵn sàng.</red>");
			return;
		}

		if (!source.hasPermission("lunamessenger.admin.discord.bypass")) {
			source.sendRichMessage("<red>❌ Bạn không có quyền dùng lệnh này.</red>");
			return;
		}
		if (args.length < 2) {
			source.sendRichMessage(CommandStrings.usage("/discord", CommandStrings.literal("unbypass"), CommandStrings.required("player", "text")));
			return;
		}

		String username = args[1];
		var target = linkService.resolvePlayerIdentity(username);
		if (target.isEmpty()) {
			source.sendRichMessage("<yellow>ℹ Không tìm thấy người chơi <white>" + escape(username) + "</white>.</yellow>");
			return;
		}

		boolean removed = linkService.revokeServerProtectionBypass(target.get().playerId());
		if (!removed) {
			source.sendRichMessage("<yellow>ℹ Người chơi <white>" + escape(target.get().playerName()) + "</white> chưa có bypass Discord-link.</yellow>");
			return;
		}

		source.sendRichMessage("<green>✔ Đã gỡ bypass Discord-link cho <white>" + escape(target.get().playerName()) + "</white>.</green>");
	}

	private void executeBypassList(CommandSource source, DiscordAccountLinkService linkService) {
		if (!source.hasPermission("lunamessenger.admin.discord.bypass")) {
			source.sendRichMessage("<red>❌ Bạn không có quyền dùng lệnh này.</red>");
			return;
		}

		List<DiscordAccountLinkService.PlayerIdentity> bypasses = linkService.listServerProtectionBypasses();
		if (bypasses.isEmpty()) {
			source.sendRichMessage("<yellow>ℹ Danh sách bypass Discord-link hiện đang trống.</yellow>");
			return;
		}

		source.sendRichMessage("<gold>Danh sách bypass Discord-link</gold> <gray>(" + bypasses.size() + ")</gray>");
		int limit = Math.min(50, bypasses.size());
		for (int index = 0; index < limit; index++) {
			DiscordAccountLinkService.PlayerIdentity identity = bypasses.get(index);
			source.sendRichMessage("<gray>•</gray> <white>" + escape(identity.playerName()) + "</white> <dark_gray>(" + identity.playerId() + ")</dark_gray>");
		}
		if (bypasses.size() > limit) {
			source.sendRichMessage("<gray>... và còn " + (bypasses.size() - limit) + " người chơi khác.</gray>");
		}
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
			CommandStrings.literal("bypass"),
			CommandStrings.literal("unbypass"),
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
