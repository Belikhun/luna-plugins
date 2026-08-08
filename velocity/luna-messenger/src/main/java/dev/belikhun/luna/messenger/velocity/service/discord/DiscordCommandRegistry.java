package dev.belikhun.luna.messenger.velocity.service.discord;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DiscordCommandRegistry {
	private final LunaLogger logger;
	private final Map<String, DiscordCommandHandler> handlers;

	public DiscordCommandRegistry(LunaLogger logger) {
		this.logger = logger.scope("DiscordCommandRegistry");
		this.handlers = new LinkedHashMap<>();
	}

	public void register(DiscordCommandHandler handler) {
		if (handler == null) {
			return;
		}

		String key = normalize(handler.commandName());
		if (key.isBlank()) {
			return;
		}
		handlers.put(key, handler);
	}

	public void registerSlashCommands(JDA jda) {
		for (DiscordCommandHandler handler : handlers.values()) {
			try {
				handler.registerSlashCommand(jda);
			} catch (Exception exception) {
				logger.warn("Không thể đăng ký slash command /" + handler.commandName() + ": " + exception.getMessage());
			}
		}
	}

	public boolean handleMessageCommand(MessageReceivedEvent event) {
		if (event == null) {
			return false;
		}

		String content = event.getMessage().getContentRaw();
		if (content == null || content.isBlank() || !content.startsWith("/")) {
			return false;
		}

		String[] tokens = content.trim().split("\\s+");
		if (tokens.length == 0) {
			return false;
		}

		String command = normalize(tokens[0].substring(1));
		DiscordCommandHandler handler = handlers.get(command);
		if (handler == null) {
			return false;
		}

		List<String> args = new ArrayList<>();
		for (int index = 1; index < tokens.length; index++) {
			args.add(tokens[index]);
		}

		return handler.handleMessage(event, List.copyOf(args));
	}

	public boolean handleSlashCommand(SlashCommandInteractionEvent event) {
		if (event == null) {
			return false;
		}

		DiscordCommandHandler handler = handlers.get(normalize(event.getName()));
		if (handler == null) {
			return false;
		}

		return handler.handleSlash(event);
	}

	private String normalize(String value) {
		if (value == null) {
			return "";
		}
		return value.trim().toLowerCase(Locale.ROOT);
	}
}
