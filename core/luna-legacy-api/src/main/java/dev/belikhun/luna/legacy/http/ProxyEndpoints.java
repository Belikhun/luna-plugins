package dev.belikhun.luna.legacy.http;

import dev.belikhun.luna.legacy.string.Strings;

import java.net.URI;

/**
 * Where a backend's other proxy endpoints are, given the one address it configures.
 *
 * A backend's config.yml names the heartbeat endpoint and nothing else, on purpose:
 * the registry stream, the messaging config and the permission mirror all live beside
 * it on the same proxy, and asking an operator to keep four addresses in step is how
 * three of them end up pointing at a host that moved.
 */
public final class ProxyEndpoints {
	private ProxyEndpoints() {
	}

	/**
	 * Derive a sibling endpoint from the heartbeat URI.
	 *
	 * Both shapes the heartbeat endpoint takes are handled: `/api/heartbeat`, where
	 * the last path segment is the endpoint name, and `/api/heartbeat/<something>`,
	 * where it is a directory. Anything the caller cannot use resolves to null rather
	 * than to a guess, since a wrong URL here would silently ask a stranger for
	 * permissions.
	 *
	 * @param heartbeatUri  the configured heartbeat endpoint
	 * @param endpointSuffix an absolute path starting with `/`, e.g. `/heartbeat/stream`
	 * @return the sibling endpoint, or null when the heartbeat URI carries no usable path
	 */
	public static URI sibling(URI heartbeatUri, String endpointSuffix) {
		if (heartbeatUri == null) {
			return null;
		}

		String path = heartbeatUri.getPath();

		if (Strings.isBlank(path)) {
			return null;
		}

		int heartbeatMarker = path.indexOf("/heartbeat/");
		String siblingPath;

		if (heartbeatMarker >= 0) {
			siblingPath = path.substring(0, heartbeatMarker) + endpointSuffix;
		} else {
			int slashIndex = path.lastIndexOf('/');

			if (slashIndex < 0) {
				return null;
			}

			siblingPath = path.substring(0, slashIndex) + endpointSuffix;
		}

		return URI.create(heartbeatUri.getScheme() + "://" + heartbeatUri.getAuthority() + siblingPath);
	}
}
