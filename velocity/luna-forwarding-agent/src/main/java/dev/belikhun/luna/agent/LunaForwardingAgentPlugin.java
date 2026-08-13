package dev.belikhun.luna.agent;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;

import org.slf4j.Logger;

/**
 * The plugin half of the forwarding agent jar.
 *
 * The same jar is two things at once, on purpose. As a **java agent** it rewrites
 * two constants while Velocity loads (see {@link ForwardingTransformer}); as a
 * **plugin** it is an ordinary lockfile entry, which is what lets luna pool it,
 * update it and deploy it into the proxy like every other addon. Before this it
 * was a loose jar an operator had to place by hand and remember to replace.
 *
 * It does no patching from here - by the time a plugin loads, every class it
 * would rewrite is long since defined. What it does is **answer the question the
 * agent cannot**: whether the `-javaagent:` flag was actually set. A proxy
 * missing that flag boots perfectly and then refuses every pre-1.13 client, which
 * looks like a broken backend rather than a missing JVM argument. Saying so here,
 * in the proxy's own log, is the difference between a one-line fix and an
 * afternoon.
 *
 * The two halves never share objects, and must not try to: the agent's copy of
 * these classes is defined by the system class loader and this one by Velocity's
 * plugin loader, so they are unrelated types at runtime. The only thing crossing
 * that gap is {@link LunaForwardingAgent#READY_PROPERTY}, which is a compile-time
 * String constant inlined into this class, read back off the JVM-wide system
 * properties. That is the whole contract.
 */
@Plugin(
	id = "lunaforwardingagent",
	name = "LunaForwardingAgent",
	version = "0.1.0-SNAPSHOT",
	description = "Modern player forwarding for pre-1.13 backends",
	authors = { "Belikhun" }
)
public final class LunaForwardingAgentPlugin {
	private final Logger logger;

	@Inject
	public LunaForwardingAgentPlugin(Logger logger) {
		this.logger = logger;
	}

	/**
	 * Checks that the agent is **attached**, not that it has finished patching.
	 *
	 * Both of its targets load lazily - `StateRegistry` is first touched by a Netty
	 * worker once a connection arrives - so at this point in startup neither has
	 * been transformed yet, and a proxy nobody connects to would never transform
	 * them at all. Testing the ready flag here would therefore warn on every boot
	 * of a perfectly working proxy, which is worse than saying nothing.
	 */
	@Subscribe
	public void onProxyInitialize(ProxyInitializeEvent event) {
		if (Boolean.parseBoolean(System.getProperty(LunaForwardingAgent.ATTACHED_PROPERTY))) {
			logger.info("Java agent đã được nạp; modern forwarding cho client < 1.13 sẽ áp dụng khi có kết nối đầu tiên.");
			return;
		}

		logger.warn("Jar này đã được nạp như một plugin nhưng KHÔNG được nạp như một java agent.");
		logger.warn("Modern forwarding cho client < 1.13 sẽ không hoạt động; backend 1.12.2 sẽ không vào được.");
		logger.warn("Hãy gắn nó vào java agent của instance proxy:");
		logger.warn("  luna instance config proxy javaAgents \"addon:luna-forwarding-agent@velocity\"");
	}
}
