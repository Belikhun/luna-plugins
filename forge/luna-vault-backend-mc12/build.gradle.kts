plugins {
	alias(libs.plugins.retrofuturagradle)
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// The backend half of the network economy on 1.12.2.
//
// The gateway, the placeholders and every wire type live in `luna-legacy-api`
// (dev.belikhun.luna.legacy.vault), generic over the player type; this module is
// the FML bootstrap and nothing else.
//
// **No `/transactions` yet.** That command opens a chest menu, and the menu layer
// the modern builds share (core/luna-core-mc/ui) is written against 1.13+ container
// classes. What this module does ship is the part other mods depend on: the
// LunaVaultApi, the snapshot cache and the plugin-message channels.
minecraft {
	mcVersion = "1.12.2"
	mcpMappingChannel = libs.versions.mcp112channel.get()
	mcpMappingVersion = libs.versions.mcp112version.get()
}

dependencies {
	compileOnly(project(":luna-legacy-api"))
	compileOnly(project(":luna-core-mc12-forge"))
}

tasks.named<ShadowJar>("shadowJar") {
	exclude("META-INF/MANIFEST.MF")

	destinationDirectory.set(layout.buildDirectory.dir("libs"))
	archiveBaseName.set("luna-vault-backend-mc12-forge")
	archiveClassifier.set("shaded")
	archiveVersion.set("")
}

tasks.named<com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar>("reobfJar") {
	inputJar.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
	destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/forge"))
	archiveBaseName.set("luna-vault-backend-mc12-forge")
	archiveClassifier.set("all")
	archiveVersion.set("")
}

tasks.named("shadowJar") {
	finalizedBy(tasks.named("reobfJar"))
}
