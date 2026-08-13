plugins {
	alias(libs.plugins.retrofuturagradle)
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// The login cage on 1.12.2.
//
// Everything a player may not do until the proxy says they are authenticated:
// held at the auth spawn, blind and immobile, chat and commands refused except
// the ones that authenticate them, and - for a name that could be premium - the
// account-mode picker.
//
// The wire types, the config and every string live in `luna-legacy-api`
// (dev.belikhun.luna.legacy.auth), shared with the modern builds so a prompt
// reads the same on 1.12.2 as on 1.21. What is here is the FML bootstrap and the
// cage itself, which cannot be shared: the modern trunk is written against
// mojmap and 1.13+ events, and neither survives the trip back.
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
	archiveBaseName.set("luna-auth-backend-mc12-forge")
	archiveClassifier.set("shaded")
	archiveVersion.set("")
}

tasks.named<com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar>("reobfJar") {
	inputJar.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
	destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/forge"))
	archiveBaseName.set("luna-auth-backend-mc12-forge")
	archiveClassifier.set("all")
	archiveVersion.set("")
}

tasks.named("shadowJar") {
	finalizedBy(tasks.named("reobfJar"))
}
