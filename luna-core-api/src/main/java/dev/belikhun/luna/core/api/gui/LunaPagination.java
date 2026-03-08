package dev.belikhun.luna.core.api.gui;

public final class LunaPagination {
	private LunaPagination() {
	}

	public static int maxPage(int itemCount, int pageSize) {
		if (itemCount <= 0) {
			return 0;
		}

		int normalizedPageSize = Math.max(1, pageSize);
		return (itemCount - 1) / normalizedPageSize;
	}

	public static int clampPage(int page, int maxPage) {
		if (page < 0) {
			return 0;
		}

		return Math.min(page, maxPage);
	}
}

