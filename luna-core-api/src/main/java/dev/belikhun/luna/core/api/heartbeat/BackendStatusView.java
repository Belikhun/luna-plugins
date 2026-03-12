package dev.belikhun.luna.core.api.heartbeat;

import java.util.Map;
import java.util.Optional;

public interface BackendStatusView {
	Optional<BackendServerStatus> status(String serverName);

	Map<String, BackendServerStatus> snapshot();
}
