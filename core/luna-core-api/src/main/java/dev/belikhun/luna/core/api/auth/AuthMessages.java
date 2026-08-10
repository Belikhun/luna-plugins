package dev.belikhun.luna.core.api.auth;

import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.api.ui.LunaPalette;

import java.util.List;

/**
 * Every player-facing string the auth backend produces itself, as MiniMessage.
 *
 * The prompts (bossbar, actionbar, chat, the per-method authenticated feedback)
 * come from each platform's config.yml; everything here is what the code emits
 * directly. It lives in the api module because paper, fabric, forge and
 * neoforge each carry their own restriction controller: with the text inlined
 * in four places the platforms drifted, one of them ending up with plain white
 * literals where paper had colour. A caller renders these through its own
 * MiniMessage bridge, so a message reads the same wherever the player lands.
 */
public final class AuthMessages {
	/** Chest row the mode selector occupies; its slots are indexed into this. */
	public static final int MODE_SELECTOR_SIZE = 9;

	public static final int MODE_SELECTOR_SLOT_INFO = 4;
	public static final int MODE_SELECTOR_SLOT_PREMIUM = 3;
	public static final int MODE_SELECTOR_SLOT_OFFLINE = 5;
	public static final int MODE_SELECTOR_SLOT_REMEMBER = 7;

	/** The slots filled with the framing pane, left to right. */
	public static final List<Integer> MODE_SELECTOR_FRAME_SLOTS = List.of(0, 1, 2, 6, 8);

	/** Plain text: a container title is not rendered through MiniMessage. */
	public static final String MODE_SELECTOR_TITLE = "Chọn kiểu tài khoản";

	public static final String ITEM_FRAME = "gray_stained_glass_pane";
	public static final String ITEM_INFO = "book";
	public static final String ITEM_PREMIUM = "nether_star";
	public static final String ITEM_OFFLINE = "iron_bars";
	public static final String ITEM_REMEMBER_ON = "lime_dye";
	public static final String ITEM_REMEMBER_OFF = "gray_dye";

	public static final String LOBBY_SELECTOR_KEY = "server_selector";
	public static final int LOBBY_SELECTOR_SLOT = 0;
	public static final String ITEM_LOBBY_SELECTOR = "compass";

	private AuthMessages() {
	}

	/** A console or command block ran a command only a player can run. */
	public static String notAPlayer() {
		return danger("❌ Lệnh này chỉ dùng trong game.");
	}

	/** `/login` with no password. */
	public static String loginUsage() {
		return CommandStrings.usage("/login", CommandStrings.required("mat_khau", "text"));
	}

	/** `/register` with a missing password or confirmation. */
	public static String registerUsage() {
		return CommandStrings.usage(
			"/register",
			CommandStrings.required("mat_khau", "text"),
			CommandStrings.required("nhap_lai", "text")
		);
	}

	/** The proxy could not be reached, so the command never left the backend. */
	public static String commandSendFailed() {
		return danger("❌ Không thể gửi yêu cầu lên proxy. Vui lòng thử lại sau vài giây.");
	}

	/**
	 * The proxy's own answer to a login or register attempt.
	 *
	 * The wording is the proxy's; only the colour is decided here, so a refusal
	 * cannot arrive looking like a success.
	 */
	public static String commandResult(boolean success, String message) {
		String text = message == null ? "" : message;

		return success
			? "<color:" + LunaPalette.SUCCESS_500 + ">" + text + "</color>"
			: danger(text);
	}

	public static String spawnUpdated(String actorName) {
		return "<color:" + LunaPalette.SUCCESS_500 + ">✔ Điểm auth-spawn đã được cập nhật bởi " + actorName + ".</color>";
	}

	public static String spawnUpdateFailed() {
		return danger("❌ Không thể cập nhật auth-spawn tại vị trí hiện tại.");
	}

	/** The premium/offline choice could not be handed to the proxy. */
	public static String probePreferenceFailed() {
		return danger("❌ Không thể gửi lựa chọn xác thực lên proxy. Vui lòng thử lại sau vài giây.");
	}

	/** Shown on the action bar, where the message above would be too long. */
	public static String modeChoiceSendFailed() {
		return danger("Không gửi được lựa chọn. Vui lòng thử lại.");
	}

	public static String modePremiumChosen(boolean remember) {
		return "<color:" + LunaPalette.WARNING_300 + ">" + (remember
			? "Đã chọn Premium (ghi nhớ vĩnh viễn). Bạn sẽ được kết nối lại để xác thực online."
			: "Đã chọn Premium (24h). Bạn sẽ được kết nối lại để xác thực online.") + "</color>";
	}

	public static String modeOfflineChosen(boolean remember) {
		return "<color:" + LunaPalette.SUCCESS_500 + ">" + (remember
			? "Đã chọn Offline (ghi nhớ vĩnh viễn). Tiếp tục đăng nhập bằng mật khẩu server."
			: "Đã chọn Offline (24h). Tiếp tục đăng nhập bằng mật khẩu server.") + "</color>";
	}

	public static String rememberToggled(boolean remember) {
		return remember
			? "<color:" + LunaPalette.WARNING_500 + ">Đã bật ghi nhớ lựa chọn vĩnh viễn.</color>"
			: "<color:" + LunaPalette.WARNING_300 + ">Đã tắt ghi nhớ vĩnh viễn (chỉ 24h).</color>";
	}

	public static String frameItemName() {
		return "<dark_gray>•</dark_gray>";
	}

	public static List<String> frameItemLore() {
		return List.of("<gray> </gray>");
	}

	public static String infoItemName() {
		return "<yellow><b>ℹ Chọn Chế Độ Đăng Nhập</b></yellow>";
	}

	public static List<String> infoItemLore() {
		return List.of(
			"<gray>Premium hoặc Offline.</gray>",
			"<gray>Nút bên phải bật/tắt ghi nhớ.</gray>",
			"",
			"<gold>⚠ Hãy chọn đúng để tránh lỗi phiên.</gold>"
		);
	}

	public static String premiumItemName() {
		return "<green><b>★ Tài Khoản Premium</b></green>";
	}

	public static List<String> premiumItemLore() {
		return List.of(
			"<gray>Dùng launcher Microsoft.</gray>",
			"<gray>Sẽ probe xác thực online.</gray>",
			"",
			"<yellow>▶ Ấn để chọn.</yellow>"
		);
	}

	public static String offlineItemName() {
		return "<aqua><b>⬤ Tài Khoản Offline</b></aqua>";
	}

	public static List<String> offlineItemLore() {
		return List.of(
			"<gray>Dùng launcher cracked.</gray>",
			"<gray>Không ép xác thực online.</gray>",
			"",
			"<yellow>▶ Ấn để chọn.</yellow>"
		);
	}

	public static String rememberItem(boolean remember) {
		return remember
			? "<gold><b>🔔 Ghi Nhớ: BẬT</b></gold>"
			: "<gray><b>🔔 Ghi Nhớ: TẮT</b></gray>";
	}

	public static List<String> rememberItemLore(boolean remember) {
		return List.of(
			remember
				? "<gray>Lựa chọn sẽ được giữ vĩnh viễn.</gray>"
				: "<gray>Lựa chọn chỉ có hiệu lực 24 giờ.</gray>",
			"<yellow>▶ Ấn để chuyển trạng thái.</yellow>"
		);
	}

	public static String lobbySelectorName() {
		return "<gradient:#C6A9FF:#FF8AB9>Chọn máy chủ</gradient>";
	}

	public static List<String> lobbySelectorLore() {
		return List.of(
			"<gray>ℹ Cầm item này và nhấn <aqua>chuột phải</aqua> để mở menu</gray>",
			"",
			"<dark_gray>> maylocnuoc</dark_gray>"
		);
	}

	private static String danger(String text) {
		return "<color:" + LunaPalette.DANGER_500 + ">" + text + "</color>";
	}
}
