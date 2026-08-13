package dev.belikhun.luna.legacy.messenger;

import dev.belikhun.luna.legacy.string.CommandStrings;
import dev.belikhun.luna.legacy.ui.LunaPalette;

/**
 * Every player-facing string luna-messenger's commands produce, as MiniMessage.
 *
 * The chat itself is rendered by the proxy; this is only what a backend says
 * when a command cannot run. It lives in the api module because paper, fabric,
 * forge and neoforge each register these commands separately, and inlining the
 * text in four places is what let the mod builds drift into plain white
 * literals while paper had colour.
 */
public final class MessengerMessages {
	/** The names paper registers in paper-plugin.yml, shared so they cannot drift. */
	public static final String[] DIRECT_COMMANDS = { "msg", "tell", "w" };
	public static final String[] REPLY_COMMANDS = { "r", "reply" };

	private MessengerMessages() {
	}

	public static String notAPlayer() {
		return danger("❌ Lệnh này chỉ dùng cho người chơi.");
	}

	public static String notReady() {
		return danger("❌ LunaMessenger chưa sẵn sàng.");
	}

	/** Bare `/msg`: naming a player alone switches the chat context to them. */
	public static String directUsage() {
		return CommandStrings.usage("/msg", CommandStrings.required("người_chơi", "text"));
	}

	/** `/msg` with a player but nothing to say. */
	public static String directSendUsage() {
		return CommandStrings.usage(
			"/msg",
			CommandStrings.required("người_chơi", "text"),
			CommandStrings.required("nội_dung", "text")
		);
	}

	public static String replyUsage() {
		return CommandStrings.usage("/r", CommandStrings.required("nội_dung", "text"));
	}

	public static String pokeUsage() {
		return CommandStrings.usage("/poke", CommandStrings.required("người_chơi", "text"));
	}

	public static String networkSwitchFailed() {
		return danger("❌ Không thể chuyển sang kênh mạng lúc này.");
	}

	public static String serverSwitchFailed() {
		return danger("❌ Không thể chuyển sang kênh máy chủ lúc này.");
	}

	public static String directSwitchFailed() {
		return danger("❌ Không thể chuyển sang nhắn tin trực tiếp lúc này.");
	}

	public static String pokeFailed() {
		return danger("❌ Không thể gửi yêu cầu chọc lúc này.");
	}

	public static String directFailed() {
		return danger("❌ Không thể gửi tin nhắn lúc này.");
	}

	public static String replyFailed() {
		return danger("❌ Không thể gửi tin nhắn trả lời lúc này.");
	}

	public static String chatFailed() {
		return danger("❌ Không thể gửi chat messenger lúc này.");
	}

	private static String danger(String text) {
		return "<color:" + LunaPalette.DANGER_500 + ">" + text + "</color>";
	}
}
