package dev.belikhun.luna.core.api.auth;

import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.api.ui.LunaGuiTitle;

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

	/**
	 * One prompt, on all three surfaces a locked player can be told something.
	 *
	 * @param bossbar   the bar across the top, present for as long as the lock is
	 * @param actionbar the line above the hotbar, repeated on a throttle
	 * @param chat      said once, when the state changes
	 */
	public record PromptText(String bossbar, String actionbar, String chat) {
	}

	/**
	 * The prompts, and the only copy of them.
	 *
	 * These used to live in three places at once: paper's plugin class, the loader
	 * platforms' config loader, and the shipped `config.yml` - in two different
	 * colour styles. A player logging into a paper backend was told to log in in
	 * amber and, on a fabric backend, the same sentence in yellow; delete a key
	 * from the file and paper fell back to its own wording while the loaders fell
	 * back to a *different* wording, or to nothing at all.
	 *
	 * So the code default and the shipped file now say the same thing by
	 * construction. `config.yml` remains the operator's override; what it
	 * overrides is this.
	 */
	public static PromptText pendingPrompt() {
		return new PromptText(
			"<yellow><b>⏳ Đang tải trạng thái xác thực...</b></yellow>",
			"<yellow>Đang kiểm tra trạng thái tài khoản...</yellow>",
			"<yellow>ℹ Đang kiểm tra trạng thái xác thực, vui lòng chờ một chút.</yellow>"
		);
	}

	public static PromptText loginPrompt() {
		return new PromptText(
			"<yellow><b>⚠ Vui lòng đăng nhập để tiếp tục</b></yellow>",
			"<yellow>Dùng <white>/login <mật_khẩu></white> để đăng nhập</yellow>",
			"<yellow>ℹ Tài khoản đã đăng ký. Dùng <white>/login <mật_khẩu></white> để tiếp tục.</yellow>"
		);
	}

	public static PromptText registerPrompt() {
		return new PromptText(
			"<yellow><b>⚠ Tài khoản chưa đăng ký</b></yellow>",
			"<yellow>Dùng <white>/register <mật_khẩu> <nhập_lại></white> để tạo tài khoản</yellow>",
			"<yellow>ℹ Tài khoản chưa đăng ký. Dùng <white>/register <mật_khẩu> <nhập_lại></white> để tiếp tục.</yellow>"
		);
	}

	/** No bossbar: the lock is gone, so the bar it belonged to is gone with it. */
	public static PromptText authenticatedPrompt() {
		return new PromptText(
			"",
			"<green>✔ Đã xác thực thành công</green>",
			"<green>✔ Bạn đã xác thực thành công. Chúc bạn chơi vui vẻ!</green>"
		);
	}

	/**
	 * The mode selector's window title, as MiniMessage.
	 *
	 * A breadcrumb like every other luna screen, and rendered rather than plain:
	 * this used to be a bare string on the theory that a container title is not
	 * MiniMessage, which is not true of any platform luna runs on - it made the
	 * one screen a player meets before they have even logged in the only one that
	 * did not look like luna.
	 */
	public static String modeSelectorTitle() {
		return LunaGuiTitle.breadcrumb("LunaAuth", "Chọn Chế Độ");
	}

	/**
	 * Shown on the action bar when a player reaches a backend already authenticated.
	 *
	 * Sent by the proxy, not a backend, which is why it is the *only* auth feedback
	 * on a game version with no auth mod yet. It used to be a bare `Component.text`
	 * - no colour, no glyph, bypassing this class entirely - and so looked nothing
	 * like the same event announced anywhere else.
	 */
	public static String alreadyAuthenticated() {
		return success("✔ Bạn đã xác thực.");
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

		return success ? success(text) : danger(text);
	}

	public static String spawnUpdated(String actorName) {
		return success("✔ Điểm auth-spawn đã được cập nhật bởi " + actorName + ".");
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

	/**
	 * Premium chosen: a warning, because the player is about to be disconnected.
	 *
	 * Its sibling below is a success. That is the one place these two deliberately
	 * differ: picking offline carries on where you are, picking premium ends the
	 * connection, and the colour is what says so before it happens.
	 */
	public static String modePremiumChosen(boolean remember) {
		return warning("⚠ " + (remember
			? "Đã chọn Premium (ghi nhớ vĩnh viễn). Bạn sẽ được kết nối lại để xác thực online."
			: "Đã chọn Premium (24h). Bạn sẽ được kết nối lại để xác thực online."));
	}

	public static String modeOfflineChosen(boolean remember) {
		return success("✔ " + (remember
			? "Đã chọn Offline (ghi nhớ vĩnh viễn). Tiếp tục đăng nhập bằng mật khẩu server."
			: "Đã chọn Offline (24h). Tiếp tục đăng nhập bằng mật khẩu server."));
	}

	public static String rememberToggled(boolean remember) {
		return remember
			? success("✔ Đã bật ghi nhớ lựa chọn vĩnh viễn.")
			: "<gray>● Đã tắt ghi nhớ vĩnh viễn (chỉ 24h).</gray>";
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
			"<yellow>Nhấn để chọn</yellow>"
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
			"<yellow>Nhấn để chọn</yellow>"
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
			"<yellow>Nhấn để chuyển trạng thái</yellow>"
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

	/**
	 * The fleet's chat colours are the named tags, not palette hex.
	 *
	 * These used to be `<color:#ef4444>` and friends, which put the backend's half
	 * of a refusal in a slightly different red from the proxy's half of the same
	 * refusal - `luna-auth` is the one module a player reads from both sides in a
	 * single sitting, so the mismatch showed.
	 */
	private static String danger(String text) {
		return "<red>" + text + "</red>";
	}

	private static String success(String text) {
		return "<green>" + text + "</green>";
	}

	private static String warning(String text) {
		return "<yellow>" + text + "</yellow>";
	}
}
