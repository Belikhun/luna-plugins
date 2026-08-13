plugins {
	alias(libs.plugins.retrofuturagradle)
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// Cross-server messaging on 1.12.2.
//
// The runtime is `luna-legacy-api`'s (dev.belikhun.luna.legacy.messenger.runtime),
// written generic over the player type so it names no Minecraft: presence tracking,
// the request/timeout bookkeeping and the placeholder resolution are all shared with
// the modern builds in shape, and identical in behaviour. This module supplies the
// FML bootstrap, the two seams and the commands.
//
// The commands are the real work. Brigadier arrived in 1.13, so what the modern
// builds declare as trees are hand-parsed CommandBase subclasses here - the same
// departure luna-countdown-mc12 makes, for the same reason.
minecraft {
	mcVersion = "1.12.2"
	mcpMappingChannel = libs.versions.mcp112channel.get()
	mcpMappingVersion = libs.versions.mcp112version.get()
}

// Nothing to shade: the runtime rides in luna-core-mc12's jar and FML 1.12.2 puts
// every mod on one LaunchClassLoader, so both are compileOnly.
dependencies {
	compileOnly(project(":luna-legacy-api"))
	compileOnly(project(":luna-core-mc12-forge"))
}

tasks.named<ShadowJar>("shadowJar") {
	exclude("META-INF/MANIFEST.MF")

	destinationDirectory.set(layout.buildDirectory.dir("libs"))
	archiveBaseName.set("luna-messenger-mc12-forge")
	archiveClassifier.set("shaded")
	archiveVersion.set("")
}

tasks.named<com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar>("reobfJar") {
	inputJar.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
	destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/forge"))
	archiveBaseName.set("luna-messenger-mc12-forge")
	archiveClassifier.set("all")
	archiveVersion.set("")
}

tasks.named("shadowJar") {
	finalizedBy(tasks.named("reobfJar"))
}
