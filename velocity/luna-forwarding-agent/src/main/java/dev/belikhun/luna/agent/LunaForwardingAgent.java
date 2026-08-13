package dev.belikhun.luna.agent;

import java.lang.instrument.Instrumentation;

/**
 * Lets a pre-1.13 backend speak Velocity's modern player forwarding, without
 * forking Velocity.
 *
 * Modern forwarding carries a player's identity over the login-plugin-message
 * exchange, and those two packets arrived in the 1.13 protocol. Velocity
 * therefore gates them at 1.13 and refuses any older client outright. That gate
 * is a statement about *vanilla* backends: one that implements the exchange
 * itself forwards perfectly well, and luna's 1.12.2 core does exactly that.
 *
 * The alternative to this agent is a source patch, which works but means
 * building and shipping our own proxy binary - a self-built jar at the cluster's
 * single point of failure, rebuilt on every upstream release. This runs the
 * stock PaperMC jar and rewrites two constants as the classes load.
 *
 * The same jar is also a velocity plugin ({@link LunaForwardingAgentPlugin}), so
 * luna pools, updates and deploys it like any other addon instead of an operator
 * placing a loose file by hand. The plugin half patches nothing.
 *
 * **It fails closed and says so.** Every transform asserts the shape it expected;
 * if the bytecode has moved, the failure is reported and the class loads
 * unpatched, leaving the stock gate in place - pre-1.13 clients are refused as
 * they were before, and no other backend is affected. See
 * {@link ForwardingTransformer} for what each one does.
 *
 * Attached through the proxy instance's java agents, by addon name:
 *
 * ```
 * luna instance config proxy javaAgents "addon:luna-forwarding-agent@velocity"
 * ```
 *
 * luna resolves that to wherever deploy put the jar and renders the
 * `-javaagent:` flag itself, so an update never moves the path out from under it.
 */
public final class LunaForwardingAgent {
	/**
	 * Set once both transforms have applied, so the state is positively observable
	 * rather than inferred from a failed login.
	 *
	 * **This is not a startup check.** Both targets load lazily - the first one is
	 * touched by a Netty worker when a connection arrives - so this is still unset
	 * while the proxy is booting, and stays unset forever on a proxy nobody
	 * connects to. Anything asking "is the agent here?" during startup wants
	 * {@link #ATTACHED_PROPERTY}.
	 */
	public static final String READY_PROPERTY = "luna.velocity.legacyForwarding";

	/**
	 * Set the moment `premain` runs, which is before the proxy's own main method.
	 *
	 * That timing is the point: it is the one fact about the agent that is already
	 * true when a plugin loads, so it is what {@link LunaForwardingAgentPlugin}
	 * checks to tell "the operator forgot the -javaagent: flag" from "the classes
	 * have simply not been touched yet".
	 */
	public static final String ATTACHED_PROPERTY = "luna.velocity.legacyForwarding.attached";

	private LunaForwardingAgent() {
	}

	public static void premain(String arguments, Instrumentation instrumentation) {
		// before the proxy's main class runs, so every target is still unloaded
		instrumentation.addTransformer(new ForwardingTransformer(), false);

		System.setProperty(ATTACHED_PROPERTY, "true");
		System.out.println("[luna-agent] Đã cài transformer cho modern forwarding trên client cũ (< 1.13).");
	}

	/**
	 * Attaching to a running proxy is not supported.
	 *
	 * The targets are loaded during startup and the transformer is not
	 * retransformation-capable, so a late attach would report success and change
	 * nothing - the worst possible outcome for something that decides identity.
	 */
	public static void agentmain(String arguments, Instrumentation instrumentation) {
		throw new UnsupportedOperationException(
			"luna-forwarding-agent phải được nạp bằng -javaagent khi khởi động, không thể attach vào tiến trình đang chạy."
		);
	}
}
