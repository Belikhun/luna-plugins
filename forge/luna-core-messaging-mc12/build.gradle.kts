plugins {
	alias(libs.plugins.retrofuturagradle)
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// AMQP messaging for the 1.12.2 backend.
//
// Unlike its neoforge and forge-1.20.1 siblings this module has no trunk to share:
// the bus, both transports and the channel bookkeeping live in `luna-legacy-api`
// (dev.belikhun.luna.legacy.messaging.bus), written generic over the player type so
// they name no Minecraft at all. What is left here is the FML bootstrap and one
// implementation of PlayerBridge - the five MCP calls the trunk needs.
//
// There is no payload fallback. 1.12.2 caps a plugin channel name at 20 characters
// and every luna channel is `luna:`-namespaced past that, so a custom payload cannot
// carry ours; this backend is AMQP-only by protocol, not by choice.
minecraft {
	mcVersion = "1.12.2"
	mcpMappingChannel = libs.versions.mcp112channel.get()
	mcpMappingVersion = libs.versions.mcp112version.get()
}

// The AMQP client is the one thing this jar has to carry. luna-core-mc12 already
// ships the legacy api, and FML 1.12.2 puts every mod on one LaunchClassLoader, so
// taking it compileOnly is what keeps a second copy out of the runtime.
//
// slf4j travels with it, and only on this platform. amqp-client logs through slf4j
// and every modern backend already has it - Paper, Velocity and NeoForge all ship
// one - but 1.12.2 Forge logs through log4j 2 directly and has no slf4j anywhere,
// so the client dies on NoClassDefFoundError the moment it opens a connection. It
// is relocated because a second copy of a logging facade on a shared class loader
// is exactly the kind of thing that breaks an unrelated mod.
val embeddedRabbitMqClient = configurations.detachedConfiguration(
	dependencies.create(libs.rabbitmq.client.get()),
	dependencies.create("org.slf4j:slf4j-api:${libs.versions.slf4j.get()}")
).apply {
	isTransitive = false
}

dependencies {
	compileOnly(project(":luna-legacy-api"))
	compileOnly(project(":luna-core-mc12-forge"))
	implementation(libs.rabbitmq.client)
}

tasks.named<ShadowJar>("shadowJar") {
	from(embeddedRabbitMqClient.files.map { zipTree(it) })
	mergeServiceFiles()
	exclude("META-INF/MANIFEST.MF")

	relocate("org.slf4j", "dev.belikhun.luna.shadow.org.slf4j")

	destinationDirectory.set(layout.buildDirectory.dir("libs"))
	archiveBaseName.set("luna-core-messaging-mc12-forge")
	archiveClassifier.set("shaded")
	archiveVersion.set("")
}

// Same chain as luna-core-mc12: shade into build/libs, then let RFG reobfuscate the
// result into output/forge. A mod naming MCP members runs in a dev workspace and
// dies on a live server, so the deliverable is always the reobfuscated jar.
tasks.named<com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar>("reobfJar") {
	inputJar.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
	destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/forge"))
	archiveBaseName.set("luna-core-messaging-mc12-forge")
	archiveClassifier.set("all")
	archiveVersion.set("")
}

tasks.named("shadowJar") {
	finalizedBy(tasks.named("reobfJar"))
}
