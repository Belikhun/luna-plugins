package dev.belikhun.luna.legacy.shop;

public final class ShopTransactionEntry {
	private final String transactionId;
	private final String playerUuid;
	private final String playerName;
	private final String action;
	private final String itemId;
	private final String category;
	private final int amount;
	private final double unitPrice;
	private final double totalPrice;
	private final boolean success;
	private final String reason;
	private final long createdAt;

	public ShopTransactionEntry(String transactionId, String playerUuid, String playerName, String action, String itemId, String category, int amount, double unitPrice, double totalPrice, boolean success, String reason, long createdAt) {
		this.transactionId = transactionId;
		this.playerUuid = playerUuid;
		this.playerName = playerName;
		this.action = action;
		this.itemId = itemId;
		this.category = category;
		this.amount = amount;
		this.unitPrice = unitPrice;
		this.totalPrice = totalPrice;
		this.success = success;
		this.reason = reason;
		this.createdAt = createdAt;
	}

	public String transactionId() {
		return transactionId;
	}

	public String playerUuid() {
		return playerUuid;
	}

	public String playerName() {
		return playerName;
	}

	public String action() {
		return action;
	}

	public String itemId() {
		return itemId;
	}

	public String category() {
		return category;
	}

	public int amount() {
		return amount;
	}

	public double unitPrice() {
		return unitPrice;
	}

	public double totalPrice() {
		return totalPrice;
	}

	public boolean success() {
		return success;
	}

	public String reason() {
		return reason;
	}

	public long createdAt() {
		return createdAt;
	}

}

