package dev.belikhun.luna.vault.service;

import dev.belikhun.luna.core.api.config.LunaYamlConfig;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.profile.UserProfileRepository;
import dev.belikhun.luna.vault.api.VaultMoney;
import dev.belikhun.luna.vault.api.model.VaultAccountModel;
import dev.belikhun.luna.vault.api.model.VaultAccountRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class LegacyBalanceImportService {
	private final LunaLogger logger;
	private final VaultAccountRepository accountRepository;
	private final UserProfileRepository userProfileRepository;

	public LegacyBalanceImportService(LunaLogger logger, VaultAccountRepository accountRepository, UserProfileRepository userProfileRepository) {
		this.logger = logger.scope("LegacyImport");
		this.accountRepository = accountRepository;
		this.userProfileRepository = userProfileRepository;
	}

	public synchronized ImportSummary importBalances(Path sourcePath, ImportOptions options) {
		Path normalizedPath = sourcePath == null ? null : sourcePath.normalize();
		if (normalizedPath == null || !Files.exists(normalizedPath) || !Files.isRegularFile(normalizedPath)) {
			throw new IllegalArgumentException("Không tìm thấy file balances.yml tại đường dẫn đã chỉ định.");
		}

		Map<String, Object> balances = LunaYamlConfig.loadMap(normalizedPath);
		ImportSummary summary = new ImportSummary(normalizedPath, options);
		if (balances.isEmpty()) {
			return summary.finish();
		}

		long now = Instant.now().toEpochMilli();
		for (Map.Entry<String, Object> entry : balances.entrySet()) {
			summary.totalEntries++;
			UUID playerId = parseUuid(entry.getKey());
			if (playerId == null) {
				summary.invalidUuid++;
				continue;
			}

			Long balanceMinor = parseMinor(entry.getValue());
			if (balanceMinor == null) {
				summary.invalidAmount++;
				continue;
			}

			if (balanceMinor == 0L && !options.includeZeroBalances()) {
				summary.zeroSkipped++;
				continue;
			}

			Optional<VaultAccountModel> existingOptional = accountRepository.find(playerId);
			if (existingOptional.isPresent() && !options.overwriteExisting()) {
				summary.existingSkipped++;
				continue;
			}

			summary.candidateEntries++;
			summary.candidateMinor += balanceMinor;
			if (!options.applyChanges()) {
				continue;
			}

			String resolvedImportedName = resolveProfileName(playerId);
			String fallbackImportedName = resolvedImportedName.isBlank() ? VaultAccountRepository.temporaryPlayerName() : resolvedImportedName;
			VaultAccountModel account = existingOptional.orElseGet(() -> accountRepository.findOrCreate(playerId, fallbackImportedName));
			long previousBalance = account.getLong("balance_minor", 0L);
			String currentName = account.getString("player_name", "");
			String importedName = selectImportedName(currentName, resolvedImportedName, fallbackImportedName);
			long createdAt = account.getLong("created_at", now);
			account
				.set("player_name", importedName)
				.set("balance_minor", balanceMinor)
				.set("created_at", createdAt <= 0L ? now : createdAt)
				.set("updated_at", now)
				.save();

			if (existingOptional.isPresent()) {
				summary.updatedEntries++;
			} else {
				summary.insertedEntries++;
			}
			summary.appliedMinor += balanceMinor;
			summary.deltaMinor += balanceMinor - previousBalance;
		}

		ImportSummary finished = summary.finish();
		if (options.applyChanges()) {
			logger.audit("Đã import BetterEconomy balances từ " + normalizedPath + " | inserted=" + finished.insertedEntries() + " updated=" + finished.updatedEntries() + " skippedExisting=" + finished.existingSkipped() + " invalidUuid=" + finished.invalidUuid() + " invalidAmount=" + finished.invalidAmount() + " zeroSkipped=" + finished.zeroSkipped());
		}
		return finished;
	}

	private String selectImportedName(String currentName, String resolvedImportedName, String fallbackImportedName) {
		String normalizedResolvedName = VaultAccountRepository.normalizePlayerName(resolvedImportedName);
		if (!normalizedResolvedName.isBlank()) {
			return normalizedResolvedName;
		}

		String normalizedCurrentName = VaultAccountRepository.normalizePlayerName(currentName);
		if (!normalizedCurrentName.isBlank()) {
			return normalizedCurrentName;
		}

		return VaultAccountRepository.normalizePlayerName(fallbackImportedName);
	}

	private String resolveProfileName(UUID playerId) {
		if (playerId == null) {
			return "";
		}

		return userProfileRepository.findByUuid(playerId)
			.map(profile -> VaultAccountRepository.normalizePlayerName(profile.name()))
			.orElse("");
	}

	private UUID parseUuid(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}

		try {
			return UUID.fromString(raw.trim());
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private Long parseMinor(Object raw) {
		if (raw == null) {
			return null;
		}

		try {
			BigDecimal value = new BigDecimal(String.valueOf(raw).trim()).setScale(VaultMoney.SCALE, RoundingMode.HALF_UP);
			return VaultMoney.toMinor(value);
		} catch (RuntimeException exception) {
			return null;
		}
	}

	public record ImportOptions(boolean applyChanges, boolean overwriteExisting, boolean includeZeroBalances) {
	}

	public static final class ImportSummary {
		private final Path sourcePath;
		private final ImportOptions options;
		private int totalEntries;
		private int candidateEntries;
		private int insertedEntries;
		private int updatedEntries;
		private int existingSkipped;
		private int zeroSkipped;
		private int invalidUuid;
		private int invalidAmount;
		private long candidateMinor;
		private long appliedMinor;
		private long deltaMinor;

		private ImportSummary(Path sourcePath, ImportOptions options) {
			this.sourcePath = sourcePath;
			this.options = options;
		}

		private ImportSummary finish() {
			return this;
		}

		public Path sourcePath() {
			return sourcePath;
		}

		public ImportOptions options() {
			return options;
		}

		public int totalEntries() {
			return totalEntries;
		}

		public int candidateEntries() {
			return candidateEntries;
		}

		public int insertedEntries() {
			return insertedEntries;
		}

		public int updatedEntries() {
			return updatedEntries;
		}

		public int existingSkipped() {
			return existingSkipped;
		}

		public int zeroSkipped() {
			return zeroSkipped;
		}

		public int invalidUuid() {
			return invalidUuid;
		}

		public int invalidAmount() {
			return invalidAmount;
		}

		public long candidateMinor() {
			return candidateMinor;
		}

		public long appliedMinor() {
			return appliedMinor;
		}

		public long deltaMinor() {
			return deltaMinor;
		}
	}
}
