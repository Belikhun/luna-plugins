package dev.belikhun.luna.core.api.messaging;

import dev.belikhun.luna.core.api.config.ConfigValues;
import dev.belikhun.luna.core.api.heartbeat.HeartbeatFormCodec;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AmqpMessagingConfigCodec {
	private AmqpMessagingConfigCodec() {
	}

	public static byte[] encode(AmqpMessagingConfig config) {
		return HeartbeatFormCodec.encode(encodeToMap(config));
	}

	public static AmqpMessagingConfig decode(byte[] payload) {
		return fromMap(HeartbeatFormCodec.decode(payload));
	}

	public static AmqpMessagingConfig fromConfigMap(Map<String, Object> rootConfig) {
		Map<String, Object> messaging = ConfigValues.map(rootConfig, "messaging");
		Map<String, Object> rabbitmq = ConfigValues.map(messaging, "rabbitmq");
		return new AmqpMessagingConfig(
			ConfigValues.booleanValue(rabbitmq, "enabled", false),
			ConfigValues.string(rabbitmq, "uri", ""),
			ConfigValues.string(rabbitmq, "exchange", "luna.plugin-messaging"),
			ConfigValues.string(rabbitmq, "proxyQueue", "luna.proxy.messaging"),
			ConfigValues.string(rabbitmq, "backendQueuePrefix", "luna.backend."),
			ConfigValues.string(rabbitmq, "localServerName", ""),
			Math.max(500, ConfigValues.intValue(rabbitmq, "connectionTimeoutMillis", 5000)),
			Math.max(5, ConfigValues.intValue(rabbitmq, "requestedHeartbeatSeconds", 15))
		).sanitize();
	}

	public static AmqpMessagingConfig fromMap(Map<String, String> values) {
		return new AmqpMessagingConfig(
			ConfigValues.booleanValue(values, "enabled", false),
			ConfigValues.string(values, "uri", ""),
			ConfigValues.string(values, "exchange", "luna.plugin-messaging"),
			ConfigValues.string(values, "proxyQueue", "luna.proxy.messaging"),
			ConfigValues.string(values, "backendQueuePrefix", "luna.backend."),
			ConfigValues.string(values, "localServerName", ""),
			Math.max(500, ConfigValues.intValue(values, "connectionTimeoutMillis", 5000)),
			Math.max(5, ConfigValues.intValue(values, "requestedHeartbeatSeconds", 15))
		).sanitize();
	}

	public static Map<String, String> encodeToMap(AmqpMessagingConfig config) {
		AmqpMessagingConfig safe = (config == null ? AmqpMessagingConfig.disabled() : config).sanitize();
		Map<String, String> values = new LinkedHashMap<>();
		values.put("enabled", String.valueOf(safe.enabled()));
		values.put("uri", safe.uri());
		values.put("exchange", safe.exchange());
		values.put("proxyQueue", safe.proxyQueue());
		values.put("backendQueuePrefix", safe.backendQueuePrefix());
		values.put("localServerName", safe.localServerName());
		values.put("connectionTimeoutMillis", String.valueOf(safe.connectionTimeoutMillis()));
		values.put("requestedHeartbeatSeconds", String.valueOf(safe.requestedHeartbeatSeconds()));
		return values;
	}
}
