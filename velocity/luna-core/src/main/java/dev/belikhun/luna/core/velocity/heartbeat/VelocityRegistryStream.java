package dev.belikhun.luna.core.velocity.heartbeat;

import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatEvent;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatListener;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusRow;
import dev.belikhun.luna.core.api.heartbeat.HeartbeatFormCodec;
import dev.belikhun.luna.core.api.http.RequestAuthorizer;
import dev.belikhun.luna.core.api.http.Router;
import dev.belikhun.luna.core.api.http.SseBroadcaster;
import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Pushes registry changes to backends the moment they happen.
 *
 * This exists because the only proxy→backend push the plugin had was a plugin
 * message, which rides a player's connection and is therefore dropped for any
 * backend with nobody on it — precisely the backend whose view of the cluster
 * matters when the first player arrives. A stream is its own connection, so it
 * reaches an empty backend, and it carries the row rather than a "go and ask"
 * nudge.
 *
 * The heartbeat poll stays the safety net: a backend that missed events while
 * reconnecting catches up from its cursor on the next beat.
 */
public final class VelocityRegistryStream {
	private final LunaLogger logger;
	private final VelocityBackendStatusRegistry registry;
	private final RequestAuthorizer authorizer;
	private final SseBroadcaster broadcaster;
	private final BackendHeartbeatListener listener;

	public VelocityRegistryStream(LunaLogger logger, VelocityBackendStatusRegistry registry, RequestAuthorizer authorizer) {
		this.logger = logger.scope("RegistryStream");
		this.registry = registry;
		this.authorizer = authorizer;
		this.broadcaster = new SseBroadcaster(logger, "registry");

		this.listener = this::onRegistryEvent;
		registry.addHeartbeatListener(listener);
	}

	public void register(Router router) {
		router.get("/heartbeat/stream", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối /heartbeat/stream do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			return broadcaster.subscribe(stream -> {
				// a fresh subscriber gets every row, so it never has to reason about
				// what it missed while it was disconnected. It is sent to merge, not
				// to replace: the registry holds only servers that have checked in,
				// while a backend's mirror also carries the configured-but-never-seen
				// ones its full sync gave it. A proxy restart still resets the mirror,
				// because the epoch carried here will not match.
				stream.event("snapshot", encode(registry.allRows(), false));
			});
		});
	}

	/**
	 * Tell every backend the selector configuration changed, so they re-fetch it
	 * instead of rendering a layout from before the reload.
	 */
	public void configChanged() {
		broadcaster.broadcast("config", "selector");
	}

	public void close() {
		registry.removeHeartbeatListener(listener);
		broadcaster.close();
	}

	private void onRegistryEvent(BackendHeartbeatEvent event) {
		if (event == null || event.current() == null || broadcaster.size() == 0) {
			return;
		}

		BackendStatusRow row = registry.row(event.current().serverName());
		if (row == null) {
			return;
		}

		broadcaster.broadcast("row", encode(List.of(row), false));
	}

	private String encode(List<BackendStatusRow> rows, boolean fullSync) {
		byte[] body = HeartbeatFormCodec.encodeRows(
			rows,
			registry.currentRevision(),
			registry.epoch(),
			fullSync,
			null,
			null
		);
		return new String(body, StandardCharsets.UTF_8);
	}
}
