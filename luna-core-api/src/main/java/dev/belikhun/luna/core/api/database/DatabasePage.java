package dev.belikhun.luna.core.api.database;

public record DatabasePage(int page, int pageSize, int maxPage, int offset) {
	public static DatabasePage of(long totalCount, int requestedPage, int requestedPageSize) {
		int safePageSize = Math.max(1, requestedPageSize);
		int safeRequestedPage = Math.max(0, requestedPage);
		int computedMaxPage = totalCount <= 0L
			? 0
			: (int) Math.min((totalCount - 1L) / safePageSize, Integer.MAX_VALUE);
		int safePage = Math.min(safeRequestedPage, computedMaxPage);
		long computedOffset = (long) safePage * safePageSize;
		int safeOffset = computedOffset > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) computedOffset;
		return new DatabasePage(safePage, safePageSize, computedMaxPage, safeOffset);
	}
}
