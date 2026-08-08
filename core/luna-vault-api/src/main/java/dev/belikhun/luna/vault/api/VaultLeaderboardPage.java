package dev.belikhun.luna.vault.api;

import java.util.List;

public record VaultLeaderboardPage(
	List<VaultLeaderboardEntry> entries,
	int page,
	int pageSize,
	int maxPage,
	int totalCount
) {
	public static VaultLeaderboardPage empty(int page, int pageSize) {
		int normalizedPage = Math.max(0, page);
		int normalizedPageSize = Math.max(1, pageSize);
		return new VaultLeaderboardPage(List.of(), normalizedPage, normalizedPageSize, 0, 0);
	}
}
