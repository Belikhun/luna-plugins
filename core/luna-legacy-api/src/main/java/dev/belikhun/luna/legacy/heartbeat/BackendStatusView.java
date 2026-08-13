package dev.belikhun.luna.legacy.heartbeat;

import java.util.Map;
import java.util.Optional;

/** Read-only access to a backend's mirror of the proxy registry. */
public interface BackendStatusView {
	Optional<BackendServerStatus> status(String serverName);

	Map<String, BackendServerStatus> snapshot();

	default Optional<BackendMetadata> currentBackendMetadata() {
		return Optional.empty();
	}
}
