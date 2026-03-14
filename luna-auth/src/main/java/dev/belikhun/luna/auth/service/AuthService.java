package dev.belikhun.luna.auth.service;

import dev.belikhun.luna.auth.model.AuthAccount;
import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthService {
	private final LunaLogger logger;
	private final AuthRepository repository;
	private final Pbkdf2PasswordHasher passwordHasher;
	private final int maxFailures;
	private final long lockoutMillis;
	private final long sessionAfterDisconnectMillis;
	private final Map<UUID, RuntimeState> runtimeStates;
	private final Map<UUID, SessionState> sessions;

	public AuthService(LunaLogger logger, AuthRepository repository, Pbkdf2PasswordHasher passwordHasher, int maxFailures, long lockoutMillis, long sessionAfterDisconnectMillis) {
		this.logger = logger.scope("Service");
		this.repository = repository;
		this.passwordHasher = passwordHasher;
		this.maxFailures = Math.max(1, maxFailures);
		this.lockoutMillis = Math.max(1000L, lockoutMillis);
		this.sessionAfterDisconnectMillis = Math.max(1000L, sessionAfterDisconnectMillis);
		this.runtimeStates = new ConcurrentHashMap<>();
		this.sessions = new ConcurrentHashMap<>();
	}

	public JoinDecision handleJoin(UUID playerUuid, String username, String ipAddress, QuickAuthTrustDecision quickAuthTrustDecision) {
		long now = System.currentTimeMillis();
		Optional<AuthAccount> accountOptional = repository.find(playerUuid);
		if (accountOptional.isEmpty() || !accountOptional.get().hasPassword()) {
			runtimeStates.put(playerUuid, new RuntimeState(false, ipAddress));
			if (quickAuthTrustDecision.trusted()) {
				recordAudit(playerUuid, ipAddress, "JOIN_REQUIRE_REGISTER_QUICK_AUTH", "FAILED", "Trusted quick-auth nhưng chưa có mật khẩu", "SYSTEM", now);
				repository.recordSessionEvent(playerUuid, username, ipAddress, "JOIN_REQUIRE_REGISTER_QUICK_AUTH", "Trusted quick-auth nhưng phải đặt mật khẩu", now);
				return JoinDecision.requireRegisterWithQuickAuthNotice();
			}

			recordAudit(playerUuid, ipAddress, "JOIN_REQUIRE_REGISTER", "FAILED", "Tài khoản chưa có mật khẩu", "SYSTEM", now);
			repository.recordSessionEvent(playerUuid, username, ipAddress, "JOIN_REQUIRE_REGISTER", "Tài khoản chưa có mật khẩu", now);
			return JoinDecision.requireRegister();
		}

		AuthAccount account = accountOptional.get();
		if (account.isLocked(now)) {
			runtimeStates.put(playerUuid, new RuntimeState(false, ipAddress));
			long remainingMillis = account.lockoutUntilEpochMillis() - now;
			recordAudit(playerUuid, ipAddress, "JOIN_LOCKED", "FAILED", "Tài khoản đang bị khóa", "SYSTEM", now);
			repository.recordSessionEvent(playerUuid, username, ipAddress, "JOIN_LOCKED", "Tài khoản đang bị khóa", now);
			return JoinDecision.lockedState(remainingMillis);
		}

		SessionReuseStatus sessionReuseStatus = hasReusableSession(playerUuid, ipAddress, now);
		if (sessionReuseStatus.reusable() && quickAuthTrustDecision.trusted()) {
			Optional<AuthAccount> usernameMapped = repository.findByUsername(username);
			if (usernameMapped.isPresent() && !usernameMapped.get().playerUuid().equals(playerUuid)) {
				recordAudit(playerUuid, ipAddress, "UUID_CONFLICT", "FAILED", "Tên người chơi đã map sang UUID khác", "SYSTEM", now);
				repository.recordSessionEvent(playerUuid, username, ipAddress, "UUID_CONFLICT", "Username mapping conflict", now);
				return JoinDecision.requireLogin();
			}
			authenticateRuntime(playerUuid, ipAddress);
			recordAudit(playerUuid, ipAddress, "QUICK_AUTH_ONLINE_UUID", "SUCCESS", "Trusted forwarding path hợp lệ", "SYSTEM", now);
			repository.recordSessionEvent(playerUuid, username, ipAddress, "QUICK_AUTH_ONLINE_UUID", "Phiên cũ hợp lệ và trusted", now);
			logger.audit("Quick-login thành công cho " + username + " (" + playerUuid + ")");
			return JoinDecision.quickLoginState();
		}
		if (sessionReuseStatus.reusable() && !quickAuthTrustDecision.trusted()) {
			recordAudit(playerUuid, ipAddress, quickAuthTrustDecision.reasonCode(), "FAILED", "Có session nhưng trust path không hợp lệ", "SYSTEM", now);
			repository.recordSessionEvent(playerUuid, username, ipAddress, quickAuthTrustDecision.reasonCode(), "Có session nhưng không đủ trust", now);
		}
		if (!sessionReuseStatus.reusable()) {
			repository.recordSessionEvent(playerUuid, username, ipAddress, "SESSION_REUSE_REJECTED", sessionReuseStatus.reason(), now);
		}

		invalidateSession(playerUuid, username, ipAddress, "Bắt buộc login lại khi join");
		runtimeStates.put(playerUuid, new RuntimeState(false, ipAddress));
		recordAudit(playerUuid, ipAddress, "JOIN_REQUIRE_LOGIN", "FAILED", "Yêu cầu đăng nhập mật khẩu", "SYSTEM", now);
		return JoinDecision.requireLogin();
	}

	public AuthResult register(UUID playerUuid, String username, String ipAddress, String password, String confirmPassword) {
		long now = System.currentTimeMillis();
		if (password == null || password.length() < 6) {
			return AuthResult.failed("❌ Mật khẩu phải có ít nhất 6 ký tự.");
		}
		if (!password.equals(confirmPassword)) {
			return AuthResult.failed("❌ Mật khẩu nhập lại không khớp.");
		}

		AuthAccount current = repository.find(playerUuid).orElse(new AuthAccount(playerUuid, username, "", ipAddress, 0, 0L, 0L, now, now));
		if (current.hasPassword()) {
			return AuthResult.failed("❌ Tài khoản đã đăng ký. Hãy dùng /login.");
		}

		String hash = passwordHasher.hash(password);
		AuthAccount next = new AuthAccount(
			playerUuid,
			username,
			hash,
			ipAddress,
			0,
			0L,
			now,
			current.createdAtEpochMillis(),
			now
		);
		repository.upsert(next);
		recordAudit(playerUuid, ipAddress, "REGISTER_SUCCESS", "SUCCESS", "Đăng ký thành công", username, now);
		repository.recordAttemptLegacy(playerUuid, username, ipAddress, true, "REGISTER_SUCCESS", now);
		authenticateRuntime(playerUuid, ipAddress);
		repository.recordSessionEvent(playerUuid, username, ipAddress, "REGISTER_SUCCESS", "Tạo mật khẩu mới", now);
		return AuthResult.success("✔ Đăng ký thành công. Bạn đã được xác thực.");
	}

	public AuthResult login(UUID playerUuid, String username, String ipAddress, String password) {
		long now = System.currentTimeMillis();
		AuthAccount account = repository.find(playerUuid).orElse(null);
		if (account == null || !account.hasPassword()) {
			recordAudit(playerUuid, ipAddress, "LOGIN_NOT_REGISTERED", "FAILED", "Chưa có mật khẩu", username, now);
			repository.recordAttemptLegacy(playerUuid, username, ipAddress, false, "LOGIN_NOT_REGISTERED", now);
			repository.recordSessionEvent(playerUuid, username, ipAddress, "LOGIN_NOT_REGISTERED", "Chưa có mật khẩu", now);
			return AuthResult.failed("❌ Tài khoản chưa đăng ký. Hãy dùng /register.");
		}
		if (account.isLocked(now)) {
			long remainingSeconds = Math.max(1L, (account.lockoutUntilEpochMillis() - now) / 1000L);
			recordAudit(playerUuid, ipAddress, "LOGIN_LOCKED", "FAILED", "Đăng nhập khi tài khoản đang lock", username, now);
			repository.recordAttemptLegacy(playerUuid, username, ipAddress, false, "LOGIN_LOCKED", now);
			repository.recordSessionEvent(playerUuid, username, ipAddress, "LOGIN_LOCKED", "Đăng nhập khi đang lock", now);
			return AuthResult.failed("❌ Tài khoản đang bị khóa tạm thời, còn " + remainingSeconds + " giây.");
		}

		boolean verified = passwordHasher.verify(account.passwordHash(), password);
		if (!verified) {
			int nextFailures = account.failedAttempts() + 1;
			long lockoutUntil = nextFailures >= maxFailures ? now + lockoutMillis : 0L;
			repository.upsert(new AuthAccount(
				account.playerUuid(),
				username,
				account.passwordHash(),
				account.lastIp(),
				nextFailures,
				lockoutUntil,
				account.lastLoginAtEpochMillis(),
				account.createdAtEpochMillis(),
				now
			));
			recordAudit(playerUuid, ipAddress, "LOGIN_WRONG_PASSWORD", "FAILED", "Sai mật khẩu", username, now);
			repository.recordAttemptLegacy(playerUuid, username, ipAddress, false, "LOGIN_WRONG_PASSWORD", now);
			repository.recordSessionEvent(playerUuid, username, ipAddress, "LOGIN_WRONG_PASSWORD", "Sai mật khẩu", now);
			if (lockoutUntil > 0L) {
				recordAudit(playerUuid, ipAddress, "LOGIN_LOCKOUT_TRIGGERED", "FAILED", "Vượt ngưỡng sai mật khẩu", username, now);
				repository.recordSessionEvent(playerUuid, username, ipAddress, "LOGIN_LOCKOUT_TRIGGERED", "Vượt ngưỡng sai mật khẩu", now);
				return AuthResult.failed("❌ Sai mật khẩu quá nhiều lần. Tài khoản bị khóa tạm thời 2 phút.");
			}
			return AuthResult.failed("❌ Sai mật khẩu. Vui lòng thử lại.");
		}

		repository.upsert(new AuthAccount(
			account.playerUuid(),
			username,
			account.passwordHash(),
			ipAddress,
			0,
			0L,
			now,
			account.createdAtEpochMillis(),
			now
		));
		recordAudit(playerUuid, ipAddress, "LOGIN_SUCCESS", "SUCCESS", "Xác thực mật khẩu thành công", username, now);
		repository.recordAttemptLegacy(playerUuid, username, ipAddress, true, "LOGIN_SUCCESS", now);
		authenticateRuntime(playerUuid, ipAddress);
		repository.recordSessionEvent(playerUuid, username, ipAddress, "LOGIN_SUCCESS", "Xác thực mật khẩu thành công", now);
		return AuthResult.success("✔ Đăng nhập thành công.");
	}

	public void onDisconnect(UUID playerUuid) {
		long now = System.currentTimeMillis();
		SessionState sessionState = sessions.get(playerUuid);
		if (sessionState == null || !sessionState.connected()) {
			return;
		}
		String username = repository.find(playerUuid).map(AuthAccount::username).orElse("unknown");
		repository.recordSessionEvent(playerUuid, username, sessionState.ipAddress(), "DISCONNECT", "Giữ session tạm thời sau disconnect", now);
		sessions.put(playerUuid, new SessionState(sessionState.ipAddress(), sessionState.createdAtEpochMillis(), now + sessionAfterDisconnectMillis, false));
	}

	public boolean isAuthenticated(UUID playerUuid) {
		RuntimeState state = runtimeStates.get(playerUuid);
		return state != null && state.authenticated();
	}

	public Optional<AuthAccount> account(UUID playerUuid) {
		return repository.find(playerUuid);
	}

	public AuthResult adminResetPassword(UUID playerUuid, String username, String ipAddress, String actor) {
		long now = System.currentTimeMillis();
		repository.deletePassword(playerUuid, ipAddress, username, now);
		invalidateSession(playerUuid, username, ipAddress, "ADMIN_RESET_PASSWORD");
		runtimeStates.put(playerUuid, new RuntimeState(false, ipAddress));
		recordAudit(playerUuid, ipAddress, "ADMIN_RESET_PASSWORD", "SUCCESS", "Quản trị viên reset mật khẩu", actor, now);
		repository.recordAttemptLegacy(playerUuid, username, ipAddress, true, "ADMIN_RESET_PASSWORD", now);
		repository.recordSessionEvent(playerUuid, username, ipAddress, "ADMIN_RESET_PASSWORD", "Quản trị viên reset mật khẩu", now);
		return AuthResult.success("✔ Đã reset mật khẩu. Người chơi sẽ phải /register lại.");
	}

	public AuthResult adminUnlock(UUID playerUuid, String username, String ipAddress, String actor) {
		long now = System.currentTimeMillis();
		AuthAccount account = repository.find(playerUuid).orElse(null);
		if (account == null) {
			return AuthResult.failed("❌ Người chơi chưa có dữ liệu xác thực.");
		}
		repository.upsert(new AuthAccount(
			account.playerUuid(),
			username,
			account.passwordHash(),
			account.lastIp(),
			0,
			0L,
			account.lastLoginAtEpochMillis(),
			account.createdAtEpochMillis(),
			now
		));
		recordAudit(playerUuid, ipAddress, "ADMIN_UNLOCK", "SUCCESS", "Quản trị viên mở khóa", actor, now);
		repository.recordAttemptLegacy(playerUuid, username, ipAddress, true, "ADMIN_UNLOCK", now);
		repository.recordSessionEvent(playerUuid, username, ipAddress, "ADMIN_UNLOCK", "Quản trị viên mở khóa", now);
		return AuthResult.success("✔ Đã mở khóa tài khoản.");
	}

	public AuthResult adminForceLogout(UUID playerUuid, String ipAddress, String actor) {
		String username = repository.find(playerUuid).map(AuthAccount::username).orElse("unknown");
		long now = System.currentTimeMillis();
		invalidateSession(playerUuid, username, ipAddress, "ADMIN_FORCE_LOGOUT");
		runtimeStates.put(playerUuid, new RuntimeState(false, ipAddress));
		recordAudit(playerUuid, ipAddress, "ADMIN_FORCE_LOGOUT", "SUCCESS", "Quản trị viên buộc logout", actor, now);
		repository.recordAttemptLegacy(playerUuid, username, ipAddress, true, "ADMIN_FORCE_LOGOUT", now);
		repository.recordSessionEvent(playerUuid, username, ipAddress, "ADMIN_FORCE_LOGOUT", "Quản trị viên buộc logout", now);
		return AuthResult.success("✔ Đã buộc đăng xuất phiên hiện tại.");
	}

	public AuthResult adminInvalidateSession(UUID playerUuid, String username, String ipAddress, String actor) {
		long now = System.currentTimeMillis();
		invalidateSession(playerUuid, username, ipAddress, "ADMIN_INVALIDATE_SESSION");
		recordAudit(playerUuid, ipAddress, "ADMIN_INVALIDATE_SESSION", "SUCCESS", "Hủy session reusable", actor, now);
		repository.recordAttemptLegacy(playerUuid, username, ipAddress, true, "ADMIN_INVALIDATE_SESSION", now);
		repository.recordSessionEvent(playerUuid, username, ipAddress, "ADMIN_INVALIDATE_SESSION", "Hủy session reusable", now);
		return AuthResult.success("✔ Đã hủy session hiện có của người chơi.");
	}

	public AuthResult adminForceLogin(UUID playerUuid, String username, String ipAddress, String actor) {
		long now = System.currentTimeMillis();
		AuthAccount account = repository.find(playerUuid).orElse(null);
		if (account == null || !account.hasPassword()) {
			return AuthResult.failed("❌ Người chơi chưa có mật khẩu. Không thể forcelogin.");
		}
		authenticateRuntime(playerUuid, ipAddress);
		recordAudit(playerUuid, ipAddress, "ADMIN_FORCE_LOGIN", "SUCCESS", "Quản trị viên xác thực thủ công", actor, now);
		repository.recordAttemptLegacy(playerUuid, username, ipAddress, true, "ADMIN_FORCE_LOGIN", now);
		repository.recordSessionEvent(playerUuid, username, ipAddress, "ADMIN_FORCE_LOGIN", "Quản trị viên xác thực thủ công", now);
		return AuthResult.success("✔ Đã xác thực cưỡng bức cho người chơi.");
	}

	public AuthResult adminChangePassword(UUID playerUuid, String username, String ipAddress, String newPassword, String actor) {
		long now = System.currentTimeMillis();
		if (newPassword == null || newPassword.length() < 6) {
			return AuthResult.failed("❌ Mật khẩu mới phải có ít nhất 6 ký tự.");
		}
		AuthAccount account = repository.find(playerUuid).orElse(null);
		if (account == null) {
			return AuthResult.failed("❌ Người chơi chưa có dữ liệu xác thực.");
		}

		String hash = passwordHasher.hash(newPassword);
		repository.upsert(new AuthAccount(
			account.playerUuid(),
			username,
			hash,
			ipAddress,
			0,
			0L,
			account.lastLoginAtEpochMillis(),
			account.createdAtEpochMillis(),
			now
		));
		invalidateSession(playerUuid, username, ipAddress, "ADMIN_CHANGE_PASSWORD");
		runtimeStates.put(playerUuid, new RuntimeState(false, ipAddress));
		recordAudit(playerUuid, ipAddress, "ADMIN_CHANGE_PASSWORD", "SUCCESS", "Quản trị viên đổi mật khẩu", actor, now);
		repository.recordAttemptLegacy(playerUuid, username, ipAddress, true, "ADMIN_CHANGE_PASSWORD", now);
		repository.recordSessionEvent(playerUuid, username, ipAddress, "ADMIN_CHANGE_PASSWORD", "Quản trị viên đổi mật khẩu", now);
		return AuthResult.success("✔ Đã đổi mật khẩu và hủy session hiện tại của người chơi.");
	}

	public SessionSnapshot snapshot(UUID playerUuid) {
		RuntimeState runtime = runtimeStates.get(playerUuid);
		SessionState session = sessions.get(playerUuid);
		return new SessionSnapshot(
			runtime != null && runtime.authenticated(),
			session != null,
			session != null && session.connected(),
			session == null ? 0L : session.createdAtEpochMillis(),
			session == null ? 0L : session.expiresAtEpochMillis(),
			session == null ? "" : session.ipAddress()
		);
	}

	public java.util.List<AuthRepository.LoginHistoryEntry> recentLoginHistory(UUID playerUuid, int limit) {
		return repository.listLoginHistory(playerUuid, limit);
	}

	public java.util.List<AuthRepository.SessionEventEntry> recentSessionEvents(UUID playerUuid, int limit) {
		return repository.listSessionEvents(playerUuid, limit);
	}

	public void claimOfflineUuidMapping(String username, UUID offlineUuid, UUID onlineUuid) {
		repository.claimOfflineUuidMapping(username, offlineUuid, onlineUuid, System.currentTimeMillis());
	}

	public Optional<UUID> findClaimedOnlineUuid(UUID offlineUuid, String username) {
		return repository.findClaimedOnlineUuid(offlineUuid, username);
	}

	public void cleanupHistoryRetention(int retentionDays) {
		long now = System.currentTimeMillis();
		long retentionMillis = Math.max(1L, retentionDays) * 24L * 60L * 60L * 1000L;
		repository.cleanupHistory(now - retentionMillis);
	}

	private void authenticateRuntime(UUID playerUuid, String ipAddress) {
		long now = System.currentTimeMillis();
		runtimeStates.put(playerUuid, new RuntimeState(true, ipAddress));
		sessions.put(playerUuid, new SessionState(ipAddress, now, Long.MAX_VALUE, true));
		String username = repository.find(playerUuid).map(AuthAccount::username).orElse("unknown");
		repository.recordSessionEvent(playerUuid, username, ipAddress, "SESSION_AUTHENTICATED", "Runtime authenticated=true", now);
	}

	private SessionReuseStatus hasReusableSession(UUID playerUuid, String ipAddress, long now) {
		SessionState sessionState = sessions.get(playerUuid);
		if (sessionState == null) {
			return new SessionReuseStatus(false, "NO_SESSION");
		}
		if (!sessionState.ipAddress().equals(ipAddress)) {
			sessions.remove(playerUuid);
			return new SessionReuseStatus(false, "IP_MISMATCH");
		}
		if (sessionState.connected()) {
			return new SessionReuseStatus(true, "ALREADY_CONNECTED");
		}
		if (sessionState.expiresAtEpochMillis() >= now) {
			sessions.put(playerUuid, new SessionState(ipAddress, sessionState.createdAtEpochMillis(), Long.MAX_VALUE, true));
			return new SessionReuseStatus(true, "RECONNECTED_IN_WINDOW");
		}
		sessions.remove(playerUuid);
		return new SessionReuseStatus(false, "SESSION_EXPIRED");
	}

	private void invalidateSession(UUID playerUuid, String username, String ipAddress, String reason) {
		sessions.remove(playerUuid);
		repository.recordSessionEvent(playerUuid, username, ipAddress, "SESSION_INVALIDATED", reason, System.currentTimeMillis());
	}

	private void recordAudit(UUID playerUuid, String ipAddress, String eventType, String result, String reason, String actor, long now) {
		repository.recordAuditEvent(playerUuid, ipAddress, eventType, result, reason, actor, now);
	}

	public record JoinDecision(boolean authenticated, boolean needsRegister, boolean locked, String message) {
		public static JoinDecision requireRegister() {
			return new JoinDecision(false, true, false, "ℹ Tài khoản chưa đăng ký. Dùng /register <mật_khẩu> <nhập_lại>.");
		}

		public static JoinDecision requireRegisterWithQuickAuthNotice() {
			return new JoinDecision(
				false,
				true,
				false,
				"Bạn đang đăng nhập bằng phiên Mojang hợp lệ nên đã được nhận diện bởi <color:#39d98a><b>Luna Quick Auth™</b></color>. Tuy nhiên tài khoản này chưa có mật khẩu, hãy đặt mật khẩu bằng <color:#f5f7fa><b>/register <mật_khẩu> <nhập_lại></b></color> để bảo vệ tài khoản khi client ở chế độ offline."
			);
		}

		public static JoinDecision requireLogin() {
			return new JoinDecision(false, false, false, "ℹ Vui lòng đăng nhập bằng /login <mật_khẩu>.");
		}

		public static JoinDecision lockedState(long remainingMillis) {
			long seconds = Math.max(1L, remainingMillis / 1000L);
			return new JoinDecision(false, false, true, "❌ Tài khoản đang bị khóa tạm thời, còn " + seconds + " giây.");
		}

		public static JoinDecision quickLoginState() {
			return new JoinDecision(true, false, false, "✔ Đăng nhập nhanh thành công.");
		}

	}

	public record AuthResult(boolean success, String message) {
		static AuthResult success(String message) {
			return new AuthResult(true, message);
		}

		static AuthResult failed(String message) {
			return new AuthResult(false, message);
		}
	}

	private record RuntimeState(boolean authenticated, String ipAddress) {
	}

	private record SessionState(String ipAddress, long createdAtEpochMillis, long expiresAtEpochMillis, boolean connected) {
	}

	public record SessionSnapshot(boolean runtimeAuthenticated, boolean hasSession, boolean sessionConnected, long sessionCreatedAtEpochMillis, long sessionExpiresAtEpochMillis, String sessionIp) {
	}

	public record QuickAuthTrustDecision(boolean trusted, String reasonCode) {
		public static QuickAuthTrustDecision trusted(String reasonCode) {
			return new QuickAuthTrustDecision(true, reasonCode);
		}

		public static QuickAuthTrustDecision denied(String reasonCode) {
			return new QuickAuthTrustDecision(false, reasonCode);
		}
	}

	private record SessionReuseStatus(boolean reusable, String reason) {
	}
}
