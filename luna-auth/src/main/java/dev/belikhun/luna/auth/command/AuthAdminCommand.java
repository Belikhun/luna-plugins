package dev.belikhun.luna.auth.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.auth.service.AuthRepository;
import dev.belikhun.luna.auth.service.AuthService;
import dev.belikhun.luna.core.api.string.CommandCompletions;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.api.ui.LunaPalette;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class AuthAdminCommand implements SimpleCommand {
	private static final String PERMISSION_BASE = "lunaauth.admin";
	private static final String PERMISSION_STATUS = "lunaauth.admin.status";
	private static final String PERMISSION_SESSION = "lunaauth.admin.session";
	private static final String PERMISSION_HISTORY = "lunaauth.admin.history";
	private static final String PERMISSION_RESETPASSWORD = "lunaauth.admin.resetpassword";
	private static final String PERMISSION_UNLOCK = "lunaauth.admin.unlock";
	private static final String PERMISSION_LOGOUT = "lunaauth.admin.logout";
	private static final String PERMISSION_INVALIDATESESSION = "lunaauth.admin.invalidatesession";
	private static final String PERMISSION_CHANGEPASSWORD = "lunaauth.admin.changepassword";
	private static final String PERMISSION_FORCELOGIN = "lunaauth.admin.forcelogin";
	private static final String PERMISSION_SETSPAWN = "lunaauth.admin.setspawn";

	private static final int HISTORY_PAGE_SIZE = 10;
	private static final List<String> SUBCOMMANDS = List.of("status", "session", "history", "resetpassword", "unlock", "logout", "invalidatesession", "changepassword", "forcelogin", "setspawn", "reset");

	private final ProxyServer proxyServer;
	private final AuthService authService;
	private final Consumer<Player> authStateSync;
	private final BiConsumer<Player, String> adminRequestSender;

	public AuthAdminCommand(ProxyServer proxyServer, AuthService authService, Consumer<Player> authStateSync, BiConsumer<Player, String> adminRequestSender) {
		this.proxyServer = proxyServer;
		this.authService = authService;
		this.authStateSync = authStateSync;
		this.adminRequestSender = adminRequestSender;
	}

	@Override
	public void execute(Invocation invocation) {
		CommandSource source = invocation.source();
		if (!source.hasPermission(PERMISSION_BASE)) {
			source.sendRichMessage(error("❌ Bạn không có quyền dùng lệnh này."));
			return;
		}

		String[] args = invocation.arguments();
		if (args.length == 0) {
			source.sendRichMessage(info("ℹ Danh sách lệnh quản trị auth:"));
			source.sendRichMessage(CommandStrings.usage("/auth", CommandStrings.literal("status"), CommandStrings.required("player", "player")));
			source.sendRichMessage(CommandStrings.usage("/auth", CommandStrings.literal("session"), CommandStrings.required("player", "player")));
			source.sendRichMessage(CommandStrings.usage("/auth", CommandStrings.literal("history"), CommandStrings.required("player", "player"), CommandStrings.optional("page", "number")));
			source.sendRichMessage(CommandStrings.usage("/auth", CommandStrings.literal("resetpassword"), CommandStrings.required("player", "player")));
			source.sendRichMessage(CommandStrings.usage("/auth", CommandStrings.literal("unlock"), CommandStrings.required("player", "player")));
			source.sendRichMessage(CommandStrings.usage("/auth", CommandStrings.literal("logout"), CommandStrings.required("player", "player")));
			source.sendRichMessage(CommandStrings.usage("/auth", CommandStrings.literal("invalidatesession"), CommandStrings.required("player", "player")));
			source.sendRichMessage(CommandStrings.usage("/auth", CommandStrings.literal("changepassword"), CommandStrings.required("player", "player"), CommandStrings.required("mat_khau_moi", "text")));
			source.sendRichMessage(CommandStrings.usage("/auth", CommandStrings.literal("forcelogin"), CommandStrings.required("player", "player")));
			source.sendRichMessage(CommandStrings.usage("/auth", CommandStrings.literal("setspawn")));
			return;
		}

		String sub = args[0].toLowerCase();
		sub = "reset".equals(sub) ? "resetpassword" : sub;

		if (!checkPermission(source, permissionFor(sub), sub)) {
			return;
		}

		if ("history".equals(sub)) {
			handleHistory(source, args);
			return;
		}
		if ("session".equals(sub)) {
			handleSession(source, args);
			return;
		}
		if (sub.equals("status")) {
			if (args.length < 2) {
				source.sendRichMessage(CommandStrings.usage("/auth", CommandStrings.literal("status"), CommandStrings.required("player", "player")));
				return;
			}
			Optional<Player> targetOptional = proxyServer.getPlayer(args[1]);
			if (targetOptional.isEmpty()) {
				source.sendRichMessage(error("❌ Người chơi đang không online."));
				return;
			}

			Player target = targetOptional.get();
			boolean authenticated = authService.isAuthenticated(target.getUniqueId());
			Optional<dev.belikhun.luna.auth.model.AuthAccount> account = authService.account(target.getUniqueId());
			long now = System.currentTimeMillis();
			boolean registered = account.isPresent() && account.get().hasPassword();
			boolean locked = account.isPresent() && account.get().isLocked(now);
			source.sendRichMessage(info("ℹ Trạng thái " + target.getUsername() + ":"));
			source.sendRichMessage(muted("- Xác thực runtime: ") + accent(authenticated ? "ĐÃ XÁC THỰC" : "CHƯA XÁC THỰC"));
			source.sendRichMessage(muted("- Đăng ký: ") + accent(registered ? "ĐÃ ĐĂNG KÝ" : "CHƯA ĐĂNG KÝ"));
			if (locked) {
				long remainingMillis = Math.max(1000L, account.get().lockoutUntilEpochMillis() - now);
				source.sendRichMessage(muted("- Khóa tạm thời: ") + error("ĐANG KHÓA (" + Formatters.duration(Duration.ofMillis(remainingMillis)) + ")"));
			} else {
				source.sendRichMessage(muted("- Khóa tạm thời: ") + success("KHÔNG"));
			}
			return;
		}
		if (sub.equals("changepassword")) {
			if (args.length < 3) {
				source.sendRichMessage(CommandStrings.usage("/auth", CommandStrings.literal("changepassword"), CommandStrings.required("player", "player"), CommandStrings.required("mat_khau_moi", "text")));
				return;
			}
			Optional<Player> targetOptional = proxyServer.getPlayer(args[1]);
			if (targetOptional.isEmpty()) {
				source.sendRichMessage(error("❌ Người chơi đang không online."));
				return;
			}
			Player target = targetOptional.get();
			String ipAddress = target.getRemoteAddress().getAddress().getHostAddress();
			AuthService.AuthResult result = authService.adminChangePassword(target.getUniqueId(), target.getUsername(), ipAddress, args[2], actorName(source));
			authStateSync.accept(target);
			target.disconnect(net.kyori.adventure.text.Component.text("Mật khẩu đã được quản trị viên thay đổi. Hãy đăng nhập lại."));
			source.sendRichMessage(result.success() ? success(result.message()) : error(result.message()));
			return;
		}
		if (sub.equals("forcelogin")) {
			if (args.length < 2) {
				source.sendRichMessage(CommandStrings.usage("/auth", CommandStrings.literal("forcelogin"), CommandStrings.required("player", "player")));
				return;
			}
			Optional<Player> targetOptional = proxyServer.getPlayer(args[1]);
			if (targetOptional.isEmpty()) {
				source.sendRichMessage(error("❌ Người chơi đang không online."));
				return;
			}
			Player target = targetOptional.get();
			String ipAddress = target.getRemoteAddress().getAddress().getHostAddress();
			AuthService.AuthResult result = authService.adminForceLogin(target.getUniqueId(), target.getUsername(), ipAddress, actorName(source));
			authStateSync.accept(target);
			source.sendRichMessage(result.success() ? success(result.message()) : error(result.message()));
			if (result.success()) {
				target.sendRichMessage(success("✔ Bạn đã được quản trị viên xác thực thủ công."));
			}
			return;
		}
		if (sub.equals("setspawn")) {
			if (!(source instanceof Player actor)) {
				source.sendRichMessage(error("❌ /auth setspawn chỉ dùng trong game."));
				return;
			}
			if (args.length > 1) {
				source.sendRichMessage(CommandStrings.usage("/auth", CommandStrings.literal("setspawn")));
				return;
			}
			adminRequestSender.accept(actor, actor.getUsername());
			source.sendRichMessage(success("✔ Đã gửi yêu cầu cập nhật auth-spawn cho server hiện tại của bạn."));
			return;
		}

		if (args.length < 2) {
			source.sendRichMessage(error("❌ Thiếu tham số player cho lệnh /auth " + sub + "."));
			return;
		}

		Optional<Player> targetOptional = proxyServer.getPlayer(args[1]);
		if (targetOptional.isEmpty()) {
			source.sendRichMessage(error("❌ Người chơi đang không online."));
			return;
		}
		Player target = targetOptional.get();
		String ipAddress = target.getRemoteAddress().getAddress().getHostAddress();

		switch (sub) {
			case "resetpassword" -> {
				AuthService.AuthResult result = authService.adminResetPassword(target.getUniqueId(), target.getUsername(), ipAddress, actorName(source));
				authStateSync.accept(target);
				target.disconnect(net.kyori.adventure.text.Component.text("Mật khẩu đã được quản trị viên reset. Hãy kết nối lại để /register."));
				source.sendRichMessage(success(result.message()));
			}
			case "unlock" -> {
				AuthService.AuthResult result = authService.adminUnlock(target.getUniqueId(), target.getUsername(), ipAddress, actorName(source));
				source.sendRichMessage(result.success() ? success(result.message()) : error(result.message()));
			}
			case "logout" -> {
				AuthService.AuthResult result = authService.adminForceLogout(target.getUniqueId(), ipAddress, actorName(source));
				authStateSync.accept(target);
				target.disconnect(net.kyori.adventure.text.Component.text("Phiên đăng nhập đã bị quản trị viên kết thúc."));
				source.sendRichMessage(success(result.message()));
			}
			case "invalidatesession" -> {
				AuthService.AuthResult result = authService.adminInvalidateSession(target.getUniqueId(), target.getUsername(), ipAddress, actorName(source));
				authStateSync.accept(target);
				target.disconnect(net.kyori.adventure.text.Component.text("Session của bạn đã bị quản trị viên hủy. Hãy đăng nhập lại."));
				source.sendRichMessage(result.success() ? success(result.message()) : error(result.message()));
			}
			default -> source.sendRichMessage(error("❌ Subcommand không hợp lệ. Dùng /auth để xem cú pháp."));
		}
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		String[] args = invocation.arguments();
		if (args.length == 0) {
			return SUBCOMMANDS;
		}
		if (args.length == 1) {
			return CommandCompletions.filterPrefix(SUBCOMMANDS, args[0]);
		}
		if (args.length == 2 && !"changepassword".equalsIgnoreCase(args[0])) {
			if ("setspawn".equalsIgnoreCase(args[0])) {
				return List.of();
			}
			List<String> onlineNames = new ArrayList<>();
			for (Player player : proxyServer.getAllPlayers()) {
				onlineNames.add(player.getUsername());
			}
			return CommandCompletions.filterPrefix(onlineNames, args[1]);
		}
		if (args.length == 3 && "history".equalsIgnoreCase(args[0])) {
			return CommandCompletions.filterPrefix(List.of("1", "2", "3", "4", "5"), args[2]);
		}
		return List.of();
	}

	private void handleHistory(CommandSource source, String[] args) {
		if (args.length < 2) {
			source.sendRichMessage(CommandStrings.usage("/auth", CommandStrings.literal("history"), CommandStrings.required("player", "player"), CommandStrings.optional("page", "number")));
			return;
		}
		Optional<Player> targetOptional = proxyServer.getPlayer(args[1]);
		if (targetOptional.isEmpty()) {
			source.sendRichMessage(error("❌ Người chơi đang không online."));
			return;
		}
		Player target = targetOptional.get();
		int page = 1;
		if (args.length >= 3) {
			try {
				page = Math.max(1, Integer.parseInt(args[2]));
			} catch (NumberFormatException ignored) {
				source.sendRichMessage(error("❌ Page phải là số."));
				return;
			}
		}

		List<AuthRepository.LoginHistoryEntry> allEntries = authService.recentLoginHistory(target.getUniqueId(), page * HISTORY_PAGE_SIZE);
		int from = (page - 1) * HISTORY_PAGE_SIZE;
		if (from >= allEntries.size()) {
			source.sendRichMessage(muted("- Chưa có dữ liệu."));
			return;
		}
		int to = Math.min(allEntries.size(), from + HISTORY_PAGE_SIZE);
		List<AuthRepository.LoginHistoryEntry> pageEntries = allEntries.subList(from, to);
		source.sendRichMessage(info("ℹ Login history của " + target.getUsername() + " (trang " + page + ", " + pageEntries.size() + " bản ghi):"));
		for (AuthRepository.LoginHistoryEntry entry : pageEntries) {
			String statusColor = "SUCCESS".equalsIgnoreCase(entry.result()) ? success(entry.result()) : error(entry.result());
			String when = Formatters.date(Instant.ofEpochMilli(entry.createdAtEpochMillis()));
			source.sendRichMessage(muted("- ") + accent(entry.eventType()) + muted(" | ") + statusColor
				+ muted(" | reason: " + entry.reason() + " | actor: " + entry.actor() + " | IP: " + entry.ipAddress() + " | lúc: " + when));
		}
	}

	private void handleSession(CommandSource source, String[] args) {
		if (args.length < 2) {
			source.sendRichMessage(CommandStrings.usage("/auth", CommandStrings.literal("session"), CommandStrings.required("player", "player")));
			return;
		}
		Optional<Player> targetOptional = proxyServer.getPlayer(args[1]);
		if (targetOptional.isEmpty()) {
			source.sendRichMessage(error("❌ Người chơi đang không online."));
			return;
		}
		Player target = targetOptional.get();
		AuthService.SessionSnapshot snapshot = authService.snapshot(target.getUniqueId());
		source.sendRichMessage(info("ℹ Session của " + target.getUsername() + ":"));
		source.sendRichMessage(muted("- Runtime authenticated: ") + accent(String.valueOf(snapshot.runtimeAuthenticated())));
		source.sendRichMessage(muted("- Has session: ") + accent(String.valueOf(snapshot.hasSession())));
		source.sendRichMessage(muted("- Session connected: ") + accent(String.valueOf(snapshot.sessionConnected())));
		source.sendRichMessage(muted("- Session ip: ") + accent(snapshot.sessionIp().isBlank() ? "N/A" : snapshot.sessionIp()));
		if (snapshot.sessionCreatedAtEpochMillis() > 0L) {
			source.sendRichMessage(muted("- Session tạo lúc: ") + accent(Formatters.date(Instant.ofEpochMilli(snapshot.sessionCreatedAtEpochMillis()))));
		}
		if (snapshot.sessionExpiresAtEpochMillis() == Long.MAX_VALUE) {
			source.sendRichMessage(muted("- Session hết hạn: ") + success("vô hạn (đang online)"));
		} else if (snapshot.sessionExpiresAtEpochMillis() > 0L) {
			long remain = Math.max(1000L, snapshot.sessionExpiresAtEpochMillis() - System.currentTimeMillis());
			source.sendRichMessage(muted("- Session hết hạn sau: ") + accent(Formatters.duration(Duration.ofMillis(remain))));
		}

		List<AuthRepository.SessionEventEntry> events = authService.recentSessionEvents(target.getUniqueId(), 5);
		source.sendRichMessage(muted("- Recent events:"));
		if (events.isEmpty()) {
			source.sendRichMessage(muted("  • Không có."));
			return;
		}
		for (AuthRepository.SessionEventEntry entry : events) {
			String when = Formatters.date(Instant.ofEpochMilli(entry.happenedAtEpochMillis()));
			source.sendRichMessage(muted("  • " + entry.eventType() + " | " + entry.detail() + " | lúc: " + when));
		}
	}

	private String permissionFor(String subcommand) {
		return switch (subcommand) {
			case "status" -> PERMISSION_STATUS;
			case "session" -> PERMISSION_SESSION;
			case "history" -> PERMISSION_HISTORY;
			case "resetpassword" -> PERMISSION_RESETPASSWORD;
			case "unlock" -> PERMISSION_UNLOCK;
			case "logout" -> PERMISSION_LOGOUT;
			case "invalidatesession" -> PERMISSION_INVALIDATESESSION;
			case "changepassword" -> PERMISSION_CHANGEPASSWORD;
			case "forcelogin" -> PERMISSION_FORCELOGIN;
			case "setspawn" -> PERMISSION_SETSPAWN;
			default -> "";
		};
	}

	private boolean checkPermission(CommandSource source, String permission, String subcommand) {
		if (permission.isBlank()) {
			return true;
		}
		if (source.hasPermission(permission)) {
			return true;
		}
		source.sendRichMessage(error("❌ Bạn thiếu quyền " + permission + " cho /auth " + subcommand + "."));
		return false;
	}

	private String actorName(CommandSource source) {
		if (source instanceof Player player) {
			return player.getUsername();
		}
		return "CONSOLE";
	}

	private String info(String text) {
		return "<color:" + LunaPalette.INFO_500 + ">" + text + "</color>";
	}

	private String success(String text) {
		return "<color:" + LunaPalette.SUCCESS_500 + ">" + text + "</color>";
	}

	private String error(String text) {
		return "<color:" + LunaPalette.DANGER_500 + ">" + text + "</color>";
	}

	private String muted(String text) {
		return "<color:" + LunaPalette.NEUTRAL_300 + ">" + text + "</color>";
	}

	private String accent(String text) {
		return "<color:" + LunaPalette.PRIMARY_300 + ">" + text + "</color>";
	}
}
