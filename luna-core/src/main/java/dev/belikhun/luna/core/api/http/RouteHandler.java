package dev.belikhun.luna.core.api.http;

@FunctionalInterface
public interface RouteHandler {
	HttpResponse handle(HttpRequest request) throws Exception;
}
