import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// One jar, two identities.
//
// As a java agent it patches Velocity while it loads; as a velocity plugin it is
// an ordinary lockfile entry, so luna pools it, updates it and deploys it into the
// proxy's plugins/ folder like any other addon - and the proxy's javaAgents field
// points at it by addon name rather than at a path somebody has to maintain.
//
// The plugin half deliberately does no patching (see LunaForwardingAgentPlugin);
// it exists so the jar can be managed, and so a proxy running without the
// -javaagent: flag says so in its own log instead of silently refusing old clients.
dependencies {
	implementation(libs.asm.tree)
	compileOnly(libs.velocity.api)
	annotationProcessor(libs.velocity.api)
}

tasks.named<ShadowJar>("shadowJar") {
	// ASM travels inside the agent and is relocated: the agent is loaded by the
	// system class loader, ahead of everything, so an unrelocated copy would be the
	// one every plugin below it resolves against.
	relocate("org.objectweb.asm", "dev.belikhun.luna.agent.shadow.asm")

	manifest {
		attributes(
			"Premain-Class" to "dev.belikhun.luna.agent.LunaForwardingAgent",
			"Agent-Class" to "dev.belikhun.luna.agent.LunaForwardingAgent",
			"Can-Retransform-Classes" to "false"
		)
	}
}

tasks.named("build") {
	dependsOn(tasks.named("shadowJar"))
}
