package dev.belikhun.luna.migrator.service;

import dev.belikhun.luna.core.api.auth.OfflineUuid;

import java.util.Optional;
import java.util.UUID;

public final class MigrationEligibilityService {
	private final MigrationStateRepository stateRepository;

	public MigrationEligibilityService(MigrationStateRepository stateRepository) {
		this.stateRepository = stateRepository;
	}

	public MigrationEligibility evaluate(UUID onlineUuid, String legacyUsername) {
		UUID legacyOfflineUuid = OfflineUuid.fromUsername(legacyUsername);
		if (legacyOfflineUuid.equals(onlineUuid)) {
			return new MigrationEligibility(MigrationEligibilityStatus.SAME_UUID, Optional.empty());
		}

		if (stateRepository.isMigrated(onlineUuid, legacyUsername)) {
			return new MigrationEligibility(MigrationEligibilityStatus.ALREADY_MIGRATED, Optional.empty());
		}

		if (!stateRepository.hasEligibleSourceData(legacyUsername)) {
			return new MigrationEligibility(MigrationEligibilityStatus.NO_SOURCE_DATA, Optional.empty());
		}

		Optional<UUID> claimedBy = stateRepository.findOnlineUuidByOldUsername(legacyUsername);
		if (claimedBy.isPresent() && !claimedBy.get().equals(onlineUuid)) {
			return new MigrationEligibility(MigrationEligibilityStatus.CLAIMED_BY_OTHER, claimedBy);
		}

		return new MigrationEligibility(MigrationEligibilityStatus.ELIGIBLE, Optional.empty());
	}

	public enum MigrationEligibilityStatus {
		ELIGIBLE,
		SAME_UUID,
		ALREADY_MIGRATED,
		NO_SOURCE_DATA,
		CLAIMED_BY_OTHER
	}

	public record MigrationEligibility(
		MigrationEligibilityStatus status,
		Optional<UUID> claimedBy
	) {
		public boolean eligible() {
			return status == MigrationEligibilityStatus.ELIGIBLE;
		}
	}
}
