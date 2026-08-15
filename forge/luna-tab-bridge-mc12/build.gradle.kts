plugins {
	alias(libs.plugins.retrofuturagradle)
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// The TAB bridge for the 1.12.2 line. The protocol itself lives in luna-legacy-api
// written generic over the player type, so what is here is the FML lifecycle, the
// three-method player seam and the custom-payload transport TAB's channel needs.
minecraft {
	mcVersion = "1.12.2"
	mcpMappingChannel = libs.versions.mcp112channel.get()
	mcpMappingVersion = libs.versions.mcp112version.get()
}

dependencies {
	// compileOnly throughout: legacy FML gives every mod one class loader, so the
	// core's classes - and the legacy api shaded inside its jar - are already there
	// at runtime, and `required-after` in the descriptor is what guarantees it
	compileOnly(project(":luna-core-mc12-forge"))
	compileOnly(project(":luna-core-messaging-mc12-forge"))
	compileOnly(project(":luna-legacy-api"))
}

tasks.named<ShadowJar>("shadowJar") {
	destinationDirectory.set(layout.buildDirectory.dir("libs"))
	archiveBaseName.set("luna-tab-bridge-mc12-forge")
	archiveClassifier.set("shaded")
	archiveVersion.set("")
	exclude("META-INF/MANIFEST.MF")
}

// reobfuscation is what turns MCP names into the SRG ones a live server resolves
tasks.named<com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar>("reobfJar") {
	inputJar.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
	destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/forge"))
	archiveBaseName.set("luna-tab-bridge-mc12-forge")
	archiveClassifier.set("all")
	archiveVersion.set("")
}

tasks.named("shadowJar") {
	finalizedBy(tasks.named("reobfJar"))
}
