package dev.belikhun.luna.core.api.query;

import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.exception.QueryException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

public final class QueryBuilder {
	private final Database database;
	private final String table;
	private final List<String> selectColumns;
	private final List<String> wheres;
	private final List<Object> bindings;
	private final List<String> orderBys;
	private Integer limit;
	private Integer offset;

	public QueryBuilder(Database database, String table) {
		this.database = database;
		this.table = table;
		this.selectColumns = new ArrayList<>();
		this.wheres = new ArrayList<>();
		this.bindings = new ArrayList<>();
		this.orderBys = new ArrayList<>();
	}

	public QueryBuilder select(String... columns) {
		for (String column : columns) {
			selectColumns.add(column);
		}
		return this;
	}

	public QueryBuilder where(String column, Object value) {
		wheres.add(column + " = ?");
		bindings.add(value);
		return this;
	}

	public QueryBuilder whereRaw(String expression, Object... values) {
		wheres.add(expression);
		for (Object value : values) {
			bindings.add(value);
		}
		return this;
	}

	public QueryBuilder orderBy(String column, boolean ascending) {
		orderBys.add(column + (ascending ? " ASC" : " DESC"));
		return this;
	}

	public QueryBuilder limit(int value) {
		if (value <= 0) {
			throw new QueryException("LIMIT phải lớn hơn 0.");
		}
		this.limit = value;
		return this;
	}

	public QueryBuilder offset(int value) {
		if (value < 0) {
			throw new QueryException("OFFSET không được âm.");
		}
		this.offset = value;
		return this;
	}

	public List<Map<String, Object>> get() {
		return database.query(toSelectSql(), new ArrayList<>(bindings));
	}

	public Optional<Map<String, Object>> first() {
		if (limit == null) {
			limit(1);
		}

		return database.first(toSelectSql(), new ArrayList<>(bindings));
	}

	public int insert(Map<String, Object> data) {
		if (data == null || data.isEmpty()) {
			throw new QueryException("Dữ liệu INSERT không được rỗng.");
		}

		List<String> columns = new ArrayList<>(data.keySet());
		StringJoiner columnJoiner = new StringJoiner(", ");
		StringJoiner valueJoiner = new StringJoiner(", ");
		List<Object> insertBindings = new ArrayList<>();
		for (String column : columns) {
			columnJoiner.add(column);
			valueJoiner.add("?");
			insertBindings.add(data.get(column));
		}

		String sql = "INSERT INTO " + table + " (" + columnJoiner + ") VALUES (" + valueJoiner + ")";
		return database.update(sql, insertBindings);
	}

	public int update(Map<String, Object> data) {
		if (data == null || data.isEmpty()) {
			throw new QueryException("Dữ liệu UPDATE không được rỗng.");
		}

		StringJoiner setJoiner = new StringJoiner(", ");
		List<Object> updateBindings = new ArrayList<>();
		for (Map.Entry<String, Object> entry : data.entrySet()) {
			setJoiner.add(entry.getKey() + " = ?");
			updateBindings.add(entry.getValue());
		}

		String sql = "UPDATE " + table + " SET " + setJoiner + whereClause();
		updateBindings.addAll(bindings);
		return database.update(sql, updateBindings);
	}

	public int delete() {
		String sql = "DELETE FROM " + table + whereClause();
		return database.update(sql, new ArrayList<>(bindings));
	}

	public String toSelectSql() {
		String columns = selectColumns.isEmpty() ? "*" : String.join(", ", selectColumns);
		StringBuilder sql = new StringBuilder("SELECT " + columns + " FROM " + table);
		sql.append(whereClause());
		if (!orderBys.isEmpty()) {
			sql.append(" ORDER BY ").append(String.join(", ", orderBys));
		}
		if (limit != null) {
			sql.append(" LIMIT ").append(limit);
		}
		if (offset != null) {
			sql.append(" OFFSET ").append(offset);
		}
		return sql.toString();
	}

	private String whereClause() {
		if (wheres.isEmpty()) {
			return "";
		}

		return " WHERE " + String.join(" AND ", wheres);
	}

	public static Map<String, Object> mapOf(String key, Object value) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put(key, value);
		return map;
	}
}

