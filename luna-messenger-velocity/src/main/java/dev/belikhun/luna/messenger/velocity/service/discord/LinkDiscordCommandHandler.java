package dev.belikhun.luna.messenger.velocity.service.discord;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.messenger.velocity.service.DiscordAccountLinkService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

import java.util.List;

public final class LinkDiscordCommandHandler implements DiscordCommandHandler {
	private final LunaLogger logger;
	private final ProxyServer proxyServer;
	private final DiscordAccountLinkService linkService;

	public LinkDiscordCommandHandler(LunaLogger logger, ProxyServer proxyServer, DiscordAccountLinkService linkService) {
		this.logger = logger.scope("DiscordLinkCommand");
		this.proxyServer = proxyServer;
		this.linkService = linkService;
	}

	@Override
	public String commandName() {
		return "link";
	}

	@Override
	public void registerSlashCommand(JDA jda) {
		jda.upsertCommand(
			Commands.slash("link", "Liên kết tài khoản Discord với Minecraft")
				.addOption(OptionType.STRING, "code", "Mã 4 số từ lệnh /discord link trong Minecraft", true)
		).queue();
	}

	@Override
	public boolean handleMessage(MessageReceivedEvent event, List<String> args) {
		if (args.isEmpty()) {
			replyMessage(event, "❌ Sai cú pháp. Dùng: /link <mã_4_số>");
			return true;
		}

		processLink(event.getAuthor(), args.getFirst(), message -> replyMessage(event, message));
		return true;
	}

	@Override
	public boolean handleSlash(SlashCommandInteractionEvent event) {
		if (event.getOption("code") == null) {
			event.reply("❌ Thiếu mã liên kết. Vui lòng dùng /link code:<mã_4_số>").setEphemeral(true).queue();
			return true;
		}

		String code = event.getOption("code").getAsString();
		processLink(event.getUser(), code, message -> event.reply(message).setEphemeral(true).queue());
		return true;
	}

	private void processLink(User user, String code, java.util.function.Consumer<String> reply) {
		DiscordAccountLinkService.LinkByCodeResult result = linkService.linkByCode(user.getId(), user.getName(), code);
		if (result.databaseDisabled()) {
			reply.accept("❌ Hệ thống liên kết hiện đang tắt vì LunaCore database chưa sẵn sàng.");
			return;
		}
		if (result.invalidCode()) {
			reply.accept("❌ Mã liên kết không hợp lệ. Hãy dùng đúng mã 4 số từ /discord link.");
			return;
		}
		if (result.expiredCode()) {
			reply.accept("⌛ Mã liên kết đã hết hạn. Hãy chạy lại /discord link trong game để lấy mã mới.");
			return;
		}
		if (result.discordAlreadyLinked()) {
			DiscordAccountLinkService.LinkedAccount existing = result.existingDiscordAccount();
			reply.accept("⚠ Discord này đang liên kết với tài khoản Minecraft " + existing.playerName() + ". Dùng /unlink trước khi liên kết tài khoản khác.");
			return;
		}
		if (!result.success() || result.account() == null) {
			reply.accept("❌ Không thể liên kết tài khoản lúc này. Vui lòng thử lại sau.");
			return;
		}

		DiscordAccountLinkService.LinkedAccount account = result.account();
		reply.accept("✔ Liên kết thành công với tài khoản Minecraft " + account.playerName() + ".");
		proxyServer.getPlayer(account.playerId()).ifPresent(player -> notifyPlayerLinked(player, user));
		logger.audit("Discord link success discord=" + user.getId() + " minecraft=" + account.playerName());
	}

	private void notifyPlayerLinked(Player player, User discordUser) {
		player.sendRichMessage("<green>✔ Đã liên kết tài khoản Discord <white>" + escape(discordUser.getName()) + "</white>.</green>");
	}

	private void replyMessage(MessageReceivedEvent event, String message) {
		event.getMessage().reply(message).queue();
	}

	private String escape(String value) {
		if (value == null) {
			return "";
		}
		return value.replace("<", "&lt;").replace(">", "&gt;");
	}
}
