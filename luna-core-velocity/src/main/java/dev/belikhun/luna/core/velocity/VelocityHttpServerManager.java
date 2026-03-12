package dev.belikhun.luna.core.velocity;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.belikhun.luna.core.api.config.ConfigValues;
import dev.belikhun.luna.core.api.config.LunaYamlConfig;
import dev.belikhun.luna.core.api.http.HttpRequest;
import dev.belikhun.luna.core.api.http.HttpResponse;
import dev.belikhun.luna.core.api.http.Router;
import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class VelocityHttpServerManager {
	private final LunaLogger logger;
	private final Router router;
	private HttpServer server;
	private String pathPrefix;

	public VelocityHttpServerManager(LunaLogger logger) {
		this.logger = logger.scope("Http");
		this.router = new Router();
		registerDefaultRoutes();
	}

	public Router router() {
		return router;
	}

	public void startIfEnabled(Path configPath) {
		Map<String, Object> root = LunaYamlConfig.loadMap(configPath);
		Map<String, Object> http = ConfigValues.map(root, "http");

		boolean enabled = ConfigValues.booleanValue(http, "enabled", false);
		if (!enabled) {
			logger.debug("HTTP server đang tắt trong cấu hình.");
			return;
		}

		String host = ConfigValues.string(http, "host", "0.0.0.0");
		int port = ConfigValues.intValue(http, "port", 32452);
		pathPrefix = normalizePathPrefix(ConfigValues.string(http, "pathPrefix", "/api"));

		try {
			server = HttpServer.create(new InetSocketAddress(host, port), 0);
			server.createContext(pathPrefix, this::handle);
			server.setExecutor(null);
			server.start();
			logger.success("HTTP server đã chạy tại " + host + ":" + port + pathPrefix);
		} catch (IOException exception) {
			logger.error("Không thể khởi động HTTP server.", exception);
		}
	}

	public void stop() {
		if (server == null) {
			return;
		}

		logger.audit("Đang dừng HTTP server.");
		server.stop(1);
		server = null;
		logger.success("HTTP server đã dừng.");
	}

	private void handle(HttpExchange exchange) throws IOException {
		HttpRequest request = fromExchange(exchange);
		String requestPath = request.uri().getPath();
		String routePath = stripPrefix(requestPath, pathPrefix);
		Router.Match routeMatch = router.match(request.method(), routePath);

		HttpResponse response;
		if (routeMatch == null) {
			logger.debug("Không tìm thấy route cho " + request.method() + " " + requestPath);
			response = HttpResponse.json(404, "{\"error\":\"Not Found\"}");
		} else {
			try {
				HttpRequest routed = request.withPathParams(routeMatch.params());
				response = routeMatch.handler().handle(routed);
			} catch (Exception exception) {
				logger.error("Lỗi khi xử lý route HTTP: " + exception.getMessage(), exception);
				response = HttpResponse.json(500, "{\"error\":\"Internal Error\"}");
			}
		}

		for (Map.Entry<String, String> header : response.headers().entrySet()) {
			exchange.getResponseHeaders().set(header.getKey(), header.getValue());
		}
		byte[] payload = response.body();
		exchange.sendResponseHeaders(response.status(), payload.length);
		try (OutputStream outputStream = exchange.getResponseBody()) {
			outputStream.write(payload);
		}
	}

	private HttpRequest fromExchange(HttpExchange exchange) throws IOException {
		URI uri = exchange.getRequestURI();
		Map<String, List<String>> headers = new LinkedHashMap<>();
		exchange.getRequestHeaders().forEach((key, value) -> headers.put(key, new ArrayList<>(value)));
		InetSocketAddress remote = exchange.getRemoteAddress();
		if (remote != null) {
			headers.computeIfAbsent("X-Luna-Remote-Ip", key -> new ArrayList<>()).add(remote.getAddress() != null ? remote.getAddress().getHostAddress() : remote.getHostString());
			headers.computeIfAbsent("X-Luna-Remote-Port", key -> new ArrayList<>()).add(String.valueOf(remote.getPort()));
		}
		byte[] body = exchange.getRequestBody().readAllBytes();
		return new HttpRequest(
			exchange.getRequestMethod(),
			uri,
			headers,
			body,
			parseQuery(uri.getRawQuery()),
			Map.of()
		);
	}

	private Map<String, String> parseQuery(String query) {
		Map<String, String> data = new LinkedHashMap<>();
		if (query == null || query.isBlank()) {
			return data;
		}

		String[] pairs = query.split("&");
		for (String pair : pairs) {
			String[] entry = pair.split("=", 2);
			String key = decode(entry[0]);
			String value = entry.length > 1 ? decode(entry[1]) : "";
			data.put(key, value);
		}
		return data;
	}

	private String decode(String value) {
		return URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

	private void registerDefaultRoutes() {
		router.get("/health", request -> HttpResponse.json(200, "{\"status\":\"ok\"}"));
	}

	private String normalizePathPrefix(String raw) {
		String value = raw == null || raw.isBlank() ? "/api" : raw.trim();
		if (!value.startsWith("/")) {
			value = "/" + value;
		}
		while (value.length() > 1 && value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		return value;
	}

	private String stripPrefix(String path, String prefix) {
		String normalizedPath = path == null || path.isBlank() ? "/" : path;
		if (!normalizedPath.startsWith(prefix)) {
			return normalizedPath;
		}

		String remaining = normalizedPath.substring(prefix.length());
		if (remaining.isBlank()) {
			return "/";
		}
		return remaining;
	}

}
