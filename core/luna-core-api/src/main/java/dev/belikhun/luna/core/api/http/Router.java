package dev.belikhun.luna.core.api.http;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class Router {
	private final List<Route> routes;

	public Router() {
		this.routes = new CopyOnWriteArrayList<>();
	}

	/**
	 * Register a route, replacing any route already on the same method and path.
	 *
	 * Matching returns the first route that fits, so appending a second handler
	 * for a path already registered would leave the *older* one serving. That is
	 * exactly backwards on a config reload, where the whole point is that the new
	 * handler — closing over the new config, the new database — takes over. A
	 * route is identified by its method and path, so registering it again means
	 * replacing it.
	 */
	public Router add(String method, String path, RouteHandler handler) {
		routes.removeIf(route -> route.matchesMethod(method) && route.path().equals(path));
		routes.add(new Route(method, path, handler));
		return this;
	}

	public Router get(String path, RouteHandler handler) {
		return add("GET", path, handler);
	}

	public Router post(String path, RouteHandler handler) {
		return add("POST", path, handler);
	}

	public Router put(String path, RouteHandler handler) {
		return add("PUT", path, handler);
	}

	public Router delete(String path, RouteHandler handler) {
		return add("DELETE", path, handler);
	}

	public Router patch(String path, RouteHandler handler) {
		return add("PATCH", path, handler);
	}

	public Match match(String method, String path) {
		for (Route route : routes) {
			if (!route.matchesMethod(method)) {
				continue;
			}

			Route.MatchResult result = route.matchesPath(path);
			if (result.matched()) {
				return new Match(route.handler(), result.params());
			}
		}

		return null;
	}

	public record Match(RouteHandler handler, Map<String, String> params) {
	}
}

