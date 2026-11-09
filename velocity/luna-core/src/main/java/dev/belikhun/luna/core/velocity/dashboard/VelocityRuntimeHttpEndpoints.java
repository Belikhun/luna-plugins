package dev.belikhun.luna.core.velocity.dashboard;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.util.ProxyVersion;
import dev.belikhun.luna.core.api.http.LunaJson;
import dev.belikhun.luna.core.api.http.RequestAuthorizer;
import dev.belikhun.luna.core.api.http.Router;
import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The proxy's own JVM, for the control console.
 *
 * Backends report their heap and tick rate on the heartbeat, but the proxy sends
 * no heartbeat to anybody, so the console had nothing past what `/proc` says
 * about the process from outside. The figures here are the ones only the JVM
 * knows: heap as the collector sees it, per-pool usage, garbage collection and
 * thread counts. Reading them is a handful of MXBean getters, cheap enough for
 * the console to poll at its sampling cadence.
 *
 * Every number is cumulative or instantaneous as the JVM reports it; turning GC
 * time into a rate is the caller's job, because only the caller knows how far
 * apart its two reads were.
 */
public final class VelocityRuntimeHttpEndpoints {
	private final LunaLogger logger;
	private final ProxyServer proxyServer;
	private final RequestAuthorizer authorizer;

	public VelocityRuntimeHttpEndpoints(LunaLogger logger, ProxyServer proxyServer, RequestAuthorizer authorizer) {
		this.logger = logger.scope("RuntimeHttp");
		this.proxyServer = proxyServer;
		this.authorizer = authorizer;
	}

	public void register(Router router) {
		router.get("/dashboard/proxy", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối truy vấn /dashboard/proxy do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			long startedAt = System.nanoTime();
			return LunaJson.envelope(200, buildSnapshot(), startedAt);
		});
	}

	private Map<String, Object> buildSnapshot() {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("generatedAtEpochMillis", System.currentTimeMillis());
		payload.put("proxy", buildProxy());
		payload.put("jvm", buildJvm());
		return payload;
	}

	private Map<String, Object> buildProxy() {
		ProxyVersion version = proxyServer.getVersion();

		Map<String, Object> proxy = new LinkedHashMap<>();
		proxy.put("name", version.getName());
		proxy.put("version", version.getVersion());
		proxy.put("onlinePlayers", proxyServer.getPlayerCount());
		proxy.put("registeredServers", proxyServer.getAllServers().size());
		return proxy;
	}

	private Map<String, Object> buildJvm() {
		RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
		MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
		ThreadMXBean threads = ManagementFactory.getThreadMXBean();
		ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
		MemoryUsage heap = memory.getHeapMemoryUsage();
		MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();

		Map<String, Object> jvm = new LinkedHashMap<>();
		jvm.put("vmName", runtime.getVmName());
		jvm.put("vmVersion", runtime.getVmVersion());
		jvm.put("javaVersion", System.getProperty("java.version", ""));
		jvm.put("uptimeMillis", runtime.getUptime());
		jvm.put("availableProcessors", Runtime.getRuntime().availableProcessors());

		jvm.put("heapUsedBytes", heap.getUsed());
		jvm.put("heapCommittedBytes", heap.getCommitted());
		// -1 when the heap has no ceiling, which is a fact rather than a zero
		jvm.put("heapMaxBytes", heap.getMax() < 0 ? null : heap.getMax());
		jvm.put("nonHeapUsedBytes", nonHeap.getUsed());
		jvm.put("nonHeapCommittedBytes", nonHeap.getCommitted());

		jvm.put("threadCount", threads.getThreadCount());
		jvm.put("daemonThreadCount", threads.getDaemonThreadCount());
		jvm.put("peakThreadCount", threads.getPeakThreadCount());
		jvm.put("totalStartedThreadCount", threads.getTotalStartedThreadCount());

		jvm.put("loadedClassCount", classes.getLoadedClassCount());
		jvm.put("unloadedClassCount", classes.getUnloadedClassCount());

		jvm.put("gc", buildCollectors());
		jvm.put("memoryPools", buildPools());
		return jvm;
	}

	private List<Map<String, Object>> buildCollectors() {
		List<Map<String, Object>> collectors = new ArrayList<>();

		for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
			if (!bean.isValid()) {
				continue;
			}

			Map<String, Object> collector = new LinkedHashMap<>();
			collector.put("name", bean.getName());
			collector.put("collectionCount", Math.max(0L, bean.getCollectionCount()));
			collector.put("collectionTimeMillis", Math.max(0L, bean.getCollectionTime()));
			collectors.add(collector);
		}

		return collectors;
	}

	private List<Map<String, Object>> buildPools() {
		List<Map<String, Object>> pools = new ArrayList<>();

		for (MemoryPoolMXBean bean : ManagementFactory.getMemoryPoolMXBeans()) {
			if (!bean.isValid()) {
				continue;
			}

			MemoryUsage usage = bean.getUsage();
			if (usage == null) {
				continue;
			}

			Map<String, Object> pool = new LinkedHashMap<>();
			pool.put("name", bean.getName());
			pool.put("type", bean.getType().name());
			pool.put("usedBytes", usage.getUsed());
			pool.put("committedBytes", usage.getCommitted());
			pool.put("maxBytes", usage.getMax() < 0 ? null : usage.getMax());
			pools.add(pool);
		}

		return pools;
	}
}
