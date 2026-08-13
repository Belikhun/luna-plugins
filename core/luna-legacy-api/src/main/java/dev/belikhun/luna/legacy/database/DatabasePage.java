package dev.belikhun.luna.legacy.database;

public final class DatabasePage {
	private final int page;
	private final int pageSize;
	private final int maxPage;
	private final int offset;

	public DatabasePage(int page, int pageSize, int maxPage, int offset) {
		this.page = page;
		this.pageSize = pageSize;
		this.maxPage = maxPage;
		this.offset = offset;
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

	public int offset() {
		return offset;
	}

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
