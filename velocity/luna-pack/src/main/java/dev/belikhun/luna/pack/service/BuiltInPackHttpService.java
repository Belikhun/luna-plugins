package dev.belikhun.luna.pack.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.http.HttpRequest;
import dev.belikhun.luna.core.api.http.HttpResponse;
import dev.belikhun.luna.core.api.http.Router;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.pack.config.LoaderConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BuiltInPackHttpService {
	private static final String BUILT_IN_TOKEN = "built-in";
	private static final String BUILT_IN_HOST = "127.0.0.1";

	@SuppressWarnings("unused")
	private final ProxyServer server;
	private final LunaLogger logger;
	private final Router router;
	private HttpServer httpServer;
	private Path packPath;
	private String effectiveBaseUrl;

	public BuiltInPackHttpService(ProxyServer server, LunaLogger logger) {
		this.server = server;
		this.logger = logger.scope("BuiltInHttp");
		this.router = new Router();
		registerRoutes();
	}

	public LoaderConfig resolve(LoaderConfig config) {
		if (!isBuiltIn(config.baseUrl())) {
			stopIfRunning();
			return config;
		}

		startOrUpdate(config.packPath());
		return new LoaderConfig(effectiveBaseUrl, config.packPath());
	}

	public void stopIfRunning() {
		if (httpServer == null) {
			return;
		}

		logger.audit("Đang dừng HTTP server built-in.");
		httpServer.stop(1);
		httpServer = null;
		effectiveBaseUrl = null;
		logger.success("HTTP server built-in đã dừng.");
	}

	private boolean isBuiltIn(String baseUrl) {
		return baseUrl != null && BUILT_IN_TOKEN.equalsIgnoreCase(baseUrl.trim());
	}

	private void startOrUpdate(Path nextPackPath) {
		packPath = nextPackPath == null ? null : nextPackPath.normalize();
		if (httpServer != null) {
			return;
		}

		try {
			httpServer = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
			httpServer.createContext("/packs", this::handle);
			httpServer.setExecutor(null);
			httpServer.start();

			int port = httpServer.getAddress().getPort();
			effectiveBaseUrl = "http://" + BUILT_IN_HOST + ":" + port + "/packs/";
			logger.success("Đã bật HTTP server built-in tại " + effectiveBaseUrl);
			logger.audit("Phục vụ file pack từ " + packPath);
		} catch (IOException exception) {
			throw new IllegalStateException("Không thể khởi động HTTP server built-in cho LunaPackLoader.", exception);
		}
	}

	private void registerRoutes() {
		router.get("/{file}", request -> {
			if (packPath == null) {
				return HttpResponse.text(503, "Pack path chưa sẵn sàng.");
			}

			String filename = request.pathParam("file", "");
			String decoded = URLDecoder.decode(filename, StandardCharsets.UTF_8);
			if (!isSafeFilename(decoded)) {
				return HttpResponse.text(400, "Tên file không hợp lệ.");
			}

			Path root = packPath.normalize();
			Path target = root.resolve(decoded).normalize();
			if (!target.startsWith(root)) {
				return HttpResponse.text(403, "Không cho phép truy cập đường dẫn này.");
			}

			if (!Files.exists(target) || !Files.isRegularFile(target)) {
				return HttpResponse.text(404, "Không tìm thấy file pack.");
			}

			try {
				byte[] payload = Files.readAllBytes(target);
				return HttpResponse.bytes(200, payload, "application/zip")
					.withHeader("Cache-Control", "no-store")
					.withHeader("Content-Disposition", "inline; filename=\"" + decoded + "\"");
			} catch (IOException exception) {
				logger.error("Không thể đọc file pack built-in: " + target, exception);
				return HttpResponse.text(500, "Không thể đọc file pack.");
			}
		});
	}

	private void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		String relative = path.replaceFirst("^/packs", "");
		if (relative.isBlank()) {
			relative = "/";
		}

		HttpRequest request = fromExchange(exchange);
		Router.Match match = router.match(request.method(), relative);
		HttpResponse response;
		if (match == null) {
			response = HttpResponse.text(404, "Không tìm thấy tài nguyên.");
		} else {
			try {
				response = match.handler().handle(request.withPathParams(match.params()));
			} catch (Exception exception) {
				logger.error("Lỗi khi xử lý HTTP built-in: " + request.uri(), exception);
				response = HttpResponse.text(500, "Lỗi nội bộ.");
			}
		}

		for (Map.Entry<String, String> header : response.headers().entrySet()) {
			exchange.getResponseHeaders().set(header.getKey(), header.getValue());
		}
		byte[] body = response.body();
		exchange.sendResponseHeaders(response.status(), body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	private HttpRequest fromExchange(HttpExchange exchange) throws IOException {
		URI uri = exchange.getRequestURI();
		Map<String, List<String>> headers = new LinkedHashMap<>();
		exchange.getRequestHeaders().forEach((key, value) -> headers.put(key, new ArrayList<>(value)));
		return new HttpRequest(
			exchange.getRequestMethod(),
			uri,
			headers,
			exchange.getRequestBody().readAllBytes(),
			Map.of(),
			Map.of()
		);
	}

	private boolean isSafeFilename(String filename) {
		if (filename == null || filename.isBlank()) {
			return false;
		}
		return !filename.contains("/") && !filename.contains("\\") && !filename.contains("..") && !filename.contains("\0");
	}
}
