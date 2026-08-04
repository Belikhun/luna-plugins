package dev.belikhun.luna.core.api.database;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A database handle that keeps its identity when the database behind it changes.
 *
 * LunaCore hands the shared {@link Database} to every plugin that needs one, and
 * each of those resolves it once, at its own startup, and holds the reference —
 * an auth repository keeps it in a final field. A config reload used to build a
 * fresh {@link JdbcDatabase} and close the old one, which left every one of those
 * plugins holding a closed handle and throwing on every query until the proxy was
 * restarted. Nothing told them to look the singleton up again, and nothing could:
 * they had already been constructed.
 *
 * So the object they hold is this one, and it never changes. A reload swaps what
 * is underneath it and closes the handle that was there before. That is cheap
 * because {@link JdbcDatabase} opens a connection per call rather than holding
 * one, so a swap costs nothing in flight.
 */
public final class SwappableDatabase implements Database {
	private volatile Database target;

	public SwappableDatabase(Database target) {
		this.target = Objects.requireNonNull(target, "target");
	}

	/**
	 * Point at a new database and close the one being replaced.
	 *
	 * @param next the database to serve from now on
	 */
	public void swap(Database next) {
		Database replaced = this.target;
		this.target = Objects.requireNonNull(next, "next");

		if (replaced != null && replaced != next) {
			replaced.close();
		}
	}

	/** The database currently underneath, for callers that must know. */
	public Database target() {
		return target;
	}

	@Override
	public Connection connection() {
		return target.connection();
	}

	@Override
	public int update(String sql, List<Object> bindings) {
		return target.update(sql, bindings);
	}

	@Override
	public List<Map<String, Object>> query(String sql, List<Object> bindings) {
		return target.query(sql, bindings);
	}

	@Override
	public void close() {
		target.close();
	}
}
