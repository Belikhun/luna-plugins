package dev.belikhun.luna.vault.api;

import java.util.List;

public record VaultTransactionPage(
	List<VaultTransactionRecord> entries,
	int page,
	int pageSize,
	int maxPage,
	int totalCount
) {
	public static VaultTransactionPage empty(int page, int pageSize) {
		return new VaultTransactionPage(List.of(), Math.max(0, page), Math.max(1, pageSize), 0, 0);
	}
}
