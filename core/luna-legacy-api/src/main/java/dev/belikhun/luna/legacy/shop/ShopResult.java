package dev.belikhun.luna.legacy.shop;

public final class ShopResult {
	private final boolean success;
	private final String message;

	public ShopResult(boolean success, String message) {
		this.success = success;
		this.message = message;
	}

	public boolean success() {
		return success;
	}

	public String message() {
		return message;
	}

	public static ShopResult ok(String message) {
		return new ShopResult(true, message);
	}

	public static ShopResult fail(String message) {
		return new ShopResult(false, message);
	}
}
