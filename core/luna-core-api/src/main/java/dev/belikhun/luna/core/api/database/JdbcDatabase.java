package dev.belikhun.luna.core.api.database;

import dev.belikhun.luna.core.api.exception.DatabaseConfigurationException;
import dev.belikhun.luna.core.api.exception.DatabaseException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class JdbcDatabase implements Database {
	private final DatabaseConfig config;
	private volatile boolean closed;

	public JdbcDatabase(DatabaseConfig config) {
		this.config = config;
		this.closed = false;
		loadDriver(config.type());
	}

	@Override
	public Connection connection() {
		if (closed) {
			throw new DatabaseException("Database connection is already closed.");
		}

		try {
			return DriverManager.getConnection(config.jdbcUrl(), config.username(), config.password());
		} catch (SQLException exception) {
			throw new DatabaseException("Cannot open database connection.", exception);
		}
	}

	@Override
	public int update(String sql, List<Object> bindings) {
		try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
			bind(statement, bindings);
			return statement.executeUpdate();
		} catch (SQLException exception) {
			throw new DatabaseException("Cannot execute update query.", exception);
		}
	}

	@Override
	public List<Map<String, Object>> query(String sql, List<Object> bindings) {
		try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
			bind(statement, bindings);
			try (ResultSet resultSet = statement.executeQuery()) {
				return readRows(resultSet);
			}
		} catch (SQLException exception) {
			throw new DatabaseException("Cannot execute select query.", exception);
		}
	}

	@Override
	public void close() {
		closed = true;
	}

	private void loadDriver(DatabaseType type) {
		try {
			Class.forName(type.driverClass());
		} catch (ClassNotFoundException exception) {
			throw new DatabaseConfigurationException("Missing JDBC driver for: " + type.name(), exception);
		}
	}

	private void bind(PreparedStatement statement, List<Object> bindings) throws SQLException {
		for (int index = 0; index < bindings.size(); index++) {
			statement.setObject(index + 1, bindings.get(index));
		}
	}

	private List<Map<String, Object>> readRows(ResultSet resultSet) throws SQLException {
		ResultSetMetaData metaData = resultSet.getMetaData();
		int columnCount = metaData.getColumnCount();
		List<Map<String, Object>> rows = new ArrayList<>();
		while (resultSet.next()) {
			Map<String, Object> row = new HashMap<>();
			for (int columnIndex = 1; columnIndex <= columnCount; columnIndex++) {
				String column = metaData.getColumnLabel(columnIndex);
				row.put(column, resultSet.getObject(columnIndex));
			}
			rows.add(row);
		}

		return rows;
	}
}

