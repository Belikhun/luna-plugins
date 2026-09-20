package dev.belikhun.luna.vault.api.ledger;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

/**
 * The few places where MariaDB and SQLite disagree.
 *
 * The ledger is written against the common subset; this names the two
 * exceptions it cannot avoid. Row locking exists only on the server databases
 * (SQLite serialises writers by file lock instead), and each engine reports a
 * busy or deadlocked transaction with its own codes.
 */
enum LedgerDialect {
	SQLITE(false),
	SERVER(true);

	private final boolean rowLocking;

	LedgerDialect(boolean rowLocking) {
		this.rowLocking = rowLocking;
	}

	/** Whether {@code SELECT ... FOR UPDATE} is understood. */
	boolean rowLocking() {
		return rowLocking;
	}

	static LedgerDialect detect(Connection connection) {
		try {
			DatabaseMetaData metaData = connection.getMetaData();
			String product = metaData == null ? "" : String.valueOf(metaData.getDatabaseProductName());

			if (product.toLowerCase(Locale.ROOT).contains("sqlite")) {
				return SQLITE;
			}
		} catch (SQLException ignored) {
			// unknown drivers get the conservative answer below
		}

		return SERVER;
	}

	/**
	 * Whether the failure is contention rather than a broken statement.
	 *
	 * MariaDB reports a deadlock as 40001/1213 and a lock wait as 1205; SQLite
	 * has no SQLState for it and says "database is locked" or "busy".
	 */
	static boolean transient_(SQLException exception) {
		String state = exception.getSQLState() == null ? "" : exception.getSQLState();
		int code = exception.getErrorCode();
		String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);

		if (state.startsWith("40") || code == 1213 || code == 1205) {
			return true;
		}

		if (message.contains("database is locked") || message.contains("sqlite_busy") || message.contains("sqlite_locked")) {
			return true;
		}

		return state.equals("08S01") || message.contains("connection reset");
	}

	/** Whether the failure is a unique-key collision. */
	static boolean duplicateKey(SQLException exception) {
		String state = exception.getSQLState() == null ? "" : exception.getSQLState();
		int code = exception.getErrorCode();
		String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);

		if (state.startsWith("23") || code == 1062 || code == 19) {
			return true;
		}

		return message.contains("unique constraint") || message.contains("duplicate");
	}
}
