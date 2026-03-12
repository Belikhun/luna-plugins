package dev.belikhun.luna.core.velocity.heartbeat;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.net.InetSocketAddress;
import java.util.Map;

public final class VelocityBackendNameResolver {
	private final ProxyServer proxyServer;

	public VelocityBackendNameResolver(ProxyServer proxyServer) {
		this.proxyServer = proxyServer;
	}

	public String resolve(String fallback, Map<String, java.util.List<String>> headers, int backendPort) {
		if (backendPort <= 0 || headers == null || headers.isEmpty()) {
			return fallback;
		}

		String remoteIp = firstHeader(headers, "X-Luna-Remote-Ip");
		if (remoteIp == null || remoteIp.isBlank()) {
			return fallback;
		}

		for (RegisteredServer server : proxyServer.getAllServers()) {
			InetSocketAddress address = server.getServerInfo().getAddress();
			if (address.getPort() != backendPort) {
				continue;
			}

			if (matches(remoteIp, address)) {
				return server.getServerInfo().getName();
			}
		}

		return fallback;
	}

	private boolean matches(String remoteIp, InetSocketAddress address) {
		String literal = address.getAddress() == null ? "" : address.getAddress().getHostAddress();
		String host = address.getHostString();
		return remoteIp.equals(host) || remoteIp.equals(literal) || (isLoopback(remoteIp) && isLoopback(host)) || (isLoopback(remoteIp) && isLoopback(literal));
	}

	private boolean isLoopback(String value) {
		if (value == null) {
			return false;
		}

		String normalized = value.trim();
		return normalized.equals("127.0.0.1") || normalized.equals("::1") || normalized.equalsIgnoreCase("localhost");
	}

	private String firstHeader(Map<String, java.util.List<String>> headers, String name) {
		for (Map.Entry<String, java.util.List<String>> entry : headers.entrySet()) {
			if (!name.equalsIgnoreCase(entry.getKey())) {
				continue;
			}

			for (String value : entry.getValue()) {
				if (value != null && !value.isBlank()) {
					return value.trim();
				}
			}
		}

		return "";
	}
}
