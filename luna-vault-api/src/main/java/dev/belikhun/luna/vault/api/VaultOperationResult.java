package dev.belikhun.luna.vault.api;

public record VaultOperationResult(
	boolean success,
	VaultFailureReason failureReason,
	String message,
	long balanceMinor,
	VaultTransactionRecord transaction
) {
	public static VaultOperationResult success(String message, long balanceMinor, VaultTransactionRecord transaction) {
		return new VaultOperationResult(true, VaultFailureReason.NONE, message, balanceMinor, transaction);
	}

	public static VaultOperationResult failed(VaultFailureReason reason, String message, long balanceMinor) {
		return new VaultOperationResult(false, reason == null ? VaultFailureReason.INTERNAL_ERROR : reason, message, balanceMinor, null);
	}
}
