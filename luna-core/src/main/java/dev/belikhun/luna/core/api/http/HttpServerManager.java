package dev.belikhun.luna.core.api.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.belikhun.luna.core.api.config.ConfigStore;
import dev.belikhun.luna.core.api.exception.HttpApiException;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.string.MessageFormatter;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HttpServerManager {
	private final Plugin plugin;
	private final ConfigStore configStore;
	private final MessageFormatter formatter;
	private final LunaLogger logger;
	private final Router router;
	private final ControllerRegistrar controllerRegistrar;
	private HttpServer server;

	public HttpServerManager(Plugin plugin, ConfigStore configStore, MessageFormatter formatter, LunaLogger logger) {
		this.plugin = plugin;
		this.configStore = configStore;
		this.formatter = formatter;
		this.logger = logger.scope("Http");
		this.router = new Router();
		this.controllerRegistrar = new ControllerRegistrar(router, this.logger.scope("Controller"));
		registerDefaultRoutes();
	}

	public Router router() {
		return router;
	}

	public int registerController(Object controller) {
		return controllerRegistrar.register(controller);
	}

	public int registerControllers(Iterable<?> controllers) {
		int total = 0;
		for (Object controller : controllers) {
			total += registerController(controller);
		}
		return total;
	}

	public void startIfEnabled() {
		if (!configStore.get("http.enabled").asBoolean(false)) {
			logger.debug("HTTP server đang tắt trong cấu hình.");
			return;
		}

		String host = configStore.get("http.host").asString("0.0.0.0");
		int port = configStore.get("http.port").asInt(8080);
		String pathPrefix = configStore.get("http.pathPrefix").asString("/api");
		try {
			server = HttpServer.create(new InetSocketAddress(host, port), 0);
			server.createContext(pathPrefix, this::handle);
			server.setExecutor(null);
			server.start();
			logger.success("HTTP server đã chạy tại " + host + ":" + port + pathPrefix);
		} catch (IOException exception) {
			logger.error("Không thể khởi động HTTP server.", exception);
			throw new HttpApiException("Cannot start HTTP server.", exception);
		}
	}

	public void stop() {
		if (server != null) {
			logger.audit("Đang dừng HTTP server.");
			server.stop(1);
			server = null;
			logger.success("HTTP server đã dừng.");
		}
	}

	private void handle(HttpExchange exchange) throws IOException {
		HttpRequest request = fromExchange(exchange);
		Router.Match routeMatch = router.match(request.method(), request.uri().getPath().replaceFirst("^/api", ""));
		HttpResponse response;
		if (routeMatch == null) {
			logger.debug("Không tìm thấy route cho " + request.method() + " " + request.uri().getPath());
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
		return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

	private void registerDefaultRoutes() {
		router.get("/health", request -> HttpResponse.json(200, "{\"status\":\"ok\"}"));
	}
}
