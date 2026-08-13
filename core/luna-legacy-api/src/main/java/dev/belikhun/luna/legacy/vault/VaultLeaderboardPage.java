package dev.belikhun.luna.legacy.vault;

import java.util.Collections;

import java.util.List;

public final class VaultLeaderboardPage {
	private final List<VaultLeaderboardEntry> entries;
	private final int page;
	private final int pageSize;
	private final int maxPage;
	private final int totalCount;

	public VaultLeaderboardPage(List<VaultLeaderboardEntry> entries, int page, int pageSize, int maxPage, int totalCount) {
		this.entries = entries;
		this.page = page;
		this.pageSize = pageSize;
		this.maxPage = maxPage;
		this.totalCount = totalCount;
	}

	public List<VaultLeaderboardEntry> entries() {
		return entries;
	}

	public int page() {
		return page;
	}

	public int pageSize() {
		return pageSize;
	}

	public int maxPage() {
		return maxPage;
	}

	public int totalCount() {
		return totalCount;
	}

	public static VaultLeaderboardPage empty(int page, int pageSize) {
		int normalizedPage = Math.max(0, page);
		int normalizedPageSize = Math.max(1, pageSize);
		return new VaultLeaderboardPage(Collections.emptyList(), normalizedPage, normalizedPageSize, 0, 0);
	}
}
