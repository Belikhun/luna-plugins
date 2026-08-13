plugins {
	alias(libs.plugins.retrofuturagradle)
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// `/countdown` and `/shutdown` for the 1.12.2 line. Compiled against luna-core-mc12
// and shipping none of it: legacy FML gives every mod one class loader, so the core's
// classes - and the legacy api inside its jar, which is where the shared countdown
// runtime lives - are already there at runtime.
minecraft {
	mcVersion = "1.12.2"
	mcpMappingChannel = libs.versions.mcp112channel.get()
	mcpMappingVersion = libs.versions.mcp112version.get()
}

dependencies {
	// compileOnly, not implementation: `required-after:lunacore` in the descriptor
	// is what guarantees these are loaded, and FML refuses to start without it
	compileOnly(project(":luna-core-mc12-forge"))
	compileOnly(project(":luna-legacy-api"))
}

tasks.named<ShadowJar>("shadowJar") {
	destinationDirectory.set(layout.buildDirectory.dir("libs"))
	archiveBaseName.set("luna-countdown-mc12-forge")
	archiveClassifier.set("shaded")
	archiveVersion.set("")
	exclude("META-INF/MANIFEST.MF")
}

// reobfuscation is what turns MCP names into the SRG ones a live server resolves
tasks.named<com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar>("reobfJar") {
	inputJar.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
	destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/forge"))
	archiveBaseName.set("luna-countdown-mc12-forge")
	archiveClassifier.set("all")
	archiveVersion.set("")
}

tasks.named("shadowJar") {
	finalizedBy(tasks.named("reobfJar"))
}
