package dev.belikhun.luna.shop.service;

public record ShopTransactionEntry(
	String transactionId,
	String playerUuid,
	String playerName,
	String action,
	String itemId,
	String category,
	int amount,
	double unitPrice,
	double totalPrice,
	boolean success,
	String reason,
	long createdAt
) {
}

