package dev.belikhun.luna.messenger.velocity.service;

import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.api.ui.LunaPalette;

import java.time.Instant;
import java.util.function.Consumer;

public final class DiscordLinkInstructionMessages {
	private static final String DISCORD_CHANNEL_COLOR = LunaPalette.INFO_500;
	private static final String LINK_COMMAND_COLOR = LunaPalette.WARNING_300;

	private DiscordLinkInstructionMessages() {
	}

	public static void sendInstruction(Consumer<String> sendRichMessage, String code, Long expiresAtEpochMs) {
		String safeCode = normalizeCode(code);
		sendRichMessage.accept("Liên kết tài khoản discord của bạn với tài khoản minecraft này");
		sendRichMessage.accept("bằng cách chạy lệnh sau trong kênh <color:" + DISCORD_CHANNEL_COLOR + ">#🧱-minecraft</color>:");
		sendRichMessage.accept("");
		sendRichMessage.accept(
			"<click:copy_to_clipboard:'/link " + safeCode + "'><hover:show_text:'<gray>Nhấn để sao chép lệnh</gray>'>"
				+ "<color:" + LINK_COMMAND_COLOR + ">/link " + safeCode + "</color>"
				+ "</hover></click>"
		);
		sendRichMessage.accept("");
		sendRichMessage.accept("<gray>ℹ Sử dụng câu lệnh của bot trong kênh này thay vì nhập trực tiếp tin nhắn vào chat</gray>");
		if (expiresAtEpochMs != null && expiresAtEpochMs > 0L) {
			sendRichMessage.accept("<gray>Mã hết hạn lúc: <white>" + Formatters.date(Instant.ofEpochMilli(expiresAtEpochMs)) + "</white></gray>");
		}
	}

	public static String plainInstructionLine(String code) {
		return "Dùng lệnh này trong Discord kênh #🧱-minecraft: /link " + normalizeCode(code);
	}

	private static String normalizeCode(String code) {
		if (code == null) {
			return "";
		}
		return code.trim();
	}
}
