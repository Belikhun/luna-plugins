package dev.belikhun.luna.messenger.velocity.service.discord;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.messenger.velocity.service.DiscordAccountLinkService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

import java.util.List;
import java.util.Objects;

public final class UnlinkDiscordCommandHandler implements DiscordCommandHandler {
	private final LunaLogger logger;
	private final ProxyServer proxyServer;
	private final DiscordAccountLinkService linkService;

	public UnlinkDiscordCommandHandler(LunaLogger logger, ProxyServer proxyServer, DiscordAccountLinkService linkService) {
		this.logger = logger.scope("DiscordUnlinkCommand");
		this.proxyServer = proxyServer;
		this.linkService = linkService;
	}

	@Override
	public String commandName() {
		return "unlink";
	}

	@Override
	public void registerSlashCommand(JDA jda) {
		jda.upsertCommand(Commands.slash("unlink", "Hủy liên kết Discord với Minecraft")).queue();
	}

	@Override
	public boolean handleMessage(MessageReceivedEvent event, List<String> args) {
		processUnlink(event.getAuthor(), message -> event.getMessage().reply(Objects.requireNonNull(message)).queue());
		return true;
	}

	@Override
	public boolean handleSlash(SlashCommandInteractionEvent event) {
		processUnlink(event.getUser(), message -> event.reply(Objects.requireNonNull(message)).setEphemeral(true).queue());
		return true;
	}

	private void processUnlink(User user, java.util.function.Consumer<String> reply) {
		DiscordAccountLinkService.UnlinkResult result = linkService.unlinkByDiscordId(user.getId());
		if (result.databaseDisabled()) {
			reply.accept("❌ Hệ thống liên kết hiện đang tắt vì LunaCore database chưa sẵn sàng.");
			return;
		}
		if (result.notLinked()) {
			reply.accept("ℹ Discord của bạn hiện chưa liên kết với tài khoản Minecraft nào.");
			return;
		}
		if (!result.success() || result.account() == null) {
			reply.accept("❌ Không thể hủy liên kết lúc này. Vui lòng thử lại sau.");
			return;
		}

		DiscordAccountLinkService.LinkedAccount account = result.account();
		reply.accept("✔ Đã hủy liên kết với tài khoản Minecraft " + account.playerName() + ".");
		proxyServer.getPlayer(account.playerId()).ifPresent(this::notifyPlayerUnlinked);
		logger.audit("Discord unlink success discord=" + user.getId() + " minecraft=" + account.playerName());
	}

	private void notifyPlayerUnlinked(Player player) {
		player.sendRichMessage("<yellow>ℹ Tài khoản Discord của bạn vừa được hủy liên kết.</yellow>");
	}
}
