package dev.belikhun.luna.messenger.velocity.service.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.util.List;

public interface DiscordCommandHandler {
	String commandName();

	void registerSlashCommand(JDA jda);

	boolean handleMessage(MessageReceivedEvent event, List<String> args);

	boolean handleSlash(SlashCommandInteractionEvent event);
}
