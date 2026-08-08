package dev.belikhun.luna.auth.http;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.auth.model.AuthAccount;
import dev.belikhun.luna.auth.service.AuthService;
import dev.belikhun.luna.core.api.heartbeat.HeartbeatFormCodec;
import dev.belikhun.luna.core.api.http.HttpResponse;
import dev.belikhun.luna.core.api.http.LunaJson;
import dev.belikhun.luna.core.api.http.RequestAuthorizer;
import dev.belikhun.luna.core.api.http.Router;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import net.kyori.adventure.text.Component;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Account administration for the Luna control console: read a player's
 * authentication state, reset their password, issue a temporary one, set a
 * permanent one, unlock a locked-out account and force a logout.
 *
 * The in-game /auth command can only act on players who are connected right
 * now, which is the wrong constraint for a console — the person who needs a
 * password reset is usually the one who cannot get in. These routes therefore
 * resolve a player through the account table first and fall back to the online
 * roster, so an offline player is administrable exactly like a connected one.
 *
 * Every response is token-gated like the rest of LunaCore's HTTP surface. A
 * generated temporary password is returned once, in the response to the request
 * that created it, and is never readable afterwards — only its hash is stored.
 */
public final class AuthHttpEndpoints {
	/**
	 * Alphabet for generated passwords: no O/0, I/l/1, so a password read off a
	 * screen and typed into a chat box survives the trip.
	 */
	private static final String PASSWORD_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

	/** Length of a generated temporary password. */
	private static final int GENERATED_PASSWORD_LENGTH = 10;

	/** Shortest temporary-password lifetime a caller may ask for. */
	private static final long MIN_TEMPORARY_MINUTES = 5L;

	/** Longest temporary-password lifetime a caller may ask for — thirty days. */
	private static final long MAX_TEMPORARY_MINUTES = 30L * 24L * 60L;

	private final LunaLogger logger;
	private final ProxyServer proxyServer;
	private final AuthService authService;
	private final Consumer<Player> authStateSync;
	private final RequestAuthorizer authorizer;
	private final SecureRandom random;

	public AuthHttpEndpoints(
		LunaLogger logger,
		ProxyServer proxyServer,
		AuthService authService,
		Consumer<Player> authStateSync,
		RequestAuthorizer authorizer
	) {
		this.logger = logger.scope("AuthHttp");
		this.proxyServer = proxyServer;
		this.authService = authService;
		this.authStateSync = authStateSync;
		this.authorizer = authorizer;
		this.random = new SecureRandom();
	}

	public void register(Router router) {
		router.get("/auth/accounts/{player}", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối truy vấn /auth/accounts do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			long startedAt = System.nanoTime();
			String reference = request.pathParam("player", "").trim();
			Target target = resolve(reference, "");

			if (target == null) {
				return LunaJson.error(404, "player not found: " + reference);
			}

			return LunaJson.envelope(200, describe(target), startedAt);
		});

		router.post("/auth/accounts/{player}", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối thao tác /auth/accounts do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			long startedAt = System.nanoTime();
			String reference = request.pathParam("player", "").trim();
			Map<String, String> body = HeartbeatFormCodec.decode(request.body());
			String action = body.getOrDefault("action", "").trim().toLowerCase(Locale.ROOT);
			Target target = resolve(reference, body.getOrDefault("username", ""));

			if (target == null) {
				return LunaJson.error(404, "player not found: " + reference);
			}

			try {
				return switch (action) {
					case "reset" -> apply(target, startedAt, body, resetPassword(target, body));
					case "temporary" -> temporaryPassword(target, startedAt, body);
					case "password" -> apply(target, startedAt, body, changePassword(target, body));
					case "unlock" -> apply(target, startedAt, body, unlock(target, body));
					case "logout" -> apply(target, startedAt, body, forceLogout(target, body));
					default -> LunaJson.error(400, "unknown action: " + action);
				};
			} catch (IllegalArgumentException invalid) {
				return LunaJson.error(400, invalid.getMessage());
			} catch (RuntimeException failure) {
				logger.error("Thao tác auth thất bại (" + action + "): " + failure.getMessage(), failure);
				return LunaJson.error(500, "auth operation failed: " + rootMessage(failure));
			}
		});
	}

	// ----------------------------------------------------------------- actions

	private AuthService.AuthResult resetPassword(Target target, Map<String, String> body) {
		AuthService.AuthResult result = authService.adminResetPassword(target.uuid(), target.username(), target.ipAddress(), actor(body));

		if (result.success()) {
			disconnect(target, "Mật khẩu của bạn đã được đặt lại. Hãy dùng /register để tạo mật khẩu mới.");
			logger.audit("Console reset mật khẩu của " + target.username() + ".");
		}

		return result;
	}

	private AuthService.AuthResult changePassword(Target target, Map<String, String> body) {
		String password = body.getOrDefault("password", "").trim();

		if (password.isBlank()) {
			throw new IllegalArgumentException("password is required for action=password");
		}

		AuthService.AuthResult result = authService.adminChangePassword(target.uuid(), target.username(), target.ipAddress(), password, actor(body));

		if (result.success()) {
			disconnect(target, "Mật khẩu đã được quản trị viên thay đổi. Hãy đăng nhập lại.");
			logger.audit("Console đổi mật khẩu của " + target.username() + ".");
		}

		return result;
	}

	/**
	 * Issue a temporary password, generating one when the caller did not supply
	 * it. The plaintext travels back in this response and nowhere else, so the
	 * console can show it to whoever will pass it on.
	 */
	private HttpResponse temporaryPassword(Target target, long startedAt, Map<String, String> body) {
		String supplied = body.getOrDefault("password", "").trim();
		boolean generated = supplied.isBlank();
		String password = generated ? generatePassword() : supplied;
		long minutes = temporaryMinutes(body);

		AuthService.AuthResult result = authService.adminSetTemporaryPassword(
			target.uuid(),
			target.username(),
			target.ipAddress(),
			password,
			minutes * 60L * 1000L,
			actor(body)
		);

		if (!result.success()) {
			return LunaJson.error(400, result.message());
		}

		disconnect(target, "Quản trị viên đã cấp cho bạn một mật khẩu tạm thời. Hãy đăng nhập lại.");
		logger.audit("Console cấp mật khẩu tạm thời cho " + target.username() + " (" + minutes + " phút).");

		Map<String, Object> payload = describe(resolveAgain(target));
		payload.put("action", "temporary");
		payload.put("success", true);
		payload.put("message", result.message());
		payload.put("password", password);
		payload.put("generated", generated);
		payload.put("expiresInMinutes", minutes);

		return LunaJson.envelope(200, payload, startedAt);
	}

	private AuthService.AuthResult unlock(Target target, Map<String, String> body) {
		AuthService.AuthResult result = authService.adminUnlock(target.uuid(), target.username(), target.ipAddress(), actor(body));

		if (result.success()) {
			sync(target);
		}

		return result;
	}

	private AuthService.AuthResult forceLogout(Target target, Map<String, String> body) {
		AuthService.AuthResult result = authService.adminForceLogout(target.uuid(), target.ipAddress(), actor(body));

		if (result.success()) {
			sync(target);
		}

		return result;
	}

	/** Wrap a plain result in the account payload, so callers see the new state. */
	private HttpResponse apply(Target target, long startedAt, Map<String, String> body, AuthService.AuthResult result) {
		if (!result.success()) {
			return LunaJson.error(400, result.message());
		}

		Map<String, Object> payload = describe(resolveAgain(target));
		payload.put("action", body.getOrDefault("action", "").trim().toLowerCase(Locale.ROOT));
		payload.put("success", true);
		payload.put("message", result.message());

		return LunaJson.envelope(200, payload, startedAt);
	}

	// ------------------------------------------------------------------ shared

	/** The account state as the console reads it. */
	private Map<String, Object> describe(Target target) {
		long now = System.currentTimeMillis();
		AuthAccount account = target.account();
		Map<String, Object> payload = new LinkedHashMap<>();

		payload.put("uuid", target.uuid().toString());
		payload.put("username", target.username());
		payload.put("online", proxyServer.getPlayer(target.uuid()).isPresent());
		payload.put("authenticated", authService.isAuthenticated(target.uuid()));
		payload.put("registered", account != null && account.hasPassword());
		payload.put("locked", account != null && account.isLocked(now));
		payload.put("lockedUntilEpochMillis", account == null ? 0L : account.lockoutUntilEpochMillis());
		payload.put("failedAttempts", account == null ? 0 : account.failedAttempts());
		payload.put("lastIp", account == null ? "" : account.lastIp());
		payload.put("lastLoginAtEpochMillis", account == null ? 0L : account.lastLoginAtEpochMillis());
		payload.put("createdAtEpochMillis", account == null ? 0L : account.createdAtEpochMillis());
		payload.put("updatedAtEpochMillis", account == null ? 0L : account.updatedAtEpochMillis());
		payload.put("temporaryPassword", account != null && account.hasTemporaryPassword());
		payload.put("temporaryPasswordUntilEpochMillis", account == null ? 0L : account.temporaryPasswordUntilEpochMillis());
		payload.put("temporaryPasswordExpired", account != null && account.temporaryPasswordExpired(now));

		AuthService.SessionSnapshot session = authService.snapshot(target.uuid());
		Map<String, Object> sessionPayload = new LinkedHashMap<>();

		sessionPayload.put("hasSession", session.hasSession());
		sessionPayload.put("connected", session.sessionConnected());
		sessionPayload.put("createdAtEpochMillis", session.sessionCreatedAtEpochMillis());
		sessionPayload.put("expiresAtEpochMillis", session.sessionExpiresAtEpochMillis());
		sessionPayload.put("ip", session.sessionIp());
		payload.put("session", sessionPayload);

		return payload;
	}

	/**
	 * Resolve a console-supplied reference — a UUID or a username — to the
	 * account it names. The account table is asked first because it holds every
	 * player who ever registered, online or not.
	 *
	 * @param fallbackUsername name to use when the reference is a UUID with no account row yet
	 */
	private Target resolve(String reference, String fallbackUsername) {
		String trimmed = reference == null ? "" : reference.trim();

		if (trimmed.isBlank()) {
			return null;
		}

		UUID parsed = null;

		try {
			parsed = UUID.fromString(trimmed);
		} catch (IllegalArgumentException notAUuid) {
			// a username, then
		}

		if (parsed != null) {
			AuthAccount account = authService.account(parsed).orElse(null);
			Player online = proxyServer.getPlayer(parsed).orElse(null);
			String username = account != null
				? account.username()
				: online != null
					? online.getUsername()
					: fallbackUsername.trim();

			if (username.isBlank()) {
				return null;
			}

			return new Target(parsed, username, account, online);
		}

		AuthAccount account = authService.accountByUsername(trimmed).orElse(null);

		if (account != null) {
			return new Target(account.playerUuid(), account.username(), account, proxyServer.getPlayer(account.playerUuid()).orElse(null));
		}

		Player online = proxyServer.getPlayer(trimmed).orElse(null);

		if (online == null) {
			return null;
		}

		return new Target(online.getUniqueId(), online.getUsername(), null, online);
	}

	/** Re-read the account after a mutation, so the response reports the new state. */
	private Target resolveAgain(Target target) {
		AuthAccount account = authService.account(target.uuid()).orElse(null);

		return new Target(target.uuid(), target.username(), account, proxyServer.getPlayer(target.uuid()).orElse(null));
	}

	/** Kick a connected player so the credential change takes effect immediately. */
	private void disconnect(Target target, String reason) {
		Player online = target.online();

		if (online == null) {
			return;
		}

		sync(target);
		online.disconnect(Component.text(reason));
	}

	private void sync(Target target) {
		Player online = target.online();

		if (online == null) {
			return;
		}

		try {
			authStateSync.accept(online);
		} catch (RuntimeException failure) {
			// the stored state is authoritative; the backend re-syncs on next join
			logger.warn("Không thể đồng bộ trạng thái auth cho " + target.username() + ": " + failure.getMessage());
		}
	}

	private long temporaryMinutes(Map<String, String> body) {
		String raw = body.getOrDefault("expiresInMinutes", "").trim();

		if (raw.isBlank()) {
			return AuthService.DEFAULT_TEMPORARY_PASSWORD_MILLIS / 60000L;
		}

		long minutes;

		try {
			minutes = Long.parseLong(raw);
		} catch (NumberFormatException notANumber) {
			throw new IllegalArgumentException("expiresInMinutes must be a number");
		}

		if (minutes < MIN_TEMPORARY_MINUTES || minutes > MAX_TEMPORARY_MINUTES) {
			throw new IllegalArgumentException("expiresInMinutes must be between " + MIN_TEMPORARY_MINUTES + " and " + MAX_TEMPORARY_MINUTES);
		}

		return minutes;
	}

	private String generatePassword() {
		StringBuilder builder = new StringBuilder(GENERATED_PASSWORD_LENGTH);

		for (int index = 0; index < GENERATED_PASSWORD_LENGTH; index++) {
			builder.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
		}

		return builder.toString();
	}

	private String actor(Map<String, String> body) {
		String actor = body.getOrDefault("actor", "").trim();

		return actor.isBlank() ? "console" : actor;
	}

	private String rootMessage(Throwable throwable) {
		Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
		String message = cause.getMessage();

		return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
	}

	/**
	 * A resolved administration target: who they are, what the account table has
	 * on them (null when they never registered) and their live connection if any.
	 */
	private record Target(UUID uuid, String username, AuthAccount account, Player online) {
		/** Best known address: the live one, else the last one recorded. */
		String ipAddress() {
			if (online != null) {
				return online.getRemoteAddress().getAddress().getHostAddress();
			}

			return account == null || account.lastIp() == null ? "" : account.lastIp();
		}
	}
}
