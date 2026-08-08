package dev.belikhun.luna.core.api.http;

import dev.belikhun.luna.core.api.exception.ControllerExecutionException;
import dev.belikhun.luna.core.api.exception.ControllerRegistrationException;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.http.annotation.Delete;
import dev.belikhun.luna.core.api.http.annotation.Get;
import dev.belikhun.luna.core.api.http.annotation.Patch;
import dev.belikhun.luna.core.api.http.annotation.Post;
import dev.belikhun.luna.core.api.http.annotation.Put;
import dev.belikhun.luna.core.api.http.annotation.Route;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

public final class ControllerRegistrar {
	private final Router router;
	private final LunaLogger logger;

	public ControllerRegistrar(Router router, LunaLogger logger) {
		this.router = router;
		this.logger = logger.scope("Annotation");
	}

	public int register(Object controller) {
		if (controller == null) {
			throw new ControllerRegistrationException("controller cannot be null");
		}

		Class<?> type = controller.getClass();
		String basePath = resolveBasePath(type);
		int registered = 0;
		for (Method method : type.getDeclaredMethods()) {
			Set<Endpoint> endpoints = resolveEndpoints(basePath, method);
			if (endpoints.isEmpty()) {
				continue;
			}

			validateSignature(method);
			method.setAccessible(true);
			for (Endpoint endpoint : endpoints) {
				router.add(endpoint.httpMethod(), endpoint.path(), request -> invoke(controller, method, request));
				registered++;
				logger.audit("Đăng ký route " + endpoint.httpMethod() + " " + endpoint.path() + " từ " + type.getSimpleName() + "#" + method.getName());
			}
		}

		return registered;
	}

	private HttpResponse invoke(Object controller, Method method, HttpRequest request) throws Exception {
		try {
			Object result;
			if (method.getParameterCount() == 0) {
				result = method.invoke(controller);
			} else {
				result = method.invoke(controller, request);
			}

			if (result == null) {
				return HttpResponse.text(204, "");
			}

			if (result instanceof HttpResponse response) {
				return response;
			}

			if (result instanceof String text) {
				return HttpResponse.text(200, text);
			}

			return HttpResponse.json(200, String.valueOf(result));
		} catch (InvocationTargetException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof Exception ex) {
				throw ex;
			}
			throw new ControllerExecutionException("Controller method threw an error.", cause);
		}
	}

	private void validateSignature(Method method) {
		if (method.getParameterCount() > 1) {
			throw new ControllerRegistrationException("Controller method can have at most one parameter: " + method.getName());
		}

		if (method.getParameterCount() == 1 && method.getParameterTypes()[0] != HttpRequest.class) {
			throw new ControllerRegistrationException("Controller parameter must be HttpRequest: " + method.getName());
		}
	}

	private String resolveBasePath(Class<?> type) {
		Route route = type.getAnnotation(Route.class);
		if (route == null) {
			return "";
		}

		return normalizePath(route.value());
	}

	private Set<Endpoint> resolveEndpoints(String basePath, Method method) {
		Set<Endpoint> endpoints = new LinkedHashSet<>();
		Route route = method.getAnnotation(Route.class);
		String routePath = route == null ? "" : route.value();

		Get get = method.getAnnotation(Get.class);
		if (get != null) {
			endpoints.add(new Endpoint("GET", buildPath(basePath, selectPath(routePath, get.value()))));
		}

		Post post = method.getAnnotation(Post.class);
		if (post != null) {
			endpoints.add(new Endpoint("POST", buildPath(basePath, selectPath(routePath, post.value()))));
		}

		Put put = method.getAnnotation(Put.class);
		if (put != null) {
			endpoints.add(new Endpoint("PUT", buildPath(basePath, selectPath(routePath, put.value()))));
		}

		Delete delete = method.getAnnotation(Delete.class);
		if (delete != null) {
			endpoints.add(new Endpoint("DELETE", buildPath(basePath, selectPath(routePath, delete.value()))));
		}

		Patch patch = method.getAnnotation(Patch.class);
		if (patch != null) {
			endpoints.add(new Endpoint("PATCH", buildPath(basePath, selectPath(routePath, patch.value()))));
		}

		if (endpoints.isEmpty() && route != null) {
			logger.warn("Bỏ qua @Route thiếu HTTP method tại " + method.getDeclaringClass().getSimpleName() + "#" + method.getName());
		}

		return endpoints;
	}

	private String selectPath(String routePath, String verbPath) {
		if (routePath != null && !routePath.isBlank()) {
			return routePath;
		}

		if (verbPath != null && !verbPath.isBlank()) {
			return verbPath;
		}

		return "";
	}

	private String buildPath(String basePath, String methodPath) {
		String left = normalizePath(basePath);
		String right = normalizePath(methodPath);
		if (left.isEmpty()) {
			return right.isEmpty() ? "/" : right;
		}
		if (right.isEmpty() || right.equals("/")) {
			return left;
		}
		if (left.endsWith("/")) {
			left = left.substring(0, left.length() - 1);
		}
		if (!right.startsWith("/")) {
			right = "/" + right;
		}
		return left + right;
	}

	private String normalizePath(String path) {
		if (path == null || path.isBlank()) {
			return "";
		}
		String normalized = path.trim();
		if (!normalized.startsWith("/")) {
			normalized = "/" + normalized;
		}
		if (normalized.length() > 1 && normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized;
	}

	private record Endpoint(String httpMethod, String path) {
	}
}

