package dev.belikhun.luna.core.api.heartbeat;

/**
 * What this backend calls itself on the network.
 *
 * The proxy is the authority. It names a server in the registry row that comes
 * back on the heartbeat, and that same name keys the server's AMQP queue, its
 * presence in the messenger and the {@code current_server} placeholder. A
 * backend falls back to its own configuration only while it has never been
 * answered: at boot, or with the proxy down.
 *
 * Reading this through a supplier rather than capturing a string is the whole
 * point - the name is not known when the modules that need it are built.
 */
@FunctionalInterface
public interface BackendIdentity {
	/** The row the proxy holds for this server, or the local fallback. */
	BackendMetadata current();

	/** Just the name; blank only when neither the proxy nor the config has one. */
	default String name() {
		BackendMetadata metadata = current();

		return metadata == null ? "" : metadata.sanitize().name();
	}

	/** The name, or {@code fallbackName} when there is none. */
	default String nameOr(String fallbackName) {
		String resolved = name();

		return resolved.isBlank() ? fallbackName : resolved;
	}
}
