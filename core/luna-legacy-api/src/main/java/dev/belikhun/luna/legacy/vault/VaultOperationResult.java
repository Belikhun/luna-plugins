package dev.belikhun.luna.legacy.vault;

public final class VaultOperationResult {
	private final boolean success;
	private final VaultFailureReason failureReason;
	private final String message;
	private final long balanceMinor;
	private final VaultTransactionRecord transaction;

	public VaultOperationResult(boolean success, VaultFailureReason failureReason, String message, long balanceMinor, VaultTransactionRecord transaction) {
		this.success = success;
		this.failureReason = failureReason;
		this.message = message;
		this.balanceMinor = balanceMinor;
		this.transaction = transaction;
	}

	public boolean success() {
		return success;
	}

	public VaultFailureReason failureReason() {
		return failureReason;
	}

	public String message() {
		return message;
	}

	public long balanceMinor() {
		return balanceMinor;
	}

	public VaultTransactionRecord transaction() {
		return transaction;
	}

	public static VaultOperationResult success(String message, long balanceMinor, VaultTransactionRecord transaction) {
		return new VaultOperationResult(true, VaultFailureReason.NONE, message, balanceMinor, transaction);
	}

	public static VaultOperationResult failed(VaultFailureReason reason, String message, long balanceMinor) {
		return new VaultOperationResult(false, reason == null ? VaultFailureReason.INTERNAL_ERROR : reason, message, balanceMinor, null);
	}
}
