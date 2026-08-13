package dev.belikhun.luna.legacy.vault;

import java.util.Collections;

import java.util.List;

public final class VaultTransactionPage {
	private final List<VaultTransactionRecord> entries;
	private final int page;
	private final int pageSize;
	private final int maxPage;
	private final int totalCount;

	public VaultTransactionPage(List<VaultTransactionRecord> entries, int page, int pageSize, int maxPage, int totalCount) {
		this.entries = entries;
		this.page = page;
		this.pageSize = pageSize;
		this.maxPage = maxPage;
		this.totalCount = totalCount;
	}

	public List<VaultTransactionRecord> entries() {
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

	public static VaultTransactionPage empty(int page, int pageSize) {
		return new VaultTransactionPage(Collections.emptyList(), Math.max(0, page), Math.max(1, pageSize), 0, 0);
	}
}
