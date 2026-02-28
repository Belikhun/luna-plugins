package dev.belikhun.luna.shop.service;

public record ShopResult(boolean success, String message) {
	public static ShopResult ok(String message) {
		return new ShopResult(true, message);
	}

	public static ShopResult fail(String message) {
		return new ShopResult(false, message);
	}
}