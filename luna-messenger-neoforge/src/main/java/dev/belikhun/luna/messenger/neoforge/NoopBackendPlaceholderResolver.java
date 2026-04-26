package dev.belikhun.luna.messenger.neoforge;

import dev.belikhun.luna.core.api.messenger.BackendPlaceholderResolver;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionRequest;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionResult;

import java.util.LinkedHashMap;

final class NoopBackendPlaceholderResolver implements BackendPlaceholderResolver {
	@Override
	public PlaceholderResolutionResult resolve(PlaceholderResolutionRequest request) {
		return new PlaceholderResolutionResult(
			request == null ? "" : request.content(),
			request == null ? java.util.Map.of() : new LinkedHashMap<>(request.internalValues())
		);
	}
}
