package dev.belikhun.luna.legacy.shop;

import java.util.UUID;

public final class ShopTransactionPlayer {
	private final UUID uuid;
	private final String name;

	public ShopTransactionPlayer(UUID uuid, String name) {
		this.uuid = uuid;
		this.name = name;
	}

	public UUID uuid() {
		return uuid;
	}

	public String name() {
		return name;
	}

}

