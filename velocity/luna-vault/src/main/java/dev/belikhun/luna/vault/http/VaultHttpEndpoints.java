package dev.belikhun.luna.vault.http;

import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.http.LunaJson;
import dev.belikhun.luna.core.api.http.RequestAuthorizer;
import dev.belikhun.luna.core.api.http.Router;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.velocity.LunaCoreVelocity;
import dev.belikhun.luna.core.velocity.VelocityMoneyFormat;
import dev.belikhun.luna.vault.api.VaultMoney;
import dev.belikhun.luna.vault.api.VaultPlayerSnapshot;
import dev.belikhun.luna.vault.api.VaultTransactionPage;
import dev.belikhun.luna.vault.api.VaultTransactionRecord;
import dev.belikhun.luna.vault.api.VaultTransactionSummary;
import dev.belikhun.luna.vault.service.VelocityVaultService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only economy state for the Luna control console: what a player is worth
 * and where their money has been.
 *
 * LunaVault is the network's source of truth for balances, so the console cannot
 * answer either question from its own data — the backends only ever hold a cache.
 * Both routes resolve a player by UUID or by name and neither creates an account,
 * because browsing the player directory must not leave empty accounts behind it.
 *
 * Amounts travel as minor units (the storable, sortable form) *and* as the string
 * the server itself would print, since the currency symbol and grouping live in
 * LunaCore's config and the console has no business guessing them.
 */
public final class VaultHttpEndpoints {
	/** Transactions returned when the caller does not ask for a page size. */
	private static final int DEFAULT_PAGE_SIZE = 25;

	/** Ceiling on a caller-supplied page size, so one request cannot pull the table. */
	private static final int MAX_PAGE_SIZE = 200;

	private final LunaLogger logger;
	private final ProxyServer proxyServer;
	private final VelocityVaultService vaultService;
	private final RequestAuthorizer authorizer;

	public VaultHttpEndpoints(
		LunaLogger logger,
		ProxyServer proxyServer,
		VelocityVaultService vaultService,
		RequestAuthorizer authorizer
	) {
		this.logger = logger.scope("VaultHttp");
		this.proxyServer = proxyServer;
		this.vaultService = vaultService;
		this.authorizer = authorizer;
	}

	public void register(Router router) {
		router.get("/vault/accounts/{player}/transactions", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối truy vấn lịch sử giao dịch do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			long startedAt = System.nanoTime();
			String reference = request.pathParam("player", "").trim();

			if (!vaultService.databaseEnabled()) {
				return LunaJson.error(503, "vault database is not available");
			}

			VelocityVaultService.AccountTarget target = vaultService.resolveReference(reference).orElse(null);

			if (target == null) {
				return LunaJson.error(404, "player not found: " + reference);
			}

			int page = intParam(request.queryParam("page", ""), 0);
			int pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, intParam(request.queryParam("pageSize", ""), DEFAULT_PAGE_SIZE)));
			VaultTransactionPage history = vaultService.history(target.playerId(), page, pageSize).join();

			return LunaJson.envelope(200, describeHistory(target, history), startedAt);
		});

		router.get("/vault/accounts/{player}", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối truy vấn số dư do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			long startedAt = System.nanoTime();
			String reference = request.pathParam("player", "").trim();

			if (!vaultService.databaseEnabled()) {
				return LunaJson.error(503, "vault database is not available");
			}

			VelocityVaultService.AccountTarget target = vaultService.resolveReference(reference).orElse(null);

			if (target == null) {
				return LunaJson.error(404, "player not found: " + reference);
			}

			return LunaJson.envelope(200, describeAccount(target), startedAt);
		});
	}

	// ----------------------------------------------------------------- payloads

	private Map<String, Object> describeAccount(VelocityVaultService.AccountTarget target) {
		VaultPlayerSnapshot snapshot = vaultService.findSnapshot(target.playerId()).orElse(null);
		VaultTransactionSummary summary = vaultService.summary(target.playerId());
		long balanceMinor = snapshot == null ? 0L : snapshot.balanceMinor();
		Map<String, Object> payload = new LinkedHashMap<>();

		payload.put("uuid", target.playerId().toString());
		payload.put("username", snapshot == null || snapshot.playerName().isBlank() ? target.playerName() : snapshot.playerName());
		payload.put("online", proxyServer.getPlayer(target.playerId()).isPresent());
		payload.put("hasAccount", snapshot != null);
		payload.put("balanceMinor", balanceMinor);
		payload.put("balance", VaultMoney.toMajorDouble(balanceMinor));
		payload.put("balanceFormatted", format(balanceMinor));
		payload.put("rank", snapshot == null ? 0 : snapshot.rank());
		payload.put("accountCount", vaultService.accountCount());
		payload.put("currency", describeCurrency());

		Map<String, Object> summaryPayload = new LinkedHashMap<>();

		summaryPayload.put("transactionCount", summary.transactionCount());
		summaryPayload.put("receivedMinor", summary.receivedMinor());
		summaryPayload.put("receivedFormatted", format(summary.receivedMinor()));
		summaryPayload.put("sentMinor", summary.sentMinor());
		summaryPayload.put("sentFormatted", format(summary.sentMinor()));
		summaryPayload.put("netMinor", summary.netMinor());
		summaryPayload.put("netFormatted", format(summary.netMinor()));
		summaryPayload.put("firstAtEpochMillis", summary.firstAtEpochMillis());
		summaryPayload.put("lastAtEpochMillis", summary.lastAtEpochMillis());
		payload.put("summary", summaryPayload);

		return payload;
	}

	private Map<String, Object> describeHistory(VelocityVaultService.AccountTarget target, VaultTransactionPage history) {
		Map<String, Object> payload = new LinkedHashMap<>();

		payload.put("uuid", target.playerId().toString());
		payload.put("username", target.playerName());
		payload.put("page", history.page());
		payload.put("pageSize", history.pageSize());
		payload.put("maxPage", history.maxPage());
		payload.put("totalCount", history.totalCount());
		payload.put("currency", describeCurrency());

		List<Object> entries = new ArrayList<>();

		for (VaultTransactionRecord record : history.entries()) {
			entries.add(describeTransaction(target.playerId(), record));
		}

		payload.put("entries", entries);

		return payload;
	}

	/**
	 * One transaction from the player's point of view: which way the money went
	 * and who was on the other side. A blank counterparty is the server itself —
	 * an admin grant, a shop purchase, a reward — and is reported as such rather
	 * than as an empty name.
	 *
	 * A row can name the same player on both sides: {@code /eco add|take} records
	 * the actor opposite the target, so an admin adjusting their own balance lands
	 * on both. Those carry no recoverable direction — the two commands write the
	 * same row — so they are reported as {@code self} rather than guessed at.
	 */
	private Map<String, Object> describeTransaction(UUID playerId, VaultTransactionRecord record) {
		boolean incoming = playerId.equals(record.receiverId());
		boolean outgoing = playerId.equals(record.senderId());
		UUID counterpartyId = incoming ? record.senderId() : record.receiverId();
		String counterpartyName = incoming ? record.senderName() : record.receiverName();
		Map<String, Object> payload = new LinkedHashMap<>();

		payload.put("id", record.transactionId() == null ? "" : record.transactionId());
		payload.put("direction", incoming && outgoing ? "self" : incoming ? "in" : "out");
		payload.put("counterpartyUuid", counterpartyId == null ? "" : counterpartyId.toString());
		payload.put("counterpartyName", counterpartyName == null ? "" : counterpartyName);
		payload.put("system", counterpartyId == null && (counterpartyName == null || counterpartyName.isBlank()));
		payload.put("amountMinor", record.amountMinor());
		payload.put("amountFormatted", format(record.amountMinor()));
		payload.put("source", record.source() == null ? "" : record.source());
		payload.put("details", record.details() == null ? "" : record.details());
		payload.put("atEpochMillis", record.completedAt());

		return payload;
	}

	private Map<String, Object> describeCurrency() {
		VelocityMoneyFormat money = LunaCoreVelocity.services().moneyFormat();
		Map<String, Object> payload = new LinkedHashMap<>();

		payload.put("symbol", plain(money.currencySymbol()));
		payload.put("grouping", money.grouping());
		payload.put("scale", VaultMoney.SCALE);

		return payload;
	}

	// ------------------------------------------------------------------ shared

	/**
	 * The amount as the server prints it in chat, minus the MiniMessage tags the
	 * money template carries — the console renders its own colours.
	 */
	private String format(long minor) {
		return plain(LunaCoreVelocity.services().moneyFormat().formatMinor(minor, VaultMoney.SCALE)).trim();
	}

	private String plain(String input) {
		return input == null ? "" : input.replaceAll("<[^>]+>", "");
	}

	private int intParam(String raw, int fallback) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}

		try {
			return Integer.parseInt(raw.trim());
		} catch (NumberFormatException notANumber) {
			return fallback;
		}
	}
}
